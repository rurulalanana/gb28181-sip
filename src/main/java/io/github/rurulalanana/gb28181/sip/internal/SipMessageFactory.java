package io.github.rurulalanana.gb28181.sip.internal;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.sip.SipFactory;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.EventHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.Header;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.SubscriptionStateHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.ViaHeader;
import javax.sip.header.WWWAuthenticateHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.security.SecureRandom;
import java.text.ParseException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * SIP 报文工厂。
 *
 * <p>封装 {@code javax.sip} 的 {@link MessageFactory} 与
 * {@link gov.nist.javax.sip.header.HeaderFactoryExt}，并按 GB/T 28181 约定提供便捷构造
 * （20 位编码 SIP URI、标准内容类型等）。
 *
 * <p>内部方法不抛受检异常，统一转为 {@link SipBuildException}。
 */
@Slf4j
public final class SipMessageFactory {

    /** MANSCDP 内容类型（标准 9.5.3.1 / 9.11.1.3）。 */
    public static final String MANSCDP_CONTENT_TYPE = "Application/MANSCDP+xml";
    /** SDP 内容类型（标准 9.2.2.2 c）。 */
    public static final String SDP_CONTENT_TYPE = "application/sdp";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SipFactory sipFactory;
    private final AddressFactory addressFactory;
    private final MessageFactory messageFactory;
    private final gov.nist.javax.sip.header.HeaderFactoryExt headerFactory;

    public SipMessageFactory() {
        this(SipFactory.getInstance());
    }

    public SipMessageFactory(SipFactory sipFactory) {
        this.sipFactory = sipFactory;
        try {
            this.addressFactory = sipFactory.createAddressFactory();
            this.messageFactory = sipFactory.createMessageFactory();
            this.headerFactory = (gov.nist.javax.sip.header.HeaderFactoryExt)
                    sipFactory.createHeaderFactory();
        } catch (Exception e) {
            throw new SipBuildException("SIP 工厂初始化失败", e);
        }
    }

    public gov.nist.javax.sip.header.HeaderFactoryExt headerFactory() {
        return headerFactory;
    }

    public SipFactory sipFactory() {
        return sipFactory;
    }

    // ==================== 基础构件 ====================

    /**
     * 构造 GB28181 SIP URI（标准 6.1.2）。
     * <p>{@code sip:username@domain}，username 为 20 位编码，domain 取其前 10 位。
     *
     * @param deviceId 设备编码（作为 username）
     * @param host     主机地址
     * @param port     端口
     */
    public SipURI createUri(String deviceId, String host, int port) {
        String domain = io.github.rurulalanana.gb28181.sip.constant.GbId.isValidDeviceId(deviceId)
                ? io.github.rurulalanana.gb28181.sip.constant.GbId.domainOf(deviceId)
                : StringUtils.defaultIfBlank(host, "127.0.0.1");
        try {
            SipURI uri = addressFactory.createSipURI(deviceId, domain);
            uri.setHost(host);
            uri.setPort(port);
            return uri;
        } catch (ParseException e) {
            throw new SipBuildException("构造 SIP URI 失败: " + deviceId, e);
        }
    }

    /** 构造仅含 host:port 的 URI（Request-URI 用）。 */
    public SipURI createHostUri(String host, int port) {
        try {
            SipURI uri = addressFactory.createSipURI(null, host);
            uri.setHost(host);
            uri.setPort(port);
            return uri;
        } catch (ParseException e) {
            throw new SipBuildException("构造 host URI 失败: " + host, e);
        }
    }

/** 生成随机 tag。 */
    public static String newTag() {
        return Long.toHexString(RANDOM.nextLong());
    }

    /** 生成随机 Call-ID。 */
    public static String newCallId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ==================== 请求 ====================

