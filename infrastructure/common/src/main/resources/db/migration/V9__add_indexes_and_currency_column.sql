-- V8: 성능 인덱스 추가 + 집계 테이블 currency 컬럼 추가

-- =============================================================
-- 1. 집계 테이블에 currency 컬럼 추가
-- =============================================================

-- ticker_minute: currency 컬럼 추가 (exchange 기준 기본값 설정 후 NOT NULL)
ALTER TABLE ticker_minute
    ADD COLUMN currency VARCHAR(10) NULL COMMENT '통화 (KRW, USD)' AFTER symbol;

UPDATE ticker_minute SET currency = 'KRW' WHERE exchange IN ('UPBIT', 'BITHUMB');
UPDATE ticker_minute SET currency = 'USD' WHERE exchange IN ('BINANCE');
UPDATE ticker_minute SET currency = 'KRW' WHERE exchange = 'FX_PROVIDER';
UPDATE ticker_minute SET currency = 'KRW' WHERE currency IS NULL;

ALTER TABLE ticker_minute MODIFY COLUMN currency VARCHAR(10) NOT NULL COMMENT '통화 (KRW, USD)';

-- ticker_hour: currency 컬럼 추가
ALTER TABLE ticker_hour
    ADD COLUMN currency VARCHAR(10) NULL COMMENT '통화 (KRW, USD)' AFTER symbol;

UPDATE ticker_hour SET currency = 'KRW' WHERE exchange IN ('UPBIT', 'BITHUMB');
UPDATE ticker_hour SET currency = 'USD' WHERE exchange IN ('BINANCE');
UPDATE ticker_hour SET currency = 'KRW' WHERE exchange = 'FX_PROVIDER';
UPDATE ticker_hour SET currency = 'KRW' WHERE currency IS NULL;

ALTER TABLE ticker_hour MODIFY COLUMN currency VARCHAR(10) NOT NULL COMMENT '통화 (KRW, USD)';

-- ticker_day: currency 컬럼 추가
ALTER TABLE ticker_day
    ADD COLUMN currency VARCHAR(10) NULL COMMENT '통화 (KRW, USD)' AFTER symbol;

UPDATE ticker_day SET currency = 'KRW' WHERE exchange IN ('UPBIT', 'BITHUMB');
UPDATE ticker_day SET currency = 'USD' WHERE exchange IN ('BINANCE');
UPDATE ticker_day SET currency = 'KRW' WHERE exchange = 'FX_PROVIDER';
UPDATE ticker_day SET currency = 'KRW' WHERE currency IS NULL;

ALTER TABLE ticker_day MODIFY COLUMN currency VARCHAR(10) NOT NULL COMMENT '통화 (KRW, USD)';

-- =============================================================
-- 2. 성능 인덱스 추가 (기존 인덱스와 중복 없는 것만)
-- =============================================================

-- ticker: symbol+observed_at 커버링 인덱스 (premium JOIN용)
CREATE INDEX idx_ticker_id_price_observed ON ticker (id, price, observed_at);

-- premium: ticker FK 조인용 인덱스 (N+1 → JOIN 쿼리 최적화)
CREATE INDEX idx_premium_symbol_observed_desc ON premium (symbol, observed_at DESC, korea_ticker_id, foreign_ticker_id, fx_ticker_id);

-- ticker_minute: exchange+symbol+minute_at 복합 인덱스에 currency 포함
CREATE INDEX idx_ticker_minute_lookup ON ticker_minute (exchange, symbol, currency, minute_at DESC);

-- ticker_hour: exchange+symbol+hour_at 복합 인덱스에 currency 포함
CREATE INDEX idx_ticker_hour_lookup ON ticker_hour (exchange, symbol, currency, hour_at DESC);

-- ticker_day: exchange+symbol+day_at 복합 인덱스에 currency 포함
CREATE INDEX idx_ticker_day_lookup ON ticker_day (exchange, symbol, currency, day_at DESC);
