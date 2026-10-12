package io.github.rurulalanana.gb28181.sip.cluster;

/**
 * 跨节点信令转发（SPI）。
 *
 * <p>用于<b>微服务多实例</b>场景：设备注册在节点 A，而业务请求落到节点 B 时，
 * 通过本接口把信令转发到持有设备会话的节点。
 *
 * <p>默认实现 {@link LocalRelay} 仅支持单节点（跨节点调用直接返回"设备不在本节点"）；
 * 集群实现见 {@code RedisRelay}（需引入 jedis）。
 *
 * <p>实现要求：线程安全、投递失败不得抛出到业务线程。
 */
public interface MessageRelay {

    /**
     * 投递信令到指定节点。
     *
     * @param targetNode 目标节点 ID
     * @param message    信令内容
     * @return 是否已成功投递（不代表对端已响应）
     */
    boolean deliver(String targetNode, RelayMessage message);

    /**
     * 投递信令并等待对端响应。
     *
     * @param timeoutMs 超时毫秒
     * @return 对端响应；超时返回 {@code null}
     */
    default RelayMessage deliverAndWait(String targetNode, RelayMessage message, long timeoutMs) {
        deliver(targetNode, message);
        return null;
    }

    /** 是否为集群模式。单机返回 {@code false}。 */
    default boolean clustered() {
        return false;
    }

    /** 释放资源。 */
    default void close() {
    }

    /** 本节点 ID。 */
    String nodeId();

    /**
     * 本地空实现：仅支持单节点。
     *
     * <p>跨节点投递时返回 {@code false}，由调用方回退到本地投递或报错。
     */
    class LocalRelay implements MessageRelay {

        private final String nodeId;

        public LocalRelay(String nodeId) {
            this.nodeId = nodeId;
        }

        @Override
        public boolean deliver(String targetNode, RelayMessage message) {
            // 单节点模式：目标即本节点时视为成功
            return nodeId.equals(targetNode);
        }

        @Override
        public String nodeId() {
            return nodeId;
        }
    }
}