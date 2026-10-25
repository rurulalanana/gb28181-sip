package io.github.rurulalanana.gb28181.sip.internal;

import io.github.rurulalanana.gb28181.sip.config.SipServerConfig;
import io.github.rurulalanana.gb28181.sip.constant.GbId;
import io.github.rurulalanana.gb28181.sip.device.DeviceRegistry;
import io.github.rurulalanana.gb28181.sip.device.DeviceSession;
import io.github.rurulalanana.gb28181.sip.event.InviteEvent;
import io.github.rurulalanana.gb28181.sip.event.KeepAliveEvent;
import io.github.rurulalanana.gb28181.sip.event.ManscdpEvent;
import io.github.rurulalanana.gb28181.sip.event.RegisterEvent;
import io.github.rurulalanana.gb28181.sip.event.SdpEvent;
import io.github.rurulalanana.gb28181.sip.event.SipCallback;
import io.github.rurulalanana.gb28181.sip.event.SipPeer;
import io.github.rurulalanana.gb28181.sip.event.SubscribeEvent;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpXml;
import io.github.rurulalanana.gb28181.sip.sdp.SdpParser;
import io.github.rurulalanana.gb28181.sip.security.CredentialProvider;
import io.github.rurulalanana.gb28181.sip.security.DigestAuth;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipListener;
import javax.sip.TimeoutEvent;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.header.AuthorizationHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.CSeqHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.Header;
import javax.sip.header.SubjectHeader;
import javax.sip.header.ToHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.util.ArrayList;
import java.util.List;

/**
 * SIP 请求处理器：把协议栈事件翻译为模块回调。
 *
 * <p>处理策略遵循标准各节的"立即应答"要求：
 * <ul>
 *   <li>REGISTER：未带认证 → 401 challenge（9.1.2.1）；认证通过 → 200 OK</li>
 *   <li>MESSAGE：立即 200 OK，<b>应答无消息体</b>（9.5.3.1 c）</li>
 *   <li>SUBSCRIBE：调用方决定有效期（9.11.3.1）</li>
 *   <li>INVITE：<b>不自动应答</b>，由调用方决定 200/488/486（9.2 + 附录 G 注 17）</li>
 * </ul>
 */
@Slf4j
public class GbSipListener implements SipListener {

    private final SipServerConfig config;
    private final SipMessageFactory factory;
    private final SipCallback callback;
    private final DeviceRegistry registry;
    private final CredentialProvider credentialProvider;
    /** 接入控制（黑白名单）；{@code null} 或 PERMISSIVE 表示不限制来源。 */
    private final io.github.rurulalanana.gb28181.sip.security.AccessControl accessControl;
    private final javax.sip.SipProvider provider;
    /** 国标级联管理器；可为 {@code null}（未启用级联）。 */
    private final io.github.rurulalanana.gb28181.sip.cascade.CascadeManager cascade;
    /** 级联目录聚合等待下级应答的超时（毫秒）。 */
    private final long cascadeTimeoutMs = 5000L;

    public GbSipListener(SipServerConfig config, SipMessageFactory factory,
                         SipCallback callback, DeviceRegistry registry,
                         CredentialProvider credentialProvider,
                         javax.sip.SipProvider provider,
                         io.github.rurulalanana.gb28181.sip.cascade.CascadeManager cascade) {
        this(config, factory, callback, registry, credentialProvider, null, provider, cascade);
    }

    public GbSipListener(SipServerConfig config, SipMessageFactory factory,
                         SipCallback callback, DeviceRegistry registry,
                         CredentialProvider credentialProvider,
                         io.github.rurulalanana.gb28181.sip.security.AccessControl accessControl,
                         javax.sip.SipProvider provider,
                         io.github.rurulalanana.gb28181.sip.cascade.CascadeManager cascade) {
        this.config = config;
        this.factory = factory;
        this.callback = callback;
        this.registry = registry;
        this.credentialProvider = credentialProvider;
        this.accessControl = accessControl;
        this.provider = provider;
        this.cascade = cascade;
    }

