-- Batch 모듈 전용 테이블 (JPA Entity 없이 JdbcTemplate으로 사용)

CREATE TABLE IF NOT EXISTS member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    UNIQUE INDEX uk_member_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS notification_subscription (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    korea_exchange VARCHAR(50) NOT NULL,
    foreign_exchange VARCHAR(50) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    lock_version BIGINT NOT NULL DEFAULT 0,
    direction VARCHAR(10) NOT NULL,
    threshold DECIMAL(10, 4) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    INDEX idx_notification_subscription_active_pair_direction (
        status, symbol, korea_exchange, foreign_exchange, direction
    ),
    INDEX idx_notification_subscription_member_id (member_id),
    CONSTRAINT fk_notification_subscription_member FOREIGN KEY (member_id) REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS notification_delivery (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    delivery_id CHAR(36) NOT NULL,
    subscription_id BIGINT NOT NULL,
    event_key VARCHAR(512) NOT NULL,
    recipient_email VARCHAR(320) NULL,
    subject VARCHAR(500) NULL,
    payload TEXT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    locked_at DATETIME(6) NULL,
    locked_by VARCHAR(100) NULL,
    claim_token CHAR(36) NULL,
    sent_at DATETIME(6) NULL,
    last_error VARCHAR(1000) NULL,
    scrubbed_at DATETIME(6) NULL,
    redrive_actor VARCHAR(100) NULL,
    redrive_reason VARCHAR(500) NULL,
    redriven_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE INDEX uk_notification_delivery_delivery_id (delivery_id),
    UNIQUE INDEX uk_notification_delivery_event_key (event_key),
    INDEX idx_notification_delivery_ready (status, next_attempt_at, id),
    INDEX idx_notification_delivery_stale (status, locked_at, id),
    INDEX idx_notification_delivery_sent_scrub (status, sent_at, scrubbed_at, id),
    INDEX idx_notification_delivery_subscription (subscription_id, created_at),
    CONSTRAINT fk_notification_delivery_subscription
        FOREIGN KEY (subscription_id) REFERENCES notification_subscription(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS exchange_rate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    base_currency VARCHAR(10) NOT NULL,
    quote_currency VARCHAR(10) NOT NULL,
    rate DECIMAL(10,4) NOT NULL,
    observed_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_currency_observed (base_currency, quote_currency, observed_at),
    INDEX idx_observed_at (observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ticker_minute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange VARCHAR(20) NOT NULL,
    symbol VARCHAR(10) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    minute_at DATETIME NOT NULL,
    high DECIMAL(20,2) NOT NULL,
    low DECIMAL(20,2) NOT NULL,
    open DECIMAL(20,2) NOT NULL,
    close DECIMAL(20,2) NOT NULL,
    avg DECIMAL(20,4) NOT NULL,
    count INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_exchange_symbol_minute (exchange, symbol, minute_at),
    INDEX idx_minute_at (minute_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ticker_hour (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange VARCHAR(20) NOT NULL,
    symbol VARCHAR(10) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    hour_at DATETIME NOT NULL,
    high DECIMAL(20,2) NOT NULL,
    low DECIMAL(20,2) NOT NULL,
    open DECIMAL(20,2) NOT NULL,
    close DECIMAL(20,2) NOT NULL,
    avg DECIMAL(20,4) NOT NULL,
    count INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_exchange_symbol_hour (exchange, symbol, hour_at),
    INDEX idx_hour_at (hour_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ticker_day (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange VARCHAR(20) NOT NULL,
    symbol VARCHAR(10) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    day_at DATE NOT NULL,
    high DECIMAL(20,2) NOT NULL,
    low DECIMAL(20,2) NOT NULL,
    open DECIMAL(20,2) NOT NULL,
    close DECIMAL(20,2) NOT NULL,
    avg DECIMAL(20,4) NOT NULL,
    count INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_exchange_symbol_day (exchange, symbol, day_at),
    INDEX idx_day_at (day_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS premium_minute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL,
    korea_exchange VARCHAR(50) NOT NULL,
    foreign_exchange VARCHAR(50) NOT NULL,
    minute_at DATETIME NOT NULL,
    high DECIMAL(10,4) NOT NULL,
    low DECIMAL(10,4) NOT NULL,
    open DECIMAL(10,4) NOT NULL,
    close DECIMAL(10,4) NOT NULL,
    avg DECIMAL(10,4) NOT NULL,
    count INT NOT NULL,
    fx_rate DECIMAL(10,4) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pair_symbol_minute (korea_exchange, foreign_exchange, symbol, minute_at),
    INDEX idx_minute_at (minute_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS premium_hour (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL,
    korea_exchange VARCHAR(50) NOT NULL,
    foreign_exchange VARCHAR(50) NOT NULL,
    hour_at DATETIME NOT NULL,
    high DECIMAL(10,4) NOT NULL,
    low DECIMAL(10,4) NOT NULL,
    open DECIMAL(10,4) NOT NULL,
    close DECIMAL(10,4) NOT NULL,
    avg DECIMAL(10,4) NOT NULL,
    count INT NOT NULL,
    fx_rate DECIMAL(10,4) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pair_symbol_hour (korea_exchange, foreign_exchange, symbol, hour_at),
    INDEX idx_hour_at (hour_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS premium_day (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL,
    korea_exchange VARCHAR(50) NOT NULL,
    foreign_exchange VARCHAR(50) NOT NULL,
    day_at DATE NOT NULL,
    high DECIMAL(10,4) NOT NULL,
    low DECIMAL(10,4) NOT NULL,
    open DECIMAL(10,4) NOT NULL,
    close DECIMAL(10,4) NOT NULL,
    avg DECIMAL(10,4) NOT NULL,
    count INT NOT NULL,
    fx_rate DECIMAL(10,4) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pair_symbol_day (korea_exchange, foreign_exchange, symbol, day_at),
    INDEX idx_day_at (day_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
