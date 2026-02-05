-- Ticker 분 집계 테이블
CREATE TABLE ticker_minute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange VARCHAR(20) NOT NULL COMMENT '거래소 (bithumb, binance)',
    symbol VARCHAR(10) NOT NULL,
    minute_at DATETIME NOT NULL COMMENT '분 정각 시각',
    high DECIMAL(20,2) NOT NULL COMMENT '최고가',
    low DECIMAL(20,2) NOT NULL COMMENT '최저가',
    open DECIMAL(20,2) NOT NULL COMMENT '시가',
    close DECIMAL(20,2) NOT NULL COMMENT '종가',
    avg DECIMAL(20,4) NOT NULL COMMENT '평균가',
    count INT NOT NULL COMMENT '데이터 수',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_exchange_symbol_minute (exchange, symbol, minute_at),
    INDEX idx_minute_at (minute_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ticker 시간 집계 테이블
CREATE TABLE ticker_hour (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange VARCHAR(20) NOT NULL COMMENT '거래소 (bithumb, binance)',
    symbol VARCHAR(10) NOT NULL,
    hour_at DATETIME NOT NULL COMMENT '시간 정각 시각',
    high DECIMAL(20,2) NOT NULL COMMENT '최고가',
    low DECIMAL(20,2) NOT NULL COMMENT '최저가',
    open DECIMAL(20,2) NOT NULL COMMENT '시가',
    close DECIMAL(20,2) NOT NULL COMMENT '종가',
    avg DECIMAL(20,4) NOT NULL COMMENT '평균가',
    count INT NOT NULL COMMENT '데이터 수',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_exchange_symbol_hour (exchange, symbol, hour_at),
    INDEX idx_hour_at (hour_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ticker 일 집계 테이블
CREATE TABLE ticker_day (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange VARCHAR(20) NOT NULL COMMENT '거래소 (bithumb, binance)',
    symbol VARCHAR(10) NOT NULL,
    day_at DATE NOT NULL COMMENT '일자',
    high DECIMAL(20,2) NOT NULL COMMENT '최고가',
    low DECIMAL(20,2) NOT NULL COMMENT '최저가',
    open DECIMAL(20,2) NOT NULL COMMENT '시가',
    close DECIMAL(20,2) NOT NULL COMMENT '종가',
    avg DECIMAL(20,4) NOT NULL COMMENT '평균가',
    count INT NOT NULL COMMENT '데이터 수',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_exchange_symbol_day (exchange, symbol, day_at),
    INDEX idx_day_at (day_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 환율 스냅샷 테이블 (30분 단위)
CREATE TABLE exchange_rate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    base_currency VARCHAR(10) NOT NULL COMMENT '기준 통화 (USD)',
    quote_currency VARCHAR(10) NOT NULL COMMENT '대상 통화 (KRW)',
    rate DECIMAL(10,4) NOT NULL COMMENT '환율',
    observed_at DATETIME NOT NULL COMMENT '수집 시각',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_currency_observed (base_currency, quote_currency, observed_at),
    INDEX idx_observed_at (observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
