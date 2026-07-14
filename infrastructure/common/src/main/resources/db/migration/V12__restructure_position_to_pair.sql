-- V12: Position 도메인을 한국 long + 해외 short 페어 모델로 재구조화
-- 기존 단일 거래소 컬럼을 한국 측 컬럼으로 rename 하고 해외 측 컬럼을 신규 추가한다.
-- 페어 정보를 채울 수 없으므로 기존 행은 TRUNCATE.

TRUNCATE TABLE position;

ALTER TABLE position
    CHANGE COLUMN exchange      korea_exchange      VARCHAR(50)     NOT NULL,
    CHANGE COLUMN quantity      korea_quantity      DECIMAL(30, 10) NOT NULL,
    CHANGE COLUMN entry_price   korea_entry_price   DECIMAL(30, 10) NOT NULL,
    ADD COLUMN    foreign_exchange     VARCHAR(50)     NOT NULL AFTER korea_entry_price,
    ADD COLUMN    foreign_quantity     DECIMAL(30, 10) NOT NULL AFTER foreign_exchange,
    ADD COLUMN    foreign_entry_price  DECIMAL(30, 10) NOT NULL AFTER foreign_quantity,
    ADD COLUMN    foreign_leverage     INT             NOT NULL DEFAULT 1 AFTER foreign_entry_price;
