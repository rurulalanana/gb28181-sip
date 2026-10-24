package io.github.rurulalanana.gb28181.sip.security;

import java.util.Map;

/**
 * 凭据提供者（SPI）。
 *
 * <p>认证方式外置，调用方可自行接入数据库、配置中心或内存 Map。
 *
 * <p>默认实现 {@link #PERMISSIVE}：<b>不做认证</b>，接受任意注册请求。
 * 生产环境请务必提供真实实现。
 */
@FunctionalInterface
public interface CredentialProvider {

    /**
     * 返回该用户名的口令。
     *
     * @param username 用户名（通常为设备编码）
     * @return 口令；{@code null} 表示用户不存在，认证将失败
     */
    String passwordOf(String username);

    /**
     * 当前是否<b>需要</b>鉴权。
     *
     * <p>默认 {@code true}（一旦提供了本实现就按鉴权处理），
     * 保持与历史行为一致。
     *
     * <p><b>为什么需要这个方法</b>：平台侧的「设备统一接入密码」是可以在界面上
     * 清空以关闭鉴权的。而 {@link #passwordOf} 返回 {@code null} 的语义是
     * 「用户不存在 → 403」，不能挪用来表示「不鉴权」。
     * 若只在启动时按密码是否为空来「传/不传」本实现，那运行期把它清空后
     * 所有设备都会被 403 挡在门外 —— 必须能<b>每次 REGISTER 重新问一次</b>。
     *
     * <p>调用方应在 {@code passwordOf} 之前先看本方法。
     *
     * @return {@code false} 表示此刻不鉴权（等同于放行）
     */
    default boolean isEnabled() {
        return true;
    }

    /** 是否为已知用户。默认实现仅判断 {@code passwordOf} 非 {@code null}。 */
    default boolean exists(String username) {
        return passwordOf(username) != null;
    }

    /** 全局通配实现：不做认证。 */
    CredentialProvider PERMISSIVE = username -> null;

    /** 固定凭据实现，适用于单设备或测试。 */
    static CredentialProvider fixed(Credential credential) {
        return username -> credential.getUsername().equals(username)
                ? credential.getPassword() : null;
    }

    /** Map 凭据实现。 */
    static CredentialProvider ofMap(Map<String, String> userToPassword) {
        return userToPassword::get;
    }
}