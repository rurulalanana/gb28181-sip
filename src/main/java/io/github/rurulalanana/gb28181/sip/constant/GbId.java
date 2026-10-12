package io.github.rurulalanana.gb28181.sip.constant;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

/**
 * GB/T 28181—2022 附录 E 统一编码规则。
 *
 * <p>20 位编码 = 中心编码(8) + 行业编码(2) + 类型编码(3) + <b>网络标识(1)</b> + 序号(6)。
 * 第 14 位是网络标识，最易漏掉，故显式建模。
 *
 * <p>另有 2/4/6/8 位行政区划编码（A.2.1.2 的 pattern：
 * {@code (\d{2}|\d{4}|\d{6}|\d{8}|\d{20})/}）。
 */
public final class GbId {

    /** 完整设备编码长度。 */
    public static final int ID_LENGTH = 20;

    private static final Pattern FULL_ID = Pattern.compile("^\\d{20}$");
    private static final Pattern DIVISION_ID = Pattern.compile("^\\d{2}$|^\\d{4}$|^\\d{6}$|^\\d{8}$");
    private static final Pattern SSRC = Pattern.compile("^\\d{10}$");

    private GbId() {
    }

    // ==================== 校验 ====================

    public static boolean isValidDeviceId(String id) {
        return id != null && FULL_ID.matcher(id.trim()).matches();
    }

    public static boolean isDivisionCode(String id) {
        return id != null && DIVISION_ID.matcher(id.trim()).matches();
    }

    /** {@code tg:deviceIDType}：行政区划码或 20 位设备码。 */
    public static boolean isValidDeviceIdType(String id) {
        return isValidDeviceId(id) || isDivisionCode(id);
    }

    // ==================== 码段 ====================

    /** 中心编码（1~8 位）。 */
    public static String centerCode(String id) {
        return slice(id, 0, 8);
    }

    /** 行业编码（9~10 位）。 */
    public static String industryCode(String id) {
        return slice(id, 8, 10);
    }

    /** 类型编码（11~13 位）。 */
    public static String typeCode(String id) {
        return slice(id, 10, 13);
    }

    /** 网络标识（14 位）。 */
    public static String networkFlag(String id) {
        return slice(id, 13, 14);
    }

    /** 序号（15~20 位）。 */
    public static String serial(String id) {
        return slice(id, 14, 20);
    }

    /** SIP 监控域编码：标准 6.1.2 规定 domain 取 ID 前十位。 */
    public static String domainOf(String deviceId) {
        return require(deviceId).substring(0, 10);
    }

    /**
     * SSRC 域标识：附录 G 规定 {@code y=} 第 2~6 位取 SIP 监控域 ID 的<b>第 4 到 8 位</b>。
     * <p>标准示例：{@code "13010000002000000001"} 中取数字 {@code "10000"} 为域标识。
     *
     * @param deviceId 20 位设备编码
     * @return 5 位域标识
     */
    public static String ssrcDomainOf(String deviceId) {
        return require(deviceId).substring(3, 8);
    }

    // ==================== SSRC ====================

    /**
     * 生成 SSRC（附录 G {@code y=}）：<b>1 位实时/历史 + 5 位域标识 + 4 位域内流标识</b>。
     *
     * <p><b>注意</b>：标准原文为「第 1 位为历史或实时媒体流的标识位，<b>0 为实时，1 为历史</b>」，
     * 即首位取值与直觉相反。
     *
     * @param streamSeq 域内流标识，0~9999 且域内唯一
     * @param realtime  true=实时（首位 0），false=历史（首位 1）
     */
    public static String buildSsrc(String deviceId, int streamSeq, boolean realtime) {
        if (streamSeq < 0 || streamSeq > 9999) {
            throw new IllegalArgumentException("域内媒体流标识必须在 0~9999 之间: " + streamSeq);
        }
        return (realtime ? "0" : "1") + ssrcDomainOf(deviceId) + String.format("%04d", streamSeq);
    }

    public static String ssrcDomain(String ssrc) {
        return normalizeSsrc(ssrc).substring(1, 6);
    }

    public static int ssrcStreamSeq(String ssrc) {
        return Integer.parseInt(normalizeSsrc(ssrc).substring(6, 10));
    }

    /**
     * SSRC 第 1 位。
     *
     * @return {@code true}=实时（首位 0），{@code false}=历史（首位 1）
     */
    public static boolean isRealtimeSsrc(String ssrc) {
        return "0".equals(normalizeSsrc(ssrc).substring(0, 1));
    }

    private static String normalizeSsrc(String ssrc) {
        if (ssrc == null || !SSRC.matcher(ssrc.trim()).matches()) {
            throw new IllegalArgumentException("SSRC 必须是 10 位十进制数字: " + ssrc);
        }
        return ssrc.trim();
    }

    private static String slice(String id, int from, int to) {
        return require(id).substring(from, to);
    }

    private static String require(String id) {
        if (!isValidDeviceId(id)) {
            throw new IllegalArgumentException("非法的 20 位设备编码: " + id);
        }
        return id.trim();
    }

    @Override
    public String toString() {
        return "GbId";
    }
}