package io.github.rurulalanana.gb28181.sip.event;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * 事件订阅 / 通知事件（标准 9.11，RFC 6665）。
 *
 * <p>{@code Event} 头域取值参考：{@code Catalog} / {@code Alarm} /
 * {@code MobilePosition} / {@code PTZPosition}。注意：这些取值属<b>行业实践惯例</b>，
 * GB/T 28181—2022 正文未逐字规定，仅引用 RFC 6665。
 */
@Getter
@Builder
@ToString
public class SubscribeEvent {

    /** 信令来源。 */
    private final SipPeer peer;

    /** SIP 方法：{@code SUBSCRIBE} 或 {@code NOTIFY}。 */
    private final String method;

    /** 事件名（{@code Event} 头域的 type 参数）。 */
    private final String eventName;

    /** 订阅有效期（秒）。{@code 0} 表示退订。 */
    private final int expires;

    /** 订阅标识（{@code Call-ID}），退订时需原样带回。 */
    private final String callId;

    /** 本地 tag。 */
    private final String localTag;

    /** 对端 tag。 */
    private final String remoteTag;

    /** 订阅状态（RFC 6665 {@code Subscription-State}）。 */
    private final String subscriptionState;

    /** 请求 URI，NOTIFY 时为订阅者地址。 */
    private final String requestUri;

    /** 消息体 XML 文本（SUBSCRIBE 常携带 Catalog 查询体）。 */
    private final String body;
}