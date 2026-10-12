package io.github.rurulalanana.gb28181.sip.internal;

/**
 * SIP 头域名称常量。
 *
 * <p>不依赖 {@code javax.sip} 的常量：不同版本实现差异较大，自行定义更可控。
 */
public final class SipHeaders {

    private SipHeaders() {
    }

    // ---- 请求/响应必需头域 ----
    public static final String ACCEPT = "Accept";
    public static final String ALLOW = "Allow";
    public static final String AUTHORIZATION = "Authorization";
    public static final String CALL_ID = "Call-ID";
    public static final String CONTACT = "Contact";
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String CSEQ = "CSeq";
    public static final String DATE = "Date";
    public static final String EXPIRES = "Expires";
    public static final String FROM = "From";
    public static final String MAX_FORWARDS = "Max-Forwards";
    public static final String PROXY_AUTH = "Proxy-Authorization";
    public static final String SERVER = "Server";
    public static final String SUBJECT = "Subject";
    public static final String SUPPORTED = "Supported";
    public static final String TO = "To";
    public static final String USER_AGENT = "User-Agent";
    public static final String VIA = "Via";
    public static final String WARNING = "Warning";
    public static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    // ---- 事件订阅（RFC 6665）----
    public static final String EVENT = "Event";
    public static final String SUBSCRIPTION_STATE = "Subscription-State";
    public static final String ALLOW_EVENTS = "Allow-Events";

    // ---- 媒体（RFC 3264 / SDP 协商）----
    public static final String CONTENT_DISPOSITION = "Content-Disposition";
    public static final String CONTENT_LENGTH = "Content-Length";

    /** 按名称查找头域。 */
    public static javax.sip.header.Header find(javax.sip.message.Message message, String name) {
        return message.getHeader(name);
    }
}