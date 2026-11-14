-- Ledger of the built-in sandbox that plays WeChat Pay and Alipay. Used to answer queries and produce daily bills.
create table sandbox_trades (
    provider        varchar(16)  not null,
    out_trade_no    varchar(64)  not null,
    trade_no        varchar(64)  not null,
    app_id          varchar(32)  not null,
    mch_id          varchar(32),
    trade_type      varchar(24)  not null,
    description     varchar(127),
    amount_fen      bigint       not null,
    refunded_fen    bigint       not null default 0,
    state           varchar(16)  not null,             -- NOTPAY, SUCCESS, CLOSED, REFUND
    notify_url      varchar(255),
    return_url      varchar(255),
    payer           varchar(128),
    expires_at      datetime(3),
    paid_at         datetime(3),
    created_at      datetime(3)  not null default current_timestamp(3),
    primary key (provider, out_trade_no)
) engine = InnoDB default charset = utf8mb4;

create table sandbox_refunds (
    provider        varchar(16)  not null,
    out_refund_no   varchar(64)  not null,
    refund_id       varchar(64)  not null,
    out_trade_no    varchar(64)  not null,
    amount_fen      bigint       not null,
    status          varchar(16)  not null,
    notify_url      varchar(255),
    created_at      datetime(3)  not null default current_timestamp(3),
    succeeded_at    datetime(3),
    primary key (provider, out_refund_no)
) engine = InnoDB default charset = utf8mb4;
