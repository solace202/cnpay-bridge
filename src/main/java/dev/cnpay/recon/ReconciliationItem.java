package dev.cnpay.recon;

public record ReconciliationItem(
        long id,
        String reconciliationId,
        String outTradeNo,
        Difference.Kind kind,
        String localStatus,
        Long localFen,
        String remoteStatus,
        Long remoteFen,
        String action) {
}
