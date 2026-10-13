package io.github.rurulalanana.gb28181.sip;

import io.github.rurulalanana.gb28181.sip.constant.CmdType;
import io.github.rurulalanana.gb28181.sip.constant.GbId;
import io.github.rurulalanana.gb28181.sip.device.DeviceSession;
import io.github.rurulalanana.gb28181.sip.event.SipPeer;
import io.github.rurulalanana.gb28181.sip.internal.SipBuildException;
import io.github.rurulalanana.gb28181.sip.internal.SipHeaders;
import io.github.rurulalanana.gb28181.sip.internal.SipMessageFactory;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpBuilder;
import io.github.rurulalanana.gb28181.sip.sdp.SdpMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.sip.ClientTransaction;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipProvider;
import javax.sip.TimeoutEvent;
import javax.sip.address.SipURI;
import javax.sip.header.FromHeader;
import javax.sip.header.Header;
import javax.sip.header.ToHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GB28181 SIP 客户端：主动发起查询、控制、点播等请求。
 *
 * <p>与 {@link Gb28181SipServer} 共用同一 SIP 栈，因此可同时提供服务端能力。
 *
 * <p><b>关于流媒体</b>：本类只负责 SIP 侧的 {@code INVITE}/{@code ACK}/{@code BYE} 与
 * SDP 协商。收到 {@code 200 OK} 后，实际的推拉流动作由调用方在
 * {@link io.github.rurulalanana.gb28181.sip.event.SipCallback#onRawResponse} 中处理。
 *
 * <p>SN 由内部原子计数器生成并自动回绕到标准要求的 [1, 9999]。
 */
@Slf4j
public class Gb28181SipClient implements AutoCloseable {

    private final Gb28181SipServer server;
    private final SipMessageFactory factory;
    private final AtomicInteger snCounter = new AtomicInteger();
    /** Call-ID → 等待中的同步请求结果。 */
    private final Map<String, CountDownLatch> pending = new ConcurrentHashMap<>();
    /** Call-ID → 收到的首个最终响应。 */
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    /** Call-ID → 对话框内下一个 CSeq（INVITE/ACK 为 1，其后 INFO/BYE 递增）。 */
    private final Map<String, java.util.concurrent.atomic.AtomicLong> dialogCSeq = new ConcurrentHashMap<>();

    Gb28181SipClient(Gb28181SipServer server) {
        this.server = server;
        this.factory = server.getFactory();
        io.github.rurulalanana.gb28181.sip.internal.Gb28181SipClientBridge.register(this);
    }

    /** 解除注册，避免内存泄漏。 */
    @Override
    public void close() {
        io.github.rurulalanana.gb28181.sip.internal.Gb28181SipClientBridge.unregister(this);
    }

    // ==================== SN ====================

    /**
     * 生成下一个合法 SN（1~9999，自动回绕）。
     *
     * <p>标准 A.2.1.3 规定 {@code SNType} 取值范围为 [1, 9999]，超范围设备会丢包。
     */
    public int nextSn() {
        int v = Math.floorMod(snCounter.getAndIncrement(), 9999) + 1;
        return v;
    }

    /** 发送 MANSCDP MESSAGE（标准 9.3 c / 9.5 d）。 */
    public void sendMessage(String deviceId, String xml) {
        SipPeer peer = requireSession(deviceId);
        Request request = factory.buildMessage(
                requestUri(peer, deviceId),
                serverUri(deviceId, configHost(), configPort()),
                factory.createUri(serverId(), peer.getIp(), peer.getPort()),
                xml);
        send(request, peer);
    }

    /** 设备目录查询（标准 9.5）。 */
    public void queryCatalog(String deviceId) {
        sendMessage(deviceId, ManscdpBuilder.catalogQuery(nextSn(), deviceId));
    }

    /** 设备目录查询（带时间范围）。 */
    public void queryCatalog(String deviceId, String startTime, String endTime) {
        sendMessage(deviceId, ManscdpBuilder.catalogQuery(nextSn(), deviceId, startTime, endTime));
    }

    /** 设备信息查询（标准 9.5）。 */
    public void queryDeviceInfo(String deviceId) {
        sendMessage(deviceId, ManscdpBuilder.deviceInfoQuery(nextSn(), deviceId));
    }

    /** 设备状态查询（标准 9.6）。 */
    public void queryDeviceStatus(String deviceId) {
        sendMessage(deviceId, ManscdpBuilder.deviceStatusQuery(nextSn(), deviceId));
    }

    /** 预置位查询（标准 9.5）。 */
    public void queryPresets(String deviceId) {
        sendMessage(deviceId, ManscdpBuilder.presetQuery(nextSn(), deviceId));
    }

    /** 历史音视频文件检索（标准 9.8）。 */
    public void queryRecordInfo(String deviceId, String startTime, String endTime) {
        sendMessage(deviceId,
                ManscdpBuilder.recordInfoQuery(nextSn(), deviceId, startTime, endTime));
    }

    // ==================== 控制命令 ====================

    /** PTZ 云台控制（标准 9.3 / A.2.3.1.2）。 */
    public void ptzControl(String deviceId, String ptzCmd) {
        sendMessage(deviceId, ManscdpBuilder.ptzControl(nextSn(), deviceId, ptzCmd));
    }

    /** PTZ 预置位调用。 */
    public void ptzPresetCall(String deviceId, int presetIndex) {
        ptzControl(deviceId, io.github.rurulalanana.gb28181.sip.constant.PtzCommand.presetCall(addressOf(deviceId), presetIndex));
    }

    /** PTZ 精准控制（标准 A.2.3.1.11）。 */
    public void ptzPrecise(String deviceId, Double pan, Double tilt, Double zoom) {
        sendMessage(deviceId,
                ManscdpBuilder.ptzPreciseControl(nextSn(), deviceId, pan, tilt, zoom));
    }

    /** 强制关键帧（标准 A.2.3.1.7）。 */
    public void forceIFrame(String deviceId) {
        sendMessage(deviceId, ManscdpBuilder.forceIFrame(nextSn(), deviceId));
    }

    /** 远程重启（标准 A.2.3.1.3）。 */
    public void remoteBoot(String deviceId) {
        sendMessage(deviceId, ManscdpBuilder.teleBoot(nextSn(), deviceId));
    }

    /** 存储卡格式化（标准 A.2.3.1.13），{@code sdCardId=0} 表示全部。 */
    public void formatSdCard(String deviceId, int sdCardId) {
        sendMessage(deviceId, ManscdpBuilder.formatSdCard(nextSn(), deviceId, sdCardId));
    }

    // ==================== 订阅与通知（标准 9.11）====================

    /**
     * 发起事件订阅（标准 9.11，RFC 6665）。
     *
     * @param eventName 事件名，如 {@code Catalog} / {@code Alarm} / {@code MobilePosition}
     * @param expires   订阅有效期（秒）
     */
    public void subscribe(String deviceId, String eventName, int expires) {
        subscribe(deviceId, eventName, expires, null);
    }

    /**
     * 发起事件订阅（携带 MANSCDP 请求体）。
     *
     * @param body 消息体，如目录订阅的 {@code <Query><CmdType>Catalog</CmdType>...}；可 {@code null}
     */
    public void subscribe(String deviceId, String eventName, int expires, String body) {
        SipPeer peer = requireSession(deviceId);
        Request request = factory.buildSubscribe(
                requestUri(peer, deviceId),
                serverUri(deviceId, configHost(), configPort()),
                factory.createUri(serverId(), peer.getIp(), peer.getPort()),
                eventName, expires, body);
        send(request, peer);
    }

    /**
     * 发送事件通知（标准 9.11.2）。
     *
     * @param callId  原订阅的 Call-ID，NOTIFY 必须复用
     * @param toTag   对端 From tag（即订阅建立时我们分配的 To tag）
     * @param body    通知体，通常是 MANSCDP XML
     */
    public void notify(String deviceId, String callId, String toTag, String body) {
        SipPeer peer = requireSession(deviceId);
        Request request = factory.buildRequest("NOTIFY",
                factory.createHostUri(peer.getIp(), peer.getPort()),
                serverUri(deviceId, configHost(), configPort()),
                null,
                factory.createUri(deviceId, peer.getIp(), peer.getPort()),
                toTag, callId, 1L,
                StringUtils.isBlank(body) ? null : SipMessageFactory.MANSCDP_CONTENT_TYPE,
                body);
        send(request, peer);
    }

    // ==================== 点播 / 回放（标准 9.2 / 9.8 / 9.9）====================

    /**
     * 发起实时点播 {@code INVITE}（标准 9.2）。
     *
     * <p><b>模块不处理流媒体</b>：收到 {@code 200 OK} 后请在
     * {@link io.github.rurulalanana.gb28181.sip.event.SipCallback#onRawResponse} 中启动拉流，
     * 并调用 {@link #ackInvite} 回 ACK 完成会话建立。
     *
     * @param sdp  SDP 描述（{@code s=Play}，需带 {@code y=} SSRC 与 {@code f=}）
     */
    public String invitePlay(String deviceId, SdpMessage sdp) {
        return invite(deviceId, sdp, subjectFor(deviceId, "Play"));
    }

    /** 发起历史回放 {@code INVITE}（标准 9.8）。 */
    public String invitePlayback(String deviceId, SdpMessage sdp) {
        return invite(deviceId, sdp, subjectFor(deviceId, "Playback"));
    }

    /**
     * 发起 {@code INVITE}。
     *
     * @param subject {@code Subject} 头域，标准 9.2 用于携带点播参数
     * @return Call-ID，后续 {@link #ackInvite} / {@link #bye} 需复用
     */
    public String invite(String deviceId, SdpMessage sdp, String subject) {
        SipPeer peer = requireSession(deviceId);
        Request request = factory.buildInvite(
                requestUri(peer, deviceId),
                serverUri(deviceId, configHost(), configPort()),
                factory.createUri(serverId(), peer.getIp(), peer.getPort()),
                sdp == null ? null : sdp.toText(),
                subject);
        send(request, peer);
        return callIdOf(request);
    }

    /**
     * 发送 {@code ACK} 完成 INVITE 会话建立（标准 9.2 流程 11/12）。
     *
     * @param callId 对应 INVITE 的 Call-ID
     * @param toTag  对端 To tag（200 OK 中的 To tag）
     * @param sdp    ACK 携带的 SDP，可 {@code null}
     */
    public void ackInvite(String deviceId, String callId, String toTag, String sdp) {
        SipPeer peer = requireSession(deviceId);
        Request ack = factory.buildRequest(Request.ACK,
                requestUri(peer, deviceId),
                serverUri(deviceId, configHost(), configPort()),
                SipMessageFactory.newTag(),
                factory.createUri(serverId(), peer.getIp(), peer.getPort()),
                toTag, callId, 1L,
                sdp == null ? null : SipMessageFactory.SDP_CONTENT_TYPE, sdp);
        send(ack, peer);
    }

    /**
     * 结束会话 {@code BYE}（标准 9.2 流程 13）。
     *
     * @param toTag 对端 tag，可 {@code null}
     */
    public void bye(String deviceId, String callId, String toTag) {
        SipPeer peer = requireSession(deviceId);
        Request request = factory.buildRequest(Request.BYE,
                requestUri(peer, deviceId),
                serverUri(deviceId, configHost(), configPort()),
                SipMessageFactory.newTag(),
                factory.createUri(serverId(), peer.getIp(), peer.getPort()),
                toTag, callId, nextDialogCSeq(callId), null, null);
        send(request, peer);
    }

    // ==================== 回放控制（标准附录 D）====================

    /**
     * 会话内 {@code INFO} 回放控制（标准附录 D，RFC 2976）。
     *
     * <p>用于播放、暂停、停止、跳转与倍速控制。INFO 必须复用回放 INVITE 的
     * Call-ID 与对端 tag（同一对话框），否则设备无法把指令关联到正在进行的回放会话。
     *
     * @param deviceId 设备/通道编码
     * @param callId   回放 INVITE 的 Call-ID（{@link #invitePlayback} / {@link #invite} 返回值）
     * @param sdp      控制指令 SDP：{@code u=} 放命令（PLAY/PAUSE/SEEK/PLAY_SPEED），
     *                 {@code t=} 放时间范围，{@code a=Scale:} 放倍速
     */
    public void info(String deviceId, String callId, SdpMessage sdp) {
        SipPeer peer = requireSession(deviceId);
        Response ok = responses.get(callId);
        String fromTag = SipMessageFactory.newTag();
        String toTag = null;
        if (ok != null) {
            FromHeader from = (FromHeader) ok.getHeader(SipHeaders.FROM);
            ToHeader to = (ToHeader) ok.getHeader(SipHeaders.TO);
            if (from != null && from.getTag() != null) {
                toTag = from.getTag();
            }
            if (to != null && to.getTag() != null) {
                fromTag = to.getTag();
            }
        }
        Request request = factory.buildInfo(
                requestUri(peer, deviceId),
                serverUri(deviceId, configHost(), configPort()),
                fromTag,
                factory.createUri(serverId(), peer.getIp(), peer.getPort()),
                toTag, callId, nextDialogCSeq(callId),
                sdp == null ? null : sdp.toText());
        send(request, peer);
    }

    // ==================== 服务端应答（回应收到的 INVITE）====================

    /**
     * 对收到的 {@code INVITE} 应答 {@code 200 OK} 并携带 SDP。
     *
     * <p>典型用法：在 {@link io.github.rurulalanana.gb28181.sip.event.SipCallback#onInvite} 中准备收流地址后调用。
     *
     * @param requestEvent 原始请求事件（从 {@code InviteEvent#getRawSdp()} 等取上下文）
     * @param sdp           应答 SDP，需回填对端声明的 SSRC 与媒体地址
     */
    public void respondInvite(RequestEvent requestEvent, SdpMessage sdp) {
        Response response = factory.buildResponse(Response.OK, requestEvent.getRequest(),
                SipMessageFactory.SDP_CONTENT_TYPE, sdp == null ? null : sdp.toText());
        // RFC 3261 §13.3.1.4：INVITE 的 200 OK 必须携带 Contact 头
        response = factory.withContact(response, server.getConfig().getServerId(),
                contactHost(), server.getConfig().getPort());
        try {
            ServerTransaction transaction = serverTransaction(requestEvent);
            if (transaction != null) {
                transaction.sendResponse(response);
            } else {
                log.warn("无服务端事务，丢弃 200 OK 应答（SIP 服务未启动？）");
            }
        } catch (Exception e) {
            throw new SipBuildException("应答 INVITE 失败", e);
        }
    }

    /**
     * 拒绝 {@code INVITE}。
     *
     * <p>标准附录 G 注 17：SDP 无法满足回 {@code 488}；设备忙回 {@code 486}。
     *
     * @param statusCode {@code 488} 或 {@code 486} 等
     */
    public void rejectInvite(RequestEvent requestEvent, int statusCode) {
        Response response = factory.buildResponse(statusCode,
                requestEvent.getRequest(), null, null);
        try {
            ServerTransaction transaction = serverTransaction(requestEvent);
            if (transaction != null) {
                transaction.sendResponse(response);
            } else {
                log.warn("无服务端事务，丢弃 {} 拒绝应答（SIP 服务未启动？）", statusCode);
            }
        } catch (Exception e) {
            throw new SipBuildException("拒绝 INVITE 失败", e);
        }
    }

    /**
     * 取得（必要时创建）服务端事务。
     *
     * <p>jain-sip 1.2.344 不再像旧版那样在投递 {@link RequestEvent} 前自动创建
     * ServerTransaction，{@code event.getServerTransaction()} 恒为 {@code null}，
     * 直接调用会导致应答被静默丢弃。此处与 {@code GbSipListener} 保持同一模式：
     * 优先取现有事务，否则经 {@link SipProvider#getNewServerTransaction} 显式创建。
     */
    private ServerTransaction serverTransaction(RequestEvent requestEvent) {
        ServerTransaction existing = requestEvent.getServerTransaction();
        if (existing != null) {
            return existing;
        }
        SipProvider provider = server.sipProvider();
        if (provider == null) {
            return null;
        }
        try {
            return provider.getNewServerTransaction(requestEvent.getRequest());
        } catch (Exception e) {
            throw new SipBuildException("创建服务端事务失败", e);
        }
    }

    /**
     * Contact 头主机。与 {@link #configHost()} 同源，保证应答与请求里的地址一致。
     */
    private String contactHost() {
        return configHost();
    }

    // ==================== 内部 ====================

    private void send(Request request, SipPeer peer) {
        SipProvider provider = server.sipProvider();
        if (provider == null) {
            throw new IllegalStateException("SIP 服务未启动，无法发送请求");
        }
        try {
            // RFC 3261 §13.1.1.4：ACK 不创建事务，须经 Provider 直接发送
            // （getNewClientTransaction 对 ACK 会抛 SipException）
            if (Request.ACK.equalsIgnoreCase(request.getMethod())) {
                if (!(provider instanceof gov.nist.javax.sip.SipProviderImpl)) {
                    throw new SipBuildException("ACK 发送需要 SipProviderImpl，当前: "
                            + provider.getClass().getName());
                }
                ((gov.nist.javax.sip.SipProviderImpl) provider).sendRequest(request);
            } else {
                ClientTransaction transaction =
                        (ClientTransaction) provider.getNewClientTransaction(request);
                transaction.sendRequest();
            }
            log.debug("已发送 {} -> {} (callId={})",
                    request.getMethod(), peer.getAddress(), callIdOf(request));
        } catch (Exception e) {
            log.error("发送 SIP 请求失败: {}", request.getMethod(), e);
            throw new SipBuildException("发送 SIP 请求失败: " + request.getMethod(), e);
        }
    }

    /** 对话框内下一个 CSeq：INVITE/ACK 占用 1，INFO/BYE 从 2 起递增。 */
    private long nextDialogCSeq(String callId) {
        return dialogCSeq.computeIfAbsent(callId,
                k -> new java.util.concurrent.atomic.AtomicLong(1)).incrementAndGet();
    }

    private SipURI requestUri(SipPeer peer, String deviceId) {
        return factory.createUri(deviceId, peer.getIp(), peer.getPort());
    }

    private SipURI serverUri(String deviceId, String host, int port) {
        return factory.createUri(serverId(), host, port);
    }

    private String serverId() {
        return server.config().getServerId();
    }

    /**
     * 本平台对外可达的地址，写入 INVITE / MESSAGE 的 From 与 Contact。
     *
     * <p>解析顺序：
     * <ol>
     *   <li>配置的 {@code advertisedHost}（多网卡 / NAT 场景显式指定，最可靠）；</li>
     *   <li>监听地址 {@code host}（当它不是 {@code 0.0.0.0}/{@code ::} 时）；</li>
     *   <li>本机第一个非回环 IPv4 地址；</li>
     *   <li>{@code 127.0.0.1} 兜底。</li>
     * </ol>
     *
     * <p><b>注意</b>：绝不能把 {@code 0.0.0.0} 或默认的 {@code 127.0.0.1} 写进
     * 发给设备的报文——设备会据 Contact 回包，指向回环地址时它无法送达，
     * 表现为「平台发了 INVITE 但设备不响应」的点播失败。
     */
    private String configHost() {
        String advertised = server.config().getAdvertisedHost();
        if (StringUtils.isNotBlank(advertised)) {
            return advertised;
        }
        String h = server.config().getHost();
        if (StringUtils.isNotBlank(h) && !"0.0.0.0".equals(h) && !"::".equals(h)
                && !"::0".equals(h)) {
            return h;
        }
        String detected = localAddress();
        return detected != null ? detected : "127.0.0.1";
    }

    /** 取本机第一个非回环 IPv4；取不到返回 {@code null}。 */
    private static String localAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nics =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                java.net.NetworkInterface nic = nics.nextElement();
                if (nic.isLoopback() || !nic.isUp()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("探测本机地址失败: {}", e.toString());
        }
        return null;
    }

    private int configPort() {
        return server.config().getPort();
    }

    /** 取设备会话；未注册时抛异常。 */
    private SipPeer requireSession(String deviceId) {
        DeviceSession session = server.deviceRegistry().get(deviceId);
        if (session != null) {
            return SipPeer.builder()
                    .deviceId(deviceId)
                    .ip(session.getIp())
                    .port(session.getPort())
                    .transport(session.getTransport())
                    .domain(session.getDomain())
                    .build();
        }
        // 级联下级平台不在设备注册表中，需回落到级联注册表寻址
        io.github.rurulalanana.gb28181.sip.cascade.CascadePlatform platform =
                server.getCascade().getRegistry().get(deviceId);
        if (platform != null) {
            return SipPeer.builder()
                    .deviceId(deviceId)
                    .ip(platform.getIp())
                    .port(platform.getPort())
                    .transport(platform.getTransport())
                    .domain(platform.getDomain())
                    .build();
        }
        throw new IllegalStateException("设备未注册或会话已失效: " + deviceId);
    }

    /** 推导设备地址（优先取编码低位，退化为 1）。 */
    private int addressOf(String deviceId) {
        try {
            if (GbId.isValidDeviceId(deviceId)) {
                int serial = Integer.parseInt(GbId.serial(deviceId));
                return (serial % 4095) + 1;
            }
        } catch (Exception ignore) {
            // 退化为默认地址
        }
        return 1;
    }

    /** 标准 9.2：Subject 头域携带点播源 ID、序列号等参数。 */
    private static String subjectFor(String deviceId, String operation) {
        return deviceId + ":" + operation + ":0";
    }

    // ==================== 内部状态（供响应回调使用）====================

    /** 由 {@link Gb28181SipListener} 在收到响应时调用。 */
    public void onResponse(ResponseEvent event) {
        String callId = event.getResponse() == null ? null : callIdOf(event.getResponse());
        if (callId == null) {
            return;
        }
        if (event.getResponse().getStatusCode() >= 200) {
            responses.put(callId, event.getResponse());
            CountDownLatch latch = pending.remove(callId);
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    /** 由 {@link Gb28181SipListener} 在事务超时时调用。 */
    public void onTimeout(TimeoutEvent event) {
        String callId = event.getClientTransaction() == null ? null
                : callIdOf(event.getClientTransaction().getRequest());
        if (callId == null) {
            return;
        }
        CountDownLatch latch = pending.remove(callId);
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * 同步等待某个 Call-ID 的首个最终响应。
     *
     * @param callId    Call-ID
     * @param timeoutMs 超时毫秒
     * @return 收到的响应；超时返回 {@code null}
     */
    public Response awaitResponse(String callId, long timeoutMs) {
        Response existing = responses.get(callId);
        if (existing != null) {
            return existing;
        }
        CountDownLatch latch = new CountDownLatch(1);
        pending.put(callId, latch);
        try {
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return responses.get(callId);
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            pending.remove(callId);
        }
    }

    private static String callIdOf(javax.sip.message.Message message) {
        javax.sip.header.CallIdHeader h =
                (javax.sip.header.CallIdHeader) message.getHeader(SipHeaders.CALL_ID);
        return h == null ? null : h.getCallId();
    }

    /** 从 Call-ID 解析设备编码（{@code Subject} 缺失时的兜底）。 */
    public static String deviceIdOf(Request request) {
        try {
            javax.sip.header.SubjectHeader subject =
                    (javax.sip.header.SubjectHeader) request.getHeader(SipHeaders.SUBJECT);
            if (subject != null) {
                String s = subject.getSubject();
                int colon = s.indexOf(':');
                if (colon > 0) {
                    return s.substring(0, colon);
                }
                return s;
            }
        } catch (Exception ignore) {
            // 忽略
        }
        return null;
    }

    @Override
    public String toString() {
        return "Gb28181SipClient{server=" + serverId() + "}";
    }

    /** 常用命令类型引用，便于调用方免导入。 */
    public static final CmdType CMD_CATALOG = CmdType.CATALOG;
}
