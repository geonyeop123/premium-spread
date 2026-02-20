-- Batch 모듈 전용 테이블 (JPA Entity 없이 JdbcTemplate으로 사용)

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
    minute_at DATETIME NOT NULL,
    high DECIMAL(10,4) NOT NULL,
    low DECIMAL(10,4) NOT NULL,
    open DECIMAL(10,4) NOT NULL,
    close DECIMAL(10,4) NOT NULL,
    avg DECIMAL(10,4) NOT NULL,
    count INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol_minute (symbol, minute_at),
    INDEX idx_minute_at (minute_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS premium_hour (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL,
    hour_at DATETIME NOT NULL,
    high DECIMAL(10,4) NOT NULL,
    low DECIMAL(10,4) NOT NULL,
    open DECIMAL(10,4) NOT NULL,
    close DECIMAL(10,4) NOT NULL,
    avg DECIMAL(10,4) NOT NULL,
    count INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol_hour (symbol, hour_at),
    INDEX idx_hour_at (hour_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS premium_day (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL,
    day_at DATE NOT NULL,
    high DECIMAL(10,4) NOT NULL,
    low DECIMAL(10,4) NOT NULL,
    open DECIMAL(10,4) NOT NULL,
    close DECIMAL(10,4) NOT NULL,
    avg DECIMAL(10,4) NOT NULL,
    count INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol_day (symbol, day_at),
    INDEX idx_day_at (day_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