    /**
     * 取得（必要时创建）服务端事务。
     *
     * <p>jain-sip 1.2.344 不会像旧版那样在投递 {@link RequestEvent} 前自动创建
     * ServerTransaction，若直接使用 {@code event.getServerTransaction()} 将恒为
     * {@code null}，导致无法 {@code sendResponse}。故此处按需显式创建。
     */
    private ServerTransaction serverTransaction(RequestEvent event) {
        try {
            ServerTransaction existing = event.getServerTransaction();
            if (existing != null) {
                return existing;
            }
            Request request = event.getRequest();
            // jain-sip 的 getNewServerTransaction 要求请求带 Max-Forwards（RFC 3261 §8.1.1.6
            // 规定每跳必带），缺失时抛 "Missing a required header : Max-Forwards"，
            // 导致应答被静默丢弃 —— 部分设备会因收不到确认而不推流。
            factory.ensureMaxForwards(request);
            return provider.getNewServerTransaction(request);
        } catch (Exception e) {
            log.warn("创建服务端事务失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void processRequest(RequestEvent requestEvent) {
        Request request = requestEvent.getRequest();
        String method = request.getMethod();
        try {
            switch (method) {
                case Request.REGISTER:
                    handleRegister(requestEvent, request);
                    break;
                case Request.MESSAGE:
                    handleMessage(requestEvent, request);
                    break;
                case Request.INVITE:
                    handleInvite(requestEvent, request);
                    break;
                case Request.ACK:
                    // ACK 无需应答，直接回调
                    callback.onAck(buildInviteEvent(request, requestEvent));
                    break;
                case Request.BYE:
                    handleBye(requestEvent, request);
                    break;
                case Request.INFO:
                    handleInfo(requestEvent, request);
                    break;
                case "SUBSCRIBE":
                    handleSubscribe(requestEvent, request);
                    break;
                case "NOTIFY":
                    handleNotify(requestEvent, request);
                    break;
                default:
                    callback.onRawRequest(requestEvent);
                    reply(requestEvent, Response.OK);
            }
        } catch (Exception e) {
            // 回调异常不得影响协议栈线程
            log.error("处理 SIP 请求失败: {}", method, e);
            safeError(e);
        }
    }

    @Override
    public void processResponse(ResponseEvent responseEvent) {
        callback.onRawResponse(responseEvent);
        // 级联目录聚合：下级平台的 Catalog 应答在此汇聚
        collectCascadeCatalog(responseEvent);
        Gb28181SipClientBridge.onResponse(responseEvent);
    }

    /**
     * 汇聚下级平台的目录应答。
     *
     * <p>仅在应答来自已注册的下级平台、且 SN 命中级联查询上下文时才消费，
     * 其余应答照常透出给业务方。
     */
    private void collectCascadeCatalog(ResponseEvent event) {
        if (cascade == null || event.getResponse() == null) {
            return;
        }
        Response response = event.getResponse();
        if (response.getStatusCode() != Response.OK) {
            return;
        }
        Object raw = response.getRawContent();
        if (raw == null) {
            return;
        }
        // 必须走 ManscdpXml.decode 而不是硬编码 UTF-8：设备可能发 GBK/GB18030，
        // 硬解会得到乱码（详见 ManscdpXml.decode 的兼容策略）
        String xml = io.github.rurulalanana.gb28181.sip.manscdp.ManscdpXml.decode((byte[]) raw);
        io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage msg;
        try {
            msg = io.github.rurulalanana.gb28181.sip.manscdp.ManscdpXml.parse(xml);
        } catch (Exception e) {
            return;
        }
        if (!"Response".equals(msg.getRootName())) {
            return;
        }
        // 应答方：Response 的 DeviceID 是发起方（本级），真正的来源在 From 头
        String from = userOf((javax.sip.header.FromHeader) response.getHeader(SipHeaders.FROM));
        if (from == null) {
            from = msg.getDeviceId();
        }
        java.util.List<io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem> items =
                new java.util.ArrayList<>();
        for (org.w3c.dom.Element e : msg.items("DeviceList")) {
            try {
                items.add(io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem.from(e));
            } catch (Exception ignored) {
                // 单条解析失败不影响整体聚合
            }
        }
        boolean consumed = cascade.onCatalogResponse(from, msg.getSn(), items);
        if (consumed) {
            log.debug("级联目录应答已汇聚: from={}, sn={}", from, msg.getSn());
        }
    }

    @Override
    public void processTimeout(TimeoutEvent timeoutEvent) {
        log.warn("SIP 事务超时: server={}", timeoutEvent.isServerTransaction());
        Gb28181SipClientBridge.onTimeout(timeoutEvent);
    }

    @Override
    public void processIOException(IOExceptionEvent exceptionEvent) {
        log.error("SIP IO 异常: {}:{} transport={}", exceptionEvent.getHost(),
                exceptionEvent.getPort(), exceptionEvent.getTransport());
    }

    @Override
    public void processTransactionTerminated(TransactionTerminatedEvent event) {
        log.debug("SIP 事务结束: server={}", event.isServerTransaction());
    }

    @Override
    public void processDialogTerminated(DialogTerminatedEvent event) {
        log.debug("SIP 对话结束: {}", event.getDialog());
    }

    // =================================================================
    //  REGISTER —— 标准 9.1
    // =================================================================

    private void handleRegister(RequestEvent event, Request request) {
        String deviceId = userOf(fromHeader(request));
        SipPeer peer = peerOf(event, deviceId);
        int expires = expiresOf(request);
        boolean unregister = expires == 0;

        // ---- 接入控制（黑白名单）：标准 9.1，位于摘要认证之前 ----
        /*
         * 为什么在鉴权之前：访问控制是网络层语义，不该以「密码是否正确」为前提。
         * 若放在鉴权之后，黑名单里的设备只要密码对了就能进 —— 那它就不叫黑名单。
         * 更不能当成密码校验失败的替补方案：两者要能独立失效。
         *
         * 注销（Expires: 0）同样要过这一关，否则被拉黑的设备连踢都踢不掉。
         */
        if (accessControl != null
                && accessControl != io.github.rurulalanana.gb28181.sip.security.AccessControl.PERMISSIVE
                && accessControl.isEnabled()) {
            io.github.rurulalanana.gb28181.sip.security.AccessControl.Decision decision =
                    accessControl.check(deviceId, peer.getIp());
            if (decision == null || !decision.isAllowed()) {
                String reason = decision == null ? "接入控制返回空结果" : decision.getReason();
                log.warn("注册被拒绝: device={}, ip={}, 原因={}", deviceId, peer.getIp(), reason);
                reply(event, factory.buildResponse(Response.FORBIDDEN, request, null, null));
                return;
            }
        }

        // ---- 摘要认证（标准 9.1.1 a） ----
        /*
         * 三重判断：null / PERMISSIVE / isEnabled()==false 都表示此刻不鉴权。
         *
         * 最后一项是「运行期可关闭」的开关：平台侧的统一接入密码可以在界面上清空，
         * 清空后必须立刻停止鉴权。若只看前两项（启动时决定），
         * 清空密码后所有设备都会被 403 挡在门外。见 CredentialProvider#isEnabled。
         */
        if (credentialProvider != null && credentialProvider != CredentialProvider.PERMISSIVE
                && credentialProvider.isEnabled()) {
            AuthorizationHeader auth = (AuthorizationHeader) request.getHeader(SipHeaders.AUTHORIZATION);
            if (auth == null) {
                // 无凭据 → 401 challenge
                reply(event, buildUnauthorized(request));
                return;
            }
            String password = credentialProvider.passwordOf(auth.getUsername());
            if (password == null || !DigestAuth.verify(auth, password, Request.REGISTER)) {
                log.warn("注册认证失败: device={}, user={}", deviceId, auth.getUsername());
                reply(event, factory.buildResponse(Response.FORBIDDEN, request, null, null));
                return;
            }
        }

        // ---- 级联分流：下级平台注册进级联注册表，不进设备注册表 ----
        if (cascade != null && cascade.getRegistry().get(deviceId) != null) {
            if (unregister) {
                cascade.onPlatformUnregister(deviceId, "logout");
            } else {
                cascade.onPlatformRegister(deviceId, peer.getIp() + ":" + peer.getPort(), expires);
            }
            reply(event, buildRegisterResponse(request, expires));
            callback.onRegister(RegisterEvent.builder()
                    .peer(peer).deviceId(deviceId).expires(expires)
                    .platformId(deviceId).unregistered(unregister).build());
            return;
        }

        // ---- 认证通过，记录会话 ----
        if (!unregister) {
            String contact = contactUriOf(request);
            registry.register(DeviceSession.builder()
                    .deviceId(deviceId)
                    .ip(peer.getIp())
                    .port(peer.getPort())
                    .transport(peer.getTransport())
                    .expires(expires > 0 ? expires : config.getRegisterExpires())
                    .registerTime(System.currentTimeMillis())
                    .lastKeepAlive(System.currentTimeMillis())
                    .domain(GbId.isValidDeviceId(deviceId) ? GbId.domainOf(deviceId) : config.getDomain())
                    .platform(false)
                    .ownerNode(null)
                    .build());
        } else {
            registry.unregister(deviceId);
        }

        reply(event, buildRegisterResponse(request, unregister ? 0 : expires));

        callback.onRegister(RegisterEvent.builder()
                .peer(peer)
                .deviceId(deviceId)
                .expires(expires)
                .unregistered(unregister)
                .username(userOf(fromHeader(request)))
                .contactUri(contactUriOf(request))
                .localAddress(peer.getAddress())
                .transport(peer.getTransport())
                .platformId(userOf(fromHeader(request)))
                .build());
    }

    // =================================================================
    //  MESSAGE —— 标准 9.3 / 9.5
    // =================================================================

    private void handleMessage(RequestEvent event, Request request) {
        String body = bodyOf(request);
        String fromUser = userOf(fromHeader(request));
        SipPeer peer = peerOf(event, fromUser);

        // 标准 9.5.3.1 c：相关设备收到 MESSAGE 后应立即返回 200 OK，应答无消息体
        reply(event, Response.OK);

        if (StringUtils.isBlank(body)) {
            log.debug("收到空 MESSAGE: from={}", fromUser);
            return;
        }

        ManscdpMessage msg;
        try {
            msg = ManscdpXml.parse(body);
        } catch (Exception e) {
            log.warn("MANSCDP 解析失败: {}", e.getMessage());
            return;
        }

        ManscdpEvent msgEvent = ManscdpEvent.builder()
                .peer(peer)
                .manscdp(msg)
                .xml(body)
                .method(Request.MESSAGE)
                .callId(callIdOf(request))
                .cSeq(cSeqOf(request))
                .request(true)
                .build();

        // ---- 心跳保活（A.2.5.2 / 9.10） ----
        if (io.github.rurulalanana.gb28181.sip.constant.CmdType.KEEPALIVE.value().equals(msg.getCmdType())) {
            // 级联场景：来自下级平台的心跳应刷新平台会话，而非设备注册表
            if (cascade != null && cascade.onPlatformKeepAlive(msg.getDeviceId())) {
                return;
            }
            registry.touch(msg.getDeviceId());
            callback.onKeepAlive(KeepAliveEvent.builder()
                    .peer(peer)
                    .deviceId(msg.getDeviceId())
                    .status(msg.firstText("Status"))
                    .sn(msg.getSn())
                    .faultyDeviceIds(faultyIdsOf(msg))
                    .build());
            return;
        }

        // ---- 级联目录查询：本级作为下级收到上级查询时，聚合各下级后统一应答 ----
        // 仅当本级确实挂有在线下级才走聚合；否则按普通设备级查询交业务方处理，
        // 避免把没有下级的普通查询误判为级联。
        if (cascade != null && cascade.hasOnlineLower()
                && io.github.rurulalanana.gb28181.sip.constant.CmdType.CATALOG.value().equals(msg.getCmdType())
                && "Query".equals(msg.getRootName())) {
            cascade.queryCascadedCatalog(msg.getDeviceId(), msg.getSn(), cascadeTimeoutMs);
            return;
        }

        // 其余交由调用方处理
        /*
         * <Response> 是「对**我们下发**命令的应用层应答」——目录查询、录像检索、
         * 设备信息、设备状态等，语义与请求完全不同，必须走 onManscdpResponse。
         *
         * 改造前两者都走 onManscdpRequest，后果：
         *   1) 录像检索（RecordInfo）的应答没有消费者，平台一直等到超时，
         *      界面上表现为「设备没有录像」—— 而设备其实回了完整列表；
         *   2) 目录查询的 <Response> 被当成「目录推送」处理，跳过了分页聚合，
         *      LiveGBS 这类一帧一页上报的平台会被读成「只有 1 个通道」。
         * 也就是说 SipCallback#onManscdpResponse 此前是死代码，从未被调用。
         */
        if ("Response".equals(msg.getRootName())) {
            try {
                callback.onManscdpResponse(msgEvent);
            } catch (Exception e) {
                log.error("MANSCDP 应答回调异常: cmd={}", msg.getCmdType(), e);
            }
            return;
        }
        Boolean handled = safeDispatch(msgEvent);
        if (handled == null && msg.cmdTypeEnum() == null) {
            callback.onUnknownCommand(msgEvent);
        }
    }

    /**
     * 构造 REGISTER 应答（标准 9.10.3）。
     *
     * <p>设备注册与下级平台注册共用；{@code expires <= 0} 表示注销应答，不带Expires 头。
     */
    private Response buildRegisterResponse(Request request, int expires) {
        Response response = factory.buildResponse(Response.OK, request, null, null);
        try {
            // Date 头须为 RFC 1123 格式，否则协议栈解析报 "bad wkday"
            response.addHeader(factory.headerFactory().createHeader(SipHeaders.DATE,
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                            .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC))));
            response.addHeader(factory.headerFactory().createHeader(SipHeaders.SERVER, "gb28181-sip/1.0"));
            response.addHeader(factory.headerFactory().createUserAgentHeader(
                    java.util.Collections.singletonList("gb28181-sip")));
            if (expires > 0) {
                response.addHeader(factory.headerFactory().createExpiresHeader(
                        expires > 0 ? expires : config.getRegisterExpires()));
            }
        } catch (Exception e) {
            log.warn("构造 REGISTER 应答头失败: {}", e.getMessage());
        }
        return response;
    }

