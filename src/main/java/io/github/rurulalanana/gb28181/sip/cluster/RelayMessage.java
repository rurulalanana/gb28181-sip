package io.github.rurulalanana.gb28181.sip.cluster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 集群节点间转发的信令消息（Redis 模式）。
 *
 * <p>当 SIP 信令需跨节点传递时（例如设备注册在 A 节点、平台请求落到 B 节点），
 * 由 {@link MessageRelay} 投递本消息到目标节点。
 *
 * <p>{@link #requestUri}/{@code fromUri} 等为字符串形式的 SIP URI，避免序列化 javax.sip 对象。
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RelayMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息唯一 ID，用于请求-响应配对。 */
    private String messageId;

    /** 目标设备编码。 */
    private String deviceId;

    /** SIP 方法：MESSAGE / INVITE / NOTIFY / SUBSCRIBE / BYE / ACK ... */
    private String method;

    /** Request-URI。 */
    private String requestUri;

    /** From 头 URI。 */
    private String fromUri;

    /** To 头 URI。 */
    private String toUri;

    /** 对端地址 {@code ip:port}，用于目标节点直接寻址。 */
    private String targetAddress;

    /** 消息体（MANSCDP XML 或 SDP）。 */
    private String body;

    /** 内容类型。 */
    private String contentType;

    /** Call-ID，跨节点保持一致以便响应回溯。 */
    private String callId;

    /** From tag。 */
    private String fromTag;

    /** To tag。 */
    private String toTag;

    /** CSeq。 */
    private long cSeq;

    /** 事件名（SUBSCRIBE/NOTIFY）。 */
    private String eventName;

    /** 订阅有效期（SUBSCRIBE）。 */
    private int expires;

    /** 是否为期望获得响应的请求（否则为单向投递）。 */
    private boolean expectResponse;

    /** 发起时间戳（毫秒）。 */
    private long timestamp = System.currentTimeMillis();
}