package dev.cnpay.provider;

/** A provider call failed (network, signature, or business error such as ORDERPAID / ACQ.TRADE_HAS_SUCCESS). */
public class ProviderException extends RuntimeException {

    private final String code;

    public ProviderException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ProviderException(String code, String message) {
        this(code, message, null);
    }

    public String code() {
        return code;
    }
}
