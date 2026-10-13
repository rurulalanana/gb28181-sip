package io.github.rurulalanana.gb28181.sip;

import io.github.rurulalanana.gb28181.sip.event.InviteEvent;
import io.github.rurulalanana.gb28181.sip.event.SipCallback;
import io.github.rurulalanana.gb28181.sip.sdp.SdpMessage;
import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 回归测试：设备发起 INVITE 后，{@code client().respondInvite()} 的 200 OK
 * 必须真正送达设备。
 *
 * <p>背景：jain-sip 1.2.344 不再自动创建 ServerTransaction，
 * {@code RequestEvent.getServerTransaction()} 恒为 {@code null}，
 * 1.0.0 的 respondInvite 直接使用该方法导致应答被静默丢弃。
 */
public class InviteResponseTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String PLATFORM_ID = "34020000002000000001";
    private static final String CAMERA_ID = "34020000001320000001";

    @Test
    public void respondInvite应送达200OK() throws Exception {
        int port = freeUdpPort();
        CountDownLatch inviteLatch = new CountDownLatch(1);

        Gb28181SipServer server = Gb28181SipServer.builder()
                .serverId(PLATFORM_ID)
                .host("127.0.0.1")
                .port(port)
                .transport("UDP")
                .callback(new SipCallback() {
                    @Override
                    public void onInvite(InviteEvent event) {
                        // 标准实时点播应答：携带收流 SDP
                        SdpMessage sdp = new SdpMessage()
                                .origin(PLATFORM_ID, "127.0.0.1")
                                .sessionName(SdpMessage.S_PLAY)
                                .connection("127.0.0.1")
                                .realtime()
                                .mediaPort(6000)
                                .payloadType("96")
                                .rtpmap("96", "PS")
                                .ssrc("0100000001");
                        server0().client().respondInvite(event.getRawRequestEvent(), sdp);
                        inviteLatch.countDown();
                    }
                })
                .build();
        SERVER_HOLDER[0] = server;
        server.start();

        try (DatagramSocket device = new DatagramSocket()) {
            device.setSoTimeout(5000);
            String callId = System.nanoTime() + "@127.0.0.1";
            String sdpOffer = "v=0\r\n"
                    + "o=" + CAMERA_ID + " 0 0 IN IP4 127.0.0.1\r\n"
                    + "s=Play\r\n"
                    + "c=IN IP4 127.0.0.1\r\n"
                    + "t=0 0\r\n"
                    + "m=video 40000 RTP/AVP 96\r\n"
                    + "a=rtpmap:96 PS/90000\r\n"
                    + "y=1234567890\r\n";
            String invite = "INVITE sip:" + PLATFORM_ID + "@3402000000 SIP/2.0\r\n"
                    + "Via: SIP/2.0/UDP 127.0.0.1:" + device.getLocalPort()
                    + ";branch=z9hG4bK-" + System.nanoTime() + "\r\n"
                    + "From: <sip:" + CAMERA_ID + "@3402000000>;tag=1928301774\r\n"
                    + "To: <sip:" + PLATFORM_ID + "@3402000000>\r\n"
                    + "Call-ID: " + callId + "\r\n"
                    + "CSeq: 2 INVITE\r\n"
                    + "Contact: <sip:" + CAMERA_ID + "@127.0.0.1:" + device.getLocalPort() + ">\r\n"
                    + "Subject: " + CAMERA_ID + ":" + PLATFORM_ID + ",0000000001:0000000001\r\n"
                    + "Content-Type: application/sdp\r\n"
                    + "Max-Forwards: 70\r\n"
                    + "Content-Length: " + sdpOffer.getBytes(UTF8).length + "\r\n\r\n"
                    + sdpOffer;

            byte[] data = invite.getBytes(UTF8);
            device.send(new DatagramPacket(data, data.length,
                    InetAddress.getByName("127.0.0.1"), port));

            assertTrue("onInvite 回调应在 5 秒内触发", inviteLatch.await(5, TimeUnit.SECONDS));

            // 跳过 100 Trying，取最终响应
            byte[] buf = new byte[8192];
            String response = null;
            for (int i = 0; i < 6; i++) {
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                device.receive(resp);
                String text = new String(resp.getData(), 0, resp.getLength(), UTF8);
                if (!text.startsWith("SIP/2.0 100") && !text.startsWith("SIP/2.0 18")) {
                    response = text;
                    break;
                }
            }
            assertNotNull("应收到 INVITE 的最终响应（1.0.0 此处静默丢弃）", response);
            assertTrue("应回 200 OK，实际: " + firstLine(response),
                    response.startsWith("SIP/2.0 200"));
            assertTrue("应答应携带 SDP: " + response, response.contains("m=video 6000 RTP/AVP 96"));
            assertTrue("应答应携带平台 SSRC: " + response, response.contains("y=0100000001"));
        } finally {
            server.stop();
        }
    }

    private static String firstLine(String msg) {
        int idx = msg.indexOf("\r\n");
        return idx > 0 ? msg.substring(0, idx) : msg;
    }

    private static int freeUdpPort() throws Exception {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** 匿名内部类中引用外部的 server 需借助数组（Java 8  effectively final 限制）。 */
    private Gb28181SipServer server0() {
        return SERVER_HOLDER[0];
    }

    private static final Gb28181SipServer[] SERVER_HOLDER = new Gb28181SipServer[1];
}
