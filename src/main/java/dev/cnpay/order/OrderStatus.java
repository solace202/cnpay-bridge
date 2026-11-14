package dev.cnpay.order;

public enum OrderStatus {
    PENDING, PAID, PARTIALLY_REFUNDED, REFUNDED, CLOSED;

    public boolean paid() {
        return this == PAID || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }
}
