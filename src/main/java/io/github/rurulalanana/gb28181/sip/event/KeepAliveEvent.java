package io.github.rurulalanana.gb28181.sip.event;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * 心跳保活事件（标准 A.2.5.2 / 9.10）。
 */
@Getter
@Builder
@ToString
public class KeepAliveEvent {

    /** 信令来源。 */
    private final SipPeer peer;

    /** 源设备编码。 */
    private final String deviceId;

    /**
     * 心跳状态字面量。
     * <p>标准 XSD 定为 {@code tg:resultType}（{@code OK}/{@code ERROR}），
     * 但存量设备常发 {@code ON}/{@code OFF}/{@code NOTIFY}，此处<b>原样透传</b>。
     */
    private final String status;

    /** 命令序列号。 */
    private final int sn;

    /** 故障设备编码列表（{@code <Info>} 容器内）。 */
    private final java.util.List<String> faultyDeviceIds;

    /** 是否正常工作：{@code true} 仅当 status 为 {@code OK} 或 {@code ON}。 */
    public boolean isNormal() {
        return "OK".equalsIgnoreCase(status) || "ON".equalsIgnoreCase(status);
    }
}