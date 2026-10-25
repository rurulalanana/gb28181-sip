package io.github.rurulalanana.gb28181.sip.cascade;

import io.github.rurulalanana.gb28181.sip.Gb28181SipClient;
import io.github.rurulalanana.gb28181.sip.Gb28181SipServer;
import io.github.rurulalanana.gb28181.sip.internal.SipHeaders;
import io.github.rurulalanana.gb28181.sip.internal.SipMessageFactory;
import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpBuilder;
import io.github.rurulalanana.gb28181.sip.security.DigestAuth;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.sip.address.SipURI;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.Header;
import javax.sip.header.WWWAuthenticateHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * 级联上行链路：本级作为<b>下级平台</b>主动向上级平台注册并保活。
 *
 * <h3>为什么必须有这个类</h3>
 * {@link CascadeManager} 处理的是<b>下行</b>——别的平台注册到我们。
 * 而「把本级挂到上级平台下」是<b>相反方向</b>：由我们主动发 REGISTER 给上级。
 * 两者不能共用一个注册表：
 * <ul>
 *   <li>下行平台在 {@link CascadeRegistry}（别人注册进来，我们能查到它的地址）；</li>
 *   <li>上行平台<b>不在任何注册表里</b>——它的地址来自配置，
 *       且永远不会主动告诉我们它在哪。</li>
 * </ul>
 * 之前的实现直接调用 {@code client.sendMessage(parentId, ...)}，
 * 而该方法第一步就是查会话表找地址，上级从不注册到我们这里，
 * 于是<b>必然抛「设备未注册或会话已失效」</b>——这就是「添加上级平台」实际上
 * 只是把配置写进库、一条信令都发不出去的根因。
 *
 * <h3>协议要点（GB/T 28181—2022 第 8 章）</h3>
 * <pre>
 * REGISTER sip:上级域 SIP/2.0
 * From:    &lt;sip:本级编码@本级域&gt;          ← 平台身份，不是设备
 * To:      &lt;sip:本级编码@上级域&gt;
 * Contact: &lt;sip:本级编码@本级对外IP:端口&gt;   ← 上级按这个地址回连
 * Expires: 注册有效期
 * </pre>
 * 上级通常要求摘要认证：先回 401 带 {@code WWW-Authenticate}，本级带
 * {@code Authorization} 重发。注册成功后靠 Keepalive MESSAGE 维持会话，
 * 并在过期前重新注册。
 *
 * <h3>线程模型</h3>
 * 本类<b>不做定时</b>：{@link #register} / {@link #keepalive} 都由调用方驱动。
 * 原因是要把「什么时候注册」的策略（间隔、退避、禁用）留给应用层配置，
 * 库只负责一次完整的协议交互。所有方法都可能阻塞（等待响应），
 * <b>不要</b>在 SIP 协议栈线程上调用。
 */
@Slf4j
public class CascadeRegistrar {

    /** 一次注册/保活交互的默认超时（毫秒）。 */
    private static final long DEFAULT_TIMEOUT_MS = 5000L;

    private final Gb28181SipServer server;
    private final String parentId;
    private final String parentDomain;
    private final String parentIp;
    private final int parentPort;
    /** 本级平台编码（From / To 的用户部分）。 */
    private final String localId;
    /** 本级对上级通告的地址与端口（上级按它回连）。 */
    private final String localIp;
    private final int localPort;
    /** 摘要认证用户名；留空时用本级平台编码。 */
    private final String username;
    private final String password;
    private final int registerExpires;
    /**
     * 对端期望的消息体字符集（默认 GB2312）。
     *
     * <p>国标惯例是 GB2312，而本模块内部一律用 UTF-8 处理。发出去之前必须转成
     * 对端记录的字符集 —— 否则对端按 GB2312 解 UTF-8 字节，中文全变乱码，
     * 而 ID/状态/通道均正常，极易被当成“只是显示问题”。
     */
    private final String charset;

    /** 注册是否成功（最近一次交互的结果）。 */
    private volatile boolean registered;
    /** 最近一次失败原因，供界面展示。 */
    private volatile String lastError;
    /**
     * 上一次已经告警过的原因。
     *
     * <p><b>为什么要比对这个</b>：链路不可达时定时任务每 5 秒重试一次，
     * 若每次都 WARN 会把日志刷满，运维反而看不到真正的变化；
     * 但全部降到 debug 又等于线上完全看不见失败原因（这正是上一版的阥病）。
     * 折中方案：<b>原因变化时告警一次</b>——首次失败、上级从“拒绝”变成“超时”、
     * 成功后再次断开，都会留下一条 WARN，重复失败则不重复刷屏。
     */
    private volatile String lastLoggedError;
    /** 最近一次成功时间戳。 */
    private volatile long lastSuccessAt;
    /** 上级应答里的 realm（用于摘要认证，缓存避免每次都猜）。 */
    private volatile String realm;

    /**
     * 构造一条上行链路。
     *
     * @param server          已启动的 SIP 服务（复用其协议栈与凭据）
     * @param parentId        上级平台编码
     * @param parentDomain    上级 SIP 域（通常取编码前 10 位）
     * @param parentIp        上级平台 IP
     * @param parentPort      上级平台 SIP 端口
     * @param localId         本级平台编码
     * @param localIp         本级对上级通告的 IP（必须是上级可达地址）
     * @param localPort       本级 SIP 端口
     * @param username        摘要认证用户名，可空（空则用 localId）
     * @param password        摘要认证密码，可空（空则不认证）
     * @param registerExpires 注册有效期（秒）
     */
    public CascadeRegistrar(Gb28181SipServer server,
                            String parentId, String parentDomain,
                            String parentIp, int parentPort,
                            String localId, String localIp, int localPort,
                            String username, String password, int registerExpires) {
        this(server, parentId, parentDomain, parentIp, parentPort,
                localId, localIp, localPort, username, password, registerExpires, null);
    }

    /**
     * 构造一条上行链路（可指定对端字符集）。
     *
     * @param charset 对端期望的消息体字符集（{@code GB2312} / {@code UTF-8}）；空则 GB2312
     */
    public CascadeRegistrar(Gb28181SipServer server,
                            String parentId, String parentDomain,
                            String parentIp, int parentPort,
                            String localId, String localIp, int localPort,
                            String username, String password, int registerExpires,
                            String charset) {
        this.server = server;
        this.parentId = parentId;
        this.parentDomain = StringUtils.defaultIfBlank(parentDomain,
                io.github.rurulalanana.gb28181.sip.constant.GbId.domainOf(parentId));
        this.parentIp = parentIp;
        this.parentPort = parentPort;
        this.localId = localId;
        this.localIp = localIp;
        this.localPort = localPort;
        this.username = StringUtils.defaultIfBlank(username, localId);
        this.password = password;
        this.registerExpires = registerExpires <= 0 ? 3600 : registerExpires;
        // 默认 GB2312：绝大多数上级平台/设备按 GB2312 收，且国标惯例如此
        this.charset = StringUtils.defaultIfBlank(charset, "GB2312");
    }

    // ==================== 注册 ====================

    /**
     * 向上级发起一次注册（含 401 挑战重试）。
     *
     * <p>整个过程是<b>同步</b>的：发 REGISTER → 等响应 → 若 401 则带
     * Authorization 重发 → 等响应。上游要在 HTTP 请求里调用时必须放到异步线程，
     * 否则会把 Web 线程占满 5 秒。
     *
     * @return 是否注册成功
     */
    public boolean register() {
        return register(DEFAULT_TIMEOUT_MS);
    }

    /**
     * 向上级发起一次注册。
     *
     * @param timeoutMs 单次交互超时（毫秒）
     * @return 是否成功（收到 2xx）
     */
    public boolean register(long timeoutMs) {
        try {
            Response first = sendRegister(null, timeoutMs);
            if (first == null) {
                return fail("上级平台无响应（检查 IP/端口/防火墙，以及上级是否已添加本级）");
            }
            int code = first.getStatusCode();
            if (code >= 200 && code < 300) {
                return succeed();
            }
            if (code == Response.UNAUTHORIZED && password != null && !password.isEmpty()) {
                // 401 挑战：取出 nonce/realm/qop，带摘要重发
                Header h = first.getHeader(SipHeaders.WWW_AUTHENTICATE);
                if (!(h instanceof WWWAuthenticateHeader)) {
                    return fail("上级返回 401 但未带 WWW-Authenticate，无法完成认证");
                }
                WWWAuthenticateHeader challenge = (WWWAuthenticateHeader) h;
                realm = challenge.getRealm();
                Response second = sendRegister(challenge, timeoutMs);
                if (second == null) {
                    return fail("上级认证后无响应");
                }
                int c2 = second.getStatusCode();
                if (c2 >= 200 && c2 < 300) {
                    return succeed();
                }
                if (c2 == Response.UNAUTHORIZED || c2 == Response.FORBIDDEN) {
                    return fail("上级拒绝认证（" + c2 + "），请核对 SIP 认证用户名与密码");
                }
                return fail("上级返回 " + c2 + " " + second.getReasonPhrase());
            }
            if (code == Response.UNAUTHORIZED) {
                return fail("上级要求摘要认证，但未配置认证密码");
            }
            if (code == Response.FORBIDDEN) {
                return fail("上级拒绝注册（403）：通常表示上级未把本级平台加入白名单/级联列表");
            }
            return fail("上级返回 " + code + " " + first.getReasonPhrase());
        } catch (Exception e) {
            return fail("注册异常：" + e.getMessage());
        }
    }

    /**
     * 注销（Expires: 0）。
     *
     * <p><b>必须尽力而为</b>：停用级联或删除配置时若不注销，上级会一直认为本级在线，
     * 直到注册有效期自然过期——期间上级点播本级的通道会全部失败，
     * 而上级侧看不到任何原因。
     */
    public void unregister() {
        try {
            sendRegister(null, 3000L, 0);
        } catch (Exception e) {
            log.debug("级联注销失败（忽略）: {}", e.toString());
        } finally {
            registered = false;
        }
    }

    /** 发送一次 REGISTER。 */
    private Response sendRegister(WWWAuthenticateHeader challenge, long timeoutMs) {
        return sendRegister(challenge, timeoutMs, registerExpires);
    }

    /**
     * 发送一次 REGISTER。
     *
     * @param challenge 401 挑战；{@code null} 表示首次（不带 Authorization）
     * @param expires   注册有效期（秒）；0 表示注销
     */
    private Response sendRegister(WWWAuthenticateHeader challenge, long timeoutMs, int expires) {
        SipMessageFactory factory = server.getFactory();
        Gb28181SipClient client = server.client();

        SipURI requestUri = factory.createUri(parentDomain, parentIp, parentPort);
        // From 用本级域、To 用上级域：这是平台身份注册的标准形态
        SipURI fromUri = factory.createUri(localId, localIp, localPort);
        // To 头指向上级域；带上级端口是合法的，且能避免上级在非 5060 端口时
        // 部分实现把 To 当成未知域而拒绝
        SipURI toUri = factory.createUri(localId, parentDomain, parentPort);

        String callId = SipMessageFactory.newCallId();
        long cSeq = 1L;
        Request request = factory.buildRequest(Request.REGISTER, requestUri,
                fromUri, factory.newTag(), toUri, null, callId, cSeq,
                null, null);
        try {
            ExpiresHeader eh = factory.headerFactory().createExpiresHeader(expires);
            request.setHeader(eh);
            if (challenge != null) {
                String auth = buildAuthorization(challenge, requestUri.toString());
                request.addHeader(factory.headerFactory()
                        .createHeader(SipHeaders.AUTHORIZATION, auth));
            }
        } catch (Exception e) {
            throw new IllegalStateException("构造 REGISTER 头失败: " + e.getMessage(), e);
        }

        try {
            javax.sip.SipProvider provider = server.sipProvider();
            if (provider == null) {
                throw new IllegalStateException("SIP 服务未启动");
            }
            javax.sip.ClientTransaction tx =
                    (javax.sip.ClientTransaction) provider.getNewClientTransaction(request);
            tx.sendRequest();
            Response response = client.awaitResponse(callId, timeoutMs);
            // 每次都用新 Call-ID，用完即清：否则 responses 表会随注册次数无限增长
            client.forgetCallId(callId);
            return response;
        } catch (Exception e) {
            throw new IllegalStateException("发送 REGISTER 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构造 Authorization 头（RFC 2617 摘要）。
     *
     * <p>直接用字符串形式交给 jain-sip 解析，而不是拼 {@code AuthorizationHeader} 对象：
     * 后者的 setter 行为在各版本间有差异（qop 参数尤其容易漏），
     * 而字符串是最贴近线上报文的形式，出问题时能直接抓包比对。
     */
    private String buildAuthorization(WWWAuthenticateHeader challenge, String digestUri) {
        String usedRealm = StringUtils.defaultIfBlank(challenge.getRealm(),
                parentDomain);
        String nonce = challenge.getNonce();
        String qop = DigestAuth.qopOf(challenge);
        String cnonce = DigestAuth.newNonce();
        String nc = "00000001";
        String response = DigestAuth.response(username, usedRealm, password,
                Request.REGISTER, digestUri, nonce, qop, nc, cnonce);

        StringBuilder sb = new StringBuilder("Digest ");
        sb.append("username=\"").append(username).append("\"");
        sb.append(", realm=\"").append(usedRealm).append("\"");
        sb.append(", nonce=\"").append(nonce).append("\"");
        sb.append(", uri=\"").append(digestUri).append("\"");
        sb.append(", response=\"").append(response).append("\"");
        sb.append(", algorithm=MD5");
        if (StringUtils.isNotBlank(qop)) {
            sb.append(", qop=").append(qop);
            sb.append(", nc=").append(nc);
            sb.append(", cnonce=\"").append(cnonce).append("\"");
        }
        return sb.toString();
    }

    // ==================== 保活 / 目录 ====================

    /**
     * 发送一次心跳。
     *
     * <p>心跳必须用 MESSAGE + MANSCDP Keepalive，且 {@code DeviceID} 填<b>本级平台编码</b>
     * ——上级靠它刷新「这个平台还活着」，不发就会在注册有效期后判定离线，
     * 即使注册本身还没过期。
     *
     * @return 是否发送成功（只代表发出去了，不代表上级回应）
     */
    public boolean keepalive() {
        try {
            sendMessage(ManscdpBuilder.keepalive(server.client().nextSn(), localId));
            return true;
        } catch (Exception e) {
            lastError = "心跳发送失败：" + e.getMessage();
            log.debug("级联心跳发送失败: {}", e.toString());
            return false;
        }
    }

    /**
     * 把通道目录<b>主动推送</b>给上级（MESSAGE + {@code <Notify>}）。
     *
     * <p><b>必须用 {@code <Notify>} 而不是 {@code <Response>}</b>：
     * 后者是对某个查询的答复，上级会按 SN 去匹配它自己发出过的查询，
     * 匹配不上就静默丢弃 —— 实测 LiveGBS 就是这样，双方的日志里都看不到错误，
     * 只有对端「通道数一直为 0」。
     *
     * @param items 通道条目
     * @param sn    消息序号；{@code <=0} 时自动生成
     * @return 是否发送成功
     */
    public boolean pushCatalog(Collection<CatalogItem> items, int sn) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        try {
            int useSn = sn > 0 ? sn : server.client().nextSn();
            sendMessage(ManscdpBuilder.catalogNotify(useSn, localId, items));
            return true;
        } catch (Exception e) {
            lastError = "目录推送失败：" + e.getMessage();
            log.warn("级联目录推送失败: {}", e.toString());
            return false;
        }
    }

    /**
     * 向<b>指定来源</b>回复目录查询结果。
     *
     * <p>与 {@link #pushCatalog} 的差别：这里必须带上甲方原始的 SN 与目标编码 ——
     * 上级发来的是 {@code Query}，它按 {@code (SN, DeviceID)} 匹配应答，
     * SN 对不上就当成超时丢弃，表现为「上级查询目录一直转圈」。
     *
     * @param querySn  上级查询里的原始 SN
     * @param targetId 上级查询里的目标编码（通常是本级编码）
     * @param items    要返回的通道
     */
    public boolean replyCatalog(int querySn, String targetId, Collection<CatalogItem> items) {
        try {
            String deviceId = StringUtils.defaultIfBlank(targetId, localId);
            sendMessage(ManscdpBuilder.catalogResponse(querySn, deviceId,
                    items == null ? java.util.Collections.emptyList() : items));
            return true;
        } catch (Exception e) {
            lastError = "目录应答失败：" + e.getMessage();
            log.warn("级联目录应答失败: {}", e.toString());
            return false;
        }
    }

    /**
     * 按配置地址向<b>上级</b>发送 MANSCDP 消息。
     *
     * <p><b>为什么不能用 {@code client.sendMessage(parentId, xml)}</b>：
     * 那个方法要先在会话表里查上级地址，而上级永远不会注册到本级，
     * 必然抛「设备未注册或会话已失效」。这里直接用配置里的 IP/端口寻址。
     */
    private void sendMessage(String xml) {
        // 必须把 localId 作为 From：级联身份可能与自身平台编码不同（见 sendMessageTo 注释），
        // 用错了上级会把它当成未知来源静默丢弃。
        // charset 同理：对端按它自己记录的设备字符集解码，发错就是通道名乱码。
        server.client().sendMessageTo(localId, parentId, parentIp, parentPort, xml, charset);
    }

    // ==================== 状态 ====================

    private boolean succeed() {
        if (!registered) {
            log.info("级联注册成功: 本级 {} -> 上级 {}({})", localId, parentId, parentIp);
        }
        registered = true;
        lastError = null;
        lastLoggedError = null;
        lastSuccessAt = System.currentTimeMillis();
        return true;
    }

    private boolean fail(String reason) {
        boolean first = !reason.equals(lastLoggedError);
        if (registered || first) {
            log.warn("级联注册失败: 上级 {}({}) —— {}", parentId, parentIp, reason);
            lastLoggedError = reason;
        }
        registered = false;
        lastError = reason;
        return false;
    }

    /** 当前是否处于已注册状态。 */
    public boolean isRegistered() {
        return registered;
    }

    /** 最近一次失败原因；成功时为 {@code null}。 */
    public String lastError() {
        return lastError;
    }

    /** 最近一次成功注册的时间戳（毫秒）。 */
    public long lastSuccessAt() {
        return lastSuccessAt;
    }

    /** 上级平台编码。 */
    public String parentId() {
        return parentId;
    }

    /** 注册有效期（秒）。 */
    public int registerExpires() {
        return registerExpires;
    }

    /** 上级应答里的 realm（用于诊断「认证失败」到底是哪一环）。 */
    public String realm() {
        return realm;
    }

    @Override
    public String toString() {
        return "CascadeRegistrar{" + localId + " -> " + parentId + "@" + parentIp
                + ":" + parentPort + ", registered=" + registered
                + (lastError == null ? "" : ", error=" + lastError) + "}";
    }
}
