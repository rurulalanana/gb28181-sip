package io.github.rurulalanana.gb28181.sip.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rurulalanana.gb28181.sip.device.DeviceRegistry;
import io.github.rurulalanana.gb28181.sip.device.DeviceSession;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis 分布式设备注册表。
 *
 * <p>用于<b>微服务多实例</b>：多个 SIP 节点共享同一份设备注册表，
 * 任意节点都能查询到设备会话及其 {@link DeviceSession#getOwnerNode() 持有节点}，
 * 从而把信令路由回正确节点。
 *
 * <p><b>数据结构</b>：
 * <pre>
 *   {prefix}:device:{deviceId}  → Hash   单设备会话字段
 *   {prefix}:devices            → Set    全部设备 ID
 *   {prefix}:domains:{domain}   → Set    域内设备 ID
 *   {prefix}:nodes              → Set    活跃节点 ID（用于服务发现）
 * </pre>
 *
 * <p>会话 TTL 按 {@code expires} 设置，由 Redis 自动过期，无需额外清理线程。
 *
 * <p><b>依赖</b>：需引入 {@code redis.clients:jedis}。单机场景请改用
 * {@link io.github.rurulalanana.gb28181.sip.device.LocalDeviceRegistry}，不引入该依赖。
 */
@Slf4j
public class RedisDeviceRegistry implements DeviceRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JedisPool pool;
    private final String prefix;
    private final String nodeId;

    public RedisDeviceRegistry(JedisPool pool, String prefix, String nodeId) {
        this.pool = pool;
        this.prefix = prefix == null ? "gb28181" : prefix;
        this.nodeId = nodeId;
    }

    /**
     * 便捷构造：从 Redis URI 创建。
     *
     * @param redisUri 例如 {@code redis://127.0.0.1:6379}
     */
    public static RedisDeviceRegistry of(String redisUri, String nodeId) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(32);
        config.setMaxIdle(16);
        try {
            return new RedisDeviceRegistry(new JedisPool(config, new java.net.URI(redisUri)),
                    "gb28181", nodeId);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("非法的 Redis URI: " + redisUri, e);
        }
    }

    /**
     * 便捷构造：指定 host/port/密码。
     *
     * @param password 为 {@code null} 或空时不启用 AUTH
     */
    public static RedisDeviceRegistry of(String host, int port, String password, String nodeId) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(32);
        config.setMaxIdle(16);
        JedisPool pool = (password == null || password.isEmpty())
                ? new JedisPool(config, host, port)
                : new JedisPool(config, host, port, 2000, password);
        return new RedisDeviceRegistry(pool, "gb28181", nodeId);
    }

    // ==================== 键名 ====================

    private String keyDevice(String deviceId) {
        return prefix + ":device:" + deviceId;
    }

    private String keyDevices() {
        return prefix + ":devices";
    }

    private String keyDomain(String domain) {
        return prefix + ":domains:" + domain;
    }

    private String keyNodes() {
        return prefix + ":nodes";
    }

    // ==================== DeviceRegistry ====================

    @Override
    public void register(DeviceSession session) {
        if (session == null || session.getDeviceId() == null) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> map = toMap(session);
            String key = keyDevice(session.getDeviceId());
            // 会话字段整体覆盖写入
            for (Map.Entry<String, String> e : map.entrySet()) {
                jedis.hset(key, e.getKey(), e.getValue());
            }
            // 过期时间随注册有效期自动清理
            if (session.getExpires() > 0) {
                jedis.expire(key, session.getExpires() + 60);
            }
            jedis.sadd(keyDevices(), session.getDeviceId());
            if (session.getDomain() != null) {
                jedis.sadd(keyDomain(session.getDomain()), session.getDeviceId());
            }
            if (nodeId != null) {
                jedis.sadd(keyNodes(), nodeId);
            }
            log.debug("设备已注册到 Redis: {} @ {} (owner={})",
                    session.getDeviceId(), session.getAddress(), nodeId);
        } catch (Exception e) {
            log.error("注册设备到 Redis 失败: {}", session.getDeviceId(), e);
        }
    }

    @Override
    public DeviceSession unregister(String deviceId) {
        if (deviceId == null) {
            return null;
        }
        try (Jedis jedis = pool.getResource()) {
            DeviceSession session = read(jedis, deviceId);
            jedis.del(keyDevice(deviceId));
            jedis.srem(keyDevices(), deviceId);
            if (session != null && session.getDomain() != null) {
                jedis.srem(keyDomain(session.getDomain()), deviceId);
            }
            return session;
        } catch (Exception e) {
            log.error("从 Redis 注销设备失败: {}", deviceId, e);
            return null;
        }
    }

    @Override
    public DeviceSession get(String deviceId) {
        if (deviceId == null) {
            return null;
        }
        try (Jedis jedis = pool.getResource()) {
            return read(jedis, deviceId);
        } catch (Exception e) {
            log.error("从 Redis 查询设备失败: {}", deviceId, e);
            return null;
        }
    }

    @Override
    public boolean touch(String deviceId) {
        if (deviceId == null) {
            return false;
        }
        try (Jedis jedis = pool.getResource()) {
            String key = keyDevice(deviceId);
            if (!jedis.exists(key)) {
                return false;
            }
            jedis.hset(key, "lastKeepAlive", String.valueOf(System.currentTimeMillis()));
            return true;
        } catch (Exception e) {
            log.error("更新 Redis 心跳失败: {}", deviceId, e);
            return false;
        }
    }

    @Override
    public Collection<DeviceSession> listAll() {
        List<DeviceSession> out = new ArrayList<>();
        try (Jedis jedis = pool.getResource()) {
            Set<String> ids = jedis.smembers(keyDevices());
            for (String id : ids) {
                DeviceSession s = read(jedis, id);
                if (s != null) {
                    out.add(s);
                }
            }
        } catch (Exception e) {
            log.error("列举 Redis 设备失败", e);
        }
        return out;
    }

    @Override
    public List<String> listByDomain(String domain) {
        if (domain == null) {
            return new ArrayList<>();
        }
        try (Jedis jedis = pool.getResource()) {
            return new ArrayList<>(jedis.smembers(keyDomain(domain)));
        } catch (Exception e) {
            log.error("列举域内设备失败: {}", domain, e);
            return new ArrayList<>();
        }
    }

    @Override
    public int size() {
        try (Jedis jedis = pool.getResource()) {
            return (int) jedis.scard(keyDevices());
        } catch (Exception e) {
            log.error("统计 Redis 设备数失败", e);
            return 0;
        }
    }

    @Override
    public void clear() {
        try (Jedis jedis = pool.getResource()) {
            Set<String> ids = jedis.smembers(keyDevices());
            for (String id : ids) {
                jedis.del(keyDevice(id));
            }
            jedis.del(keyDevices());
        } catch (Exception e) {
            log.error("清空 Redis 注册表失败", e);
        }
    }

    /** 列出集群中活跃的节点 ID。 */
    public List<String> listNodes() {
        try (Jedis jedis = pool.getResource()) {
            return new ArrayList<>(jedis.smembers(keyNodes()));
        } catch (Exception e) {
            log.error("列举节点失败", e);
            return new ArrayList<>();
        }
    }

    /** 关闭连接池。 */
    public void shutdown() {
        pool.close();
    }

    // ==================== 内部 ====================

    private DeviceSession read(Jedis jedis, String deviceId) {
        Map<String, String> map = jedis.hgetAll(keyDevice(deviceId));
        return map.isEmpty() ? null : fromMap(map);
    }

    private static Map<String, String> toMap(DeviceSession s) {
        Map<String, String> m = new java.util.HashMap<>();
        m.put("deviceId", s.getDeviceId());
        m.put("ip", nullToEmpty(s.getIp()));
        m.put("port", String.valueOf(s.getPort()));
        m.put("transport", nullToEmpty(s.getTransport()));
        m.put("expires", String.valueOf(s.getExpires()));
        m.put("registerTime", String.valueOf(s.getRegisterTime()));
        m.put("lastKeepAlive", String.valueOf(System.currentTimeMillis()));
        m.put("domain", nullToEmpty(s.getDomain()));
        m.put("platform", String.valueOf(s.isPlatform()));
        m.put("ownerNode", nullToEmpty(s.getOwnerNode()));
        return m;
    }

    private static DeviceSession fromMap(Map<String, String> m) {
        return DeviceSession.builder()
                .deviceId(m.get("deviceId"))
                .ip(emptyToNull(m.get("ip")))
                .port(parseInt(m.get("port")))
                .transport(emptyToNull(m.get("transport")))
                .expires(parseInt(m.get("expires")))
                .registerTime(parseLong(m.get("registerTime")))
                .lastKeepAlive(parseLong(m.get("lastKeepAlive")))
                .domain(emptyToNull(m.get("domain")))
                .platform(Boolean.parseBoolean(m.get("platform")))
                .ownerNode(emptyToNull(m.get("ownerNode")))
                .build();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0L;
        }
    }
}