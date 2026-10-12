package io.github.rurulalanana.gb28181.sip.event;

import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * 设备注册 / 注销事件（标准 9.1）。
 *
 * <p>注销时 {@code expires=0}，模块以 {@code unregistered} 标记区分，不单独开事件方法。
 */
@Getter
@Builder
@ToString
public class RegisterEvent {

    /** 信令来源。 */
    private final SipPeer peer;

    /** 设备编码。 */
    private final String deviceId;

    /** 注册有效期（秒）。{@code 0} 表示注销。 */
    private final int expires;

    /** 是否为注销（Expires=0）。 */
    private final boolean unregistered;

    /** 认证用的用户名（通常等于 deviceId）。 */
    private final String username;

    /** Contact 头域携带的传输地址，可能与 peer 不同（NAT 场景）。 */
    private final String contactUri;

    /** 本地接收地址（服务端 IP:port）。 */
    private final String localAddress;

    /** 传输协议：{@code UDP} / {@code TCP}。 */
    private final String transport;

    /** 平台 ID（From 头域 user），上级平台注册时为上级编码。 */
    private final String platformId;
}