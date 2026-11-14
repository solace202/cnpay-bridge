package dev.cnpay.order;

import dev.cnpay.provider.Channel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @Schema(example = "SHOP-10001", description = "Your order number; unique, at most one payment order each")
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_\\-]+") String merchantOrderNo,
        @Schema(example = "WECHAT_NATIVE") @NotNull Channel channel,
        @Schema(example = "88.00", description = "CNY, at most two decimals") @NotBlank String amount,
        @Schema(example = "Matcha starter kit") @NotBlank @Size(max = 127) String description,
        @Schema(description = "Required for WECHAT_JSAPI: the payer's openid under your appid") String payerOpenid,
        @Schema(description = "Shopper IP, required by WECHAT_H5 (defaults to the caller's address)") String clientIp,
        @Schema(example = "30", description = "Minutes until the order expires (default 30)") @Min(1) @Max(1440) Integer expiresInMinutes) {
}
