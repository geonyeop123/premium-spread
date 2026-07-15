-- 알림 구독을 canonical MarketPair와 revision 기반 event identity로 확장한다.
ALTER TABLE notification_subscription
    ADD COLUMN korea_exchange VARCHAR(50) NULL AFTER symbol,
    ADD COLUMN foreign_exchange VARCHAR(50) NULL AFTER korea_exchange,
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 AFTER foreign_exchange,
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0 AFTER revision;

UPDATE notification_subscription
SET korea_exchange = 'BITHUMB',
    foreign_exchange = 'BINANCE'
WHERE korea_exchange IS NULL
   OR foreign_exchange IS NULL;

ALTER TABLE notification_subscription
    MODIFY COLUMN korea_exchange VARCHAR(50) NOT NULL,
    MODIFY COLUMN foreign_exchange VARCHAR(50) NOT NULL,
    DROP INDEX idx_notification_subscription_status_symbol,
    ADD INDEX idx_notification_subscription_active_pair_direction (
        status,
        symbol,
        korea_exchange,
        foreign_exchange,
        direction
    );

-- SMTP 호출과 분리해 재시도와 장애 복구가 가능한 durable delivery queue다.
CREATE TABLE notification_delivery (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    delivery_id        CHAR(36)     NOT NULL,
    subscription_id    BIGINT       NOT NULL,
    event_key           VARCHAR(512) NOT NULL,
    recipient_email     VARCHAR(320) NULL,
    subject             VARCHAR(500) NULL,
    payload             TEXT         NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempt_count       INT          NOT NULL DEFAULT 0,
    next_attempt_at     DATETIME(6)  NOT NULL,
    locked_at           DATETIME(6)  NULL,
    locked_by           VARCHAR(100) NULL,
    claim_token         CHAR(36)     NULL,
    sent_at             DATETIME(6)  NULL,
    last_error          VARCHAR(1000) NULL,
    scrubbed_at         DATETIME(6)  NULL,
    redrive_actor       VARCHAR(100) NULL,
    redrive_reason      VARCHAR(500) NULL,
    redriven_at         DATETIME(6)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    CONSTRAINT uk_notification_delivery_delivery_id UNIQUE (delivery_id),
    CONSTRAINT uk_notification_delivery_event_key UNIQUE (event_key),
    INDEX idx_notification_delivery_ready (status, next_attempt_at, id),
    INDEX idx_notification_delivery_stale (status, locked_at, id),
    INDEX idx_notification_delivery_sent_scrub (status, sent_at, scrubbed_at, id),
    INDEX idx_notification_delivery_subscription (subscription_id, created_at),
    CONSTRAINT fk_notification_delivery_subscription
        FOREIGN KEY (subscription_id) REFERENCES notification_subscription(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
