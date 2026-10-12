package io.github.rurulalanana.gb28181.sip.device;

import java.util.Collection;
import java.util.List;

/**
 * 设备注册表（SPI）。
 *
 * <p>这是模块支持<b>微服务多实例</b>的关键扩展点：
 * <ul>
 *   <li>单机：使用 {@link LocalDeviceRegistry}；</li>
 *   <li>集群：使用 {@code RedisDeviceRegistry}（需引入 jedis），各节点共享注册表，
 *       {@link DeviceSession#getOwnerNode()} 用于把信令路由回持有者节点。</li>
 * </ul>
 *
 * <p>所有方法必须<b>线程安全</b>。
 */
public interface DeviceRegistry {

    /**
     * 注册或刷新设备会话。
     *
     * @param session 会话信息
     */
    void register(DeviceSession session);

    /**
     * 注销设备。
     *
     * @param deviceId 设备编码
     * @return 被移除的会话；不存在返回 {@code null}
     */
    DeviceSession unregister(String deviceId);

    /**
     * 查询设备会话。
     *
     * @param deviceId 设备编码
     * @return 不存在返回 {@code null}
     */
    DeviceSession get(String deviceId);

    /**
     * 更新心跳时间戳。
     *
     * @param deviceId 设备编码
     * @return 是否命中
     */
    boolean touch(String deviceId);

    /** 是否在线。 */
    default boolean isOnline(String deviceId) {
        DeviceSession s = get(deviceId);
        return s != null && !s.isExpired();
    }

    /** 全部设备会话。 */
    Collection<DeviceSession> listAll();

    /** 按监控域查询设备编码列表。 */
    List<String> listByDomain(String domain);

    /** 当前会话总数。 */
    int size();

    /** 清空（通常仅测试用）。 */
    void clear();
}