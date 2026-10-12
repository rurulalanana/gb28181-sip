package io.github.rurulalanana.gb28181.sip.security;

import lombok.Builder;
import lombok.Getter;

/**
 * 摘要认证凭据。
 */
@Getter
@Builder
public class Credential {

    /** 用户名，通常为设备编码。 */
    private final String username;

    /** 口令。 */
    private final String password;

    /** 静态工厂。 */
    public static Credential of(String username, String password) {
        return Credential.builder().username(username).password(password).build();
    }
}