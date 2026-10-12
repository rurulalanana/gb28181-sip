package io.github.rurulalanana.gb28181.sip.cascade;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个下级平台的会话与级联状态。
 *
 * <p>标准 8.2：下级平台向上级平台注册，注册成功后上级维护该下级平台的会话，
 * 并可通过 {@code Catalog} 查询其下挂设备目录。
 *
 * <p><b>本类只承载信令状态</b>，流转发（上级从下级收流）由调用方通过
 * {@code SipCallback#onCascadeInvite} 回调处理。
 */
@Getter
@Builder
@ToString
public class CascadePlatform implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 下级平台编码（20 位）。 */
    private final String platformId;

    /** 对端 IP。 */
    private final String ip;

    /** 对端端口。 */
    private final int port;

    /** 传输协议。 */
    private final String transport;

    /** 注册有效期（秒）。 */
    private final int expires;

    /** 注册时间戳（毫秒）。 */
    private final long registerTime;

    /** 最近心跳时间戳。 */
    private volatile long lastKeepAlive;

    /** 该下级平台的 SIP 监控域（取编码前 10 位）。 */
    private final String domain;

    /** 是否已完成级联目录查询（用于避免重复全量查询）。 */
    private volatile boolean catalogSynced;

    /** 缓存的设备总数（标准 Catalog 应答的 SumNum）。 */
    private volatile int deviceCount;

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

    /** 刷新心跳时间戳。 */
    public CascadePlatform touch() {
        this.lastKeepAlive = System.currentTimeMillis();
        return this;
    }

    /** 在线判定：未过期且心跳未超时。 */
    public boolean isOnline(long silentTimeoutSeconds) {
        return !isExpired() && !isSilent(silentTimeoutSeconds);
    }

    /**
     * 级联查询的内部序列号分配器。
     *
     * <p>标准 A.2.1.3：SN 取值 [1, 9999]。级联时上级向下级发的查询需要
     * 与调用方原始 SN 关联，故此处维护"SN → 原始上下文"的映射。
     */
    public static class SnAllocator {

        private static final int MAX = 9999;

        /** 内部 SN → 上下文。 */
        private final Map<Integer, Object> contexts = new ConcurrentHashMap<>();
        private final AtomicInteger cursor = new AtomicInteger(1);

        /**
         * 分配一个内部 SN。
         *
         * @param context 关联上下文
         * @return 1~9999 的 SN
         */
        public int allocate(Object context) {
            int sn = Math.floorMod(cursor.getAndIncrement(), MAX) + 1;
            contexts.put(sn, context == null ? Boolean.TRUE : context);
            return sn;
        }

        /**
         * 取出并移除 SN 对应的上下文。
         *
         * @return 上下文，从未分配过则返回 {@code null}
         */
        public Object consume(int sn) {
            return contexts.remove(sn);
        }

        /** 清理超过保留期的 SN 上下文，避免内存泄漏。 */
        public void purge(int keepSize) {
            if (contexts.size() <= keepSize) {
                return;
            }
            contexts.keySet().stream()
                    .sorted()
                    .limit(Math.max(0, contexts.size() - keepSize))
                    .forEach(contexts::remove);
        }

        /** 当前挂起的级联查询数。 */
        public int pending() {
            return contexts.size();
        }

        /** 只读视图，供测试与监控。 */
        public Map<Integer, Object> contexts() {
            return Collections.unmodifiableMap(contexts);
        }
    }
}