package io.github.rurulalanana.gb28181.sip.cascade;

import java.util.Collection;
import java.util.List;

/**
 * 下级平台注册表。
 *
 * <p>与 {@code DeviceRegistry} 并列——下级平台本质上是"会替下属设备应答的上级"，
 * 但生命周期与查询语义不同，故独立管理。
 */
public interface CascadeRegistry {

    /** 注册/刷新一个下级平台。 */
    void register(CascadePlatform platform);

    /** 注销一个下级平台。 */
    CascadePlatform unregister(String platformId);

    /** 按平台编码查询。 */
    CascadePlatform get(String platformId);

    /** 是否在线（未过期且心跳未超时）。 */
    boolean isOnline(String platformId, long silentTimeoutSeconds);

    /** 全部下级平台。 */
    Collection<CascadePlatform> listAll();

    /** 在线平台编码列表。 */
    List<String> listOnline(long silentTimeoutSeconds);

    /** 在线数量。 */
    int onlineCount(long silentTimeoutSeconds);

    /** 刷新心跳。 */
    boolean touch(String platformId);

    /** 清空。 */
    void clear();
}