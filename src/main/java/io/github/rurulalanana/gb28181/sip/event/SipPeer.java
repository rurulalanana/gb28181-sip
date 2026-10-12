package io.github.rurulalanana.gb28181.sip.event;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * SIP 对端会话上下文。
 *
 * <p>承载一次信令交互的来源信息，模块不做任何业务判断，全部交给调用方。
 */
@Getter
@Builder
@ToString
public class SipPeer {

    /** 设备编码（取自 SIP URI 的 user 部分或 MANSCDP 的 DeviceID）。 */
    private final String deviceId;

    /** 对端 IP。 */
    private final String ip;

    /** 对端端口。 */
    private final int port;

    /** 传输协议：{@code UDP} / {@code TCP}。 */
    private final String transport;

    /** SIP 监控域，取设备编码前 10 位（标准 6.1.2）。 */
    private final String domain;

    /** {@code ip:port} 形式，便于直接使用。 */
    public String getAddress() {
        return ip + ":" + port;
    }

    /** 静态工厂。 */
    public static SipPeer of(String deviceId, String ip, int port) {
        return SipPeer.builder().deviceId(deviceId).ip(ip).port(port)
                .domain(io.github.rurulalanana.gb28181.sip.constant.GbId.isValidDeviceId(deviceId)
                        ? io.github.rurulalanana.gb28181.sip.constant.GbId.domainOf(deviceId) : null)
                .build();
    }

    /** 静态工厂（含传输协议）。 */
    public static SipPeer of(String deviceId, String ip, int port, String transport) {
        return SipPeer.builder().deviceId(deviceId).ip(ip).port(port).transport(transport)
                .domain(io.github.rurulalanana.gb28181.sip.constant.GbId.isValidDeviceId(deviceId)
                        ? io.github.rurulalanana.gb28181.sip.constant.GbId.domainOf(deviceId) : null)
                .build();
    }
}