package io.github.rurulalanana.gb28181.sip;

import io.github.rurulalanana.gb28181.sip.cascade.CascadeEvents;
import io.github.rurulalanana.gb28181.sip.cascade.CascadeManager;
import io.github.rurulalanana.gb28181.sip.cascade.CascadePlatform;
import io.github.rurulalanana.gb28181.sip.cascade.LocalCascadeRegistry;
import io.github.rurulalanana.gb28181.sip.event.SipCallback;
import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import org.junit.Test;

import java.net.DatagramSocket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 国标级联测试（仅信令层）。
 */
public class CascadeTest {

    private static final String SELF_ID = "34020000002000000001";
    private static final String UPPER_ID = "34020000003000000001";
    private static final String LOWER1 = "34020000001100000001";
    private static final String LOWER2 = "34020000001100000002";
    private static final String LOWER3 = "34020000001100000003";

    // ==================== 下级平台生命周期 ====================

    @Test
    public void 下级平台注册应进入级联注册表() {
        Gb28181SipServer server = start();
        try {
            server.getCascade().onPlatformRegister(LOWER1, "127.0.0.1:5060", 3600);
            assertNotNull(server.getCascade().getRegistry().get(LOWER1));
            assertEquals("127.0.0.1", server.getCascade().getRegistry().get(LOWER1).getIp());
            assertEquals(5060, server.getCascade().getRegistry().get(LOWER1).getPort());
            assertEquals("3402000000", server.getCascade().getRegistry().get(LOWER1).getDomain());
            assertTrue(server.getCascade().getRegistry().isOnline(LOWER1, 180));
            assertEquals(1, server.getCascade().getRegistry().onlineCount(180));
        } finally {
            server.stop();
        }
    }

