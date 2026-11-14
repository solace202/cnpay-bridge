-- CnPay Bridge schema (MySQL 8). Amounts are stored as BIGINT fen (1 CNY = 100 fen), never as floating point.

create table orders (
    id                 char(36)     not null primary key,
    merchant_order_no  varchar(64)  not null,
    out_trade_no       varchar(32)  not null,          -- sent to WeChat/Alipay (6-32 chars)
    provider           varchar(16)  not null,          -- WECHAT | ALIPAY
    channel            varchar(24)  not null,          -- WECHAT_NATIVE, WECHAT_JSAPI, WECHAT_H5, ALIPAY_QR, ALIPAY_PAGE, ALIPAY_WAP
    description        varchar(127) not null,
    amount_fen         bigint       not null,
    refunded_fen       bigint       not null default 0,
    currency           char(3)      not null default 'CNY',
    status             varchar(24)  not null,          -- PENDING, PAID, PARTIALLY_REFUNDED, REFUNDED, CLOSED
    payer_openid       varchar(128),
    client_ip          varchar(45),
    provider_trade_no  varchar(64),
    payer_account      varchar(128),
    pay_payload        json,                           -- code_url / prepay params / h5_url / form, as returned to the client
    expires_at         datetime(3)  not null,
    paid_at            datetime(3),
    closed_at          datetime(3),
    created_at         datetime(3)  not null default current_timestamp(3),
    updated_at         datetime(3)  not null default current_timestamp(3) on update current_timestamp(3),
    unique key uk_orders_merchant_order (merchant_order_no),
    unique key uk_orders_out_trade_no (out_trade_no),
    key idx_orders_status_expiry (status, expires_at),
    key idx_orders_paid_at (provider, paid_at)
) engine = InnoDB default charset = utf8mb4;

create table refunds (
    id                 char(36)     not null primary key,
    order_id           char(36)     not null,
    out_refund_no      varchar(64)  not null,
    amount_fen         bigint       not null,
    reason             varchar(80),
    status             varchar(16)  not null,          -- PROCESSING, SUCCESS, FAILED
    provider_refund_no varchar(64),
    created_at         datetime(3)  not null default current_timestamp(3),
    succeeded_at       datetime(3),
    unique key uk_refunds_out_refund_no (out_refund_no),
    key idx_refunds_order (order_id),
    constraint fk_refunds_order foreign key (order_id) references orders (id)
) engine = InnoDB default charset = utf8mb4;

-- Every asynchronous notification received, verified or not. (provider, notify_id) makes redeliveries harmless.
create table payment_events (
    id              bigint       not null auto_increment primary key,
    provider        varchar(16)  not null,
    notify_id       varchar(64)  not null,
    event_type      varchar(48)  not null,
    out_trade_no    varchar(32),
    signature_valid boolean      not null,
    result          varchar(24)  not null,             -- APPLIED, DUPLICATE, REJECTED, IGNORED
    detail          varchar(500),
    payload         json,                              -- decrypted resource / form params (never card data)
    deliveries      int          not null default 1,
    received_at     datetime(3)  not null default current_timestamp(3),
    unique key uk_events_notify (provider, notify_id),
    key idx_events_trade (out_trade_no)
) engine = InnoDB default charset = utf8mb4;

-- Idempotency-Key for POST /api/orders and refunds: same key + same body replays the first response.
create table idempotency_keys (
    idem_key      varchar(80)  not null,
    scope         varchar(32)  not null,
    request_hash  char(64)     not null,
    response_code int,
    response_body json,
    created_at    datetime(3)  not null default current_timestamp(3),
    primary key (scope, idem_key)
) engine = InnoDB default charset = utf8mb4;

create table reconciliations (
    id             char(36)     not null primary key,
    provider       varchar(16)  not null,
    bill_date      date         not null,
    status         varchar(16)  not null,              -- MATCHED, DIFFERENCES
    remote_count   int          not null,
    remote_fen     bigint       not null,
    local_count    int          not null,
    local_fen      bigint       not null,
    matched        int          not null,
    differences    int          not null,
    repaired       int          not null default 0,
    created_at     datetime(3)  not null default current_timestamp(3),
    key idx_recon_date (provider, bill_date)
) engine = InnoDB default charset = utf8mb4;

create table reconciliation_items (
    id                bigint       not null auto_increment primary key,
    reconciliation_id char(36)     not null,
    out_trade_no      varchar(64)  not null,
    kind              varchar(24)  not null,           -- MISSING_LOCAL, MISSING_REMOTE, STATUS_MISMATCH, AMOUNT_MISMATCH, REFUND_MISMATCH
    local_status      varchar(24),
    local_fen         bigint,
    remote_status     varchar(24),
    remote_fen        bigint,
    action            varchar(120),
    key idx_items_recon (reconciliation_id),
    constraint fk_items_recon foreign key (reconciliation_id) references reconciliations (id)
) engine = InnoDB default charset = utf8mb4;