    /**
     * 构造带完整必需头域的请求。
     *
     * @param toTag       To 头 tag，可 {@code null}
     * @param contentType 内容类型，可 {@code null}
     * @param body        消息体，可 {@code null}
     */
    public Request buildRequest(String method, SipURI requestUri,
                                SipURI fromUri, String fromTag,
                                SipURI toUri, String toTag,
                                String callId, long cSeq,
                                String contentType, String body) {
        try {
            FromHeader from = headerFactory.createFromHeader(
                    addressFactory.createAddress(fromUri), fromTag);
            ToHeader to = headerFactory.createToHeader(
                    addressFactory.createAddress(toUri), toTag);
            CSeqHeader cseq = headerFactory.createCSeqHeader(cSeq, method);
            CallIdHeader callIdHeader = headerFactory.createCallIdHeader(callId);
            MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);
            // jain-sip 的 createRequest 对 ct 与 body 都要求非 null，
            // 无消息体时用 application/octet-stream 占位（协议栈会忽略 Content-Type）
            ContentTypeHeader ct = contentType(contentType != null ? contentType
                    : "application/octet-stream");

            // Via 必须显式构造并使用「对外通告地址」：
            // 传空列表时协议栈按监听地址（常为 0.0.0.0 / [::]）补全，
            // 真实设备收到形如 "Via: SIP/2.0/UDP [0:0:0:0:0:0:0:0]:5060"
            // 的 Via 会直接丢弃 INVITE，表现为点播无响应。
            // fromUri 已带通告地址，故直接复用。
            String viaHost = fromUri.getHost() != null ? fromUri.getHost() : "127.0.0.1";
            int viaPort = fromUri.getPort() != -1 ? fromUri.getPort() : 5060;
            ViaHeader viaHeader = headerFactory.createViaHeader(viaHost, viaPort, "UDP", newTag());
            // rport（RFC 3581）：要求对端把响应发回<b>实际收到报文的源地址</b>，
            // 而不是 Via 里写的地址。国标设备普遍要求带此参数，
            // 且经 NAT / frp 端口映射时尤为重要：
            // 映射后平台看到的设备源端口（如 frpc 在本地开的临时端口）
            // 与设备自报的 5060 不同，不带 rport 时响应会发到错误端口而丢失，
            // 表现为「INVITE 已发出但设备无响应」。
            // 注意：从无参构造的 Via 上调用 setRPort 才会真正写入 "rport"，
            // 所以此处单独构造而非复用上面的临时变量。
            try {
                viaHeader.setRPort();
            } catch (Exception ignore) {
                // 部分实现不支持 rport，忽略即可（不影响基础功能）
            }
            List<ViaHeader> via = Collections.singletonList(viaHeader);
            // jain-sip 要求 body 参数非 null（无消息体时传空串而非 null），
            // 否则 createRequest 抛 NPE("Null parameters")
            String bodyArg = body == null ? "" : body;
            Request request = messageFactory.createRequest(
                    requestUri, method, callIdHeader, cseq, from, to,
                    via, maxForwards, ct, bodyArg);

            // REGISTER 等需带 Contact
            ContactHeader contact = headerFactory.createContactHeader(
                    addressFactory.createAddress(fromUri));
            request.addHeader(contact);
            return request;
        } catch (Exception e) {
            throw new SipBuildException("构造 SIP 请求失败: " + method, e);
        }
    }

    /**
     * 构造内容类型头。
     *
     * <p>标准使用 {@code Application/MANSCDP+xml}（含参数形式），
     * 故此处按 type/subtype 拆分后调用双参重载。
     */
    private ContentTypeHeader contentType(String contentType) throws ParseException {
        if (StringUtils.isBlank(contentType)) {
            return null;
        }
        int slash = contentType.indexOf('/');
        if (slash < 0) {
            return headerFactory.createContentTypeHeader(contentType, "xml");
        }
        return headerFactory.createContentTypeHeader(
                contentType.substring(0, slash), contentType.substring(slash + 1));
    }

    /** 构造 MANSCDP MESSAGE 请求（标准 9.3 c / 9.5 d）。 */
    public Request buildMessage(SipURI requestUri, SipURI fromUri, SipURI toUri, String xml) {
        return buildRequest(Request.MESSAGE, requestUri, fromUri, newTag(),
                toUri, null, newCallId(), 1L, MANSCDP_CONTENT_TYPE, xml);
    }

    /**
     * 构造 MANSCDP MESSAGE 请求，消息体按指定字符集编码（标准 9.3 c / 9.5 d）。
     *
     * <p><b>为什么要能指定字符集</b>：国标里 MANSCDP 的字符集由<b>对端</b>决定 ——
     * 很多平台（实测 LiveGBS）对每个设备存了一个 {@code Charset}（默认 GB2312），
     * 收到消息后按它解码。我们发 UTF-8 字节、声明里也写 UTF-8，但对方按 GB2312 解，
     * 结果就是通道名变成「鐏姳姘村簱」这种乱码 —— 而<b>通道本身、ID、状态全都正常</b>，
     * 所以很容易被当成「只是显示问题」忽略掉。
     *
     * <p>这里把「声明」与「实际字节」一起按目标字符集生成，两者永远一致。
     *
     * @param charset 对端期望的字符集（如 {@code GB2312} / {@code UTF-8}）；空则 UTF-8
     */
    public Request buildMessage(SipURI requestUri, SipURI fromUri, SipURI toUri,
                                String xml, String charset) {
        String cs = StringUtils.isBlank(charset) ? "UTF-8" : charset.trim();
        // 声明与实际字节必须一致：严格的解析器会因不一致直接报错，
        // 宽松的解析器按声明解，照样乱码。
        String body = xml == null ? "" : xml.replaceFirst(
                "(?i)encoding\\s*=\\s*\"[^\"]*\"", "encoding=\"" + cs + "\"");
        byte[] bytes;
        try {
            bytes = body.getBytes(cs);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new SipBuildException("不支持的字符集: " + cs, e);
        }
        return buildRequestBytes(Request.MESSAGE, requestUri, fromUri, newTag(),
                toUri, null, newCallId(), 1L, MANSCDP_CONTENT_TYPE, cs, bytes);
    }

    /**
     * 构造消息体为原始字节的请求。
     *
     * <p>与 {@link #buildRequest} 的唯一区别是 content 走 jain-sip 的 {@code byte[]}
     * 重载，从而<b>完全控制字符编码</b>。String 重载由协议栈内部决定编码，
     * 调用方无法干预 —— 这正是级联字符集问题的根源。
     */
    public Request buildRequestBytes(String method, SipURI requestUri,
                                     SipURI fromUri, String fromTag,
                                     SipURI toUri, String toTag,
                                     String callId, long cSeq,
                                     String contentType, String charset, byte[] content) {
        try {
            FromHeader from = headerFactory.createFromHeader(
                    addressFactory.createAddress(fromUri), fromTag);
            ToHeader to = headerFactory.createToHeader(
                    addressFactory.createAddress(toUri), toTag);
            CSeqHeader cseq = headerFactory.createCSeqHeader(cSeq, method);
            CallIdHeader callIdHeader = headerFactory.createCallIdHeader(callId);
            MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);
            ContentTypeHeader ct = contentType(contentType);
            if (ct != null && StringUtils.isNotBlank(charset)) {
                // 显式带 charset 参数：部分平台优先看这里而不是 XML 声明
                ct.setParameter("charset", charset);
            }
            String viaHost = fromUri.getHost() != null ? fromUri.getHost() : "127.0.0.1";
            int viaPort = fromUri.getPort() != -1 ? fromUri.getPort() : 5060;
            ViaHeader viaHeader = headerFactory.createViaHeader(viaHost, viaPort, "UDP", newTag());
            try {
                viaHeader.setRPort();
            } catch (Exception ignore) {
                // 同上：不支持 rport 的实现忽略即可
            }
            List<ViaHeader> via = Collections.singletonList(viaHeader);
            Request request = messageFactory.createRequest(requestUri, method, callIdHeader,
                    cseq, from, to, via, maxForwards, ct,
                    content == null ? new byte[0] : content);
            ContactHeader contact = headerFactory.createContactHeader(
                    addressFactory.createAddress(fromUri));
            request.addHeader(contact);
            return request;
        } catch (Exception e) {
            throw new SipBuildException("构造 SIP 请求失败: " + method, e);
        }
    }

    /** 构造带 SDP 的 INVITE 请求（标准 9.2）。 */
    public Request buildInvite(SipURI requestUri, SipURI fromUri, SipURI toUri,
                               String sdp, String subject) {
        Request req = buildRequest(Request.INVITE, requestUri, fromUri, newTag(),
                toUri, null, newCallId(), 1L, SDP_CONTENT_TYPE, sdp);
        if (StringUtils.isNotBlank(subject)) {
            try {
                req.addHeader(headerFactory.createHeader(SipHeaders.SUBJECT, subject));
            } catch (ParseException e) {
                log.warn("构造 Subject 头失败: {}", e.getMessage());
            }
        }
        return req;
    }

    /**
     * 补齐 {@code Max-Forwards} 头（RFC 3261 §8.1.1.6 要求逐跳携带）。
     *
     * <p>jain-sip 的 {@code getNewServerTransaction} 会校验该头，缺失时抛
     * {@code Missing a required header : Max-Forwards}，导致应答被静默丢弃。
     * 部分国标设备收不到应答确认就不推流，因此必须补齐。
     *
     * @return 是否补齐（原本已存在则返回 false）
     */
    public boolean ensureMaxForwards(Request request) {
        if (request.getHeader(SipHeaders.MAX_FORWARDS) != null) {
            return false;
        }
        try {
            request.addHeader(headerFactory.createMaxForwardsHeader(70));
            return true;
        } catch (javax.sip.InvalidArgumentException e) {
            log.warn("补齐 Max-Forwards 失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 构造会话内 {@code INFO} 请求（标准附录 D 回放控制，RFC 2976）。
     *
     * <p>与 {@link #buildInvite} 不同：INFO 必须复用 INVITE 的 Call-ID 与对端 tag，
     * 属于同一对话框内的请求，否则设备无法把控制指令关联到正在进行的回放会话。
     *
     * @param sdp 控制指令 SDP（{@code u=} 携带 PLAY/PAUSE/SEEK 等命令，{@code a=Scale:} 携带倍速）
     */
    public Request buildInfo(SipURI requestUri, SipURI fromUri, String fromTag,
                             SipURI toUri, String toTag, String callId, long cSeq,
                             String sdp) {
        return buildRequest("INFO", requestUri, fromUri, fromTag,
                toUri, toTag, callId, cSeq, SDP_CONTENT_TYPE, sdp);
    }

    /**
     * 构造 {@code INFO} 请求，显式指定消息体类型。
     *
     * <p>回放控制的消息体是 <b>RTSP 风格文本</b>而不是 SDP，
     * 用 {@code application/sdp} 声明它会被部分设备判为格式非法。
     * 国标惯例写 {@code application/MANSRTSP}。
     *
     * @param contentType 消息体类型；{@code null} 表示不带 Content-Type 头
     */
    public Request buildInfo(SipURI requestUri, SipURI fromUri, String fromTag,
                             SipURI toUri, String toTag, String callId, long cSeq,
                             String body, String contentType) {
        return buildRequest("INFO", requestUri, fromUri, fromTag,
                toUri, toTag, callId, cSeq, contentType, body);
    }

    /**
     * 构造 SUBSCRIBE 请求（标准 9.11，RFC 6665）。
     *
     * @param eventName 事件名，如 {@code Catalog}
     * @param expires   订阅有效期秒数
     */
    public Request buildSubscribe(SipURI requestUri, SipURI fromUri, SipURI toUri,
                                  String eventName, int expires, String body) {
        Request req = buildRequest("SUBSCRIBE", requestUri, fromUri, newTag(),
                toUri, null, newCallId(), 1L,
                StringUtils.isBlank(body) ? null : MANSCDP_CONTENT_TYPE, body);
        try {
            if (StringUtils.isNotBlank(eventName)) {
                // 事件的 type 取 Event 头冒号前的包名（如 "presence"），
                // id 取具体事件名（如 "Catalog"）；先设 type 再设 id，
                // 否则 createHeader 解析出的空 type 会导致 addHeader 失败
                // createHeader("Event") 会因无初始值而解析失败，
                // 须一次性给出完整头值 "Event: presence;id=Catalog"
                String type = "presence";
                String id = eventName;
                int colon = eventName.indexOf(':');
                if (colon > 0) {
                    type = eventName.substring(0, colon);
                    id = eventName.substring(colon + 1);
                }
                EventHeader event = (EventHeader) headerFactory.createHeader(
                        SipHeaders.EVENT, type + ";id=" + id);
                req.addHeader(event);
            }
            req.addHeader(headerFactory.createHeader(SipHeaders.EXPIRES, String.valueOf(expires)));
        } catch (ParseException e) {
            throw new SipBuildException("构造 SUBSCRIBE 失败", e);
        }
        return req;
    }

    // ==================== 响应 ====================

/**
 * 构造对请求的响应。
 *
 * <p><b>注意</b>：jain-sip 的 {@code createResponse(int, Request, ContentType, body)}
 * 在 body 为 {@code null} 时会抛 NPE，故无消息体场景走两参重载。
 *
 * @param statusCode 状态码
 * @param body      消息体，可 {@code null}
 */
    public Response buildResponse(int statusCode, Request request, String contentType, String body) {
        try {
            if (StringUtils.isBlank(body)) {
                Response response = messageFactory.createResponse(statusCode, request);
                return withToTag(response);
            }
            ContentTypeHeader ct = contentType(contentType);
            byte[] raw = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Response response = ct == null
                    ? messageFactory.createResponse(statusCode, request, null, raw)
                    : messageFactory.createResponse(statusCode, request, ct, raw);
            return withToTag(response);
        } catch (Exception e) {
            throw new SipBuildException("构造 SIP 响应失败: " + statusCode, e);
        }
    }

    /**
     * 应答的 To 头需带 tag（RFC 3261 §8.2.6.2）。
     */
    private Response withToTag(Response response) throws ParseException {
        ToHeader to = (ToHeader) response.getHeader(SipHeaders.TO);
        if (to != null && to.getTag() == null) {
            response.addHeader(headerFactory.createToHeader(to.getAddress(), newTag()));
        }
        return response;
    }

    /**
     * 为 INVITE 的 2xx 应答补充 Contact 头。
     *
     * <p>RFC 3261 §13.3.1.4：{@code 200 OK} 应答 INVITE 时<b>必须</b>携带 Contact 头
     * （对端据此发送 ACK / BYE）；jain-sip 对缺失 Contact 的 INVITE 2xx 应答会直接抛
     * {@code IllegalTransactionStateException} 拒绝发送。
     */
    public Response withContact(Response response, String userId, String host, int port) {
        try {
            if (response.getHeader(ContactHeader.NAME) != null) {
                return response;
            }
            Address address = addressFactory.createAddress(createUri(userId, host, port));
            response.setHeader(headerFactory.createContactHeader(address));
            return response;
        } catch (Exception e) {
            throw new SipBuildException("添加 Contact 头失败", e);
        }
    }

    /** 构造 401 challenge（标准 9.1.2.1 b）。 */
    public Response buildUnauthorized(Request request, String realm, String nonce) {
        Response response = buildResponse(Response.UNAUTHORIZED, request, null, null);
        try {
            WWWAuthenticateHeader auth = headerFactory.createWWWAuthenticateHeader(
                    "Digest realm=\"" + realm + "\", nonce=\"" + nonce + "\"");
            auth.setAlgorithm("MD5");
            response.addHeader(auth);
        } catch (Exception e) {
            throw new SipBuildException("构造 401 challenge 失败", e);
        }
        return response;
    }

    /**
     * 追加 {@code Subscription-State} 头（RFC 6665）。
     *
     * @param state   {@code active} / {@code terminated} / {@code pending}
     * @param expires 剩余有效期秒数，{@code <=0} 时不带 expires 参数
     */
    public void addSubscriptionState(Response response, String state, int expires) {
        try {
            SubscriptionStateHeader ssh = (SubscriptionStateHeader)
                    headerFactory.createHeader(SipHeaders.SUBSCRIPTION_STATE);
            ssh.setState(state);
            if (expires > 0) {
                ssh.setExpires(expires);
            }
            response.addHeader(ssh);
        } catch (Exception e) {
            log.warn("构造 Subscription-State 失败: {}", e.getMessage());
        }
    }

    /** 提取请求的 Request-URI 字符串。 */
    public static String requestUriOf(Request request) {
        return request.getRequestURI() == null ? null : request.getRequestURI().toString();
    }

    /** 提取 {@code user} 部分（设备编码）。 */
    public static String userOf(Address address) {
        if (address == null || address.getURI() == null) {
            return null;
        }
        String uri = address.getURI().toString();
        int at = uri.indexOf('@');
        int colon = uri.indexOf(':');
        if (colon >= 0 && at > colon) {
            return uri.substring(colon + 1, at);
        }
        return null;
    }
}