    /** 提取心跳 {@code <Info>} 中的故障设备编码。 */
    private static List<String> faultyIdsOf(ManscdpMessage msg) {
        List<String> out = new ArrayList<>();
        msg.elements("Info").forEach(info ->
                msg.indexChildren(info).getOrDefault("DeviceID", new ArrayList<>())
                        .forEach(e -> out.add(ManscdpMessage.textOf(e))));
        return out;
    }

    // =================================================================
    //  INVITE / BYE / INFO —— 标准 9.2 / 9.8 / 9.9
    // =================================================================

    private void handleInvite(RequestEvent event, Request request) {
        // 模块不处理流媒体，也不自动应答 INVITE（标准注 17 由设备/调用方决定 488/486）
        InviteEvent invite = buildInviteEvent(request, event);
        callback.onInvite(invite);

        // ---- 级联点播：上级对下级设备点播时，流转发交业务方处理 ----
        if (cascade != null && invite.getPeer() != null
                && cascade.getRegistry().get(invite.getPeer().getDeviceId()) != null) {
            String platformId = invite.getPeer().getDeviceId();
            io.github.rurulalanana.gb28181.sip.cascade.CascadeEvents.CascadeInvite cascadeInvite =
                    io.github.rurulalanana.gb28181.sip.cascade.CascadeEvents.CascadeInvite.builder()
                            .platformId(platformId)
                            .ssrc(invite.getSdp() == null ? null : invite.getSdp().getSsrc())
                            .playback(invite.isPlayback())
                            .rawRequestEvent(event)
                            .build();
            try {
                callback.onCascadeInvite(cascadeInvite);
            } catch (Exception e) {
                log.error("级联点播回调异常", e);
            }
        }
    }

