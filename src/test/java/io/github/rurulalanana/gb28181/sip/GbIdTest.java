package io.github.rurulalanana.gb28181.sip;

import io.github.rurulalanana.gb28181.sip.constant.GbId;
import io.github.rurulalanana.gb28181.sip.constant.ManscdpTypes;
import io.github.rurulalanana.gb28181.sip.constant.PtzCommand;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 统一编码规则（附录 E）与 SSRC（附录 G）测试。
 */
public class GbIdTest {

    private static final String CAMERA = "34020000001320000001";
    private static final String PLATFORM = "34020000002000000001";

    @Test
    public void 设备编码校验() {
        assertTrue(GbId.isValidDeviceId(CAMERA));
        assertFalse(GbId.isValidDeviceId("3402000000132000000"));   // 19 位
        assertFalse(GbId.isValidDeviceId("340200000013200000012")); // 21 位
        assertFalse(GbId.isValidDeviceId("3402000000132000000a"));  // 含字母
        assertFalse(GbId.isValidDeviceId(null));
    }

    @Test
    public void 行政区划码校验() {
        // A.2.1.2 的 pattern: (\d{2}|\d{4}|\d{6}|\d{8}|\d{20})
        assertTrue(GbId.isDivisionCode("34"));
        assertTrue(GbId.isDivisionCode("3402"));
        assertTrue(GbId.isDivisionCode("340200"));
        assertFalse(GbId.isDivisionCode("34020"));      // 5 位非法
        assertFalse(GbId.isDivisionCode("34020000001")); // 11 位非法
        assertTrue(GbId.isValidDeviceIdType("340200"));
    }

    @Test
    public void 码段拆分应符合附录E() {
        // 附录 E.1: 中心编码(8) + 行业编码(2) + 类型编码(3) + 网络标识(1) + 序号(6) = 20
        assertEquals("34020000", GbId.centerCode(PLATFORM));   // 1~8 位
        assertEquals("00", GbId.industryCode(PLATFORM));       // 9~10 位
        assertEquals("200", GbId.typeCode(PLATFORM));          // 11~13 位（200=中心信令控制服务器）
        assertEquals("0", GbId.networkFlag(PLATFORM));         // 第 14 位网络标识
        assertEquals("000001", GbId.serial(PLATFORM));         // 15~20 位
        assertEquals(20, CAMERA.length());
    }

    @Test
    public void 监控域取前十位() {
        // 标准 6.1.2: domain 宜采用 ID 统一编码的前十位编码
        assertEquals("3402000000", GbId.domainOf(PLATFORM));
        assertEquals("3402000000", GbId.domainOf(CAMERA));
    }

    @Test
    public void SSRC域标识取第4到8位() {
        // 附录 G 标准示例: "13010000002000000001" 中取数字 "10000" 为域标识
        assertEquals("10000", GbId.ssrcDomainOf("13010000002000000001"));
        // 34020000002000000001 → 第 4~8 位 = 20000
        assertEquals("20000", GbId.ssrcDomainOf(PLATFORM));
    }

    @Test
    public void 实时SSRC首位为0() {
        // 附录 G 原文: "第 1 位为历史或实时媒体流的标识位, 0 为实时, 1 为历史"
        // 结构: 1 位实时/历史 + 5 位域标识 + 4 位域内流标识
        String ssrc = GbId.buildSsrc(PLATFORM, 1, true);
        assertEquals(10, ssrc.length());
        assertEquals("0200000001", ssrc);      // 0 + 20000 + 0001
        assertTrue(GbId.isRealtimeSsrc(ssrc));
        assertEquals("20000", GbId.ssrcDomain(ssrc));
        assertEquals(1, GbId.ssrcStreamSeq(ssrc));
    }

    @Test
    public void 历史SSRC首位为1() {
        // 标准规定历史为 1（与直觉相反，务必注意）
        String ssrc = GbId.buildSsrc(PLATFORM, 2, false);
        assertEquals("1200000002", ssrc);      // 1 + 20000 + 0002
        assertFalse(GbId.isRealtimeSsrc(ssrc));
        assertEquals(2, GbId.ssrcStreamSeq(ssrc));
    }

    @Test
    public void 非法SSRC应抛异常() {
        try {
            GbId.buildSsrc(PLATFORM, 10000, true);
            fail("域内流标识超过 9999 应抛异常");
        } catch (IllegalArgumentException expected) {
            // 符合标准约束
        }
        try {
            GbId.ssrcDomain("123");   // 非 10 位
            fail("非 10 位 SSRC 应抛异常");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void SN取值范围应为1到9999() {
        // 标准 A.2.1.3: minInclusive = 1
        assertEquals(1, ManscdpTypes.SN_MIN);
        assertEquals(9999, ManscdpTypes.SN_MAX);
    }

    @Test
    public void PTZ控制码按表A3为8字节且首字节A5() {
        // 表 A.3 定义 8 个字节 → 16 个十六进制字符
        String ptz = PtzCommand.builder().address(1).left().build().encode();
        assertEquals(16, ptz.length());
        assertTrue(ptz.startsWith("A5"));
        assertNotNull(ptz);
        // XSD 约束 length=8 时可截取低 4 字节
        assertEquals(8, PtzCommand.builder().address(1).left().build().encodeHex4().length());
    }

    @Test
    public void PTZ字节2校验位应符合A3() {
        // 标准 A.3: 校验位 = (字节1高4位 + 字节1低4位 + 字节2高4位) % 16
        String ptz = PtzCommand.builder().address(1).left().build().encode();
        int b1 = Integer.parseInt(ptz.substring(0, 2), 16);
        int b2 = Integer.parseInt(ptz.substring(2, 4), 16);
        int expected = (((b1 >> 4) + (b1 & 0x0F) + (b2 >> 4)) % 16);
        assertEquals(expected, b2 & 0x0F);
    }

    @Test
    public void PTZ校验码应为前七字节和() {
        // 附录 A.3: 字节8 = (字节1+...+字节7) % 256
        String ptz = PtzCommand.builder().address(1).up().horizSpeed(6).vertSpeed(6).build().encode();
        int sum = 0;
        for (int i = 0; i < 7; i++) {
            sum += Integer.parseInt(ptz.substring(i * 2, i * 2 + 2), 16);
        }
        int check = Integer.parseInt(ptz.substring(14, 16), 16);
        assertEquals(sum % 256, check);
    }

    @Test
    public void PTZ互斥指令应被拒绝() {
        // 标准: Bit5/Bit4、Bit3/Bit2、Bit1/Bit0 不应同时为 1
        try {
            PtzCommand.builder().address(1).left().right().build().encode();
            fail("左/右互斥约束未生效");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}