package dev.cnpay.provider.wechat;

import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.exception.WechatPayException;
import com.wechat.pay.java.service.billdownload.DigestBillEntity;
import com.wechat.pay.java.service.billdownload.model.BillType;
import com.wechat.pay.java.service.billdownload.model.GetTradeBillRequest;
import com.wechat.pay.java.service.payments.h5.model.H5Info;
import com.wechat.pay.java.service.payments.h5.model.SceneInfo;
import com.wechat.pay.java.service.payments.jsapi.model.Payer;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.nativepay.model.CloseOrderRequest;
import com.wechat.pay.java.service.payments.nativepay.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.Status;
import dev.cnpay.config.ClockConfig;
import dev.cnpay.config.CnpayProperties;
import dev.cnpay.config.PublicUrls;
import dev.cnpay.order.Order;
import dev.cnpay.provider.BillLine;
import dev.cnpay.provider.PayInstruction;
import dev.cnpay.provider.PaymentProvider;
import dev.cnpay.provider.Provider;
import dev.cnpay.provider.ProviderException;
import dev.cnpay.provider.RefundOutcome;
import dev.cnpay.provider.TradeSnapshot;
import dev.cnpay.refund.Refund;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** WeChat Pay API v3 through the official wechatpay-java SDK. */
@Component
public class WechatPayProvider implements PaymentProvider {

    private final WechatPayClients clients;
    private final CnpayProperties properties;
    private final PublicUrls urls;

    public WechatPayProvider(WechatPayClients clients, CnpayProperties properties, PublicUrls urls) {
        this.clients = clients;
        this.properties = properties;
        this.urls = urls;
    }

    @Override
    public Provider id() {
        return Provider.WECHAT;
    }

    @Override
    public PayInstruction create(Order order) {
        return call(() -> switch (order.channel()) {
            case WECHAT_NATIVE -> {
                var request = new com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest();
                request.setAppid(appId());
                request.setMchid(mchId());
                request.setDescription(order.description());
                request.setOutTradeNo(order.outTradeNo());
                request.setTimeExpire(rfc3339(order.expiresAt()));
                request.setNotifyUrl(urls.wechatNotifyUrl());
                var amount = new com.wechat.pay.java.service.payments.nativepay.model.Amount();
                amount.setTotal(Math.toIntExact(order.amountFen()));
                amount.setCurrency("CNY");
                request.setAmount(amount);
                yield PayInstruction.qr(clients.get().nativePay().prepay(request).getCodeUrl());
            }
            case WECHAT_JSAPI -> {
                var request = new com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest();
                request.setAppid(appId());
                request.setMchid(mchId());
                request.setDescription(order.description());
                request.setOutTradeNo(order.outTradeNo());
                request.setTimeExpire(rfc3339(order.expiresAt()));
                request.setNotifyUrl(urls.wechatNotifyUrl());
                var amount = new com.wechat.pay.java.service.payments.jsapi.model.Amount();
                amount.setTotal(Math.toIntExact(order.amountFen()));
                amount.setCurrency("CNY");
                request.setAmount(amount);
                Payer payer = new Payer();
                payer.setOpenid(order.payerOpenid());
                request.setPayer(payer);
                // The SDK signs appId\ntimeStamp\nnonceStr\npackage\n for wx.requestPayment / wx.requestPayment in mini-programs.
                PrepayWithRequestPaymentResponse r = clients.get().jsapi().prepayWithRequestPayment(request);
                Map<String, String> params = new LinkedHashMap<>();
                params.put("appId", r.getAppId());
                params.put("timeStamp", r.getTimeStamp());
                params.put("nonceStr", r.getNonceStr());
                params.put("package", r.getPackageVal());
                params.put("signType", r.getSignType());
                params.put("paySign", r.getPaySign());
                yield PayInstruction.jsapi(params);
            }
            case WECHAT_H5 -> {
                var request = new com.wechat.pay.java.service.payments.h5.model.PrepayRequest();
                request.setAppid(appId());
                request.setMchid(mchId());
                request.setDescription(order.description());
                request.setOutTradeNo(order.outTradeNo());
                request.setTimeExpire(rfc3339(order.expiresAt()));
                request.setNotifyUrl(urls.wechatNotifyUrl());
                var amount = new com.wechat.pay.java.service.payments.h5.model.Amount();
                amount.setTotal(Math.toIntExact(order.amountFen()));
                amount.setCurrency("CNY");
                request.setAmount(amount);
                SceneInfo scene = new SceneInfo();
                scene.setPayerClientIp(order.clientIp());
                H5Info h5 = new H5Info();
                h5.setType("Wap");
                scene.setH5Info(h5);
                request.setSceneInfo(scene);
                yield PayInstruction.redirect(clients.get().h5().prepay(request).getH5Url());
            }
            default -> throw new IllegalArgumentException(order.channel() + " is not a WeChat Pay channel");
        });
    }

