-- 기준 포지션 단계 알림(#131) 상태 — 기준 leg 4개와 국면·단계.
-- 기존 열의 의미는 바꾸지 않는다.
-- 새 열은 모두 NULL 허용이라 이 migration 이전 binary 의 INSERT·UPDATE(새 열 미포함)가 그대로 동작하고 새 열 값을 보존한다.
-- 수량·가격은 소수 자릿수 손실 없이 비교하려고 정규화한 10진 문자열로 저장한다.
ALTER TABLE account_pnl_monitor_state
    ADD COLUMN reference_korea_quantity   VARCHAR(64) NULL,
    ADD COLUMN reference_korea_price      VARCHAR(64) NULL,
    ADD COLUMN reference_foreign_quantity VARCHAR(64) NULL,
    ADD COLUMN reference_foreign_price    VARCHAR(64) NULL,
    ADD COLUMN reference_step_phase       VARCHAR(8)  NULL,
    ADD COLUMN reference_step_level       INT         NULL,
    ADD CONSTRAINT ck_account_pnl_monitor_state_reference_step CHECK (
        (reference_korea_quantity IS NULL AND reference_korea_price IS NULL
            AND reference_foreign_quantity IS NULL AND reference_foreign_price IS NULL
            AND reference_step_phase IS NULL AND reference_step_level IS NULL)
        OR (reference_korea_quantity IS NOT NULL AND reference_korea_price IS NOT NULL
            AND reference_foreign_quantity IS NOT NULL AND reference_foreign_price IS NOT NULL
            AND reference_step_phase IN ('HOLDING', 'FLAT') AND reference_step_level IS NOT NULL)
    );
