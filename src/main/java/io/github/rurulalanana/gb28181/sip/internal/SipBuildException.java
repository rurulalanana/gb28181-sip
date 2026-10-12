package io.github.rurulalanana.gb28181.sip.internal;

/**
 * SIP 报文构造异常（包装 {@code javax.sip} 的受检异常）。
 */
public class SipBuildException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SipBuildException(String message) {
        super(message);
    }

    public SipBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}