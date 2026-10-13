package io.github.rurulalanana.gb28181.sip.config;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * SIP 服务端配置。
 *
 * <p>由 {@link io.github.rurulalanana.gb28181.sip.Gb28181SipServerBuilder} 构造，不建议直接使用。
 */
@Getter
@Builder
@ToString
public class SipServerConfig {

    /** 平台/服务器编码（20 位），用作 From 头域 user 部分。 */
    private final String serverId;

    /** SIP 监控域，取 serverId 前 10 位。 */
    private final String domain;

    /** 监听 IP；{@code null} 或 {@code 0.0.0.0} 表示所有网卡。 */
    private final String host;

    /**
     * 对外通告的地址（写入 INVITE / MESSAGE 的 From、Contact）。
     *
     * <p>{@link #host} 是监听地址（常为 {@code 0.0.0.0}），不能出现在发给对端的
     * 报文里：设备收到 {@code Contact: <sip:平台@127.0.0.1>} 后无法回包，会直接丢弃，
     * 表现为点播无响应。多网卡或 NAT 场景下监听地址与可通告地址也不同，
     * 因此单列本项。为空时退化为「按注册来源地址推断」。
     */
    @Builder.Default
    private final String advertisedHost = null;

    /** 监听端口。 */
    private final int port;

    /** 传输协议：{@code UDP} / {@code TCP}。 */
    private final String transport;

    /** 注册有效期（秒），标准缺省 86400。 */
    private final int registerExpires;

    /** 注册刷新间隔（秒），标准要求不超过 3600。 */
    private final int refreshInterval;

    /** 离线判定：超过该秒数无心跳视为离线。标准心跳周期通常 60s，故默认 180s。 */
    private final int offlineTimeout;

    /**
     * 是否启用 2016 版 {@code MediaControl} 兼容。
     *
     * <p>GB/T 28181—2022 已删除该命令，但存量设备仍在使用。默认关闭。
     */
    private final boolean legacyMediaControl;

    /** 是否在响应 {@code SUBSCRIBE} 时自动携带 {@code Subscription-State}。 */
    private final boolean autoSubscriptionState;

    /** 事务超时（毫秒）。 */
    private final long transactionTimeoutMillis;

/** 过期会话清理周期（秒）。 */
    private final int cleanupInterval;
}