-- Premium 저장 식별자를 symbol 단독에서 canonical MarketPair로 확장한다.
-- 기존 timestamp는 Phase 0 audit 결정에 따라 변환하지 않고 UTC 의미를 그대로 유지한다.

ALTER TABLE premium_snapshot
    ADD COLUMN korea_exchange VARCHAR(50) NULL AFTER symbol,
    ADD COLUMN foreign_exchange VARCHAR(50) NULL AFTER korea_exchange;

UPDATE premium_snapshot
SET korea_exchange = 'BITHUMB',
    foreign_exchange = 'BINANCE'
WHERE korea_exchange IS NULL
   OR foreign_exchange IS NULL;

ALTER TABLE premium_snapshot
    MODIFY COLUMN korea_exchange VARCHAR(50) NOT NULL,
    MODIFY COLUMN foreign_exchange VARCHAR(50) NOT NULL,
    DROP INDEX idx_snapshot_symbol_observed,
    ADD INDEX idx_snapshot_pair_symbol_observed (
        korea_exchange,
        foreign_exchange,
        symbol,
        observed_at DESC
    );

ALTER TABLE premium_minute
    ADD COLUMN korea_exchange VARCHAR(50) NULL AFTER symbol,
    ADD COLUMN foreign_exchange VARCHAR(50) NULL AFTER korea_exchange;

UPDATE premium_minute
SET korea_exchange = 'BITHUMB',
    foreign_exchange = 'BINANCE'
WHERE korea_exchange IS NULL
   OR foreign_exchange IS NULL;

ALTER TABLE premium_minute
    MODIFY COLUMN korea_exchange VARCHAR(50) NOT NULL,
    MODIFY COLUMN foreign_exchange VARCHAR(50) NOT NULL,
    DROP INDEX uk_symbol_minute,
    ADD UNIQUE KEY uk_pair_symbol_minute (
        korea_exchange,
        foreign_exchange,
        symbol,
        minute_at
    );

ALTER TABLE premium_hour
    ADD COLUMN korea_exchange VARCHAR(50) NULL AFTER symbol,
    ADD COLUMN foreign_exchange VARCHAR(50) NULL AFTER korea_exchange;

UPDATE premium_hour
SET korea_exchange = 'BITHUMB',
    foreign_exchange = 'BINANCE'
WHERE korea_exchange IS NULL
   OR foreign_exchange IS NULL;

ALTER TABLE premium_hour
    MODIFY COLUMN korea_exchange VARCHAR(50) NOT NULL,
    MODIFY COLUMN foreign_exchange VARCHAR(50) NOT NULL,
    DROP INDEX uk_symbol_hour,
    ADD UNIQUE KEY uk_pair_symbol_hour (
        korea_exchange,
        foreign_exchange,
        symbol,
        hour_at
    );

ALTER TABLE premium_day
    ADD COLUMN korea_exchange VARCHAR(50) NULL AFTER symbol,
    ADD COLUMN foreign_exchange VARCHAR(50) NULL AFTER korea_exchange;

UPDATE premium_day
SET korea_exchange = 'BITHUMB',
    foreign_exchange = 'BINANCE'
WHERE korea_exchange IS NULL
   OR foreign_exchange IS NULL;

ALTER TABLE premium_day
    MODIFY COLUMN korea_exchange VARCHAR(50) NOT NULL,
    MODIFY COLUMN foreign_exchange VARCHAR(50) NOT NULL,
    DROP INDEX uk_symbol_day,
    ADD UNIQUE KEY uk_pair_symbol_day (
        korea_exchange,
        foreign_exchange,
        symbol,
        day_at
    );
