-- BaseEntity와 컨벤션 일치: DATETIME(6), deleted_at (soft delete), utf8mb4
CREATE TABLE notification_subscription (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id  BIGINT         NOT NULL,
    symbol     VARCHAR(20)    NOT NULL,
    direction  VARCHAR(10)    NOT NULL,   -- ABOVE | BELOW
    threshold  DECIMAL(10, 4) NOT NULL,   -- 단위: %
    status     VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6)    NOT NULL,
    updated_at DATETIME(6)    NOT NULL,
    deleted_at DATETIME(6)    NULL,
    INDEX idx_notification_subscription_status_symbol (status, symbol),
    INDEX idx_notification_subscription_member_id (member_id),
    CONSTRAINT fk_notification_subscription_member FOREIGN KEY (member_id) REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
