package io.github.rurulalanana.gb28181.sip;

import io.github.rurulalanana.gb28181.sip.constant.CmdType;
import io.github.rurulalanana.gb28181.sip.constant.GbId;
import io.github.rurulalanana.gb28181.sip.constant.ManscdpTypes;
import io.github.rurulalanana.gb28181.sip.constant.PtzCommand;
import io.github.rurulalanana.gb28181.sip.event.InviteEvent;
import io.github.rurulalanana.gb28181.sip.event.KeepAliveEvent;
import io.github.rurulalanana.gb28181.sip.event.ManscdpEvent;
import io.github.rurulalanana.gb28181.sip.event.RegisterEvent;
import io.github.rurulalanana.gb28181.sip.event.SipCallback;
import io.github.rurulalanana.gb28181.sip.event.SubscribeEvent;
import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpBuilder;
import io.github.rurulalanana.gb28181.sip.sdp.SdpMediaFormat;
import io.github.rurulalanana.gb28181.sip.sdp.SdpMessage;
import io.github.rurulalanana.gb28181.sip.security.Credential;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * README 示例的编译与行为验证。
 *
 * <p>文档里的代码若编译不过就没有价值，故以测试形式固化。
 */
public class ReadmeExampleTest {

    private static final String PLATFORM_ID = "34020000002000000001";
    private static final String CAMERA_ID = "34020000001320000001";

    /** README 第 2 节：最小用法。 */
    @Test
    public void 最小用法示例可运行() {
        Gb28181SipServer server = Gb28181SipServer.builder()
                .serverId(PLATFORM_ID)
                .host("127.0.0.1")
                .port(freePort())
                .transport("UDP")
                .build()
                .start();
        try {
            Gb28181SipClient client = server.client();
            assertNotNull(client);
            assertTrue(server.isRunning());
        } finally {
            server.stop();
        }
    }

    /** README 第 3 节：完整回调示例。 */
    @Test
    public void 完整回调示例可编译并工作() {
        final Gb28181SipServer[] holder = new Gb28181SipServer[1];
        final int[] port = {freePort()};

        Gb28181SipServer server = Gb28181SipServer.builder()
                .serverId(PLATFORM_ID)
                .port(port[0])
                .callback(new SipCallback() {
                    @Override
                    public void onRegister(RegisterEvent e) {
                        if (e.isUnregistered()) {
                            return;
                        }
                        assertEquals(CAMERA_ID, e.getDeviceId());
                    }

                    @Override
                    public void onKeepAlive(KeepAliveEvent e) {
                        // 模块已自动刷新在线时间戳
                    }

                    @Override
                    public Boolean onManscdpRequest(ManscdpEvent e) {
                        if (e.cmdTypeEnum() == CmdType.CATALOG) {
                            int sn = e.getSn();
                            String resp = ManscdpBuilder.catalogResponse(sn, e.getDeviceId(),
                                    myChannels());
                            holder[0].client().sendMessage(e.getDeviceId(), resp);
                        }
                        return null;
                    }

                    @Override
                    public void onInvite(InviteEvent e) {
                        if (e.isPlay()) {
                            String ssrc = GbId.buildSsrc(e.getPeer().getDeviceId(), 1, true);
                            SdpMessage sdp = new SdpMessage()
                                    .origin(PLATFORM_ID, "127.0.0.1")
                                    .connection("127.0.0.1")
                                    .mediaConnection("127.0.0.1")
                                    .ssrc(ssrc)
                                    .mediaFormat(SdpMediaFormat.h264Pip())
                                    .sessionName(SdpMessage.S_PLAY)
                                    .realtime()
                                    .recvonly()
                                    .mediaPort(10000);
                            holder[0].client().respondInvite(e.getRawRequestEvent(), sdp);
                        }
                    }

                    @Override
                    public int onSubscribe(SubscribeEvent e) {
                        return e.getEventName() != null && "Catalog".equals(e.getEventName())
                                ? 3600 : 0;
                    }
                })
                .build()
                .start();
        holder[0] = server;
        try {
            assertTrue(server.isRunning());
        } finally {
            server.stop();
        }
    }

    /** README 第 6 节：三种认证方式均可编译。 */
    @Test
    public void 认证配置示例可编译() {
        assertNotNull(Gb28181SipServer.builder()
                .credential(Credential.of(CAMERA_ID, "123456")));
        assertNotNull(Gb28181SipServer.builder()
                .credentials(Collections.singletonMap(CAMERA_ID, "123456")));
        assertNotNull(Gb28181SipServer.builder()
                .credentialProvider(username -> "123456"));
    }

    /** README 第 7 节：协议要点示例输出正确。 */
    @Test
    public void 协议要点示例输出正确() {
        // SSRC：0 为实时、1 为历史
        assertEquals("0200000001", GbId.buildSsrc(PLATFORM_ID, 1, true));
        assertEquals("1200000002", GbId.buildSsrc(PLATFORM_ID, 2, false));
        // 域标识取第 4~8 位
        assertEquals("10000", GbId.ssrcDomainOf("13010000002000000001"));

        // f= 三种形态
        assertEquals("v/2/6/25/1/2048/a/1/8/1",
                SdpMediaFormat.build("2", "6", 25, 1, 2048, "1", 8, 1));
        assertEquals("v/2/5/25/1/1024/a///",
                SdpMediaFormat.videoOnly("2", "5", 25, 1, 1024));
        assertEquals("v//////a/1/8/1", SdpMediaFormat.audioOnly("1", 8, 1));

        // 20 位编码拆分
        assertEquals("34020000", GbId.centerCode(PLATFORM_ID));
        assertEquals("00", GbId.industryCode(PLATFORM_ID));
        assertEquals("200", GbId.typeCode(PLATFORM_ID));
        assertEquals("0", GbId.networkFlag(PLATFORM_ID));
        assertEquals("000001", GbId.serial(PLATFORM_ID));

        // PTZ 8 字节控制码
        String ptz = PtzCommand.builder()
                .address(1).up().left()
                .horizSpeed(6).vertSpeed(6)
                .build().encode();
        assertEquals(16, ptz.length());
        assertTrue(ptz.startsWith("A5"));

        // 标准缺省值
        assertEquals(86400, ManscdpTypes.DEFAULT_REGISTER_EXPIRES);
        assertEquals(3600, ManscdpTypes.DEFAULT_REFRESH_INTERVAL);
    }

    /** README 中 onSubscribe 的取值逻辑。 */
    @Test
    public void 订阅有效期判断正确() {
        assertEquals(3600, subscribeResult("Catalog"));
        assertEquals(0, subscribeResult(null));
        assertEquals(0, subscribeResult("Alarm"));
    }

    private static int subscribeResult(String eventName) {
        return eventName != null && "Catalog".equals(eventName) ? 3600 : 0;
    }

    private static List<CatalogItem> myChannels() {
        return Collections.singletonList(CatalogItem.builder()
                .deviceId(CAMERA_ID)
                .name("前端摄像机01")
                .manufacturer("示例厂商")
                .model("MODEL-X")
                .civilCode("340200")
                .address("示例地址")
                .parental(0)
                .parentId(PLATFORM_ID)
                .registerWay(1)
                .secrecy(0)
                .status("ON")
                .longitude(113.123456)
                .latitude(23.123456)
                .ptzType(1)
                .grassrootsCode("000000")
                .build());
    }

    private static int freePort() {
        try (java.net.DatagramSocket s = new java.net.DatagramSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}