package io.github.rurulalanana.gb28181.sip.cascade;

import io.github.rurulalanana.gb28181.sip.Gb28181SipClient;
import io.github.rurulalanana.gb28181.sip.constant.GbId;
import io.github.rurulalanana.gb28181.sip.event.SipCallback;
import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 国标级联管理（GB/T 28181—2022 第 8 章，<b>仅信令层</b>）。
 *
 * <p><b>职责边界</b>：只负责协议层的级联——
 * <ul>
 *   <li>下级平台注册 / 注销 / 心跳保活</li>
 *   <li>级联目录查询转发与<b>聚合</b>（并发查询、设备编码去重、SN 重映射）</li>
 *   <li>级联订阅穿透</li>
 * </ul>
 * <b>流转发不在此处理</b>——上级点播下级时通过
 * {@link SipCallback#onCascadeInvite(CascadeEvents.CascadeInvite)} 把协商交还业务方。
 *
 * <h3>SN 重映射的必要性</h3>
 * 上级发起查询的 SN 属于"上级→本级"这条会话，原样转发给下级会与本级事务冲突，
 * 故转发时分配内部 SN，下级应答回来后再映射回上级原始 SN。
 */
@Slf4j
public class CascadeManager {

    private final String serverId;
    private final CascadeRegistry registry;
    private final Gb28181SipClient client;
    private final SipCallback callback;
    private final long silentTimeoutSeconds;

    /** 内部 SN → 聚合上下文。 */
    private final Map<Integer, CascadeQuery> byInnerSn = new ConcurrentHashMap<>();

    /** 一次级联目录查询的聚合上下文。 */
    public static final class CascadeQuery {
        final int originSn;
        final String originPlatformId;
        final List<String> targets;
        final long deadline;
        /** 按设备编码去重，同时保序。 */
        final Map<String, CatalogItem> merged = Collections.synchronizedMap(new LinkedHashMap<>());
        final List<String> responded = Collections.synchronizedList(new ArrayList<>());
        final CompletableFuture<CascadeEvents.CatalogMerged> future = new CompletableFuture<>();
        /** 本次分配出去的内部 SN。 */
        final List<Integer> innerSns = Collections.synchronizedList(new ArrayList<>());
        /** 内部 SN → 下级平台编码。 */
        final Map<Integer, String> platformByInnerSn = new ConcurrentHashMap<>();
        /** 已收到应答的下级平台数。 */
        int respondedCount;
        boolean finished;

        CascadeQuery(int originSn, String originPlatformId, List<String> targets, long deadline) {
            this.originSn = originSn;
            this.originPlatformId = originPlatformId;
            this.targets = targets;
            this.deadline = deadline;
        }
    }

    public CascadeManager(String serverId, CascadeRegistry registry,
                          Gb28181SipClient client, SipCallback callback,
                          long silentTimeoutSeconds) {
        this.serverId = serverId;
        this.registry = registry;
        this.client = client;
        this.callback = callback;
        this.silentTimeoutSeconds = silentTimeoutSeconds;
    }

    // ==================== 下级平台生命周期 ====================

    /** 处理下级平台 REGISTER。 */
    public void onPlatformRegister(String platformId, String address, int expires) {
        if (!GbId.isValidDeviceId(platformId)) {
            log.warn("下级平台编码非法，忽略注册: {}", platformId);
            return;
        }
        boolean refresh = registry.isOnline(platformId, silentTimeoutSeconds);
        String[] addr = splitAddress(address);

        registry.register(CascadePlatform.builder()
                .platformId(platformId)
                .ip(addr[0])
                .port(Integer.parseInt(addr[1]))
                .transport("UDP")
                .expires(expires)
                .registerTime(System.currentTimeMillis())
                .lastKeepAlive(System.currentTimeMillis())
                .domain(GbId.domainOf(platformId))
                .build());

        log.info("下级平台{}: {} ({})", refresh ? "续注册" : "注册", platformId, address);
        callback.onCascadePlatformRegister(CascadeEvents.PlatformRegistered.builder()
                .platformId(platformId).address(address)
                .expires(expires).refresh(refresh).build());
    }

    /** 处理下级平台注销 / 超时下线。 */
    public void onPlatformUnregister(String platformId, String reason) {
        if (registry.unregister(platformId) == null) {
            return;
        }
        log.info("下级平台注销: {} ({})", platformId, reason);
        callback.onCascadePlatformUnregister(CascadeEvents.PlatformUnregistered.builder()
                .platformId(platformId).reason(reason).build());
    }

    /** 下级平台心跳。 */
    public boolean onPlatformKeepAlive(String platformId) {
        return registry.touch(platformId);
    }

    /** 清理超时下级平台，由服务定时调用，返回清理数量。 */
    public int sweepTimeout() {
        int removed = 0;
        for (CascadePlatform p : registry.listAll()) {
            if (!p.isOnline(silentTimeoutSeconds)) {
                onPlatformUnregister(p.getPlatformId(), p.isExpired() ? "expired" : "silent");
                removed++;
            }
        }
        return removed;
    }

    // ==================== 级联目录 ====================

    /**
     * 向上级平台查询级联目录（聚合所有在线下级）。
     *
     * <p>并发向各在线下级发 {@code Catalog}，收集应答后按设备编码去重合并，
     * 用<b>上级原始 SN</b> 生成应答并主动发给上级。
     *
     * @param originPlatformId 上级平台编码
     * @param originSn         上级原始 SN
     * @param timeoutMs        等待下级应答的超时毫秒
     */
    public CascadeEvents.CatalogMerged queryCascadedCatalog(String originPlatformId,
                                                            int originSn, long timeoutMs) {
        CascadeQuery query = startQuery(originPlatformId, originSn, timeoutMs);
        if (query == null) {
            return replyEmpty(originPlatformId, originSn);
        }
        try {
            return query.future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("级联目录聚合超时: originSn={} 已收 {}/{} 个下级应答",
                    query.originSn, query.respondedCount, query.targets.size());
            return finalize(query);
        } finally {
            for (Integer sn : query.innerSns) {
                byInnerSn.remove(sn);
            }
        }
    }

    /**
     * 启动一次级联目录查询（不阻塞）。
     *
     * <p>供异步场景使用：返回后可随时调用 {@link #onCatalogResponse} 注入应答，
     * 全部收齐或超时后由 {@link #finalize} 统一收口。
     *
     * @return 查询上下文；无在线下级时返回 {@code null}
     */
    public CascadeQuery startQuery(String originPlatformId, int originSn, long timeoutMs) {
        return beginQuery(originPlatformId, originSn, timeoutMs);
    }

    /**
     * 启动查询并返回内部 SN → 下级平台的映射，便于逐个注入应答。
     *
     * @return 映射表；无在线下级时返回空表
     */
    public java.util.Map<Integer, String> innerSnMap(CascadeQuery query) {
        java.util.Map<Integer, String> m = new java.util.LinkedHashMap<>();
        if (query != null) {
            m.putAll(query.platformByInnerSn);
        }
        return m;
    }

    /** 等待某次查询收口（超时走兜底）。 */
    public CascadeEvents.CatalogMerged awaitQuery(CascadeQuery query, long timeoutMs) {
        if (query == null) {
            return null;
        }
        try {
            return query.future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return finalize(query);
        } finally {
            for (Integer sn : query.innerSns) {
                byInnerSn.remove(sn);
            }
        }
    }

    private CascadeQuery beginQuery(String originPlatformId, int originSn, long timeoutMs) {
        List<String> targets = registry.listOnline(silentTimeoutSeconds);

        if (targets.isEmpty()) {
            // 无在线下级：由调用方回空目录，让上级知道本级下无设备
            return null;
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        CascadeQuery query = new CascadeQuery(originSn, originPlatformId, targets, deadline);

        for (String lower : targets) {
            int innerSn = nextInnerSn(lower);
            query.innerSns.add(innerSn);
            query.platformByInnerSn.put(innerSn, lower);
            byInnerSn.put(innerSn, query);
            try {
                client.sendMessage(lower, ManscdpBuilder.catalogQuery(innerSn, serverId));
            } catch (Exception e) {
                // 发送失败只记录：下级平台的收件地址来自级联注册表，
                // 未必存在于设备注册表（sendMessage 以设备表寻址），不能因此放弃本次聚合
                log.warn("向下级 {} 发送目录查询失败: {}", lower, e.getMessage());
            }
        }
        return query;
    }

    /**
     * 接收下级平台的目录应答。
     *
     * <p>由 {@code SipCallback#onManscdpResponse} 驱动，返回是否被级联查询消费。
     *
     * @param from     应答方（必须是已注册的下级平台）
     * @param sn       应答中的 SN（必须是本管理器分配的内部 SN）
     * @param items    应答中的设备条目
     */
    public boolean onCatalogResponse(String from, int sn, List<CatalogItem> items) {
        if (registry.get(from) == null) {
            return false;
        }
        CascadeQuery q = byInnerSn.get(sn);
        if (q == null) {
            return false;
        }
        boolean allDone;
        synchronized (q) {
            if (items != null) {
                for (CatalogItem it : items) {
                    if (it != null && it.getDeviceId() != null) {
                        // 标准要求按设备编码唯一，重叠区域以先到者为准
                        q.merged.putIfAbsent(it.getDeviceId(), it);
                    }
                }
            }
            String owner = q.platformByInnerSn.get(sn);
            if (owner != null && !q.responded.contains(owner)) {
                q.responded.add(owner);
                q.respondedCount++;
            }
            allDone = q.respondedCount >= q.targets.size();
        }
        // finalize 会再次进入 synchronized(q)，必须在此锁外调用，否则自死锁
        if (allDone) {
            finalize(q);
        }
        return true;
    }

    /** 收口：生成应答 XML、发给上级、触发回调、完成 future。 */
    private CascadeEvents.CatalogMerged finalize(CascadeQuery q) {
        List<CatalogItem> items;
        List<String> responded;
        synchronized (q) {
            if (q.finished) {
                return null;
            }
            q.finished = true;
            items = new ArrayList<>(q.merged.values());
            responded = new ArrayList<>(q.responded);
        }

        List<String> failed = new ArrayList<>(q.targets);
        failed.removeAll(responded);

        String xml = items.isEmpty()
                ? ManscdpBuilder.emptyCatalogResponse(q.originSn, q.originPlatformId)
                : ManscdpBuilder.catalogResponse(q.originSn, q.originPlatformId, items);

        CascadeEvents.CatalogMerged merged = CascadeEvents.CatalogMerged.builder()
                .sn(q.originSn)
                .totalCount(items.size())
                .respondedPlatforms(responded)
                .failedPlatforms(failed)
                .items(items)
                .responseXml(xml)
                .build();

        try {
            client.sendMessage(q.originPlatformId, xml);
        } catch (Exception e) {
            log.warn("向级平台 {} 回复目录失败: {}", q.originPlatformId, e.getMessage());
        }
        callback.onCascadeCatalog(merged);
        q.future.complete(merged);
        return merged;
    }

    private CascadeEvents.CatalogMerged replyEmpty(String originPlatformId, int originSn) {
        String xml = ManscdpBuilder.emptyCatalogResponse(originSn, originPlatformId);
        try {
            client.sendMessage(originPlatformId, xml);
        } catch (Exception e) {
            log.warn("回复空目录失败: {}", e.getMessage());
        }
        CascadeEvents.CatalogMerged merged = CascadeEvents.CatalogMerged.builder()
                .sn(originSn).totalCount(0)
                .respondedPlatforms(Collections.<String>emptyList())
                .failedPlatforms(Collections.<String>emptyList())
                .items(Collections.<CatalogItem>emptyList())
                .responseXml(xml)
                .build();
        callback.onCascadeCatalog(merged);
        return merged;
    }

    /** 分配内部 SN（A.2.1.3：1~9999），与下级平台绑定。 */
    private int nextInnerSn(String lower) {
        for (int i = 0; i < 10000; i++) {
            int sn = (innerCursor.getAndIncrement() % 9999) + 1;
            if (!byInnerSn.containsKey(sn)) {
                return sn;
            }
        }
        throw new IllegalStateException("内部 SN 耗尽");
    }

    private final java.util.concurrent.atomic.AtomicInteger innerCursor =
            new java.util.concurrent.atomic.AtomicInteger(1);

    // ==================== 级联订阅 ====================

    /**
     * 订阅穿透：上级订阅本级目录，本级向所有在线下级发同样订阅。
     *
     * @return 实际收到订阅的下级平台数量
     */
    public int subscribeCascade(String originPlatformId, String eventName, int expires) {
        int count = 0;
        List<String> failed = new ArrayList<>();
        for (String lower : registry.listOnline(silentTimeoutSeconds)) {
            try {
                client.subscribe(lower, eventName, expires);
                count++;
            } catch (Exception e) {
                // 下级平台未必在设备注册表中（sendMessage/subscribe 以设备表寻址），
                // 发送失败只记录，不影响其他下级
                log.warn("向下级 {} 转发订阅失败: {}", lower, e.getMessage());
                failed.add(lower);
            }
        }
        log.info("级联订阅穿透 {} event={} → 成功 {} 个下级, 失败 {}",
                originPlatformId, eventName, count, failed);
        return count;
    }

    // ==================== 工具 ====================

    private static String[] splitAddress(String address) {
        if (address == null || address.isEmpty()) {
            return new String[]{"0.0.0.0", "5060"};
        }
        int i = address.lastIndexOf(':');
        if (i < 0) {
            return new String[]{address, "5060"};
        }
        return new String[]{address.substring(0, i), address.substring(i + 1)};
    }

    public CascadeRegistry getRegistry() {
        return registry;
    }

    /**
     * 本级是否挂有在线下级平台。
     *
     * <p>用于判断某个目录查询是否应走级联聚合：无下级时应回落到业务方的
     * 设备级处理，避免把普通查询误判为级联。
     */
    public boolean hasOnlineLower() {
        return !registry.listOnline(silentTimeoutSeconds).isEmpty();
    }
}