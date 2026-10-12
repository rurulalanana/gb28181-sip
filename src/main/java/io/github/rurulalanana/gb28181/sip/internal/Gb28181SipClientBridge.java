package io.github.rurulalanana.gb28181.sip.internal;

import javax.sip.ResponseEvent;
import javax.sip.TimeoutEvent;

/**
 * SIP 栈事件 → 客户端同步等待 的桥接。
 *
 * <p>模块内部协议栈监听器收到响应/超时时，通过静态注册表找到等待中的
 * {@link io.github.rurulalanana.gb28181.sip.Gb28181SipClient} 实例并唤醒。
 *
 * <p>设计取舍：同一 {@code SipStack} 通常由单个服务实例持有，因此静态单例即可满足
 * 多微服务场景（每个进程各自一份）。若单进程内需运行多个独立 SIP 栈，
 * 可改为按栈实例注册。
 */
public final class Gb28181SipClientBridge {

    /** 已注册的客户端。 */
    private static final java.util.List<io.github.rurulalanana.gb28181.sip.Gb28181SipClient> CLIENTS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private Gb28181SipClientBridge() {
    }

    public static void register(io.github.rurulalanana.gb28181.sip.Gb28181SipClient client) {
        CLIENTS.add(client);
    }

    public static void unregister(io.github.rurulalanana.gb28181.sip.Gb28181SipClient client) {
        CLIENTS.remove(client);
    }

    /** 收到响应时唤醒等待方。 */
    public static void onResponse(ResponseEvent event) {
        for (io.github.rurulalanana.gb28181.sip.Gb28181SipClient c : snapshot()) {
            c.onResponse(event);
        }
    }

    /** 事务超时时释放等待方。 */
    public static void onTimeout(TimeoutEvent event) {
        for (io.github.rurulalanana.gb28181.sip.Gb28181SipClient c : snapshot()) {
            c.onTimeout(event);
        }
    }

    private static java.util.List<io.github.rurulalanana.gb28181.sip.Gb28181SipClient> snapshot() {
        synchronized (CLIENTS) {
            return new java.util.ArrayList<>(CLIENTS);
        }
    }
}