-- owner 계정 알림(#89) 상태 — 포지션 변경 감지용 leg, 순수익률 급변 기준선, 미반영 리마인더.
-- 기존 열(position_fingerprint 등 수익 알림 에피소드)의 의미는 바꾸지 않는다.
-- 새 열은 NULL 이거나 기본값이 있어 이 migration 이전 binary 의 INSERT(9열)·UPDATE(새 열 미포함)가 그대로 동작하고 새 열 값을 보존한다.
-- 수량·가격·펀딩은 소수 자릿수 손실 없이 비교하려고 정규화한 10진 문자열로 저장한다.
ALTER TABLE account_pnl_monitor_state
    ADD COLUMN korea_quantity            VARCHAR(64)   NULL,
    ADD COLUMN korea_average_buy_price   VARCHAR(64)   NULL,
    ADD COLUMN foreign_signed_quantity   VARCHAR(64)   NULL,
    ADD COLUMN foreign_entry_price       VARCHAR(64)   NULL,
    ADD COLUMN hedge_mismatch_warned     BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN swing_bucket_start        DATETIME(6)   NULL,
    ADD COLUMN swing_baseline_percent    DECIMAL(12,2) NULL,
    ADD COLUMN swing_up_sent             BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN swing_down_sent           BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN reminder_day_start        DATETIME(6)   NULL,
    ADD COLUMN manual_funding_usdt       VARCHAR(64)   NULL,
    ADD COLUMN manual_funding_changed_at DATETIME(6)   NULL,
    ADD CONSTRAINT ck_account_pnl_monitor_state_legs CHECK (
        (korea_quantity IS NULL AND korea_average_buy_price IS NULL
            AND foreign_signed_quantity IS NULL AND foreign_entry_price IS NULL)
        OR (korea_quantity IS NOT NULL AND korea_average_buy_price IS NOT NULL
            AND foreign_signed_quantity IS NOT NULL AND foreign_entry_price IS NOT NULL)
    ),
    ADD CONSTRAINT ck_account_pnl_monitor_state_swing CHECK (
        swing_bucket_start IS NOT NULL
        OR (swing_baseline_percent IS NULL AND swing_up_sent = FALSE AND swing_down_sent = FALSE)
    ),
    ADD CONSTRAINT ck_account_pnl_monitor_state_funding CHECK (
        manual_funding_changed_at IS NOT NULL OR manual_funding_usdt IS NULL
    );
