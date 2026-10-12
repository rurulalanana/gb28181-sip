package io.github.rurulalanana.gb28181.sip.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.function.Consumer;

/**
 * 基于 Redis Pub/Sub 的跨节点信令转发。
 *
 * <p><b>微服务多实例</b>典型场景：
 * <ol>
 *   <li>设备注册到节点 A（{@link RedisDeviceRegistry} 记录 {@code ownerNode=A}）；</li>
 *   <li>业务请求经负载均衡落到节点 B；</li>
 *   <li>B 查注册表发现设备在 A，于是通过 Redis 把信令转发到 A；</li>
 *   <li>A 收到后用本地 SIP 栈发给设备，响应再回传 B。</li>
 * </ol>
 *
 * <p><b>通道设计</b>：每个节点订阅 {@code {prefix}:relay:{nodeId}}；
 * 投递时 PUBLISH 到目标节点的频道。
 *
 * <p><b>依赖</b>：需引入 {@code redis.clients:jedis}。
 */
@Slf4j
public class RedisRelay implements MessageRelay {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JedisPool pool;
    private final String prefix;
    private final String nodeId;
    private final String channel;
    /** 订阅线程。 */
    private volatile Thread subscriber;
    /** 消息处理器，由 {@link Gb28181SipServer} 注册。 */
    private volatile Consumer<RelayMessage> handler;

    public RedisRelay(JedisPool pool, String prefix, String nodeId) {
        this.pool = pool;
        this.prefix = prefix == null ? "gb28181" : prefix;
        this.nodeId = nodeId;
        this.channel = this.prefix + ":relay:" + nodeId;
    }

    /** 便捷构造：指定 host/port。 */
    public static RedisRelay of(String host, int port, String nodeId) {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(16);
        cfg.setMaxIdle(8);
        return new RedisRelay(new JedisPool(cfg, host, port), "gb28181", nodeId);
    }

    /** 便捷构造：指定 host/port/密码。 */
    public static RedisRelay of(String host, int port, String password, String nodeId) {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(16);
        cfg.setMaxIdle(8);
        JedisPool pool = (password == null || password.isEmpty())
                ? new JedisPool(cfg, host, port)
                : new JedisPool(cfg, host, port, 2000, password);
        return new RedisRelay(pool, "gb28181", nodeId);
    }

    /**
     * 注册本节点的收信处理器并启动订阅线程。
     *
     * @param handler 收到转发信令时的处理逻辑
     */
    public void subscribe(Consumer<RelayMessage> handler) {
        this.handler = handler;
        this.subscriber = new Thread(this::listenLoop, "gb28181-relay-" + nodeId);
        this.subscriber.setDaemon(true);
        this.subscriber.start();
        log.info("已订阅 Redis 转发频道: {}", channel);
    }

    private void listenLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try (Jedis jedis = pool.getResource()) {
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String ch, String message) {
                        if (!channel.equals(ch)) {
                            return;
                        }
                        try {
                            RelayMessage rm = MAPPER.readValue(message, RelayMessage.class);
                            Consumer<RelayMessage> h = handler;
                            if (h != null) {
                                h.accept(rm);
                            }
                        } catch (Exception e) {
                            log.warn("转发信令反序列化失败: {}", e.getMessage());
                        }
                    }
                }, channel);
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                log.warn("Redis 转发订阅中断，3 秒后重连: {}", e.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public boolean deliver(String targetNode, RelayMessage message) {
        if (targetNode == null || message == null) {
            return false;
        }
        try (Jedis jedis = pool.getResource()) {
            long receivers = jedis.publish(prefix + ":relay:" + targetNode,
                    MAPPER.writeValueAsString(message));
            log.debug("转发信令到节点 {}: {} (订阅者 {})", targetNode, message.getMethod(), receivers);
            return receivers > 0;
        } catch (Exception e) {
            log.error("转发信令到节点 {} 失败: {}", targetNode, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean clustered() {
        return true;
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    @Override
    public void close() {
        Thread t = subscriber;
        if (t != null) {
            t.interrupt();
        }
        pool.close();
    }
}