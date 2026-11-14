package dev.cnpay.provider.wechat;

import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.core.http.HttpClient;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.service.billdownload.BillDownloadServiceExtension;
import com.wechat.pay.java.service.payments.h5.H5Service;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.refund.RefundService;
import dev.cnpay.config.CnpayProperties;
import dev.cnpay.config.PublicUrls;
import dev.cnpay.crypto.KeyRing;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.stereotype.Component;

/**
 * Builds the official wechatpay-java clients once. In sandbox mode an OkHttp interceptor redirects
 * {@code https://api.mch.weixin.qq.com/...} to the built-in sandbox; nothing else differs from production:
 * requests are signed with the merchant key and every response signature is verified by the SDK.
 */
@Component
public class WechatPayClients {

    public static final String API_HOST = "api.mch.weixin.qq.com";

    private final CnpayProperties properties;
    private final KeyRing keys;
    private final PublicUrls urls;
    private volatile Bundle bundle;

    public record Bundle(
            RSAPublicKeyConfig config,
            NativePayService nativePay,
            JsapiServiceExtension jsapi,
            H5Service h5,
            RefundService refunds,
            BillDownloadServiceExtension bills,
            NotificationParser notificationParser) {
    }

    public WechatPayClients(CnpayProperties properties, KeyRing keys, PublicUrls urls) {
        this.properties = properties;
        this.keys = keys;
        this.urls = urls;
    }

    public Bundle get() {
        Bundle b = bundle;
        if (b == null) {
            synchronized (this) {
                if (bundle == null) {
                    bundle = build();
                }
                b = bundle;
            }
        }
        return b;
    }

    private Bundle build() {
        RSAPublicKeyConfig config = new RSAPublicKeyConfig.Builder()
                .merchantId(properties.wechat().mchId())
                .privateKey(keys.wechatMerchantPrivateKey())
                .merchantSerialNumber(keys.wechatMerchantSerialNumber())
                .publicKey(keys.wechatPlatformPublicKey())
                .publicKeyId(keys.wechatPlatformPublicKeyId())
                .apiV3Key(keys.wechatApiV3Key())
                .build();

        OkHttpClient.Builder ok = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS);
        if (properties.sandboxMode()) {
            ok.addInterceptor(sandboxRedirect(urls.sandboxBase() + "/wechatpay"));
        }
        HttpClient http = new DefaultHttpClientBuilder().config(config).okHttpClient(ok.build()).build();

        return new Bundle(
                config,
                new NativePayService.Builder().httpClient(http).build(),
                new JsapiServiceExtension.Builder().config(config).httpClient(http).build(),
                new H5Service.Builder().httpClient(http).build(),
                new RefundService.Builder().httpClient(http).build(),
                new BillDownloadServiceExtension.Builder().config(config).httpClient(http).build(),
                new NotificationParser(config));
    }

    static Interceptor sandboxRedirect(String sandboxBase) {
        HttpUrl base = HttpUrl.get(sandboxBase);
        return chain -> {
            Request request = chain.request();
            if (!API_HOST.equals(request.url().host())) {
                return chain.proceed(request);
            }
            HttpUrl.Builder target = base.newBuilder();
            for (String segment : request.url().pathSegments()) {
                target.addPathSegment(segment);
            }
            target.encodedQuery(request.url().encodedQuery());
            return chain.proceed(request.newBuilder().url(target.build()).build());
        };
    }
}
