package io.github.rurulalanana.gb28181.sip.sdp;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * GB/T 28181—2022 附录 G SDP 模型。
 *
 * <p>必选：{@code v= o= s= t= m=}；GB 特有扩展：{@code y=}（SSRC）、{@code f=}（媒体描述）。
 *
 * <p>链式 API 全部返回 {@code this}，可直接 {@code new SdpMessage().connection(ip).ssrc(...)}。
 */
@Getter
@Setter
@Accessors(chain = true)
public class SdpMessage {

    // ==================== s= 字段取值 ====================
    /** 实时点播。 */
    public static final String S_PLAY = "Play";
    /** 历史回放。 */
    public static final String S_PLAYBACK = "Playback";
    /** 文件下载。 */
    public static final String S_DOWNLOAD = "Download";

    /** RTP over UDP。 */
    public static final String PROTO_UDP = "RTP/AVP";
    /** RTP over TCP。 */
    public static final String PROTO_TCP = "TCP/RTP/AVP";

    // ==================== 字段 ====================
    private String version = "0";
    private String origin = "- 0 0 IN IP4 0.0.0.0";
    private String sessionName = S_PLAY;
    private String connection = "IN IP4 0.0.0.0";
    private String time = "0 0";
    private String uri;
    private String bandwidth;
    private String ssrc;
    private String mediaFormat;
    private String mediaName = "video";
    private int mediaPort = 6000;
    private String mediaProto = PROTO_UDP;
    private String payloadType = "96";
    private String mediaConnection;
    private String setup;
    private String attributes = "";

    // ==================== 便捷构建 ====================

    /** {@code o=}：标准 9.2.2.2 g) 要求 username 为本设备编码。 */
    public SdpMessage origin(String username, String address) {
        this.origin = username + " 0 0 IN IP4 " + address;
        return this;
    }

    /** {@code c=} 行。 */
    public SdpMessage connection(String address) {
        this.connection = "IN IP4 " + address;
        return this;
    }

    /** 媒体级 {@code c=} 行。 */
    public SdpMessage mediaConnection(String address) {
        this.mediaConnection = "IN IP4 " + address;
        return this;
    }

    /** 通过设备编码生成 {@code y=} 行（SSRC）。 */
    public SdpMessage ssrcOf(String deviceId, int streamSeq, boolean realtime) {
        this.ssrc = io.github.rurulalanana.gb28181.sip.constant.GbId.buildSsrc(deviceId, streamSeq, realtime);
        return this;
    }

    /** 构造标准 {@code f=} 音视频完整形态。 */
    public SdpMessage mediaFormat(String videoCodec, String resolution, int frameRate,
                                  int bitrateType, int bitrate,
                                  String audioCodec, int audioBitrate, int sampleRate) {
        this.mediaFormat = SdpMediaFormat.build(videoCodec, resolution, frameRate, bitrateType, bitrate,
                audioCodec, audioBitrate, sampleRate);
        return this;
    }

    /** 使用预置的 {@code f=} 组合，如 {@link SdpMediaFormat#h264Pip()}。 */
    public SdpMessage mediaFormat(String f) {
        this.mediaFormat = f;
        return this;
    }

    /**
     * {@code m=} 行媒体类型：{@code "video"} 或 {@code "audio"}。
     * <p>标准用 {@code video} 标识视频或视音频混合内容，{@code audio} 标识纯音频。
 */
    public SdpMessage mediaName(String mediaName) {
        this.mediaName = mediaName;
        return this;
    }

    /**
     * {@code m=} 行端口。
     *
     * <p>标准示例：{@code m=video 6000 RTP/AVP 96}。
     */
    public SdpMessage mediaPort(int mediaPort) {
        if (mediaPort > 0 && mediaPort <= 65535) {
            this.mediaPort = mediaPort;
        }
        return this;
    }

    /**
     * {@code m=} 行负载类型。
     *
     * @param payloadType 如 {@code "96"}（H.264）、{@code "8"}（G.711）
     */
    public SdpMessage payloadType(String payloadType) {
        this.payloadType = payloadType;
        return this;
    }

    /**
     * {@code y=} 行，SSRC 值（GB 特有扩展，附录 G）。
     *
     * @see io.github.rurulalanana.gb28181.sip.constant.GbId#buildSsrc(String, int, boolean)
     */
    public SdpMessage ssrc(String ssrc) {
        this.ssrc = ssrc;
        return this;
    }

    /**
     * {@code a=setup:} —— {@code active} 为主动，{@code passive} 为被动
     * （附录 G，协商机制参考 RFC 4571）。
     */
    public SdpMessage setup(String setup) {
        this.setup = setup;
        return this;
    }

    /**
     * {@code m=} 行传输协议。
     *
     * @param proto {@code RTP/AVP}（UDP）或 {@code TCP/RTP/AVP}（TCP）
     */
    public SdpMessage mediaProto(String proto) {
        this.mediaProto = proto;
        return this;
    }

    /** 是否使用 TCP 承载 RTP。 */
    public SdpMessage tcp(boolean tcp) {
        this.mediaProto = tcp ? PROTO_TCP : PROTO_UDP;
        return this;
    }

