package dev.cnpay.recon;

import dev.cnpay.provider.Provider;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record Reconciliation(
        String id,
        Provider provider,
        LocalDate billDate,
        String status,
        int remoteCount,
        long remoteFen,
        int localCount,
        long localFen,
        int matched,
        int differences,
        int repaired,
        LocalDateTime createdAt) {
}
