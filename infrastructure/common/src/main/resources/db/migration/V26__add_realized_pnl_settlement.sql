-- 정리한 포지션의 확정 손익(#130) — 체결 기준 정산 결과를 건 행에 둔다.
-- 기존 열의 의미는 바꾸지 않는다. 새 열은 모두 NULL 허용이고 기존 행 변환은 없다(NULL = 아직 정산 안 함).
-- 모니터의 건 UPDATE 와 쓰는 열이 겹치지 않는다. 금액은 표시·합산 값이라 DECIMAL 로 저장한다.
ALTER TABLE account_position_episode
    ADD COLUMN korea_settlement          VARCHAR(16)   NULL,
    ADD COLUMN foreign_settlement        VARCHAR(16)   NULL,
    ADD COLUMN korea_realized_krw        DECIMAL(24,4) NULL,
    ADD COLUMN foreign_realized_pnl_usdt DECIMAL(30,8) NULL,
    ADD COLUMN foreign_commission_usdt   DECIMAL(30,8) NULL,
    ADD COLUMN foreign_funding_usdt      DECIMAL(30,8) NULL,
    ADD COLUMN fx_rate                   DECIMAL(20,8) NULL,
    ADD COLUMN fx_observed_at            DATETIME(6)   NULL,
    ADD COLUMN total_realized_krw        DECIMAL(24,0) NULL,
    ADD COLUMN settled_seq               INT           NULL,
    ADD COLUMN settled_at                DATETIME(6)   NULL,
    ADD COLUMN settlement_attempts       INT           NULL,
    ADD COLUMN settlement_failure        VARCHAR(32)   NULL,
    ADD COLUMN settlement_next_at        DATETIME(6)   NULL,
    ADD COLUMN settlement_target_seq     INT           NULL,
    ADD CONSTRAINT ck_account_position_episode_settlement_status CHECK (
        (korea_settlement IS NULL OR korea_settlement IN ('PENDING', 'CONFIRMED', 'MANUAL'))
        AND (foreign_settlement IS NULL OR foreign_settlement IN ('PENDING', 'CONFIRMED', 'MANUAL'))
    ),
    ADD CONSTRAINT ck_account_position_episode_settlement_failure CHECK (
        settlement_failure IS NULL OR settlement_failure IN (
            'KOREA_READ', 'FOREIGN_READ', 'KOREA_QUANTITY', 'FOREIGN_QUANTITY', 'FX', 'WINDOW_LIMIT'
        )
    );
