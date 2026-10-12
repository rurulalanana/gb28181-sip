package io.github.rurulalanana.gb28181.sip.event;

import io.github.rurulalanana.gb28181.sip.sdp.SdpMessage;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * SDP 相关事件（当前用于 {@code INFO} 历史回放控制，RFC 2976）。
 */
@Getter
@Builder
@ToString
public class SdpEvent {

    /** 信令来源。 */
    private final SipPeer peer;

    /** SIP 方法。 */
    private final String method;

    /** Call-ID。 */
    private final String callId;

    /** CSeq。 */
    private final long cSeq;

    /** 解析后的 SDP。 */
    private final SdpMessage sdp;

    /** 原始 SDP 文本。 */
    private final String rawSdp;
}