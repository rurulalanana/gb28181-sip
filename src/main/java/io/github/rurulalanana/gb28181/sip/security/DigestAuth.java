package io.github.rurulalanana.gb28181.sip.security;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import javax.sip.header.AuthorizationHeader;
import javax.sip.header.WWWAuthenticateHeader;

/**
 * SIP 摘要认证工具（RFC 3261 §22 / RFC 7616）。
 *
 * <p>GB/T 28181—2022 标准 9.1.1 a) 要求"认证方式应支持<b>数字摘要认证方式</b>"，
 * 本类实现 MD5 摘要。
 *
 * <p>响应计算遵循 RFC 2617：
 * <pre>
 *   HA1 = MD5(username ":" realm ":" password)
 *   HA2 = MD5(method ":" digestUri)
 *   无 qop:  response = MD5(HA1 ":" nonce ":" HA2)
 *   qop=auth: response = MD5(HA1 ":" nonce ":" nc ":" cnonce ":" qop ":" HA2)
 * </pre>
 */
@Slf4j
public final class DigestAuth {

    private DigestAuth() {
    }

    /** 计算摘要响应。 */
    public static String response(String username, String realm, String password,
                                  String method, String digestUri,
                                  String nonce, String qop, String nc, String cnonce) {
        String ha1 = DigestUtils.md5Hex(username + ":" + realm + ":" + password);
        String ha2 = DigestUtils.md5Hex(method + ":" + digestUri);
        if (StringUtils.isNotBlank(qop)) {
            // qop 存在时必须带 cnonce 与 nc
            String c = StringUtils.defaultIfBlank(cnonce, "");
            String n = StringUtils.defaultIfBlank(nc, "");
            return DigestUtils.md5Hex(ha1 + ":" + nonce + ":" + n + ":" + c + ":" + qop + ":" + ha2);
        }
        return DigestUtils.md5Hex(ha1 + ":" + nonce + ":" + ha2);
    }

    /**
     * 校验 {@code Authorization} 头域是否与给定凭据匹配。
 *
     * @param authHeader 对端携带的 Authorization 头
     * @param password   期望的口令
     * @param method     SIP 方法（REGISTER / MESSAGE / INVITE ...），参与摘要计算
     * @return 匹配返回 {@code true}
     */
    public static boolean verify(AuthorizationHeader authHeader, String password, String method) {
        if (authHeader == null || password == null) {
            return false;
        }
        try {
            String username = authHeader.getUsername();
            String realm = authHeader.getRealm();
            String nonce = authHeader.getNonce();
            String uri = authHeader.getURI() == null ? null : authHeader.getURI().toString();
            String qop = authHeader.getQop();
            int nonceCount = authHeader.getNonceCount();
            String cnonce = authHeader.getCNonce();
            String response = authHeader.getResponse();

            if (StringUtils.isAnyBlank(username, realm, nonce, uri, response)) {
                return false;
            }
            String nc = nonceCount > 0 ? String.format("%08x", nonceCount) : null;
            String expected = response(username, realm, password, method, uri,
                    nonce, qop, nc, cnonce);
            return expected != null && expected.equalsIgnoreCase(response);
        } catch (Exception e) {
            log.debug("摘要认证校验异常", e);
            return false;
        }
    }

    /**
     * 从 {@code WWW-Authenticate} 头域取出 {@code qop}。
     *
     * @return {@code null} 表示未指定 qop
     */
    public static String qopOf(WWWAuthenticateHeader h) {
        return h == null ? null : h.getQop();
    }

    /**
     * 生成服务端 challenge 所需的 nonce。
     *
     * <p>使用 {@link java.security.SecureRandom} 保证不可预测。
     */
    public static String newNonce() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        return DigestUtils.md5Hex(bytes);
    }
}