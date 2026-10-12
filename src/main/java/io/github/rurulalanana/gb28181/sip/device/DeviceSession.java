package io.github.rurulalanana.gb28181.sip.device;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 已注册的设备/平台会话。
 *
 * <p>实现 {@link Serializable} 以支持经 Redis 等中间件在集群节点间传递。
 */
@Getter
@Builder
@ToString
public class DeviceSession implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 设备编码。 */
    private final String deviceId;

    /** 对端 IP。 */
    private final String ip;

    /** 对端端口。 */
    private final int port;

    /** 传输协议：{@code UDP} / {@code TCP}。 */
    private final String transport;

    /** 注册有效期（秒）。 */
    private final int expires;

    /** 注册时间戳（毫秒）。 */
    private final long registerTime;

    /** 最近一次心跳时间戳（毫秒）。 */
    private volatile long lastKeepAlive;

    /** SIP 监控域。 */
    private final String domain;

    /** 是否为上级平台（true）还是前端设备（false）。 */
    private final boolean platform;

    /**
     * 持有该设备会话的节点 ID。
     *
     * <p>集群场景下，用于把信令路由回注册该设备的节点，调用方无需感知。
     */
    private final String ownerNode;

    /** {@code ip:port} 形式。 */
    public String getAddress() {
        return ip + ":" + port;
    }

    /** 注册是否已过期。 */
    public boolean isExpired() {
        if (expires <= 0) {
            return false;
        }
        return System.currentTimeMillis() - registerTime > expires * 1000L;
    }

    /** 距上次心跳是否超过指定秒数。 */
    public boolean isSilent(long seconds) {
        return System.currentTimeMillis() - lastKeepAlive > seconds * 1000L;
    }

    /** 复制并更新心跳时间戳。 */
    public DeviceSession touch() {
        this.lastKeepAlive = System.currentTimeMillis();
        return this;
    }
}