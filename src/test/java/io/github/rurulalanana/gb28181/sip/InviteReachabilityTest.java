package io.github.rurulalanana.gb28181.sip;

import io.github.rurulalanana.gb28181.sip.event.InviteEvent;
import io.github.rurulalanana.gb28181.sip.event.SipCallback;
import io.github.rurulalanana.gb28181.sip.sdp.SdpMessage;
import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机点播回归：平台以 {@code 0.0.0.0} 通配监听时，发出的 INVITE 不得把
 * 回环地址或通配地址写进 From / Contact —— 设备据此无法回包，会静默丢弃，
 * 表现为「平台发了 INVITE 但设备不响应」。
 *
 * <p>另有 Max-Forwards 回归：jain-sip 建服务端事务时要求该头存在，
 * 缺失会导致 REGISTER 200 OK 等应答被静默丢弃。
 */
public class InviteReachabilityTest {

    private static final String PLATFORM = "34020000002000000001";
    private static final String DEVICE = "34020000001110000009";

    private static int freeUdpPort() {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String header(String msg, String name) {
        for (String line : msg.split("\r\n")) {
            if (line.toLowerCase().startsWith(name.toLowerCase() + ":")) {
                return line.substring(name.length() + 1).trim();
            }
        }
        return "";
    }

    private static String hostOf(String uri) {
        String s = uri.replace("<", "").replace(">", "");
        int at = s.indexOf('@');
        if (at < 0) {
            return "";
        }
        String rest = s.substring(at + 1);
        for (String stop : new String[]{":", ";", ",", " "}) {
            int i = rest.indexOf(stop);
            if (i > 0) {
                rest = rest.substring(0, i);
            }
        }
        return rest;
    }

    /** 通配监听时 INVITE 的 From / Contact 必须是设备可达地址，不能是 0.0.0.0 或 127.0.0.1。 */
    @Test
    public void 通配监听时INVITE不应通告回环地址() throws Exception {
        int sipPort = freeUdpPort();
        int devPort = freeUdpPort();
        BlockingQueue<String> invites = new ArrayBlockingQueue<>(4);

        Gb28181SipServer server = Gb28181SipServer.builder()
                .serverId(PLATFORM)
                .host("0.0.0.0")           // 复现默认配置：通配监听
                .port(sipPort)
                .transport("UDP")
                .callback(new SipCallback() {
                    @Override
                    public void onInvite(InviteEvent event) {
                        // 不处理
                    }
                })
                .build()
                .start();
        try (Gb28181SipClient client = server.client()) {
            // 设备侧裸 socket：注册
            try (DatagramSocket dev = new DatagramSocket(devPort)) {
                dev.setSoTimeout(500);
                byte[] buf = new byte[8192];
                // 注册
                sendRegister(dev, sipPort, devPort, "z9hG4bK-t1", "reach-1");

                // 收平台的 200 OK（注册确认）
                boolean registered = false;
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    dev.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (msg.startsWith("SIP/2.0 200")) {
                        registered = true;
                        break;
                    }
                }
                assertTrue("设备应收到 REGISTER 200 OK（此前因缺 Max-Forwards 被丢弃）", registered);

                // 点播
                SdpMessage offer = new SdpMessage()
                        .origin(PLATFORM, "192.168.20.138")
                        .sessionName(SdpMessage.S_PLAY)
                        .connection("192.168.20.138")
                        .realtime()
                        .mediaPort(30000)
                        .payloadType("96")
                        .rtpmap("96", "PS")
                        .ssrc("0200000001");
                client.invitePlay(DEVICE, offer);

                // 抓 INVITE
                String invite = null;
                deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    dev.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (msg.startsWith("INVITE")) {
                        invite = msg;
                        invites.offer(msg);
                        break;
                    }
                }
                assertNotNull("设备应收到 INVITE，实际:\n" + drain(invites), invite);

                String from = hostOf(header(invite, "From"));
                String contact = hostOf(header(invite, "Contact"));
                System.out.println("[INVITE] From 主机=" + from + "  Contact 主机=" + contact);

                assertTrue("From 不得为通配地址: " + from,
                        !"0.0.0.0".equals(from) && !"::".equals(from));
                assertTrue("From 不得为回环地址（设备无法回包）: " + from,
                        !"127.0.0.1".equals(from));
                assertTrue("Contact 不得为通配地址: " + contact,
                        !"0.0.0.0".equals(contact) && !"::".equals(contact));
                assertTrue("Contact 不得为回环地址（设备无法回包）: " + contact,
                        !"127.0.0.1".equals(contact));
                assertEquals("From 与 Contact 主机应一致", from, contact);
                assertEquals("INVITE 应带 Max-Forwards（RFC 3261）", "70",
                        header(invite, "Max-Forwards").trim());
            }
        } finally {
            server.stop();
        }
    }

    /** 显式 advertisedHost 时必须优先生效（NAT / 多网卡场景）。 */
    @Test
    public void advertisedHost应优先于监听地址() throws Exception {
        int sipPort = freeUdpPort();
        int devPort = freeUdpPort();

        Gb28181SipServer server = Gb28181SipServer.builder()
                .serverId(PLATFORM)
                .host("0.0.0.0")
                .advertisedHost("10.20.30.40")   // 显式通告地址
                .port(sipPort)
                .transport("UDP")
                .callback(new SipCallback() {
                    @Override
                    public void onInvite(InviteEvent event) {
                    }
                })
                .build()
                .start();
        try (Gb28181SipClient client = server.client();
             DatagramSocket dev = new DatagramSocket(devPort)) {
            dev.setSoTimeout(500);
            byte[] buf = new byte[8192];
            sendRegister(dev, sipPort, devPort, "z9hG4bK-t2", "reach-2");

            long deadline = System.currentTimeMillis() + 5000;
            boolean registered = false;
            while (System.currentTimeMillis() < deadline && !registered) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                dev.receive(p);
                registered = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8)
                        .startsWith("SIP/2.0 200");
            }
            assertTrue("设备应收到 REGISTER 200 OK", registered);

            client.invitePlay(DEVICE, new SdpMessage()
                    .origin(PLATFORM, "10.20.30.40")
                    .sessionName(SdpMessage.S_PLAY)
                    .connection("10.20.30.40")
                    .realtime()
                    .mediaPort(30001)
                    .payloadType("96")
                    .rtpmap("96", "PS")
                    .ssrc("0200000002"));

            String invite = null;
            deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                dev.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (msg.startsWith("INVITE")) {
                    invite = msg;
                    break;
                }
            }
            assertNotNull("设备应收到 INVITE", invite);
            assertEquals("advertisedHost 应写入 From", "10.20.30.40",
                    hostOf(header(invite, "From")));
            assertEquals("advertisedHost 应写入 Contact", "10.20.30.40",
                    hostOf(header(invite, "Contact")));
        } finally {
            server.stop();
        }
    }

    private static void sendRegister(DatagramSocket sock, int sipPort, int devPort,
                                     String branch, String callId) throws Exception {
        byte[] payload = ("REGISTER sip:" + PLATFORM + "@127.0.0.1:" + sipPort + " SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP 127.0.0.1:" + devPort + ";branch=" + branch + "\r\n"
                + "From: <sip:" + DEVICE + "@127.0.0.1>;tag=devtag\r\n"
                + "To: <sip:" + DEVICE + "@127.0.0.1>\r\n"
                + "Call-ID: " + callId + "\r\nCSeq: 1 REGISTER\r\n"
                + "Contact: <sip:" + DEVICE + "@127.0.0.1:" + devPort + ">\r\n"
                + "Expires: 3600\r\nContent-Length: 0\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        sock.send(new DatagramPacket(payload, payload.length,
                java.net.InetAddress.getByName("127.0.0.1"), sipPort));
    }

    private static String drain(BlockingQueue<String> q) {
        StringBuilder sb = new StringBuilder();
        q.forEach(m -> sb.append(m).append('\n'));
        return sb.toString();
    }
}
