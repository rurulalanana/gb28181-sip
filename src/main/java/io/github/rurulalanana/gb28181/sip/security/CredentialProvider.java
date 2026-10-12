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