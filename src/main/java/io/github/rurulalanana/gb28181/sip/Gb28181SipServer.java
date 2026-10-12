package io.github.rurulalanana.gb28181.sip;

import io.github.rurulalanana.gb28181.sip.config.SipServerConfig;
import io.github.rurulalanana.gb28181.sip.constant.ManscdpTypes;
import io.github.rurulalanana.gb28181.sip.device.DeviceRegistry;
import io.github.rurulalanana.gb28181.sip.device.LocalDeviceRegistry;
import io.github.rurulalanana.gb28181.sip.event.SipCallback;
import io.github.rurulalanana.gb28181.sip.internal.GbSipListener;
import io.github.rurulalanana.gb28181.sip.internal.SipMessageFactory;
import io.github.rurulalanana.gb28181.sip.security.Credential;
import io.github.rurulalanana.gb28181.sip.security.CredentialProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.sip.ListeningPoint;
import javax.sip.SipFactory;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import java.util.Map;
import java.util.Properties;

/**
 * GB/T 28181—2022 SIP 服务。
 *
 * <p><b>统一声明入口</b>，其余模块通过它声明 SIP 端口与行为：
 * <pre>{@code
 * // 最小可用
 * Gb28181SipServer server = Gb28181SipServer.builder()
 *         .serverId("34020000002000000001")
 *         .port(5060)
 *         .start();
 *
 * // 完整用法
 * Gb28181SipServer server = Gb28181SipServer.builder()
 *         .serverId("34020000002000000001")
 *         .host("0.0.0.0")
 *         .port(5060)
 *         .transport("UDP")
 *         .credential(Credential.of("34020000001320000001", "123456"))
 *         .callback(new MyCallback())
 *         .build()
 *         .start();
 *
 * Gb28181SipClient client = server.client();
 * }</pre>
 *
 * <p><b>设计边界</b>：
 * <ul>
 *   <li>只做 SIP / MANSCDP / SDP 协议层，不含流媒体、不含业务；</li>
 *   <li>零 Spring 依赖，可运行于任意 Java 容器；</li>
 *   <li>集群通过 {@link DeviceRegistry} 扩展（Redis 实现见 {@code RedisDeviceRegistry}）。</li>
 * </ul>
 */
@Slf4j
public class Gb28181SipServer implements AutoCloseable {

    @Getter
    private final SipServerConfig config;

    @Getter
    private final SipMessageFactory factory;

    @Getter
    private final DeviceRegistry registry;

    @Getter
    private final SipCallback callback;

    private final CredentialProvider credentialProvider;
    private final String nodeId;

    private volatile SipStack sipStack;
    private volatile SipProvider sipProvider;
    /** 包级可见：供 {@link Gb28181SipClient} 判断服务是否可用。 */
    volatile boolean running;

    Gb28181SipServer(SipServerConfig config, SipCallback callback, DeviceRegistry registry,
                     CredentialProvider credentialProvider, String nodeId) {
        this.config = config;
        this.callback = callback == null ? new SipCallback() {
        } : callback;
        this.registry = registry == null ? new LocalDeviceRegistry() : registry;
        this.credentialProvider = credentialProvider;
        this.nodeId = nodeId;
        this.factory = new SipMessageFactory(SipFactory.getInstance());
    }

    // ==================== 生命周期 ====================

    /** 启动服务并绑定端口。 */
    public synchronized Gb28181SipServer start() {
        if (running) {
            log.warn("SIP 服务已运行: {}:{}", config.getHost(), config.getPort());
            return this;
        }
        try {
            SipFactory sipFactory = SipFactory.getInstance();
            Properties props = new Properties();
            props.setProperty("javax.sip.STACK_NAME", "gb28181-" + nodeId);
            // 设备侧 NAT 场景：记录收到的源地址而非 Contact
            props.setProperty("javax.sip.IP_TABLES", "");

            sipStack = sipFactory.createSipStack(props);
            // 注意参数顺序为 (address, port, transport)，与接口声明的命名易混淆
            ListeningPoint lp = sipStack.createListeningPoint(
                    StringUtils.defaultIfBlank(config.getHost(), "0.0.0.0"),
                    config.getPort(),
                    config.getTransport());
            sipProvider = sipStack.createSipProvider(lp);
            // jain-sip 1.2.344 不再自动创建 ServerTransaction，需由监听器显式创建后才能应答
            sipProvider.addSipListener(new GbSipListener(
                    config, new SipMessageFactory(sipFactory), callback, registry,
                    credentialProvider, sipProvider));
            sipStack.start();

            running = true;
            log.info("GB28181 SIP 服务已启动: {}:{}/{}  serverId={} node={}",
                    config.getHost(), config.getPort(), config.getTransport(),
                    config.getServerId(), nodeId);
            callback.onStarted(config.getHost(), config.getPort(), config.getTransport());
            return this;
        } catch (Exception e) {
            throw new IllegalStateException("SIP 服务启动失败: " + e.getMessage(), e);
        }
    }

