package dev.cnpay.refund;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRefundRequest(
        @Schema(example = "20.00", description = "CNY; partial refunds are allowed until the order is fully refunded")
        @NotBlank String amount,
        @Schema(example = "Item out of stock") @Size(max = 80) String reason) {
}