    @Test
    public void 非法平台编码应被拒绝() {
        Gb28181SipServer server = start();
        try {
            server.getCascade().onPlatformRegister("3402000000", "127.0.0.1:5060", 3600);
            assertNull(server.getCascade().getRegistry().get("3402000000"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void 续注册应被标记为refresh() {
        AtomicReference<CascadeEvents.PlatformRegistered> got = new AtomicReference<>();
        Gb28181SipServer server = startWithCallback(new SipCallback() {
            @Override
            public void onCascadePlatformRegister(CascadeEvents.PlatformRegistered e) {
                got.set(e);
            }
        });
        try {
            server.getCascade().onPlatformRegister(LOWER1, "127.0.0.1:5060", 3600);
            assertFalse(got.get().isRefresh());
            server.getCascade().onPlatformRegister(LOWER1, "127.0.0.1:5060", 3600);
            assertTrue(got.get().isRefresh());
        } finally {
            server.stop();
        }
    }

    @Test
    public void 注销应触发回调并移除() {
        AtomicReference<CascadeEvents.PlatformUnregistered> got = new AtomicReference<>();
        Gb28181SipServer server = startWithCallback(new SipCallback() {
            @Override
            public void onCascadePlatformUnregister(CascadeEvents.PlatformUnregistered e) {
                got.set(e);
            }
        });
        try {
            server.getCascade().onPlatformRegister(LOWER1, "127.0.0.1:5060", 3600);
            server.getCascade().onPlatformUnregister(LOWER1, "logout");
            assertNotNull(got.get());
            assertEquals("logout", got.get().getReason());
            assertNull(server.getCascade().getRegistry().get(LOWER1));
        } finally {
            server.stop();
        }
    }

    @Test
    public void 心跳应刷新时间戳() {
        Gb28181SipServer server = start();
        try {
            server.getCascade().onPlatformRegister(LOWER1, "127.0.0.1:5060", 3600);
            assertTrue(server.getCascade().onPlatformKeepAlive(LOWER1));
            assertFalse(server.getCascade().onPlatformKeepAlive("34020000001100000099"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void 超时平台应被清理() {
        Gb28181SipServer server = start();
        try {
            // 注册有效期 1 秒 + 心跳超时 0 → 立即判定超时
            server.getCascade().getRegistry().register(CascadePlatform.builder()
                    .platformId(LOWER1).ip("10.0.0.1").port(5060).transport("UDP")
                    .expires(1).registerTime(System.currentTimeMillis() - 5000)
                    .lastKeepAlive(System.currentTimeMillis() - 5000)
                    .domain("3402000000").build());
            assertEquals(1, server.getCascade().sweepTimeout());
            assertNull(server.getCascade().getRegistry().get(LOWER1));
        } finally {
            server.stop();
        }
    }

    // ==================== 级联目录聚合 ====================

    @Test
    public void 无在线下级时应回空目录() {
        AtomicReference<CascadeEvents.CatalogMerged> got = new AtomicReference<>();
        Gb28181SipServer server = startWithCallback(new SipCallback() {
            @Override
            public void onCascadeCatalog(CascadeEvents.CatalogMerged e) {
                got.set(e);
            }
        });
        try {
            CascadeEvents.CatalogMerged m =
                    server.getCascade().queryCascadedCatalog(UPPER_ID, 100, 1000);
            assertEquals(100, m.getSn());
            assertEquals(0, m.getTotalCount());
            assertNotNull(m.getResponseXml());
            assertTrue(m.getResponseXml().contains("<SumNum>0</SumNum>"));
            assertNotNull(got.get());
        } finally {
            server.stop();
        }
    }

    @Test
    public void 单下级应答应被原样聚合() {
        Gb28181SipServer server = start();
        try {
            server.getCascade().onPlatformRegister(LOWER1, "127.0.0.1:5060", 3600);
            List<CatalogItem> items = Arrays.asList(
                    item("34020000001320000001", "cam1"),
                    item("34020000001320000002", "cam2"));
            CascadeEvents.CatalogMerged m = runQuery(server, items);
            assertEquals(2, m.getTotalCount());
            assertTrue(m.getItems().stream().anyMatch(i -> "cam1".equals(i.getName())));
        } finally {
            server.stop();
        }
    }

    @Test
    public void 多下级设备编码重复应去重() {
        Gb28181SipServer server = start();
        try {
            server.getCascade().onPlatformRegister(LOWER1, "127.0.0.1:5060", 3600);
            server.getCascade().onPlatformRegister(LOWER2, "127.0.0.1:5060", 3600);

            List<CatalogItem> a = Arrays.asList(
                    item("34020000001320000001", "cam1"),
                    item("34020000001320000002", "cam2"));
            List<CatalogItem> b = Arrays.asList(
                    item("34020000001320000002", "cam2-dup"),
                    item("34020000001320000003", "cam3"));

            CascadeEvents.CatalogMerged m = runQuery(server, a, b);
            // 34020000001320000002 出现两次，应只保留一条
            assertEquals(3, m.getTotalCount());
            long dupCount = m.getItems().stream()
                    .filter(i -> "34020000001320000002".equals(i.getDeviceId())).count();
            assertEquals(1, dupCount);
        } finally {
            server.stop();
        }
    }

    @Test
    public void 应答XML的SumNum须与条目数一致() {
        Gb28181SipServer server = start();
        try {
            server.getCascade().onPlatformRegister(LOWER1, "127.0.0.1:5060", 3600);
            CascadeEvents.CatalogMerged m = runQuery(server,
                    Arrays.asList(item("34020000001320000001", "a"),
                            item("34020000001320000002", "b")));
            assertEquals(2, m.getTotalCount());
            assertTrue(m.getResponseXml().contains("<SumNum>2</SumNum>"));
            assertTrue(m.getResponseXml().contains("Num=\"2\""));
        } finally {
            server.stop();
        }
    }

    @Test
    public void 全部下级超时也应给出结果不挂死() {
        Gb28181SipServer server = start();
        try {
            server.getCascade().onPlatformRegister(LOWER1, "127.0.0.1:5060", 3600);
            server.getCascade().onPlatformRegister(LOWER2, "127.0.0.1:5060", 3600);
            // 不注入任何应答，应走超时兜底
            CascadeEvents.CatalogMerged m =
                    server.getCascade().queryCascadedCatalog(UPPER_ID, 200, 400);
            assertEquals(200, m.getSn());
            assertEquals(0, m.getTotalCount());
            assertEquals(2, m.getFailedPlatforms().size());
        } finally {
            server.stop();
        }
    }

    @Test
    public void 非下级平台的应答不应被级联消费() {
        Gb28181SipServer server = start();
        try {
            server.getCascade().onPlatformRegister(LOWER1, "127.0.0.1:5060", 3600);
            // SN 不在级联上下文中
            boolean consumed = server.getCascade().onCatalogResponse(
                    LOWER1, 9999, Collections.singletonList(item("x", "x")));
            assertFalse(consumed);
            // 来源不是已注册下级
            boolean consumed2 = server.getCascade().onCatalogResponse(
                    "34020000001320000001", 1, Collections.<CatalogItem>emptyList());
            assertFalse(consumed2);
        } finally {
            server.stop();
        }
    }

    @Test
    public void 订阅穿透应覆盖全部在线下级() {
        Gb28181SipServer server = start();
        try {
            server.getCascade().onPlatformRegister(LOWER1, "127.0.0.1:5060", 3600);
            server.getCascade().onPlatformRegister(LOWER2, "127.0.0.1:5060", 3600);
            int n = server.getCascade().subscribeCascade(UPPER_ID, "Catalog", 3600);
            assertEquals(2, n);
        } finally {
            server.stop();
        }
    }

    @Test
    public void Server应暴露级联管理器() {
        Gb28181SipServer server = start();
        try {
            assertNotNull(server.getCascade());
            assertNotNull(server.getCascade().getRegistry());
            assertTrue(server.getCascade().getRegistry() instanceof LocalCascadeRegistry);
        } finally {
            server.stop();
        }
    }

    // ==================== 辅助 ====================

    private static CatalogItem item(String deviceId, String name) {
        return CatalogItem.builder()
                .deviceId(deviceId).name(name)
                .civilCode("340200").parental(0)
                .registerWay(1).secrecy(0).status("ON")
                .parentId(SELF_ID)
                .build();
    }

    /**
     * 发起级联查询并注入各下级应答。
     *
     * <p>用异步接口以拿到内部 SN，再逐个喂入应答验证聚合结果。
     */
    @SafeVarargs
    private static CascadeEvents.CatalogMerged runQuery(Gb28181SipServer server,
                                                        List<CatalogItem>... replies) {
        CascadeManager cm = server.getCascade();
        CascadeManager.CascadeQuery q = cm.startQuery(UPPER_ID, 1, 3000);
        if (q == null) {
            return null;
        }
        java.util.Map<Integer, String> snMap = cm.innerSnMap(q);
        int i = 0;
        for (java.util.Map.Entry<Integer, String> e : snMap.entrySet()) {
            List<CatalogItem> payload = i < replies.length
                    ? replies[i]
                    : Collections.<CatalogItem>emptyList();
            cm.onCatalogResponse(e.getValue(), e.getKey(), payload);
            i++;
        }
        return cm.awaitQuery(q, 2000);
    }

    private static Gb28181SipServer start() {
        return startWithCallback(null);
    }

    private static Gb28181SipServer startWithCallback(SipCallback cb) {
        Gb28181SipServer.Builder b = Gb28181SipServer.builder()
                .serverId(SELF_ID).host("127.0.0.1").port(freePort()).transport("UDP");
        if (cb != null) {
            b.callback(cb);
        }
        return b.build().start();
    }

    private static int freePort() {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}