    private void handleBye(RequestEvent event, Request request) {
        callback.onBye(buildInviteEvent(request, event));
        reply(event, Response.OK);
    }

    private void handleInfo(RequestEvent event, Request request) {
        String rawSdp = bodyOf(request);
        callback.onInfo(SdpEvent.builder()
                .peer(peerOf(event, userOf(fromHeader(request))))
                .method(Request.INFO)
                .callId(callIdOf(request))
                .cSeq(cSeqOf(request))
                .sdp(SdpParser.parse(rawSdp))
                .rawSdp(rawSdp)
                .build());
        reply(event, Response.OK);
    }

    private InviteEvent buildInviteEvent(Request request, RequestEvent event) {
        String rawSdp = bodyOf(request);
        return InviteEvent.builder()
                .peer(peerOf(event, userOf(fromHeader(request))))
                .method(request.getMethod())
                .callId(callIdOf(request))
                .localTag(tagOf(toHeader(request)))
                .remoteTag(tagOf(fromHeader(request)))
                .cSeq(cSeqOf(request))
                .subject(subjectOf(request))
                .sdp(SdpParser.parse(rawSdp))
                .rawSdp(rawSdp)
                .rawRequestEvent(event)
                .build();
    }

    // =================================================================
    //  SUBSCRIBE / NOTIFY —— 标准 9.11
    // =================================================================

