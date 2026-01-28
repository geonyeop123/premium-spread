-- Premium table: stores calculated premium rates
CREATE TABLE premium (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    korea_ticker_id BIGINT NOT NULL,
    foreign_ticker_id BIGINT NOT NULL,
    fx_ticker_id BIGINT NOT NULL,
    premium_rate DECIMAL(10, 2) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,

    INDEX idx_premium_symbol (symbol, observed_at DESC),
    INDEX idx_premium_observed (observed_at DESC),

    CONSTRAINT fk_premium_korea_ticker FOREIGN KEY (korea_ticker_id) REFERENCES ticker(id),
    CONSTRAINT fk_premium_foreign_ticker FOREIGN KEY (foreign_ticker_id) REFERENCES ticker(id),
    CONSTRAINT fk_premium_fx_ticker FOREIGN KEY (fx_ticker_id) REFERENCES ticker(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
