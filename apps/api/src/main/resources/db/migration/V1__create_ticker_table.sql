-- Ticker table: stores price snapshots from exchanges
CREATE TABLE ticker (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange VARCHAR(50) NOT NULL,
    exchange_region VARCHAR(20) NOT NULL,
    base_code VARCHAR(20) NOT NULL,
    base_type VARCHAR(20) NOT NULL,
    quote_currency VARCHAR(10) NOT NULL,
    price DECIMAL(30, 10) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,

    INDEX idx_ticker_lookup (exchange, base_code, quote_currency, observed_at DESC),
    INDEX idx_ticker_observed (observed_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
