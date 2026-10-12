package io.github.rurulalanana.gb28181.sip.sdp;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * {@code f=} 媒体描述字段的构造与解析（附录 G）。
 *
 * <p>视频：{@code v/编码格式/分辨率/帧率/码率类型/码率大小}
 * <ul>
 *   <li>编码格式：1=MPEG-4, 2=H.264, 3=SVAC, 4=3GP, 5=H.265</li>
 *   <li>分辨率：1=QCIF, 2=CIF, 3=4CIF, 4=D1, 5=720P, 6=1080P/F，其余 {@code WxH}</li>
 *   <li>帧率 0~99；码率类型 1=CBR 2=VBR；码率 0~100000 kb/s</li>
 * </ul>
 * <p>音频：{@code a/编码格式/码率大小/采样率}
 * <ul>
 *   <li>编码格式：1=G.711, 2=G.723.1, 3=G.729, 4=G.722.1, 5=SVAC, 6=AAC</li>
 * </ul>
 */
public final class SdpMediaFormat {

    public static final int VC_MPEG4 = 1;
    public static final int VC_H264 = 2;
    public static final int VC_SVAC = 3;
    public static final int VC_3GP = 4;
    public static final int VC_H265 = 5;

    public static final String RES_QCIF = "1";
    public static final String RES_CIF = "2";
    public static final String RES_4CIF = "3";
    public static final String RES_D1 = "4";
    public static final String RES_720P = "5";
    public static final String RES_1080P = "6";

    public static final int CBR = 1;
    public static final int VBR = 2;

    private SdpMediaFormat() {
    }

    /**
     * 构造 {@code f=} 字段。
     *
     * <p>标准附录 G 原文：
     * <pre>
     *   完整:  f=v/编码格式/分辨率/帧率/码率类型/码率大小/a/编码格式/码率大小/采样率
     *   仅视频: f=v/编码格式/分辨率/帧率/码率类型/码率大小/a///
     *   仅音频: f=v///////a/编码格式/码率大小/采样率
     * </pre>
     * 分隔符 {@code /} <b>不可省略</b>；空项表示无该参数（标准注 14）。
     */
    public static String build(String videoCodec, String resolution, Integer frameRate,
                               Integer bitrateType, Integer bitrate,
                               String audioCodec, Integer audioBitrate, Integer sampleRate) {
        // 注意 "a" 前需 7 个分隔符：5 个视频项 + 1 个分隔 + 1 个分隔（对齐标准第 7 段）
        return "v" + sep(videoCodec) + sep(resolution) + sep(frameRate)
                + sep(bitrateType) + sep(bitrate) + sep("a")
                + sep(audioCodec) + sep(audioBitrate) + sep(sampleRate);
    }

    /** 仅视频形态（标准注 15 第二种形态）。 */
    public static String videoOnly(String videoCodec, String resolution, Integer frameRate,
                                   Integer bitrateType, Integer bitrate) {
        return build(videoCodec, resolution, frameRate, bitrateType, bitrate, null, null, null);
    }

    /**
     * 仅音频形态（标准注 15 第三种形态）。
     *
     * <p>标准原文：{@code v///////a/编码格式/码率大小/采样率}——
     * 视频五项全部留空，但保留 7 个 {@code /} 分隔符（8 段中的前 6 段为空，
     * 第 7 段为 {@code a}），分隔符不可省略。
     */
    public static String audioOnly(String audioCodec, Integer audioBitrate, Integer sampleRate) {
        return build(null, null, null, null, null, audioCodec, audioBitrate, sampleRate);
    }

    /** H.264 1080P / 25fps / CBR 2048kbps + G.711 64kbps 8kHz。 */
    public static String h264Pip() {
        return build(String.valueOf(VC_H264), RES_1080P, 25, CBR, 2048, "1", 8, 1);
    }

    /** H.264 720P / 25fps / CBR 1024kbps。 */
    public static String h264Sub() {
        return videoOnly(String.valueOf(VC_H264), RES_720P, 25, CBR, 1024);
    }

    /** H.265 1080P / 25fps / CBR 4096kbps。 */
    public static String h265Pip() {
        return build(String.valueOf(VC_H265), RES_1080P, 25, CBR, 4096, null, null, null);
    }

    /**
     * 解析 {@code f=} 值。缺失项为 {@code null}。
     *
     * <p>标准形态形如 {@code v/2/6/25/1/2048/a/1/8/1}：
     * 索引 0 为 {@code v}，1~5 为视频五参，6 为 {@code a}，7~9 为音频三参。
     *
     * @throws IllegalArgumentException 首段既非 {@code v} 也非 {@code a}
     */
    public static Parsed parse(String f) {
        if (StringUtils.isBlank(f)) {
            throw new IllegalArgumentException("f= 字段不能为空");
        }
        String[] p = f.trim().split("/", -1);
        Parsed r = new Parsed();
        if (p[0].startsWith("v")) {
            r.video = true;
            r.videoCodec = at(p, 1);
            r.resolution = at(p, 2);
            r.frameRate = toInt(at(p, 3));
            r.bitrateType = toInt(at(p, 4));
            r.bitrate = toInt(at(p, 5));
            if (p.length > 6 && "a".equalsIgnoreCase(p[6])) {
                // 标准形态：p[6]="a"，音频自 p[7] 起
                r.audio = true;
                r.audioCodec = at(p, 7);
                r.audioBitrate = toInt(at(p, 8));
                r.sampleRate = toInt(at(p, 9));
            } else {
                // 兼容部分设备省略 "a" 标记的写法
                r.audio = p.length > 6;
                r.audioCodec = at(p, 6);
                r.audioBitrate = toInt(at(p, 7));
                r.sampleRate = toInt(at(p, 8));
            }
        } else if (p[0].startsWith("a")) {
            r.audio = true;
            r.audioCodec = at(p, 1);
            r.audioBitrate = toInt(at(p, 2));
            r.sampleRate = toInt(at(p, 3));
        } else {
            throw new IllegalArgumentException("无法解析 f= 字段，首段应为 v 或 a: " + f);
        }
        return r;
    }

    private static String sep(Object o) {
        return "/" + (o == null ? "" : o);
    }

    private static String at(String[] p, int i) {
        return (i < p.length) ? StringUtils.trimToNull(p[i]) : null;
    }

    private static Integer toInt(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code f=} 解析结果。 */
    @Getter
    public static class Parsed {
        public boolean video;
        public boolean audio;
        public String videoCodec;
        public String resolution;
        public Integer frameRate;
        public Integer bitrateType;
        public Integer bitrate;
        public String audioCodec;
        public Integer audioBitrate;
        public Integer sampleRate;

        @Override
        public String toString() {
            return "Parsed{video=" + video + ", videoCodec=" + videoCodec
                    + ", resolution=" + resolution + ", frameRate=" + frameRate
                    + ", bitrateType=" + bitrateType + ", bitrate=" + bitrate
                    + ", audio=" + audio + ", audioCodec=" + audioCodec
                    + ", audioBitrate=" + audioBitrate + ", sampleRate=" + sampleRate + "}";
        }
    }
}