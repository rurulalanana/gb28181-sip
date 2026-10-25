package io.github.rurulalanana.gb28181.sip.security;

/**
 * 接入控制（黑白名单）SPI。
 *
 * <p>与 {@link CredentialProvider} 平行、互不相干的两道闸门：
 * <ul>
 *   <li>{@link CredentialProvider} 回答「<b>密码对不对</b>」；</li>
 *   <li>{@code AccessControl} 回答「<b>这个来源许不许进</b>」。</li>
 * </ul>
 * 之所以分开，是因为两者的失效场景完全不同：密码一旦泄露或被批量写入
 * 弱口令，密码校验就形同虚设；而来源过滤不依赖密码，
 * 是设备被盗用、内网横向移动时的最后一道拦截。
 *
 * <p><b>检查顺序</b>：本接口在摘要认证<b>之前</b>被调用。
 * 访问控制是网络层语义，不该以「密码是否正确」为前提 ——
 * 否则黑名单里的设备只要密码对了就能进，那它就不叫黑名单了。
 *
 * <p>默认实现 {@link #PERMISSIVE} 放行一切，保持与历史行为一致：
 * 不装配本实现，平台就没有任何来源过滤。
 *
 * <p><b>注意本模块锁定 Java 8</b>（pom 用 {@code <release>8</release>} 强制校验），
 * 所以这里不能用 {@code record} 等新语法 —— 否则编出「声明 8、实际要 16+」的
 * 字节码，跑在 JDK 8 上直接 {@code UnsupportedClassVersionError}。
 */
@FunctionalInterface
public interface AccessControl {

    /**
     * 判定一次注册是否放行。
     *
     * <p>实现方需保证<b>不阻塞</b>且线程安全：本方法在 jain-sip 协议栈线程上
     * 被调用，每个 REGISTER 都会走一次。查数据库时请自行加缓存。
     *
     * @param deviceId 设备编码（From 头中取出的 20 位编码）；
     *                 级联场景下是下级平台编码
     * @param ip       对端 IP（已从 Via / 实际源地址解析），可能为 {@code null}
     * @return 判定结果，见 {@link Decision}
     */
    Decision check(String deviceId, String ip);

    /** 是否启用。返回 {@code false} 时调用方跳过全部检查。 */
    default boolean isEnabled() {
        return true;
    }

    /** 全放行实现：不做任何来源过滤。 */
    AccessControl PERMISSIVE = new AccessControl() {
        @Override
        public Decision check(String deviceId, String ip) {
            return Decision.allow();
        }
    };

    /**
     * 判定结果。
     *
     * <p>带 {@code reason} 是为了让现场排障有据可依：设备注册不上时，
     * 日志里写「命中黑名单：34020000001320000001」远比一个光秃秃的 403 有用 ——
     * 后者会让人先去怀疑网络、密码、SIP 域，最后才想到名单。
     */
    final class Decision {

        private static final Decision ALLOWED = new Decision(true, null);

        private final boolean allowed;
        private final String reason;

        private Decision(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        /** 放行。 */
        public static Decision allow() {
            return ALLOWED;
        }

        /** 拒绝，并说明原因。 */
        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }

        public boolean isAllowed() {
            return allowed;
        }

        /** 拒绝原因（放行时为 {@code null}）。 */
        public String getReason() {
            return reason;
        }

        @Override
        public String toString() {
            return allowed ? "ALLOW" : "DENY(" + reason + ")";
        }
    }
}
