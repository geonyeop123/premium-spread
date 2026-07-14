-- Premium snapshot table: stores premium data without FK dependencies
-- Used by batch server for storing calculated premiums
CREATE TABLE premium_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    premium_rate DECIMAL(10, 4) NOT NULL,
    korea_price DECIMAL(20, 8) NOT NULL,
    foreign_price DECIMAL(20, 8) NOT NULL,
    foreign_price_krw DECIMAL(20, 8) NOT NULL,
    fx_rate DECIMAL(15, 4) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,

    INDEX idx_snapshot_symbol_observed (symbol, observed_at DESC),
    INDEX idx_snapshot_observed (observed_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
