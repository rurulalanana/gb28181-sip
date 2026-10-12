package io.github.rurulalanana.gb28181.sip.event;

import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import io.github.rurulalanana.gb28181.sip.sdp.SdpMessage;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * INVITE / ACK / BYE 会话事件（标准 9.2 / 9.8 / 9.9）。
 *
 * <p><b>模块不处理流媒体</b>，仅提供协议层信息；收流地址协商、推拉流启动、
 * 断流回收全部由调用方负责。
 */
@Getter
@Builder
@ToString
public class InviteEvent {

    /** 信令来源。 */
    private final SipPeer peer;

    /** SIP 方法：{@code INVITE} / {@code ACK} / {@code BYE}。 */
    private final String method;

    /** SIP Call-ID。 */
    private final String callId;

    /** 本地 tag（To 头域）。 */
    private final String localTag;

    /** 对端 tag（From 头域）。 */
    private final String remoteTag;

    /** CSeq。 */
    private final long cSeq;

    /** {@code Subject} 头域，标准 9.2 用其携带点播相关参数。 */
    private final String subject;

    /** 解析后的 SDP；无消息体时为 {@code null}。 */
    private final SdpMessage sdp;

    /** 原始 SDP 文本。 */
    private final String rawSdp;

    /** MANSCDP 消息体（语音广播等场景可能携带）。 */
    private final ManscdpMessage manscdp;

    /**
     * 底层 {@link RequestEvent}，用于调用
     * {@link io.github.rurulalanana.gb28181.sip.Gb28181SipClient#respondInvite(RequestEvent, io.github.rurulalanana.gb28181.sip.sdp.SdpMessage)}
     * 回复 200 OK / 486 / 488。
     *
     * <p>调用方<b>不应</b>长期持有本引用（协议栈会复用事件对象）。
     */
    private final javax.sip.RequestEvent rawRequestEvent;

    // ---------- 业务类型便捷判断（依据 {@code s=} 字段） ----------

    /** 是否为实时点播（{@code s=Play}）。 */
    public boolean isPlay() {
        return SdpMessage.S_PLAY.equalsIgnoreCase(sField());
    }

    /** 是否为历史回放（{@code s=Playback}）。 */
    public boolean isPlayback() {
        return SdpMessage.S_PLAYBACK.equalsIgnoreCase(sField());
    }

    /** 是否为文件下载（{@code s=Download}）。 */
    public boolean isDownload() {
        return SdpMessage.S_DOWNLOAD.equalsIgnoreCase(sField());
    }

    private String sField() {
        return sdp == null ? null : sdp.getSessionName();
    }

    /** 对端请求的媒体端口（{@code m=} 行）。 */
    public int getMediaPort() {
        return sdp == null ? 0 : sdp.getMediaPort();
    }

    /** 对端的媒体 IP（{@code c=} 行）。 */
    public String getMediaIp() {
        return sdp == null ? null : sdp.resolveMediaIp();
    }

    /** 对端声明的 SSRC（{@code y=} 行）。 */
    public String getSsrc() {
        return sdp == null ? null : sdp.getSsrc();
    }
}