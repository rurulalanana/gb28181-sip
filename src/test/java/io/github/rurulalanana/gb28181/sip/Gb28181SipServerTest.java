package io.github.rurulalanana.gb28181.sip;

import io.github.rurulalanana.gb28181.sip.cluster.MessageRelay;
import io.github.rurulalanana.gb28181.sip.device.DeviceRegistry;
import io.github.rurulalanana.gb28181.sip.device.DeviceSession;
import io.github.rurulalanana.gb28181.sip.device.LocalDeviceRegistry;
import io.github.rurulalanana.gb28181.sip.event.SipCallback;
import org.junit.Test;

import java.net.DatagramSocket;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 服务启动与注册表测试。
 */
public class Gb28181SipServerTest {

    private static final String SERVER_ID = "34020000002000000001";
    private static final String CAMERA = "34020000001320000001";

    @Test
    public void 应能启动并绑定端口() {
        int port = freePort();
        Gb28181SipServer server = Gb28181SipServer.builder()
                .serverId(SERVER_ID)
                .host("127.0.0.1")
                .port(port)
                .transport("UDP")
                .build()
                .start();
        try {
            assertTrue(server.isRunning());
            // 端口应已被占用
            assertTrue(portInUse(port));
        } finally {
            server.stop();
        }
        assertFalse(server.isRunning());
    }

    @Test
    public void 重复启动应幂等() {
        int port = freePort();
        Gb28181SipServer server = Gb28181SipServer.builder()
                .serverId(SERVER_ID).host("127.0.0.1").port(port).build().start();
        try {
            // 再次 start 不应抛异常
            server.start();
            assertTrue(server.isRunning());
        } finally {
            server.stop();
        }
    }

    @Test
    public void 非法参数应被拒绝() {
        try {
            Gb28181SipServer.builder().serverId(null).build();
            fail("serverId 为空应抛异常");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            Gb28181SipServer.builder().serverId(SERVER_ID).port(0).build();
            fail("端口非法应抛异常");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            Gb28181SipServer.builder().serverId(SERVER_ID).port(70000).build();
            fail("端口越界应抛异常");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void 默认配置应符合标准缺省值() {
        // 标准 9.1.1 d): 注册有效期缺省 86400s，刷新间隔不超过 3600s
        Gb28181SipServer server = Gb28181SipServer.builder()
                .serverId(SERVER_ID).port(freePort()).build();
        assertEquals(SERVER_ID, server.getConfig().getServerId());
        assertEquals("3402000000", server.getConfig().getDomain());
        assertEquals(86400, server.getConfig().getRegisterExpires());
        assertEquals(3600, server.getConfig().getRefreshInterval());
        assertNotNull(server.getRegistry());
        assertNotNull(server.getCallback());
    }

    @Test
    public void 回调可为null应回退为默认实现() {
        SipCallback cb = Gb28181SipServer.builder()
                .serverId(SERVER_ID).port(freePort()).build().getCallback();
        assertNotNull(cb);
        // 默认实现的所有回调应为空操作，不抛异常
        cb.onStarted("127.0.0.1", 5060, "UDP");
        cb.onStopped();
    }

    @Test
    public void 内存注册表应支持增删查() {
        LocalDeviceRegistry registry = new LocalDeviceRegistry();
        DeviceSession s = DeviceSession.builder()
                .deviceId(CAMERA)
                .ip("192.168.1.64")
                .port(5060)
                .transport("UDP")
                .expires(3600)
                .registerTime(System.currentTimeMillis())
                .domain("3402000000")
                .build();

        registry.register(s);
        assertEquals(1, registry.size());
        assertTrue(registry.isOnline(CAMERA));
        DeviceSession got = registry.get(CAMERA);
        assertNotNull(got);
        assertEquals("192.168.1.64", got.getIp());
        assertEquals(5060, got.getPort());
        assertEquals("192.168.1.64:5060", got.getAddress());

        assertTrue(registry.touch(CAMERA));
        assertFalse(registry.touch("34020000001320000099"));

        Collection<String> ids = registry.listByDomain("3402000000");
        assertTrue(ids.contains(CAMERA));

        assertNotNull(registry.unregister(CAMERA));
        assertNull(registry.get(CAMERA));
        assertEquals(0, registry.size());
    }

    @Test
    public void 过期会话应判定为离线() {
        LocalDeviceRegistry registry = new LocalDeviceRegistry();
        registry.register(DeviceSession.builder()
                .deviceId(CAMERA).ip("1.2.3.4").port(5060)
                // 注册时间为 1 小时前，有效期 60s → 已过期
                .expires(60)
                .registerTime(System.currentTimeMillis() - 3600_000L)
                .build());
        assertFalse(registry.isOnline(CAMERA));
        // 但会话记录仍在，可查
        assertNotNull(registry.get(CAMERA));
    }

    @Test
    public void 本地转发器仅支持同节点() {
        MessageRelay relay = new MessageRelay.LocalRelay("node-1");
        assertFalse(relay.clustered());
        assertEquals("node-1", relay.nodeId());
        assertTrue(relay.deliver("node-1", null));
        assertFalse(relay.deliver("node-2", null));
    }

    @Test
    public void 客户端应在未注册设备时报错() {
        Gb28181SipServer server = Gb28181SipServer.builder()
                .serverId(SERVER_ID).host("127.0.0.1").port(freePort()).build().start();
        try {
            Gb28181SipClient client = server.client();
            try {
                client.queryCatalog(CAMERA);
                fail("设备未注册时应抛异常");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains(CAMERA));
            }
        } finally {
            server.stop();
        }
    }

    @Test
    public void SN生成应在1到9999之间循环() {
        int port = freePort();
        Gb28181SipServer server = Gb28181SipServer.builder()
                .serverId(SERVER_ID).host("127.0.0.1").port(port).build().start();
        try {
            Gb28181SipClient client = server.client();
            // 标准 A.2.1.3: SNType 取值 [1, 9999]
            for (int i = 1; i <= 20; i++) {
                int sn = client.nextSn();
                assertTrue("SN 必须在 1~9999: " + sn, sn >= 1 && sn <= 9999);
                assertEquals(i, sn);
            }
        } finally {
            server.stop();
        }
    }

    // ==================== 工具 ====================

    private static int freePort() {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("无法分配空闲端口", e);
        }
    }

    private static boolean portInUse(int port) {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}