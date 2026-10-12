package io.github.rurulalanana.gb28181.sip.cascade;

import io.github.rurulalanana.gb28181.sip.constant.GbId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存级下级平台注册表。
 *
 * <p>单机场景使用；集群场景可替换为 Redis 实现。
 */
public class LocalCascadeRegistry implements CascadeRegistry {

    private final Map<String, CascadePlatform> platforms = new ConcurrentHashMap<>();

    @Override
    public void register(CascadePlatform platform) {
        if (platform == null || platform.getPlatformId() == null) {
            return;
        }
        CascadePlatform p = platform.getLastKeepAlive() > 0
                ? platform
                : CascadePlatform.builder()
                        .platformId(platform.getPlatformId())
                        .ip(platform.getIp())
                        .port(platform.getPort())
                        .transport(platform.getTransport())
                        .expires(platform.getExpires())
                        .registerTime(platform.getRegisterTime())
                        .lastKeepAlive(System.currentTimeMillis())
                        .domain(platform.getDomain() != null
                                ? platform.getDomain()
                                : GbId.domainOf(platform.getPlatformId()))
                        .catalogSynced(platform.isCatalogSynced())
                        .deviceCount(platform.getDeviceCount())
                        .build();
        platforms.put(p.getPlatformId(), p);
    }

    @Override
    public CascadePlatform unregister(String platformId) {
        return platformId == null ? null : platforms.remove(platformId);
    }

    @Override
    public CascadePlatform get(String platformId) {
        return platformId == null ? null : platforms.get(platformId);
    }

    @Override
    public boolean isOnline(String platformId, long silentTimeoutSeconds) {
        CascadePlatform p = get(platformId);
        return p != null && p.isOnline(silentTimeoutSeconds);
    }

    @Override
    public Collection<CascadePlatform> listAll() {
        return new ArrayList<>(platforms.values());
    }

    @Override
    public List<String> listOnline(long silentTimeoutSeconds) {
        List<String> out = new ArrayList<>();
        for (CascadePlatform p : platforms.values()) {
            if (p.isOnline(silentTimeoutSeconds)) {
                out.add(p.getPlatformId());
            }
        }
        return out;
    }

    @Override
    public int onlineCount(long silentTimeoutSeconds) {
        return listOnline(silentTimeoutSeconds).size();
    }

    @Override
    public boolean touch(String platformId) {
        CascadePlatform p = get(platformId);
        if (p == null) {
            return false;
        }
        p.touch();
        return true;
    }

    @Override
    public void clear() {
        platforms.clear();
    }
}