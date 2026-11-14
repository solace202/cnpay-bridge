package dev.cnpay.provider.alipay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayResponse;
import com.alipay.api.domain.AlipayDataDataserviceBillDownloadurlQueryModel;
import com.alipay.api.domain.AlipayTradeCloseModel;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradePrecreateModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.domain.AlipayTradeWapPayModel;
import com.alipay.api.request.AlipayDataDataserviceBillDownloadurlQueryRequest;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import dev.cnpay.config.PublicUrls;
import dev.cnpay.money.Money;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import org.springframework.stereotype.Component;

/** Alipay OpenAPI through the official alipay-sdk-java. */
@Component
public class AlipayProvider implements PaymentProvider {

    static final DateTimeFormatter ALIPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AlipayClients clients;
    private final PublicUrls urls;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public AlipayProvider(AlipayClients clients, PublicUrls urls) {
        this.clients = clients;
        this.urls = urls;
    }

    @Override
    public Provider id() {
        return Provider.ALIPAY;
    }

    @Override
    public PayInstruction create(Order order) {
        String total = Money.toYuan(order.amountFen());
        String expire = order.expiresAt().format(ALIPAY_TIME);
        try {
            return switch (order.channel()) {
                case ALIPAY_QR -> {
                    AlipayTradePrecreateModel model = new AlipayTradePrecreateModel();
                    model.setOutTradeNo(order.outTradeNo());
                    model.setTotalAmount(total);
                    model.setSubject(order.description());
                    model.setTimeExpire(expire);
                    AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
                    request.setBizModel(model);
                    request.setNotifyUrl(urls.alipayNotifyUrl());
                    yield PayInstruction.qr(ok(clients.get().execute(request)).getQrCode());
                }
                case ALIPAY_PAGE -> {
                    AlipayTradePagePayModel model = new AlipayTradePagePayModel();
                    model.setOutTradeNo(order.outTradeNo());
                    model.setTotalAmount(total);
                    model.setSubject(order.description());
                    model.setProductCode("FAST_INSTANT_TRADE_PAY");
                    model.setTimeExpire(expire);
                    AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
                    request.setBizModel(model);
                    request.setNotifyUrl(urls.alipayNotifyUrl());
                    request.setReturnUrl(urls.checkoutUrl(order.id()));
                    // GET returns a signed gateway URL to redirect the shopper to; POST would return an auto-submit form.
                    yield PayInstruction.redirect(clients.get().pageExecute(request, "GET").getBody());
                }
                case ALIPAY_WAP -> {
                    AlipayTradeWapPayModel model = new AlipayTradeWapPayModel();
                    model.setOutTradeNo(order.outTradeNo());
                    model.setTotalAmount(total);
                    model.setSubject(order.description());
                    model.setProductCode("QUICK_WAP_WAY");
                    model.setTimeExpire(expire);
                    model.setQuitUrl(urls.checkoutUrl(order.id()));
                    AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest();
                    request.setBizModel(model);
                    request.setNotifyUrl(urls.alipayNotifyUrl());
                    request.setReturnUrl(urls.checkoutUrl(order.id()));
                    yield PayInstruction.redirect(clients.get().pageExecute(request, "GET").getBody());
                }
                default -> throw new IllegalArgumentException(order.channel() + " is not an Alipay channel");
            };
        } catch (AlipayApiException e) {
            throw translate(e);
        }
    }

