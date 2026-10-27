package io.github.rurulalanana.gb28181.sip.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已发出「最终应答」的 INVITE 记录：Call-ID → 状态码。
 *
 * <h3>为什么需要它</h3>
 * INVITE 服务端事务的网络层超时（{@code TimeoutEvent.isServerTransaction() == true}）
 * 有两个截然不同的成因，日志里却长得一模一样：
 * <ul>
 *   <li><b>对端不回 ACK</b> —— INVITE 的最终应答要求 UAC 回 ACK；公网扫描器、
 *       部分非标准实现从不回，服务端事务就一直重传直到 Timer H 超时。
 *       这是<b>对端的问题</b>，功能上毫无影响。</li>
 *   <li><b>我们压根没应答</b> —— 真故障（曾因请求缺 {@code Contact} 头导致
 *       连 403 都发不出去，见 {@code SipMessageFactory#ensureContact}）。</li>
 * </ul>
 * 不区分就会让前者淹没后者：实测公网扫描器每 ~2 分钟一条，
 * 日志里 WARN 刷屏，而真正的「没有应答」根本看不见。
 *
 * <h3>为什么放在这里而不是某个类里</h3>
 * INVITE 的应答有两条发送路径 —— 监听器的 {@code send()} 与
 * {@code Gb28181SipClient#respondInvite/rejectInvite}，两者没有共同持有者。
 * 用一个极小的静态注册表让双方都能记录/查询，比为此改造构造参数更合适。
 *
 * <p>条目只是短期诊断信息：超过上限整体清空，不做精细淘汰
 * （记录方会在超时事件里主动消费，正常情况下不会长期累积）。
 */
public final class InviteResponseRegistry {

    /** Call-ID → 已发出的最终应答状态码。 */
    private static final Map<String, Integer> ANSWERED = new ConcurrentHashMap<>();

    /** 条目上限：超过即整体清空。 */
    private static final int MAX = 512;

    private InviteResponseRegistry() {
    }

    /**
     * 记录「已对这个 INVITE 发出最终应答」。
     *
     * <p>只记非 2xx：2xx 之后由对话（Dialog）接管，事务层不会再有 Timer H 超时，
     * 记了也不会被用到，纯粹占内存。
     *
     * @param callId Call-ID；为空时忽略
     * @param status 最终应答状态码
     */
    public static void answered(String callId, int status) {
        if (callId == null || status < 300) {
            return;
        }
        if (ANSWERED.size() > MAX) {
            ANSWERED.clear();
        }
        ANSWERED.put(callId, status);
    }

    /**
     * 取出并移除某 Call-ID 的应答记录。
     *
     * @return 已发出的状态码；从未记录过返回 {@code null}
     */
    public static Integer consume(String callId) {
        return callId == null ? null : ANSWERED.remove(callId);
    }
}
