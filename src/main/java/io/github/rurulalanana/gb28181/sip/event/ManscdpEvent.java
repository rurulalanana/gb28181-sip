package io.github.rurulalanana.gb28181.sip.event;

import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * MANSCDP 消息事件（{@code MESSAGE} 方法承载的 XML 消息体）。
 *
 * <p>{@link #getManscdp()} 已完成 XML 解析，{@link #getXml()} 保留原始文本便于排查。
 */
@Getter
@Builder
@ToString
public class ManscdpEvent {

    /** 信令来源。 */
    private final SipPeer peer;

    /** 解析后的消息体。 */
    private final ManscdpMessage manscdp;

    /** 原始 XML 文本。 */
    private final String xml;

    /** SIP 方法：恒为 {@code MESSAGE}。 */
    private final String method;

    /** SIP Call-ID，用于串联同一会话。 */
    private final String callId;

    /** 事务内发送的 CSeq。 */
    private final long cSeq;

    /** 是否为请求（{@code true}）或应答（{@code false}）。 */
    private final boolean request;

    /** 命令类型字面量。 */
    public String getCmdType() {
        return manscdp == null ? null : manscdp.getCmdType();
    }

    /** 命令序列号。 */
    public int getSn() {
        return manscdp == null ? 0 : manscdp.getSn();
    }

    /** 设备编码。 */
    public String getDeviceId() {
        return manscdp == null ? null : manscdp.getDeviceId();
    }

    /** 命令类型枚举；设备私有命令返回 {@code null}。 */
    public io.github.rurulalanana.gb28181.sip.constant.CmdType cmdTypeEnum() {
        return manscdp == null ? null : manscdp.cmdTypeEnum();
    }
}