    private void handleSubscribe(RequestEvent event, Request request) {
        int expires = expiresOf(request);
        String eventName = eventNameOf(request);
        SubscribeEvent subEvent = SubscribeEvent.builder()
                .peer(peerOf(event, userOf(fromHeader(request))))
                .method("SUBSCRIBE")
                .eventName(eventName)
                .expires(expires)
                .callId(callIdOf(request))
                .localTag(tagOf(toHeader(request)))
                .remoteTag(tagOf(fromHeader(request)))
                .subscriptionState(null)
                .requestUri(factory.requestUriOf(request))
                .body(bodyOf(request))
                .build();

        int granted = 0;
        try {
            granted = callback.onSubscribe(subEvent);
        } catch (Exception e) {
            log.error("SUBSCRIBE 回调异常", e);
        }

        if (granted <= 0) {
            // 调用方拒绝订阅 → 4xx 结束本次会话（标准 9.11.3.1 图 25）
            reply(event, factory.buildResponse(Response.NOT_FOUND, request, null, null));
            return;
        }
        Response response = factory.buildResponse(Response.OK, request, null, null);
        if (config.isAutoSubscriptionState()) {
            factory.addSubscriptionState(response, "active", granted);
        }
        send(event, response);

        // ---- 级联订阅穿透：上级订阅本级目录时，同步向各在线下级发起订阅 ----
        if (cascade != null && cascade.getRegistry().get(subEvent.getPeer().getDeviceId()) != null) {
            cascade.subscribeCascade(subEvent.getPeer().getDeviceId(), eventName, granted);
        }
    }