    /** 停止服务并释放端口。 */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        try {
            if (sipStack != null) {
                sipStack.stop();
            }
        } catch (Exception e) {
            log.warn("停止 SIP 栈异常", e);
        } finally {
            running = false;
            sipStack = null;
            sipProvider = null;
            log.info("GB28181 SIP 服务已停止");
            callback.onStopped();
        }
    }

    /** 是否处于运行状态。 */
    public boolean isRunning() {
        return running;
    }

    /** 获取本实例的协议栈提供者（供内部发报文使用）。 */
    public SipProvider sipProvider() {
        return sipProvider;
    }

    /** 获取 SIP 栈。 */
    public SipStack sipStack() {
        return sipStack;
    }

    /** 本节点 ID（集群路由用）。 */
    public String nodeId() {
        return nodeId;
    }

    /** 包级访问配置（供同包客户端使用）。 */
    SipServerConfig config() {
        return config;
    }

    /** 包级访问注册表（供同包客户端使用）。 */
    DeviceRegistry deviceRegistry() {
        return registry;
    }

    /**
     * 创建客户端，用于主动发起查询、控制、点播等请求。
     *
     * <p>客户端与本服务端共用 SIP 栈，因此可同时提供双向服务。
     */
    public Gb28181SipClient client() {
        return new Gb28181SipClient(this);
    }

    @Override
    public void close() {
        stop();
    }

    // ==================== Builder ====================

    /**
     * {@link Gb28181SipServer} 的声明式构建器。
     *
     * <p>所有参数均有合理默认值，仅 {@code serverId} 与 {@code port} 建议显式指定。
     */
    public static class Builder {

        private String serverId = "34020000002000000001";
        private String host;
        private int port = 5060;
        private String transport = "UDP";
        private int registerExpires = ManscdpTypes.DEFAULT_REGISTER_EXPIRES;
        private int refreshInterval = ManscdpTypes.DEFAULT_REFRESH_INTERVAL;
        private int offlineTimeout = 180;
        private boolean legacyMediaControl;
        private boolean autoSubscriptionState = true;
        private long transactionTimeoutMillis = 30_000L;
        private int cleanupInterval = 60;
        private SipCallback callback;
        private DeviceRegistry registry;
        private CredentialProvider credentialProvider;
        private String nodeId;

        /** 平台/服务器编码（20 位）。 */
        public Builder serverId(String serverId) {
            this.serverId = serverId;
            return this;
        }

        /** 监听 IP，默认全部网卡。 */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /** 监听端口，默认 5060。 */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /** 传输协议：{@code UDP}（默认）或 {@code TCP}。 */
        public Builder transport(String transport) {
            this.transport = transport;
            return this;
        }

        /** 注册有效期（秒），默认 86400。 */
        public Builder registerExpires(int seconds) {
            this.registerExpires = seconds;
            return this;
        }

        /** 注册刷新间隔（秒），默认 3600。 */
        public Builder refreshInterval(int seconds) {
            this.refreshInterval = seconds;
            return this;
        }

        /** 离线判定超时（秒），默认 180。 */
        public Builder offlineTimeout(int seconds) {
            this.offlineTimeout = seconds;
            return this;
        }

        /** 启用 2016 版 MediaControl 兼容（2022 版已删除该命令）。 */
        public Builder enableLegacyMediaControl(boolean enabled) {
            this.legacyMediaControl = enabled;
            return this;
        }

        /** 是否自动为 SUBSCRIBE 应答携带 Subscription-State。 */
        public Builder autoSubscriptionState(boolean enabled) {
            this.autoSubscriptionState = enabled;
            return this;
        }

        /** 事务超时（毫秒）。 */
        public Builder transactionTimeout(long millis) {
            this.transactionTimeoutMillis = millis;
            return this;
        }

        /** 过期会话清理周期（秒）。 */
        public Builder cleanupInterval(int seconds) {
            this.cleanupInterval = seconds;
            return this;
        }

        /** 事件回调。 */
        public Builder callback(SipCallback callback) {
            this.callback = callback;
            return this;
        }

        /** 设备注册表；集群场景传入 Redis 实现。 */
        public Builder registry(DeviceRegistry registry) {
            this.registry = registry;
            return this;
        }

        /** 凭据提供者；不设置则不校验注册认证。 */
        public Builder credentialProvider(CredentialProvider provider) {
            this.credentialProvider = provider;
            return this;
        }

        /** 单设备凭据便捷方法。 */
        public Builder credential(Credential credential) {
            this.credentialProvider = CredentialProvider.fixed(credential);
            return this;
        }

        /** 用户名-口令 Map 便捷方法。 */
        public Builder credentials(Map<String, String> users) {
            this.credentialProvider = CredentialProvider.ofMap(users);
            return this;
        }

        /** 节点 ID（集群路由用），默认取 serverId。 */
        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        /** 构建（不启动）。 */
        public Gb28181SipServer build() {
            if (serverId == null || serverId.trim().isEmpty()) {
                throw new IllegalArgumentException("serverId 不能为空");
            }
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("端口非法: " + port);
            }
            String id = (nodeId == null || nodeId.trim().isEmpty()) ? serverId : nodeId;
            SipServerConfig cfg = SipServerConfig.builder()
                    .serverId(serverId)
                    .domain(io.github.rurulalanana.gb28181.sip.constant.GbId.isValidDeviceId(serverId)
                            ? io.github.rurulalanana.gb28181.sip.constant.GbId.domainOf(serverId)
                            : StringUtils.left(serverId, 10))
                    .host(host)
                    .port(port)
                    .transport(transport)
                    .registerExpires(registerExpires)
                    .refreshInterval(refreshInterval)
                    .offlineTimeout(offlineTimeout)
                    .legacyMediaControl(legacyMediaControl)
                    .autoSubscriptionState(autoSubscriptionState)
                    .transactionTimeoutMillis(transactionTimeoutMillis)
                    .cleanupInterval(cleanupInterval)
                    .build();
            return new Gb28181SipServer(cfg, callback, registry, credentialProvider, id);
        }

        /** 构建并启动。 */
        public Gb28181SipServer start() {
            return build().start();
        }
    }

    /** 创建构建器。 */
    public static Builder builder() {
        return new Builder();
    }
}