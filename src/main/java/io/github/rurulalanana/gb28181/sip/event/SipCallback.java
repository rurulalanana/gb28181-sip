package io.github.rurulalanana.gb28181.sip.event;

import io.github.rurulalanana.gb28181.sip.constant.CmdType;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import io.github.rurulalanana.gb28181.sip.sdp.SdpMessage;

import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import java.util.List;

/**
 * GB28181 SIP 信令回调。
 *
 * <p>本接口是模块与业务方的<b>唯一契约</b>：模块只负责 SIP/MANSCDP/SDP 协议编解码、
 * 事务与认证，<b>不包含任何流媒体与业务逻辑</b>。
 *
 * <p>所有方法均为 {@code default} 空实现，调用方只需覆写关心的部分。
 *
 * <h3>关于流媒体</h3>
 * 模块<b>不集成</b>流媒体。收到 {@code INVITE} 时只做协议层解析并回调
 * {@link #onInvite}，由调用方决定收流地址、启动拉流/推流，再通过
 * {@link io.github.rurulalanana.gb28181.sip.Gb28181SipClient#invite} 等 API 发起到点播；
 * {@code BYE}/{@code ACK} 同样以事件形式回调。
 *
 * <h3>线程安全</h3>
 * 回调在 SIP 协议栈线程上执行，<b>不应阻塞</b>；耗时操作请自行转交业务线程池。
 * 实现需保证线程安全。
 */
public interface SipCallback {

    // =================================================================
    //  生命周期
    // =================================================================

    /** 模块启动完成（已绑定端口）。 */
    default void onStarted(String host, int port, String transport) {
    }

    /** 模块已停止。 */
    default void onStopped() {
    }

    /** 发生不可恢复的异常。 */
    default void onError(Throwable t) {
    }

    // =================================================================
    //  注册与心跳（标准 9.1 / 9.10）
    // =================================================================

    /**
     * 设备注册或注销（标准 9.1）。
     *
     * <p>模块已完成摘要认证校验（若配置了认证器），此回调仅用于通知业务。
     */
    default void onRegister(RegisterEvent event) {
    }

    /**
     * 心跳保活（标准 9.10 / A.2.5.2）。
     *
     * <p>模块已自动刷新设备的在线时间戳，调用方一般无需处理。
     */
    default void onKeepAlive(KeepAliveEvent event) {
    }

    // =================================================================
    //  MANSCDP 消息（标准 9.3 / 9.5）
    // =================================================================

    /**
     * 收到 MANSCDP 请求消息（{@code MESSAGE} 方法，Content-Type 为 Application/MANSCDP+xml）。
     *
     * <p>模块已自动回复 {@code 200 OK}（标准 9.5.3.1 c 要求"立即返回 200 OK 应答，
     * <b>应答无消息体</b>"）。业务应答需通过后续独立 MESSAGE 主动发送，
     * 例如 {@link io.github.rurulalanana.gb28181.sip.Gb28181SipClient#sendMessage}。
     *
     * @return 若调用方希望自行接管该消息的响应流程，返回非 {@code null}；
     *         通常返回 {@code null}
     */
    default Boolean onManscdpRequest(ManscdpEvent event) {
        return null;
    }

    /**
     * 收到 MANSCDP 应答消息（对端对我方查询的应答）。
     */
    default void onManscdpResponse(ManscdpEvent event) {
    }

    /**
     * 收到未识别的 MANSCDP 命令。
     *
     * <p>设备私有扩展命令很常见，未识别不应视为错误，交给调用方兜底处理。
     */
    default void onUnknownCommand(ManscdpEvent event) {
    }

    // =================================================================
    //  订阅与通知（标准 9.11，RFC 6665）
    // =================================================================

    /**
     * 收到 {@code SUBSCRIBE}（事件订阅）。
     *
     * <p>模块已自动回复 {@code 200 OK} 并携带
     * {@code Subscription-State: active;expires=...}。调用方需自行记录订阅关系，
     * 后续事件发生时通过 {@link io.github.rurulalanana.gb28181.sip.Gb28181SipClient#notify} 推送。
     *
     * @return 订阅有效期（秒）；返回 {@code 0} 表示拒绝订阅（模块将回 4xx）
     */
    default int onSubscribe(SubscribeEvent event) {
        return 0;
    }

    /**
     * 收到 {@code NOTIFY}（事件通知，可能是订阅确认也可能是事件推送）。
     */
    default void onNotify(SubscribeEvent event) {
    }

    /**
     * 订阅被对端终止（{@code Subscription-State: terminated}）。
     *
     * @param reason 终止原因
     */
    default void onSubscriptionTerminated(String callId, String eventName, String reason) {
    }

    // =================================================================
    //  点播 / 回放 / 下载（标准 9.2 / 9.8 / 9.9）—— 流媒体由调用方处理
    // =================================================================

    /**
     * 收到 {@code INVITE}（点播、回放、下载、语音广播）。
     *
     * <p><b>模块不处理流媒体</b>：仅完成 SDP 解析与事件派发。调用方需：
     * <ol>
     *   <li>根据 {@link InviteEvent#getSdp()} 判断业务类型（{@code s=} 字段为
     *       Play / Playback / Download）；</li>
     *   <li>自行准备收流地址，调用
     *       {@link io.github.rurulalanana.gb28181.sip.Gb28181SipClient#respondInvite} 回 200 OK 并携带 SDP；
     *       无法满足时按标准注 17 回 {@code 488}（SDP 不满足）或 {@code 486}（忙）。</li>
     * </ol>
     */
    default void onInvite(InviteEvent event) {
    }

    /**
     * 收到 {@code ACK}。
     */
    default void onAck(InviteEvent event) {
    }

    /**
     * 收到 {@code BYE}（会话结束）。
     */
    default void onBye(InviteEvent event) {
    }

    /**
     * 收到 {@code INFO}（历史回放控制，RFC 2976）。
     */
    default void onInfo(SdpEvent event) {
    }

    // =================================================================
    //  原始报文（兜底）
    // =================================================================

    /**
     * 收到无法归类的 SIP 请求。
     *
     * <p>模块已自动回复 {@code 200 OK}，此处仅作日志与扩展用途。
     */
    default void onRawRequest(RequestEvent event) {
    }

    /**
     * 收到我方请求的 SIP 响应（含超时前收到的所有响应）。
     *
     * <p>用于点播/回放等需要跟踪 Call-Id 的场景。
     */
    default void onRawResponse(ResponseEvent event) {
    }
}