    @Override
    public TradeSnapshot query(String outTradeNo) {
        QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setMchid(mchId());
        request.setOutTradeNo(outTradeNo);
        try {
            return toSnapshot(clients.get().nativePay().queryOrderByOutTradeNo(request));
        } catch (ServiceException e) {
            if ("ORDER_NOT_EXIST".equals(e.getErrorCode()) || "RESOURCE_NOT_EXISTS".equals(e.getErrorCode())) {
                return new TradeSnapshot(outTradeNo, TradeSnapshot.TradeState.NOT_FOUND, null, 0, null, null);
            }
            throw translate(e);
        } catch (WechatPayException e) {
            throw translate(e);
        }
    }

    public static TradeSnapshot toSnapshot(Transaction t) {
        TradeSnapshot.TradeState state = switch (t.getTradeState()) {
            case SUCCESS -> TradeSnapshot.TradeState.PAID;
            case REFUND -> TradeSnapshot.TradeState.REFUNDED;
            case NOTPAY, USERPAYING, ACCEPT -> TradeSnapshot.TradeState.NOT_PAID;
            case CLOSED, REVOKED, PAYERROR -> TradeSnapshot.TradeState.CLOSED;
        };
        long total = t.getAmount() == null || t.getAmount().getTotal() == null ? 0 : t.getAmount().getTotal();
        String payer = t.getPayer() == null ? null : t.getPayer().getOpenid();
        return new TradeSnapshot(t.getOutTradeNo(), state, t.getTransactionId(), total, payer,
                parseTime(t.getSuccessTime()));
    }

    @Override
    public void close(String outTradeNo) {
        CloseOrderRequest request = new CloseOrderRequest();
        request.setMchid(mchId());
        request.setOutTradeNo(outTradeNo);
        call(() -> {
            clients.get().nativePay().closeOrder(request);
            return null;
        });
    }

    @Override
    public RefundOutcome refund(Order order, Refund refund) {
        CreateRequest request = new CreateRequest();
        request.setOutTradeNo(order.outTradeNo());
        request.setOutRefundNo(refund.outRefundNo());
        request.setReason(refund.reason());
        request.setNotifyUrl(urls.wechatNotifyUrl());
        AmountReq amount = new AmountReq();
        amount.setRefund(refund.amountFen());
        amount.setTotal(order.amountFen());
        amount.setCurrency("CNY");
        request.setAmount(amount);
        var result = call(() -> clients.get().refunds().create(request));
        RefundOutcome.Status status = result.getStatus() == Status.SUCCESS
                ? RefundOutcome.Status.SUCCESS : RefundOutcome.Status.PROCESSING;
        if (result.getStatus() == Status.CLOSED || result.getStatus() == Status.ABNORMAL) {
            throw new ProviderException("REFUND_" + result.getStatus(), "Refund was not accepted: " + result.getStatus());
        }
        return new RefundOutcome(status, result.getRefundId());
    }

    @Override
    public List<BillLine> downloadBill(LocalDate billDate) {
        GetTradeBillRequest request = new GetTradeBillRequest();
        request.setBillDate(billDate.toString());
        request.setBillType(BillType.ALL);
        return call(() -> {
            DigestBillEntity bill = clients.get().bills().getTradeBill(request);
            String csv;
            try (InputStream in = bill.getInputStream()) {
                csv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new ProviderException("BILL_DOWNLOAD", "Could not read bill: " + e.getMessage(), e);
            }
            if (!bill.verifyHash()) {
                throw new ProviderException("BILL_HASH", "Bill digest does not match hash_value; refusing to use it");
            }
            return WechatBillParser.parse(csv);
        });
    }

    private String mchId() {
        return properties.wechat().mchId();
    }

    private String appId() {
        return properties.wechat().appId();
    }

    static String rfc3339(LocalDateTime beijing) {
        return beijing.withNano(0).atZone(ClockConfig.BEIJING).toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public static LocalDateTime parseTime(String rfc3339) {
        if (rfc3339 == null || rfc3339.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(rfc3339).atZoneSameInstant(ClockConfig.BEIJING).toLocalDateTime();
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (WechatPayException e) {
            throw translate(e);
        }
    }

    private static ProviderException translate(WechatPayException e) {
        if (e instanceof ServiceException se) {
            return new ProviderException(se.getErrorCode(), "WeChat Pay: " + se.getErrorMessage(), e);
        }
        return new ProviderException("WECHATPAY_CLIENT", "WeChat Pay: " + e.getMessage(), e);
    }
}
