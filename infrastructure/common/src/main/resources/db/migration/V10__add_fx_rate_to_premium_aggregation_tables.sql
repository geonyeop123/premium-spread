-- 프리미엄 집계 테이블에 환율(close) 컬럼 추가
ALTER TABLE premium_minute ADD COLUMN fx_rate DECIMAL(10,4) NULL COMMENT '종료 시점 환율 (USD/KRW)';
ALTER TABLE premium_hour ADD COLUMN fx_rate DECIMAL(10,4) NULL COMMENT '종료 시점 환율 (USD/KRW)';
ALTER TABLE premium_day ADD COLUMN fx_rate DECIMAL(10,4) NULL COMMENT '종료 시점 환율 (USD/KRW)';
