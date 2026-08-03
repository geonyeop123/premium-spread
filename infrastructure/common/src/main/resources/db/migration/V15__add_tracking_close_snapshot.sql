-- V15: 추적 종료 시점의 시세를 확정 저장한다.
-- ALTER 한 문장만 수행한다 — DDL 과 DML 을 섞으면 중단 시 부분 적용 상태가 남는다.
-- 기존 컬럼의 값을 재작성하지 않는다 — 이전 application image 롤백 호환을 유지해야 한다
-- (docs/runbooks/deployment.md "Rollback 제약", design.md D4).
ALTER TABLE position
    ADD COLUMN closed_at           DATETIME(6)     NULL AFTER status,
    ADD COLUMN close_price_source  VARCHAR(30)     NULL AFTER closed_at,
    ADD COLUMN close_observed_at   DATETIME(6)     NULL AFTER close_price_source,
    ADD COLUMN close_fx_observed_at DATETIME(6)     NULL AFTER close_observed_at,
    ADD COLUMN close_korea_price   DECIMAL(30, 10) NULL AFTER close_fx_observed_at,
    ADD COLUMN close_foreign_price DECIMAL(30, 10) NULL AFTER close_korea_price,
    ADD COLUMN close_fx_rate       DECIMAL(20, 6)  NULL AFTER close_foreign_price,
    ADD COLUMN close_premium_rate  DECIMAL(10, 2)  NULL AFTER close_fx_rate;