    /**
     * {@code s=} 行，决定操作类型。
     *
     * <p>标准附录 G：{@code "Play"} 实时点播、{@code "Playback"} 历史回放、
     * {@code "Download"} 文件下载。
     */
    public SdpMessage sessionName(String sessionName) {
        this.sessionName = sessionName;
        return this;
    }

    /** {@code t=} 行，UNIX 时间戳。实时点播两端均置 0（标准 9.2.2.2 f）。 */
    public SdpMessage time(String time) {
        this.time = time;
        return this;
    }

    /** {@code u=} 行，历史回放/下载时填写媒体 URI。 */
    public SdpMessage uri(String uri) {
        this.uri = uri;
        return this;
    }

    /** 实时点播：{@code t=0 0}（标准 9.2.2.2 f）。 */
    public SdpMessage realtime() {
        this.time = "0 0";
        return this;
    }

    /** {@code a=recvonly}。 */
    public SdpMessage recvonly() {
        return attr("recvonly");
    }

    /** {@code a=sendonly}。 */
    public SdpMessage sendonly() {
        return attr("sendonly");
    }

    /** {@code a=sendrecv}。 */
    public SdpMessage sendrecv() {
        return attr("sendrecv");
    }

    /** {@code a=streamnumber:N}。 */
    public SdpMessage streamNumber(int n) {
        return attr("streamnumber:" + n);
    }

    /** {@code a=rtpmap:...}，携带非国标编码器厂商标识。 */
    public SdpMessage rtpmap(String pt, String encoder) {
        return attr("rtpmap:" + pt + " " + encoder + "/90000");
    }

    /** {@code a=filesize:N}。 */
    public SdpMessage filesize(long size) {
        return attr("filesize:" + size);
    }

    /**
     * {@code a=downloadspeed:N}：请求设备按 N 倍速推送录像文件。
     *
     * <p>语义见标准 9.9.1 b) 与附录 G：媒体流接收者在请求的 SDP 里携带
     * 期望的下载发送倍速，设备据此加快推流 —— 8 倍速下载 1 小时录像
     * 只需约 7.5 分钟，而不是实时等满 1 小时。
     *
     * <p><b>N 是倍数不是码率</b>：取值来自设备目录应答的
     * {@code Info/DownloadSpeed}（多档用 {@code /} 分隔，如 {@code 1/2/4/8}）。
     * 设备不支持的档位会被忽略，退化为 1 倍速（实时）。
     *
     * @param speed 倍速，{@code 1} 表示不限速（实时）
     */
    public SdpMessage downloadspeed(int speed) {
        return attr("downloadspeed:" + speed);
    }

    /** 追加任意 {@code a=} 属性。 */
    public SdpMessage attr(String attr) {
        this.attributes = this.attributes.isEmpty() ? attr : this.attributes + "\r\n" + attr;
        return this;
    }

    /** 解析器用：追加属性行（不带 {@code a=} 前缀）。 */
    public SdpMessage addAttr(String attr) {
        return attr(attr);
    }

    // ==================== 输出 ====================

    /** 序列化为标准 SDP 文本，行尾 CRLF（RFC 4566）。 */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        line(sb, "v=", version);
        line(sb, "o=", origin);
        line(sb, "s=", sessionName);
        if (notEmpty(uri)) line(sb, "u=", uri);
        if (notEmpty(connection)) line(sb, "c=", connection);
        if (notEmpty(time)) line(sb, "t=", time);
        if (notEmpty(bandwidth)) line(sb, "b=", bandwidth);
        if (notEmpty(mediaFormat)) line(sb, "f=", mediaFormat);
        sb.append("m=").append(mediaName).append(' ').append(mediaPort)
                .append(' ').append(mediaProto).append(' ').append(payloadType).append("\r\n");
        if (notEmpty(mediaConnection)) line(sb, "c=", mediaConnection);
        if (notEmpty(setup)) line(sb, "a=setup:", setup);
        if (notEmpty(attributes)) {
            for (String a : attributes.split("\r\n")) {
                line(sb, "a=", a);
            }
        }
        // y= 放在末尾：对照 WVP-GB28181-pro 的实际报文顺序（m= → a= → y=）
        if (notEmpty(ssrc)) line(sb, "y=", ssrc);
        return sb.toString();
    }

    /**
     * 从 {@code c=} 行解析媒体 IP，用于回复 200 OK 时回填收流地址。
     *
     * @return 未找到时返回 {@code null}
     */
    public String resolveMediaIp() {
        if (notEmpty(mediaConnection)) {
            String ip = ipOf(mediaConnection);
            if (usable(ip)) {
                return ip;
            }
        }
        if (notEmpty(connection)) {
            String ip = ipOf(connection);
            if (usable(ip)) {
                return ip;
            }
        }
        return null;
    }

    /** 0.0.0.0 属占位地址，不视为可用收流地址。 */
    private static boolean usable(String ip) {
        return ip != null && !ip.isEmpty() && !"0.0.0.0".equals(ip) && !"::".equals(ip);
    }

    // ==================== 内部 ====================

    private static void line(StringBuilder sb, String prefix, String value) {
        sb.append(prefix).append(value).append("\r\n");
    }

    private static String ipOf(String c) {
        String[] p = c.trim().split("\\s+");
        return p.length == 3 ? p[2] : null;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    @Override
    public String toString() {
        return toText();
    }
}