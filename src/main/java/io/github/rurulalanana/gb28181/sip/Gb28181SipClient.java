package io.github.rurulalanana.gb28181.sip;

import io.github.rurulalanana.gb28181.sip.constant.CmdType;
import io.github.rurulalanana.gb28181.sip.constant.GbId;
import io.github.rurulalanana.gb28181.sip.device.DeviceSession;
import io.github.rurulalanana.gb28181.sip.event.SipPeer;
import io.github.rurulalanana.gb28181.sip.internal.InviteResponseRegistry;
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
                factory.createUri(deviceId, peer.getIp(), peer.getPort()),
                xml);
        send(request, peer);
    }

    /**
     * 向<b>显式地址</b>发送 MANSCDP MESSAGE。
     *
     * <p><b>为什么需要绕过会话表</b>：国标级联里「本级作为下级向上级注册」这个方向上，
     * 上级永远不会注册到本级，因此会话表里永远查不到它的地址 ——
     * {@link #sendMessage(String, String)} 第一行就抛「设备未注册或会话已失效」。
     * 之前的级联目录推送正是卡在这里：配置存下来了，但一条信令都发不出去。
     *
     * <p>本方法用配置里的 IP/端口直接寻址，适用于：
     * <ul>
     *   <li>向上级平台推送目录 / 发心跳；</li>
     *   <li>回复上级的目录查询（同样不知上级地址）。</li>
     * </ul>
     *
     * @param targetId 对端平台编码（写入 To 头）
     * @param ip       对端 IP
     * @param port     对端端口
     * @param xml      MANSCDP 消息体
     */
    public void sendMessageTo(String targetId, String ip, int port, String xml) {
        sendMessageTo(null, targetId, ip, port, xml);
    }

    /**
     * 向<b>显式地址</b>发送 MANSCDP MESSAGE，并指定 {@code From} 里的编码。
     *
     * <p><b>为什么还需要单独指定 From</b>：国标级联允许本级用一个
     * <b>与自身平台编码不同</b>的编号向上级注册（{@code gb_cascade.local_id}）。
     * 典型场景是对端与本级用了相同的平台编码 —— 那时必须换一个编号，
     * 否则上级会认为「你在拿我自己的编码注册」。
     *
     * <p>如果这里固定用 {@code server.config().serverId()}，就会出现一种
     * 非常难查的现象：
     * <ul>
     *   <li>REGISTER 能成功（应答由注册事务匹配，不看 From 内容）；</li>
     *   <li>但 Keepalive / 目录 Notify 全部被静默丢弃 —— 因为对端从
     *       {@code From} 里读到的编码不是它认得的那个下级平台
     *       （最坏情况是它自己的编码），于是当成未知来源忽略掉。</li>
     * </ul>
     * 表现为「注册在线、通道也能查到，但心跳永远不更新、主动推送不生效」。
     *
     * @param fromId 本级在级联中的编码；{@code null}/{@code blank} 时用平台配置的编码
     * @param targetId 对端平台编码（写入 To 头与 Request-URI）
     * @param ip       对端 IP
     * @param port     对端端口
     * @param xml      MANSCDP 消息体
     */
    public void sendMessageTo(String fromId, String targetId, String ip, int port, String xml) {
        sendMessageTo(fromId, targetId, ip, port, xml, null);
    }

    /**
     * 向显式地址发送 MANSCDP MESSAGE，并指定 {@code From} 编码与字符集。
     *
     * @param charset 对端期望的消息体字符集（如 {@code GB2312}）；
     *                {@code null}/blank 时用 UTF-8。
     *                不指定时如果对端按 GB2312 解，中文字段会变乱码（详见
     *                {@code SipMessageFactory#buildMessage(SipURI, SipURI, SipURI, String, String)}）。
     */
    public void sendMessageTo(String fromId, String targetId, String ip, int port,
                              String xml, String charset) {
        if (StringUtils.isBlank(ip) || port <= 0) {
            throw new IllegalStateException("对端地址未配置: " + ip + ":" + port);
        }
        SipPeer peer = SipPeer.builder()
                .deviceId(targetId)
                .ip(ip)
                .port(port)
                .transport(configTransport())
                .domain(io.github.rurulalanana.gb28181.sip.constant.GbId.isValidDeviceId(targetId)
                        ? io.github.rurulalanana.gb28181.sip.constant.GbId.domainOf(targetId)
                        : server.config().getDomain())
                .build();
        // From 用本级在级联中的编码；To / Request-URI 用对端编码
        String useFrom = StringUtils.isBlank(fromId) ? serverId() : fromId.trim();
        SipURI fromUri = factory.createUri(useFrom, configHost(), configPort());
        SipURI targetUri = factory.createUri(targetId, ip, port);
        Request request = factory.buildMessage(targetUri, fromUri, targetUri, xml, charset);
        send(request, peer);
    }

    /** 当前生效的传输协议（多协议时取第一个）。 */
    private String configTransport() {
        String transports = server.config().getTransport();
        if (StringUtils.isBlank(transports)) {
            return "UDP";
        }
        int comma = transports.indexOf(',');
        return (comma > 0 ? transports.substring(0, comma) : transports).trim();
    }

    /**
     * 丢弃某个 Call-ID 的响应缓存。
     *
     * <p>供「每次都用新 Call-ID」的调用方（如级联注册）用完清理，
     * 否则 {@code responses} 表会随交互次数无限增长 —— 一天几百次注册就是几百条泄漏。
     */
    public void forgetCallId(String callId) {
        if (callId == null) {
            return;
        }
        responses.remove(callId);
        pending.remove(callId);
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
        queryRecordInfo(deviceId, deviceId, startTime, endTime);
    }

    /**
     * 历史音视频文件检索（标准 9.8），<b>SIP 寻址与 {@code <DeviceID>} 分开</b>。
     *
     * <p><b>为什么必须分开</b>：国标里录像挂在<b>通道</b>上，
     * 所以 {@code <DeviceID>} 要写通道编码；而 SIP 的 To / 请求行 / 会话表
     * 只认<b>设备编码</b>（设备才是注册实体）。
     * 底层 {@link #sendMessage(String, String)} 只收一个 ID 并同时用于这两处，
     * 于是「设备编码 ≠ 通道编码」时必然抛「设备未注册或会话已失效」：
     * <pre>
     * 直连摄像机：设备 34020000001310000001 / 通道 34020000001310000001 → 恰好相同，能跑通
     * 下级平台：  设备 34020000002000000006 / 通道 34020000001310000001 → 报「设备未注册」
     * </pre>
     *
     * @param sipTarget 设备编码（用于 SIP 寻址）
     * @param channelId 通道编码（写入 {@code <DeviceID>}，也用于匹配应答）
     * @return 本次请求的 {@code Call-ID}，可用于 {@link #awaitResponse(String, long)}
     *         观察设备的最终响应
     */
    public String queryRecordInfo(String sipTarget, String channelId,
                                  String startTime, String endTime) {
        SipPeer peer = requireSession(sipTarget);
        Request request = factory.buildMessage(
                requestUri(peer, sipTarget),
                serverUri(sipTarget, configHost(), configPort()),
                factory.createUri(sipTarget, peer.getIp(), peer.getPort()),
                ManscdpBuilder.recordInfoQuery(nextSn(), channelId, startTime, endTime));
        send(request, peer);
        return callIdOf(request);
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
     * <p>通道编码与设备编码相同的场景（直连摄像机、设备自身即一路通道）用这个重载；
     * 下挂子通道必须用 {@link #invitePlay(String, String, SdpMessage)}。
     *
     * @param sdp SDP 描述（{@code s=Play}，需带 {@code y=} SSRC 与 {@code f=}）
     */
    public String invitePlay(String deviceId, SdpMessage sdp) {
        return invitePlay(deviceId, deviceId, sdp);
    }

    /**
     * 发起实时点播 {@code INVITE}（标准 9.2），显式指定被点播的通道。
     *
     * <p><b>为什么必须区分 deviceId 与 channelId</b>：标准 9.2 规定 INVITE 的
     * Request-URI 与 To 头域填<b>被点播的通道编码</b>（不是设备编码），
     * {@code Subject} 填 {@code <通道编码>:<SSRC>,<平台编码>:0}。
     * 设备/下级平台据此在自己管辖的通道列表里定位目标：
     * <ul>
     *   <li>填<b>通道</b>编码 → 命中，正常回 200 OK 并推流；</li>
     *   <li>填<b>设备</b>编码 → 设备在自己的通道列表里找不到这个"通道"，
     *       直接丢弃或回 404，表现为「INVITE 已发出但设备毫无响应、点播失败」。</li>
     * </ul>
     * 模拟器类设备（收到任何 INVITE 就推流）不会暴露该问题，
     * 只有 LiveGBS / NVR 这类真实实现会严格校验，因此必须按标准填。
     *
     * <p>另有一层隐蔽风险：若设备注册时用的编码恰好等于本级平台编码，
     * 把设备编码同时写进 From 与 To 会构成「自环」报文，对端直接静默丢弃。
     * 填通道编码后 From（平台）与 To（通道）天然不同，该问题一并消除。
     *
     * @param deviceId  设备编码（SIP 寻址用：据此从注册表取对端 IP/端口）
     * @param channelId 通道编码（写入 Request-URI / To / Subject）
     */
    public String invitePlay(String deviceId, String channelId, SdpMessage sdp) {
        return invite(deviceId, channelId, sdp, null);
    }

    /** 发起历史回放 {@code INVITE}（标准 9.8）。 */
    public String invitePlayback(String deviceId, SdpMessage sdp) {
        return invitePlayback(deviceId, deviceId, sdp);
    }

    /** 发起历史回放 {@code INVITE}（标准 9.8），显式指定被回放的通道。 */
    public String invitePlayback(String deviceId, String channelId, SdpMessage sdp) {
        return invite(deviceId, channelId, sdp, null);
    }

    /**
     * 发起 {@code INVITE}（兼容入口：通道编码与设备编码相同）。
     *
     * @param subject {@code Subject} 头域；为空时按标准自动生成
     */
    public String invite(String deviceId, SdpMessage sdp, String subject) {
        return invite(deviceId, deviceId, sdp, subject);
    }

    /**
     * 发起 {@code INVITE}。
     *
     * @param deviceId  设备编码（SIP 寻址用：据此从注册表取对端 IP/端口）
     * @param channelId 通道编码（Request-URI / To 的 user 部分）；为空时退回设备编码
     * @param subject   {@code Subject} 头域；为空时按标准生成
     *                  {@code <通道编码>:<SSRC>,<平台编码>:0}
     * @return Call-ID，后续 {@link #ackInvite} / {@link #bye} 需复用
     */
    public String invite(String deviceId, String channelId, SdpMessage sdp, String subject) {
        SipPeer peer = requireSession(deviceId);
        String target = StringUtils.isBlank(channelId) ? deviceId : channelId;
        String subjectHeader = StringUtils.isNotBlank(subject)
                ? subject
                : subjectFor(target, sdp == null ? null : sdp.getSsrc());
        Request request = factory.buildInvite(
                requestUri(peer, target),
                serverUri(deviceId, configHost(), configPort()),
                factory.createUri(target, peer.getIp(), peer.getPort()),
                sdp == null ? null : sdp.toText(),
                subjectHeader);
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
        ackInvite(deviceId, deviceId, callId, toTag, sdp);
    }

    /**
     * 发送 {@code ACK}，显式指定通道编码。
     *
     * <p>ACK 属于 INVITE 对话框（RFC 3261 §13.2.2.4）：Request-URI 与 To 必须
     * 与原 INVITE 一致，即都用<b>通道编码</b>。只传设备编码会让部分设备
     * 匹配不上对话框而丢弃 ACK，表现为「设备回了 200 OK 但不推流」。
     */
    public void ackInvite(String deviceId, String channelId, String callId, String toTag, String sdp) {
        SipPeer peer = requireSession(deviceId);
        String target = StringUtils.isBlank(channelId) ? deviceId : channelId;
        /*
         * ACK 的 From tag 必须与 INVITE 的 From tag **完全相同**。
         *
         * <p>RFC 3261 §13.2.2.4：ACK 与 INVITE 属于同一对话框，
         * From tag（我们自己的 tag）必须一致。原来这里用
         * {@code SipMessageFactory.newTag()} 生成随机 tag，抓包可见：
         * <pre>
         * INVITE  From: ...;tag=54b201738dcb5dc6
         * ACK     From: ...;tag=6f8829a0e43b0d05   ← 随机，错
         * </pre>
         * 这会让设备把 ACK 当作「另一个对话框的报文」而丢弃，
         * 会话停留在未确认状态 —— 表现为回放能出画面但控制指令全部无效。
         */
        String fromTag = dialogTagsOf(callId)[0];
        Request ack = factory.buildRequest(Request.ACK,
                requestUri(peer, target),
                serverUri(deviceId, configHost(), configPort()),
                fromTag,
                factory.createUri(target, peer.getIp(), peer.getPort()),
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
        bye(deviceId, deviceId, callId, toTag);
    }

    /**
     * 结束会话 {@code BYE}，显式指定通道编码。
     *
     * <p>BYE 与 INVITE 同属一个对话框：Request-URI 与 To 必须与 INVITE 一致
     * （都是<b>通道编码</b>），否则设备匹配不到对话框，BYE 被忽略，
     * 设备会继续向上行推流直到自己超时——白白占用带宽。
     */
    public void bye(String deviceId, String channelId, String callId, String toTag) {
        SipPeer peer = requireSession(deviceId);
        String target = StringUtils.isBlank(channelId) ? deviceId : channelId;
        Request request = factory.buildRequest(Request.BYE,
                requestUri(peer, target),
                serverUri(deviceId, configHost(), configPort()),
                SipMessageFactory.newTag(),
                factory.createUri(target, peer.getIp(), peer.getPort()),
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
        info(deviceId, deviceId, callId, sdp);
    }

    /**
     * 会话内 {@code INFO} 回放控制，显式指定通道编码。
     *
     * <p>INFO 也必须在 INVITE 对话框内发送（RFC 2976），Request-URI 与 To
     * 同样用<b>通道编码</b>，否则设备无法把指令关联到正在进行的回放会话。
     */
    public void info(String deviceId, String channelId, String callId, SdpMessage sdp) {
        SipPeer peer = requireSession(deviceId);
        String target = StringUtils.isBlank(channelId) ? deviceId : channelId;
        String[] tags = dialogTagsOf(callId);
        Request request = factory.buildInfo(
                requestUri(peer, target),
                serverUri(deviceId, configHost(), configPort()),
                tags[0],
                factory.createUri(target, peer.getIp(), peer.getPort()),
                tags[1], callId, nextDialogCSeq(callId),
                sdp == null ? null : sdp.toText());
        send(request, peer);
    }

    /**
     * 会话内 {@code INFO}，消息体为<b>原文</b>（不做 SDP 解析/校验）。
     *
     * <p><b>为什么需要它</b>：GB/T 28181 的回放控制（暂停/拖动/倍速）消息体
     * 是 <b>RTSP 风格文本</b>，不是 SDP：
     * <pre>
     * PLAY RTSP/1.0\r\nCSeq: 1\r\nRange: npt=120-\r\n
     * PAUSE RTSP/1.0\r\nCSeq: 2\r\nPauseTime: now\r\n
     * PLAY RTSP/1.0\r\nCSeq: 3\r\nScale: 2.000000\r\n
     * </pre>
     * 而 {@link #info(String, String, String, SdpMessage)} 只能输出 SDP，
     * 用它发控制指令设备不认（实测 LiveGBS 直接忽略，画面毫无反应）。
     *
     * @param contentType 消息体类型；国标设备普遍不校验，可传
     *                    {@code application/MANSRTSP}（推荐）或 {@code null}
     */
    public void infoRaw(String deviceId, String channelId, String callId,
                        String contentType, String body) {
        SipPeer peer = requireSession(deviceId);
        String target = StringUtils.isBlank(channelId) ? deviceId : channelId;
        String[] tags = dialogTagsOf(callId);
        String fromTag = tags[0];
        String toTag = tags[1];
        Request request = factory.buildInfo(
                requestUri(peer, target),
                serverUri(deviceId, configHost(), configPort()),
                fromTag,
                factory.createUri(target, peer.getIp(), peer.getPort()),
                toTag, callId, nextDialogCSeq(callId),
                body, contentType);
        send(request, peer);
    }

    /**
     * 取回放/点播对话框的两个 tag：{@code [我方 From tag, 设备 To tag]}。
     *
     * <h3>为什么必须按这个顺序取</h3>
     * 我们主动发起 INVITE（UAC），设备回的 200 OK 里：
     * <ul>
     *   <li>{@code From} = 我们 INVITE 的 From，带<b>我方 tag</b>；</li>
     *   <li>{@code To} = 我们 INVITE 的 To，后面被设备<b>追加了它的 tag</b>。</li>
     * </ul>
     * 所以后续 INFO / ACK 必须：From 用 {@code ok.getFrom().getTag()}，
     * To 用 {@code ok.getTo().getTag()}。
     *
     * <p><b>原来这里是写反的</b>（To 取 From 的 tag、From 取 To 的 tag），
     * 于是 INFO 落在对话框之外。实测抓包：
     * <pre>
     * INVITE  From: ...;tag=54b201738dcb5dc6      To: ...（无 tag）
     * 200 OK  From: ...;tag=54b201738dcb5dc6      To: ...;tag=469824905
     * INFO    From: ...;tag=469824905  ← 用了设备的 tag
     *         To:   ...;tag=54b201738dcb5dc6  ← 用了我方的 tag
     * </pre>
     * 设备对这种「不在对话内」的 INFO 会回 {@code 200 OK}（宽容），
     * 然后<b>直接忽略指令</b> —— 表现就是「点进度条毫无反应」、
     * 「暂停不生效」、「倍速不变」，而日志里一切正常。
     *
     * @return 长度为 2 的数组；拿不到 200 OK 时 From 用随机新 tag、To 为 {@code null}
     */
    private String[] dialogTagsOf(String callId) {
        Response ok = responses.get(callId);
        if (ok == null) {
            return new String[]{SipMessageFactory.newTag(), null};
        }
        FromHeader from = (FromHeader) ok.getHeader(SipHeaders.FROM);
        ToHeader to = (ToHeader) ok.getHeader(SipHeaders.TO);
        String fromTag = (from != null && from.getTag() != null)
                ? from.getTag() : SipMessageFactory.newTag();
        String toTag = (to != null && to.getTag() != null) ? to.getTag() : null;
        return new String[]{fromTag, toTag};
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
        sendInviteResponse(requestEvent, response);
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
        sendInviteResponse(requestEvent, response);
    }

    /**
     * 发送 INVITE 的最终应答（事务优先，失败则无事务发送）。
     *
     * <p><b>为什么必须有兜底</b>：jain-sip 建服务端事务时会校验请求头，
     * INVITE 缺 {@code Contact}（公网扫描器、部分非标准实现普遍如此）时直接抛
     * {@code TransactionUnavailableException}。原实现把它转成异常往上抛，
     * 结果<b>一个字节都发不出去</b>：对端事务等到超时，我们日志里刷
     * {@code SIP 事务超时: server=true}。
     *
     * <p>{@link SipProvider#sendResponse} 走 Via 头无状态发送，不需要事务，
     * 对「拒绝一个非法请求」这种场景完全够用。
     */
    private void sendInviteResponse(RequestEvent requestEvent, Response response) {
        /*
         * 先登记本次应答。
         *
         * 对端不回 ACK 时（公网扫描器普遍如此），INVITE 服务端事务会在
         * Timer H 超时并触发 TimeoutEvent；监听器靠这份登记把它与
         * 「我们压根没应答」区分开，否则真故障会被噪音淹没。
         */
        InviteResponseRegistry.answered(callIdOf(requestEvent.getRequest()),
                response.getStatusCode());
        try {
            ServerTransaction transaction = serverTransaction(requestEvent);
            if (transaction != null) {
                transaction.sendResponse(response);
                return;
            }
        } catch (Exception e) {
            log.debug("经事务发送 INVITE 应答复失败，改用无事务发送: {}", e.getMessage());
        }
        try {
            SipProvider provider = server.sipProvider();
            if (provider != null) {
                provider.sendResponse(response);
            }
        } catch (Exception e) {
            throw new SipBuildException("发送 INVITE 应答失败: " + response.getStatusCode(), e);
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
        /*
         * INVITE 必须带 Contact，否则 getNewServerTransaction 抛
         * "Missing a required header : Contact"，应答发不出去。
         * 详见 SipMessageFactory#ensureContact 的说明。
         */
        if (Request.INVITE.equalsIgnoreCase(requestEvent.getRequest().getMethod())) {
            factory.ensureContact(requestEvent.getRequest());
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

    /**
     * 标准 9.2 的 {@code Subject} 头域格式：
     * {@code <被点播通道编码>:<SSRC>,<平台编码>:<序号>}。
     *
     * <p>历史实现曾填 {@code <设备编码>:Play:0}（把操作名当 SSRC），
     * 对端解析不出 SSRC 与目标通道，是点播失败的原因之一。
     * 这里与 WVP-GB28181-pro 的
     * {@code String.format("%s:%s,%s:%s", channelId, ssrc, sipConfig.getId(), 0)} 对齐。
     *
     * @param channelId 被点播的通道编码
     * @param ssrc      SDP {@code y=} 行声明的 SSRC；未知时填 {@code 0}
     */
    private String subjectFor(String channelId, String ssrc) {
        return channelId + ":" + (StringUtils.isBlank(ssrc) ? "0" : ssrc)
                + "," + serverId() + ":0";
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
