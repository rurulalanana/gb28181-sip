package io.github.rurulalanana.gb28181.sip;

import io.github.rurulalanana.gb28181.sip.device.DeviceSession;
import io.github.rurulalanana.gb28181.sip.event.RegisterEvent;
import io.github.rurulalanana.gb28181.sip.event.SipCallback;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpBuilder;
import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 端到端信令测试：真实 UDP 报文往返。
 *
 * <p>模拟一个前端设备向模块发 REGISTER，验证模块能正确应答并登记会话；
 * 再模拟平台侧发 MANSCDP 目录查询，验证能正确解析并回调。
 */
public class SipSignalingTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String PLATFORM_ID = "34020000002000000001";
    private static final String CAMERA_ID = "34020000001320000001";

    @Test
    public void 设备注册应被接受并登记会话() throws Exception {
        int port = freeUdpPort();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RegisterEvent> got = new AtomicReference<>();

        Gb28181SipServer server = Gb28181SipServer.builder()
                .serverId(PLATFORM_ID)
                .host("127.0.0.1")
                .port(port)
                .transport("UDP")
                .callback(new SipCallback() {
                    @Override
                    public void onRegister(RegisterEvent event) {
                        got.set(event);
                        latch.countDown();
                    }
                })
                .build()
                .start();

        try (DatagramSocket device = new DatagramSocket()) {
            device.setSoTimeout(5000);
            String register = "REGISTER sip:" + PLATFORM_ID + "@3402000000 SIP/2.0\r\n"
                    + "Via: SIP/2.0/UDP 127.0.0.1:" + device.getLocalPort()
                    + ";branch=z9hG4bK-" + System.nanoTime() + "\r\n"
                    + "From: <sip:" + CAMERA_ID + "@3402000000>;tag=1928301774\r\n"
                    + "To: <sip:" + CAMERA_ID + "@3402000000>\r\n"
                    + "Call-ID: " + System.nanoTime() + "@127.0.0.1\r\n"
                    + "CSeq: 1 REGISTER\r\n"
                    + "Contact: <sip:" + CAMERA_ID + "@127.0.0.1:"
                    + device.getLocalPort() + ">\r\n"
                    + "Expires: 3600\r\n"
                    + "Max-Forwards: 70\r\n"
                    + "Content-Length: 0\r\n"
                    + "\r\n";

            device.send(new DatagramPacket(register.getBytes(UTF8),
                    register.length(), InetAddress.getByName("127.0.0.1"), port));

            byte[] buf = new byte[8192];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            device.receive(resp);

            String response = new String(resp.getData(), 0, resp.getLength(), UTF8);
            assertTrue("应返回 200 OK，实际: " + firstLine(response),
                    response.startsWith("SIP/2.0 200"));

            assertTrue("回调应在 5 秒内触发", latch.await(5, TimeUnit.SECONDS));
            RegisterEvent event = got.get();
            assertNotNull(event);
            assertEquals(CAMERA_ID, event.getDeviceId());
            assertEquals(3600, event.getExpires());

            // 会话应已登记
            DeviceSession session = server.getRegistry().get(CAMERA_ID);
            assertNotNull("设备会话应已登记", session);
            assertTrue("设备应判定在线", server.getRegistry().isOnline(CAMERA_ID));
        } finally {
            server.stop();
        }
    }

    @Test
    public void 目录查询报文应被解析并回调() throws Exception {
        int port = freeUdpPort();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> cmdType = new AtomicReference<>();
        AtomicReference<String> xml = new AtomicReference<>();

        Gb28181SipServer server = Gb28181SipServer.builder()
                .serverId(PLATFORM_ID)
                .host("127.0.0.1")
                .port(port)
                .transport("UDP")
                .callback(new SipCallback() {
                    @Override
                    public Boolean onManscdpRequest(io.github.rurulalanana.gb28181.sip.event.ManscdpEvent event) {
                        cmdType.set(event.getCmdType());
                        xml.set(event.getXml());
                        latch.countDown();
                        return null;
                    }
                })
                .build()
                .start();

        try (DatagramSocket peer = new DatagramSocket()) {
            peer.setSoTimeout(5000);
            String body = ManscdpBuilder.catalogQuery(1, CAMERA_ID);
            String message = "MESSAGE sip:" + CAMERA_ID + "@3402000000 SIP/2.0\r\n"
                    + "Via: SIP/2.0/UDP 127.0.0.1:" + peer.getLocalPort()
                    + ";branch=z9hG4bK-" + System.nanoTime() + "\r\n"
                    + "From: <sip:" + PLATFORM_ID + "@3402000000>;tag=111\r\n"
                    + "To: <sip:" + CAMERA_ID + "@3402000000>\r\n"
                    + "Call-ID: cat-" + System.nanoTime() + "@127.0.0.1\r\n"
                    + "CSeq: 2 MESSAGE\r\n"
                    + "Max-Forwards: 70\r\n"
                    + "Content-Type: Application/MANSCDP+xml\r\n"
                    + "Content-Length: " + body.getBytes(UTF8).length + "\r\n"
                    + "\r\n" + body;

            peer.send(new DatagramPacket(message.getBytes(UTF8),
                    message.length(), InetAddress.getByName("127.0.0.1"), port));

            byte[] buf = new byte[8192];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            peer.receive(resp);
            String response = new String(resp.getData(), 0, resp.getLength(), UTF8);
            // 标准 9.5.3.1 c: 收到 MESSAGE 立即返回 200 OK 且应答无消息体
            assertTrue("应立即返回 200 OK，实际: " + firstLine(response),
                    response.startsWith("SIP/2.0 200"));

            assertTrue("目录查询回调应触发", latch.await(5, TimeUnit.SECONDS));
            assertEquals("Catalog", cmdType.get());
            assertTrue("回调应携带原始 XML", xml.get().contains("<CmdType>Catalog</CmdType>"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void 心跳保活应被识别且不触发普通MANSCDP回调() throws Exception {
        int port = freeUdpPort();
        CountDownLatch keepalive = new CountDownLatch(1);
        CountDownLatch other = new CountDownLatch(1);

        Gb28181SipServer server = Gb28181SipServer.builder()
                .serverId(PLATFORM_ID)
                .host("127.0.0.1")
                .port(port)
                .transport("UDP")
                .callback(new SipCallback() {
                    @Override
                    public void onKeepAlive(io.github.rurulalanana.gb28181.sip.event.KeepAliveEvent event) {
                        keepalive.countDown();
                    }

                    @Override
                    public Boolean onManscdpRequest(io.github.rurulalanana.gb28181.sip.event.ManscdpEvent event) {
                        other.countDown();
                        return null;
                    }
                })
                .build()
                .start();

        try (DatagramSocket peer = new DatagramSocket()) {
            peer.setSoTimeout(3000);
            String body = ManscdpBuilder.keepalive(1, CAMERA_ID);
            String message = "MESSAGE sip:" + CAMERA_ID + "@3402000000 SIP/2.0\r\n"
                    + "Via: SIP/2.0/UDP 127.0.0.1:" + peer.getLocalPort()
                    + ";branch=z9hG4bK-" + System.nanoTime() + "\r\n"
                    + "From: <sip:" + CAMERA_ID + "@3402000000>;tag=222\r\n"
                    + "To: <sip:" + CAMERA_ID + "@3402000000>\r\n"
                    + "Call-ID: ka-" + System.nanoTime() + "@127.0.0.1\r\n"
                    + "CSeq: 1 MESSAGE\r\n"
                    + "Max-Forwards: 70\r\n"
                    + "Content-Type: Application/MANSCDP+xml\r\n"
                    + "Content-Length: " + body.getBytes(UTF8).length + "\r\n"
                    + "\r\n" + body;

            peer.send(new DatagramPacket(message.getBytes(UTF8),
                    message.length(), InetAddress.getByName("127.0.0.1"), port));

            byte[] buf = new byte[8192];
            peer.receive(new DatagramPacket(buf, buf.length));

            assertTrue("心跳回调应触发", keepalive.await(5, TimeUnit.SECONDS));
            // 心跳有专门回调，不应再走通用 MANSCDP 回调
            assertTrue("心跳不应触发通用 MANSCDP 回调", !other.await(1, TimeUnit.SECONDS));
        } finally {
            server.stop();
        }
    }

    // ==================== 工具 ====================

    private static int freeUdpPort() {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String firstLine(String s) {
        int i = s.indexOf("\r\n");
        return i < 0 ? s : s.substring(0, i);
    }
}