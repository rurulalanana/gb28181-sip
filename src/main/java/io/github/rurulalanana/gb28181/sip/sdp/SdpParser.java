package io.github.rurulalanana.gb28181.sip.sdp;

import java.util.HashMap;
import java.util.Map;

/**
 * SDP 文本解析器（附录 G）。
 *
 * <p>容错设计：真实设备的 SDP 并不严格遵循 RFC 4566，常见偏离包括
 * <ul>
 *   <li>缺少 {@code v=} / {@code o=} 行</li>
 *   <li>{@code y=} / {@code f=} 出现在 {@code m=} 之前或之后</li>
 *   <li>行尾使用 LF 而非 CRLF</li>
 *   <li>多行同名属性</li>
 * </ul>
 * 因此本解析器不抛异常：能识别多少就填充多少，缺失字段保持 {@code null}。
 */
public final class SdpParser {

    private SdpParser() {
    }

    /**
     * 解析 SDP 文本。
     *
     * @param text SDP 文本，可为 {@code null} 或空串
     * @return 解析结果；{@code null}/空输入返回 {@code null}
     */
    public static SdpMessage parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        SdpMessage sdp = new SdpMessage();
        // 保留同名多值属性（如多个 a=）
        Map<String, Integer> mIndex = new HashMap<String, Integer>();
        boolean inMedia = false;

        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() < 2 || line.charAt(1) != '=') {
                continue;
            }
            char type = line.charAt(0);
            String value = line.substring(2).trim();

            switch (type) {
                case 'v':
                    sdp.setVersion(value);
                    break;
                case 'o':
                    sdp.setOrigin(value);
                    break;
                case 's':
                    sdp.setSessionName(value);
                    break;
                case 'u':
                    sdp.setUri(value);
                    break;
                case 'c':
                    if (inMedia) {
                        sdp.setMediaConnection(value);
                    } else {
                        sdp.setConnection(value);
                    }
                    break;
                case 't':
                    sdp.setTime(value);
                    break;
                case 'b':
                    sdp.setBandwidth(value);
                    break;
                case 'y':
                    sdp.setSsrc(value);
                    break;
                case 'f':
                    sdp.setMediaFormat(value);
                    break;
                case 'm':
                    inMedia = true;
                    applyMediaLine(sdp, value);
                    break;
                case 'a':
                    applyAttribute(sdp, value, mIndex);
                    break;
                default:
                    break;
            }
        }
        return sdp;
    }

    private static void applyMediaLine(SdpMessage sdp, String value) {
        // m=<media> <port> <proto> <fmt> ...
        String[] p = value.split("\\s+");
        if (p.length > 0) {
            sdp.setMediaName(p[0]);
        }
        if (p.length > 1) {
            try {
                // 兼容 m=video 6000/2 RTP/AVP 96 的端口/通道写法
                String portPart = p[1];
                int slash = portPart.indexOf('/');
                if (slash > 0) {
                    portPart = portPart.substring(0, slash);
                }
                sdp.setMediaPort(Integer.parseInt(portPart));
            } catch (NumberFormatException ignore) {
                // 保持 0
            }
        }
        if (p.length > 2) {
            sdp.setMediaProto(p[2]);
        }
        if (p.length > 3) {
            sdp.setPayloadType(p[3]);
        }
    }

    private static void applyAttribute(SdpMessage sdp, String value, Map<String, Integer> mIndex) {
        // 仅取第一个 setup：单流场景足够；多流场景由调用方自行处理
        if (value.startsWith("setup:")) {
            if (mIndex.get("setup") == null) {
                sdp.setSetup(value.substring("setup:".length()));
                mIndex.put("setup", 1);
            }
            return;
        }
        sdp.attr(value);
    }
}