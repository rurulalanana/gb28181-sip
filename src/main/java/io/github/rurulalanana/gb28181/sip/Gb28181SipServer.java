package io.github.rurulalanana.gb28181.sip;

import io.github.rurulalanana.gb28181.sip.cascade.CascadeManager;
import io.github.rurulalanana.gb28181.sip.cascade.CascadeRegistry;
import io.github.rurulalanana.gb28181.sip.cascade.LocalCascadeRegistry;
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

    /** 级联管理器（国标级联，仅信令层）。 */
    @Getter
    private final CascadeManager cascade;

    private final CredentialProvider credentialProvider;
    /** 接入控制（黑白名单）；{@code null} 表示不限制来源。 */
    private final io.github.rurulalanana.gb28181.sip.security.AccessControl accessControl;
    private final String nodeId;

    private volatile SipStack sipStack;

    /**
     * 主 {@link SipProvider}（第一个协议的那个）。
     *
     * <p>主动发送报文（INVITE / MESSAGE / ACK）默认走它。
     * 多协议时各协议各有 provider，见 {@link #sipProviders}。
     */
    private volatile SipProvider sipProvider;

    /**
     * 全部 {@link SipProvider}（按 transport 排列，主 provider 在前）。
     *
     * <p><b>为什么必须每个协议一个 provider</b>：jain-sip 的
     * {@code getNewServerTransaction} 必须由<b>收到该请求的那个 provider</b>
     * 调用 —— 用 UDP 的 provider 给 TCP 收到的请求建事务会抛异常，
     * 于是 TCP 设备注册后收不到 200 OK，注册永远不成功。
     */
    private final java.util.List<SipProvider> sipProviders = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 包级可见：供 {@link Gb28181SipClient} 判断服务是否可用。 */
    volatile boolean running;

    Gb28181SipServer(SipServerConfig config, SipCallback callback, DeviceRegistry registry,
                     CascadeRegistry cascadeRegistry,
                     CredentialProvider credentialProvider,
                     io.github.rurulalanana.gb28181.sip.security.AccessControl accessControl,
                     String nodeId) {
        this.config = config;
        this.callback = callback == null ? new SipCallback() {
        } : callback;
        this.registry = registry == null ? new LocalDeviceRegistry() : registry;
        this.credentialProvider = credentialProvider;
        this.accessControl = accessControl;
        this.nodeId = nodeId;
        this.factory = new SipMessageFactory(SipFactory.getInstance());
        this.cascade = new CascadeManager(
                config.getServerId(),
                cascadeRegistry == null ? new LocalCascadeRegistry() : cascadeRegistry,
                client(),
                this.callback,
                config.getOfflineTimeout());
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
            // 【关键】允许处理「无对应订阅会话」的 NOTIFY（jain-sip 默认丢弃并回 481）。
            //
            // 现场问题：下级平台（LiveGBS v250103）点「推送通道」时直接发
            // SIP NOTIFY + MANSCDP Catalog 体，而我们从未向它发起过 SUBSCRIBE
            // （国标里 SUBSCRIBE 是可选行为，很多平台不依赖它）。
            // jain-sip 的订阅会话校验发现没有匹配的 dialog，就地回
            // 481 Subscription does not exist，请求根本到不了 processRequest，
            // 表现为「下级点了推送，本级毫无反应，只有重新注册（走 MESSAGE 全量
            // 目录查询）才生效」。
            // 置为 true 后这类 NOTIFY 会正常投递到 SipListener。
            props.setProperty("gov.nist.javax.sip.DELIVER_UNSOLICITED_NOTIFY", "true");
            // NOTIFY 的 Call-ID 由下级自造（如 832621896），与任何本地 dialog 都不匹配，
            // 必须放宽 dialog 校验，否则仍会被协议栈挡掉。
            props.setProperty("gov.nist.javax.sip.DELIVER_TERMINATED_EVENT_FOR_NULL_DIALOG", "true");

            sipStack = sipFactory.createSipStack(props);
            // 【多协议的正确做法】jain-sip 限制：一个 SipStack 只允许一个 SipListener，
            // 所以不能「每个协议一个 provider + 一个 listener」——
            // 第二个 addSipListener 会抛
            // "Stack already has a listener. Only one listener per stack allowed"
            // （实测踩过：TCP 端口已监听但收不到请求）。
            //
            // 正确姿势：每个协议建自己的 ListeningPoint，然后全部挂到
            // **同一个** SipProvider 上。SipProviderImpl#addListeningPoint 支持这个，
            // 收到的请求统一投给同一个 listener，getNewServerTransaction
            // 会由 jain-sip 内部按 transaction 的 transport 正确处理。
            //
            // 【为什么要多协议】GB/T 28181 允许设备用 UDP 或 TCP 传信令，由设备侧配置。
            // 只监听 UDP 时，配成 TCP 的设备根本注册不上，且平台日志一条都没有 ——
            // 极易误判成网络不通或鉴权失败。
            java.util.List<String> transports = resolveTransports();
            if (transports.isEmpty()) {
                throw new IllegalStateException("SIP transport 配置为空");
            }
            java.util.List<ListeningPoint> points = new java.util.ArrayList<>();
            java.util.List<String> started = new java.util.ArrayList<>();
            for (String transport : transports) {
                try {
                    // 注意参数顺序为 (address, port, transport)，与接口声明的命名易混淆
                    points.add(sipStack.createListeningPoint(
                            StringUtils.defaultIfBlank(config.getHost(), "0.0.0.0"),
                            config.getPort(),
                            transport));
                    started.add(transport);
                } catch (Exception e) {
                    // 单协议失败不阻断另一个：设备可能只用其中一种，
                    // 有种能通就比全不启动强。但必须明确告警，否则会以为监听正常。
                    log.warn("SIP {} 监听 {}:{} 失败（另一协议不受影响）: {}",
                            transport, config.getHost(), config.getPort(), e.getMessage());
                }
            }
            if (points.isEmpty()) {
                throw new IllegalStateException(
                        "SIP 所有协议都启动失败，端口 " + config.getPort() + " 可能被占用");
            }
            SipProvider provider = sipStack.createSipProvider(points.get(0));
            // 其余协议挂到同一个 provider 上（见上面「多协议的正确做法」）
            // 注意：本模块 source=8，不能用 instanceof 模式匹配，故显式强转
            if (provider instanceof gov.nist.javax.sip.SipProviderImpl) {
                gov.nist.javax.sip.SipProviderImpl impl =
                        (gov.nist.javax.sip.SipProviderImpl) provider;
                for (int i = 1; i < points.size(); i++) {
                    try {
                        impl.addListeningPoint(points.get(i));
                    } catch (Exception e) {
                        log.warn("SIP 附加 {} 监听失败: {}",
                                started.get(i), e.getMessage());
                        started.remove(i);
                    }
                }
            } else {
                log.warn("SipProvider 非 jain-sip 实现（{}），只能监听 {} 一个协议",
                        provider.getClass().getName(), started.get(0));
                if (points.size() > 1) {
                    started.subList(1, started.size()).clear();
                }
            }
            provider.addSipListener(new GbSipListener(
                    config, new SipMessageFactory(sipFactory), callback, registry,
                    credentialProvider, accessControl, provider, cascade));
            sipProvider = provider;
            sipProviders.add(provider);
            sipStack.start();

            running = true;
            log.info("GB28181 SIP 服务已启动: {}:{}/{}  serverId={} node={}",
                    config.getHost(), config.getPort(), String.join("+", started),
                    config.getServerId(), nodeId);
            callback.onStarted(config.getHost(), config.getPort(), String.join("+", started));
            return this;
        } catch (Exception e) {
            throw new IllegalStateException("SIP 服务启动失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析要监听的协议列表。
     *
     * <p>兼容两种写法：
     * <ul>
     *   <li>单值历史写法 {@code "UDP"}；</li>
     *   <li>逗号分隔多值 {@code "UDP,TCP"}（新默认）。</li>
     * </ul>
     * 大小写不敏感、去重、去空白，非法值（非 UDP/TCP）直接忽略并告警 ——
     * 让配置写错时「部分能用」而不是整个 SIP 起不来。
     */
    private java.util.List<String> resolveTransports() {
        String raw = config.getTransport();
        if (StringUtils.isBlank(raw)) {
            return java.util.List.of("UDP");
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String t = part.trim().toUpperCase(java.util.Locale.ROOT);
            if (t.isEmpty()) {
                continue;
            }
            if (!"UDP".equals(t) && !"TCP".equals(t)) {
                log.warn("忽略不支持的 SIP transport: {}（只支持 UDP / TCP）", t);
                continue;
            }
            out.add(t);
        }
        return new java.util.ArrayList<>(out);
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
            sipProviders.clear();
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

        /**
         * 对外通告地址（写入 From / Contact）。为空时自动推断本机非回环地址。
         *
         * <p>NAT 或多网卡场景下监听地址（{@code 0.0.0.0}）不能直接发给设备，
         * 应显式指定设备可达的本机 IP。
         */
        private String advertisedHost;
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
        private CascadeRegistry cascadeRegistry;
        private CredentialProvider credentialProvider;
        private io.github.rurulalanana.gb28181.sip.security.AccessControl accessControl;
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

        /**
         * 设置对外通告地址（From / Contact 中使用）。
         *
         * <p>监听地址为 {@code 0.0.0.0} 或平台在 NAT 后时必须显式设置，
         * 否则设备收到回环地址的 Contact 无法回包，表现为点播无响应。
         */
        public Builder advertisedHost(String advertisedHost) {
            this.advertisedHost = advertisedHost;
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

        /**
         * 下级平台注册表（国标级联）。
         *
         * <p>不设置则使用内存实现。集群场景可传 Redis 实现以共享拓扑。
         */
        public Builder cascadeRegistry(CascadeRegistry cascadeRegistry) {
            this.cascadeRegistry = cascadeRegistry;
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

        /**
         * 接入控制（黑白名单）；不设置则不限制来源。
         *
         * <p>与 {@link #credentialProvider} 是<b>两道独立</b>的闸门：
         * 本项在摘要认证之前生效，不依赖密码是否正确。
         * 实现方需保证非阻塞且线程安全（在协议栈线程上每个 REGISTER 调用一次）。
         */
        public Builder accessControl(io.github.rurulalanana.gb28181.sip.security.AccessControl control) {
            this.accessControl = control;
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
                    .advertisedHost(advertisedHost)
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
            return new Gb28181SipServer(cfg, callback, registry, cascadeRegistry,
                    credentialProvider, accessControl, id);
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