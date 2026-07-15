-- Position table: stores trading positions
CREATE TABLE position (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    exchange VARCHAR(50) NOT NULL,
    quantity DECIMAL(30, 10) NOT NULL,
    entry_price DECIMAL(30, 10) NOT NULL,
    entry_fx_rate DECIMAL(20, 6) NOT NULL,
    entry_premium_rate DECIMAL(10, 2) NOT NULL,
    entry_observed_at DATETIME(6) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,

    INDEX idx_position_status (status),
    INDEX idx_position_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