    private void handleNotify(RequestEvent event, Request request) {
        callback.onNotify(SubscribeEvent.builder()
                .peer(peerOf(event, userOf(fromHeader(request))))
                .method("NOTIFY")
                .eventName(eventNameOf(request))
                .expires(expiresOf(request))
                .callId(callIdOf(request))
                .localTag(tagOf(toHeader(request)))
                .remoteTag(tagOf(fromHeader(request)))
                .subscriptionState(subscriptionStateOf(request))
                .requestUri(factory.requestUriOf(request))
                .body(bodyOf(request))
                .build());
        reply(event, Response.OK);
    }

    // =================================================================
    //  应答工具
    // =================================================================

    private Response buildUnauthorized(Request request) {
        return factory.buildUnauthorized(request, config.getDomain(), DigestAuth.newNonce());
    }

    private void reply(RequestEvent event, int statusCode) {
        reply(event, factory.buildResponse(statusCode, event.getRequest(), null, null));
    }

    private void reply(RequestEvent event, Response response) {
        send(event, response);
    }

    private void send(RequestEvent event, Response response) {
        try {
            ServerTransaction transaction = serverTransaction(event);
            if (transaction != null) {
                transaction.sendResponse(response);
            } else {
                log.warn("无服务端事务，丢弃状态码 {} 的应答", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("发送响应失败: {}", response.getStatusCode(), e);
        }
    }

    private Boolean safeDispatch(ManscdpEvent event) {
        try {
            return callback.onManscdpRequest(event);
        } catch (Exception e) {
            log.error("MANSCDP 回调异常: cmd={}", event.getCmdType(), e);
            safeError(e);
            return Boolean.FALSE;
        }
    }

    private void safeError(Throwable t) {
        try {
            callback.onError(t);
        } catch (Exception ignore) {
            // 回调自身的异常忽略
        }
    }

    // =================================================================
    //  头域工具
    // =================================================================

    /** 从 URI 中取 user 部分作为设备编码。 */
    static String userOf(FromHeader from) {
        if (from == null || from.getAddress() == null || from.getAddress().getURI() == null) {
            return null;
        }
        String uri = from.getAddress().getURI().toString();
        int at = uri.indexOf(':');
        int amp = uri.indexOf('@');
        if (at >= 0 && amp > at) {
            return uri.substring(at + 1, amp);
        }
        return null;
    }

    private static String tagOf(ToHeader to) {
        return to == null ? null : to.getTag();
    }

    private static String tagOf(FromHeader from) {
        return from == null ? null : from.getTag();
    }

    private static String callIdOf(Request request) {
        CallIdHeader h = (CallIdHeader) request.getHeader(SipHeaders.CALL_ID);
        return h == null ? null : h.getCallId();
    }

    private static FromHeader fromHeader(Request request) {
        return (FromHeader) request.getHeader(SipHeaders.FROM);
    }

    private static ToHeader toHeader(Request request) {
        return (ToHeader) request.getHeader(SipHeaders.TO);
    }

    private static long cSeqOf(Request request) {
        CSeqHeader h = (CSeqHeader) request.getHeader(SipHeaders.CSEQ);
        return h == null ? 0 : h.getSeqNumber();
    }

    private static String subjectOf(Request request) {
        SubjectHeader h = (SubjectHeader) request.getHeader(SipHeaders.SUBJECT);
        return h == null ? null : h.getSubject();
    }

    /** 取 Expires 头；缺失时回退 Contact 参数。 */
    private static int expiresOf(Request request) {
        ExpiresHeader h = (ExpiresHeader) request.getHeader(SipHeaders.EXPIRES);
        if (h != null) {
            return h.getExpires();
        }
        try {
            javax.sip.header.ContactHeader c =
                    (javax.sip.header.ContactHeader) request.getHeader(SipHeaders.CONTACT);
            if (c != null && c.getExpires() != -1) {
                return c.getExpires();
            }
        } catch (Exception ignore) {
            // 部分实现不支持 Contact expires
        }
        return -1;
    }

    private static String contactUriOf(Request request) {
        try {
            javax.sip.header.ContactHeader c =
                    (javax.sip.header.ContactHeader) request.getHeader(SipHeaders.CONTACT);
            if (c != null && c.getAddress() != null && c.getAddress().getURI() != null) {
                return c.getAddress().getURI().toString();
            }
        } catch (Exception ignore) {
            // 忽略
        }
        return null;
    }

    private static String eventNameOf(Request request) {
        try {
            javax.sip.header.EventHeader e =
                    (javax.sip.header.EventHeader) request.getHeader(SipHeaders.EVENT);
            if (e != null) {
                return StringUtils.defaultIfBlank(e.getEventType(), e.getEventId());
            }
        } catch (Exception ignore) {
            // 忽略
        }
        return null;
    }

    private static String subscriptionStateOf(Request request) {
        try {
            javax.sip.header.SubscriptionStateHeader s =
                    (javax.sip.header.SubscriptionStateHeader)
                            request.getHeader(SipHeaders.SUBSCRIPTION_STATE);
            if (s != null) {
                return s.getState();
            }
        } catch (Exception ignore) {
            // 忽略
        }
        return null;
    }

    /** 取请求体文本。 */
    private static String bodyOf(Request request) {
        byte[] content = request.getRawContent();
        return content == null || content.length == 0 ? null : ManscdpXml.decode(content);
    }

    /** 构造对端信息。 */
    private SipPeer peerOf(RequestEvent event, String deviceId) {
        try {
            javax.sip.header.ViaHeader via =
                    (javax.sip.header.ViaHeader) event.getRequest().getHeader(SipHeaders.VIA);
            // received/rport 由协议栈在收包时填充；部分实现下可能为空，需回退到 Via 的 host/port
            String ip = null;
            int port = 0;
            String transport = config.getTransport();
            if (via != null) {
                ip = StringUtils.firstNonBlank(via.getReceived(), via.getHost());
                port = via.getRPort() > 0 ? via.getRPort() : via.getPort();
                transport = StringUtils.defaultIfBlank(via.getTransport(), transport);
            }
            return SipPeer.builder()
                    .deviceId(deviceId)
                    .ip(ip)
                    .port(port)
                    .transport(transport)
                    .domain(GbId.isValidDeviceId(deviceId) ? GbId.domainOf(deviceId) : config.getDomain())
                    .build();
        } catch (Exception e) {
            return SipPeer.builder().deviceId(deviceId)
                    .transport(config.getTransport()).build();
        }
    }

    @Override
    public String toString() {
        return "GbSipListener";
    }
}