    @Override
    public TradeSnapshot query(String outTradeNo) {
        AlipayTradeQueryModel model = new AlipayTradeQueryModel();
        model.setOutTradeNo(outTradeNo);
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        request.setBizModel(model);
        try {
            AlipayTradeQueryResponse r = clients.get().execute(request);
            if (!r.isSuccess()) {
                if ("ACQ.TRADE_NOT_EXIST".equals(r.getSubCode())) {
                    return new TradeSnapshot(outTradeNo, TradeSnapshot.TradeState.NOT_FOUND, null, 0, null, null);
                }
                throw new ProviderException(r.getSubCode(), "Alipay: " + r.getSubMsg());
            }
            TradeSnapshot.TradeState state = switch (r.getTradeStatus()) {
                case "TRADE_SUCCESS", "TRADE_FINISHED" -> TradeSnapshot.TradeState.PAID;
                case "TRADE_CLOSED" -> r.getSendPayDate() != null
                        ? TradeSnapshot.TradeState.REFUNDED : TradeSnapshot.TradeState.CLOSED;
                default -> TradeSnapshot.TradeState.NOT_PAID;
            };
            return new TradeSnapshot(outTradeNo, state, r.getTradeNo(), Money.parseYuan(r.getTotalAmount()),
                    r.getBuyerLogonId(), toLocal(r.getSendPayDate()));
        } catch (AlipayApiException e) {
            throw translate(e);
        }
    }

    @Override
    public void close(String outTradeNo) {
        AlipayTradeCloseModel model = new AlipayTradeCloseModel();
        model.setOutTradeNo(outTradeNo);
        AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
        request.setBizModel(model);
        try {
            var r = clients.get().execute(request);
            // An unpaid QR trade that was never scanned does not exist on Alipay's side yet; nothing to close.
            if (!r.isSuccess() && !"ACQ.TRADE_NOT_EXIST".equals(r.getSubCode())) {
                throw new ProviderException(r.getSubCode(), "Alipay: " + r.getSubMsg());
            }
        } catch (AlipayApiException e) {
            throw translate(e);
        }
    }

    @Override
    public RefundOutcome refund(Order order, Refund refund) {
        AlipayTradeRefundModel model = new AlipayTradeRefundModel();
        model.setOutTradeNo(order.outTradeNo());
        model.setRefundAmount(Money.toYuan(refund.amountFen()));
        model.setOutRequestNo(refund.outRefundNo());
        model.setRefundReason(refund.reason());
        AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
        request.setBizModel(model);
        try {
            AlipayTradeRefundResponse r = ok(clients.get().execute(request));
            // fund_change=N on a retried out_request_no means "already refunded" — still a success.
            return new RefundOutcome(RefundOutcome.Status.SUCCESS, r.getTradeNo() + "#" + refund.outRefundNo());
        } catch (AlipayApiException e) {
            throw translate(e);
        }
    }

    @Override
    public List<BillLine> downloadBill(LocalDate billDate) {
        AlipayDataDataserviceBillDownloadurlQueryModel model = new AlipayDataDataserviceBillDownloadurlQueryModel();
        model.setBillType("trade");
        model.setBillDate(billDate.toString());
        AlipayDataDataserviceBillDownloadurlQueryRequest request = new AlipayDataDataserviceBillDownloadurlQueryRequest();
        request.setBizModel(model);
        try {
            String url = ok(clients.get().execute(request)).getBillDownloadUrl();
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new ProviderException("BILL_DOWNLOAD", "Alipay bill download returned HTTP " + response.statusCode());
            }
            return AlipayBillParser.parseZip(response.body());
        } catch (AlipayApiException e) {
            throw translate(e);
        } catch (IOException e) {
            throw new ProviderException("BILL_DOWNLOAD", "Alipay bill download failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("BILL_DOWNLOAD", "Interrupted", e);
        }
    }

    private static <T extends AlipayResponse> T ok(T response) {
        if (!response.isSuccess()) {
            throw new ProviderException(response.getSubCode(), "Alipay: " + response.getSubMsg());
        }
        return response;
    }

    /** The SDK parses Alipay's Beijing wall-clock timestamps in the JVM zone; undo that the same way. */
    private static LocalDateTime toLocal(Date date) {
        return date == null ? null : LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    private static ProviderException translate(AlipayApiException e) {
        String code = e.getErrCode() == null ? "ALIPAY_CLIENT" : e.getErrCode();
        return new ProviderException(code, "Alipay: " + e.getMessage(), e);
    }
}
