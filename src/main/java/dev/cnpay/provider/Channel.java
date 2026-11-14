package dev.cnpay.provider;

/** Checkout flavours. Each maps to one provider API. */
public enum Channel {
    WECHAT_NATIVE(Provider.WECHAT, "Native QR code (desktop)", true),
    WECHAT_JSAPI(Provider.WECHAT, "JSAPI / mini-program (inside WeChat)", false),
    WECHAT_H5(Provider.WECHAT, "H5 (mobile browser)", false),
    ALIPAY_QR(Provider.ALIPAY, "QR code (alipay.trade.precreate)", true),
    ALIPAY_PAGE(Provider.ALIPAY, "PC cashier page (alipay.trade.page.pay)", false),
    ALIPAY_WAP(Provider.ALIPAY, "Mobile cashier (alipay.trade.wap.pay)", false);

    private final Provider provider;
    private final String description;
    private final boolean qr;

    Channel(Provider provider, String description, boolean qr) {
        this.provider = provider;
        this.description = description;
        this.qr = qr;
    }

    public Provider provider() {
        return provider;
    }

    public String description() {
        return description;
    }

    public boolean qr() {
        return qr;
    }
}
