package dev.cnpay.provider;

public enum Provider {
    WECHAT("WeChat Pay"),
    ALIPAY("Alipay");

    private final String label;

    Provider(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
