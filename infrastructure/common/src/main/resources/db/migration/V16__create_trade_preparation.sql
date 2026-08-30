-- 거래 준비 계획(TradePreparation, design.md D4~D23)을 저장한다. CREATE TABLE 한 문장뿐이라
-- 기존 테이블을 건드리지 않는 append-only 계약과 무충돌이다.
CREATE TABLE trade_preparation (
    id                                BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id                          BIGINT          NOT NULL,
    symbol                            VARCHAR(20)     NOT NULL,
    korea_exchange                    VARCHAR(50)     NOT NULL,
    foreign_exchange                  VARCHAR(50)     NOT NULL,
    reference_foreign_price           DECIMAL(30, 10) NOT NULL,
    reference_fx_rate                 DECIMAL(20, 6)  NOT NULL,
    reference_premium_rate            DECIMAL(10, 2)  NOT NULL,
    reference_observed_at             DATETIME(6)     NOT NULL,
    reference_fx_source               VARCHAR(50)     NOT NULL,
    reference_fx_observed_at          DATETIME(6)     NOT NULL,
    quantity                          DECIMAL(30, 10) NOT NULL,
    leverage                          DECIMAL(20, 10) NOT NULL,
    bound_balance_snapshot_id         VARCHAR(100)    NOT NULL,
    bound_balance_basis               VARCHAR(20)     NOT NULL,
    status                            VARCHAR(20)     NOT NULL,
    -- D16·D23: 유일성의 범위는 활성 계획 전체(WATCHING·ARMED)다. WATCHING만 묶으면 같은 owner에
    -- ARMED가 여러 개 남아 같은 자본에 복수의 durable 실행 후보가 생긴다.
    active_key BIGINT AS (CASE WHEN status IN ('WATCHING', 'ARMED') THEN owner_id END) STORED,
    desired_entry_premium_rate        DECIMAL(10, 2)  NULL,
    -- D11: 모든 상태 전이를 `WHERE id=? AND version=? AND status=?` 조건부 update로 하기 위한
    -- 낙관적 잠금 컬럼이다.
    version                           BIGINT          NOT NULL DEFAULT 0,
    lock_version                     BIGINT          NOT NULL DEFAULT 0,
    invalidation_reason               VARCHAR(30)     NULL,
    invalidated_at                    DATETIME(6)     NULL,
    condition_first_met_at            DATETIME(6)     NULL,
    condition_first_met_premium_rate  DECIMAL(10, 2)  NULL,
    created_at                        DATETIME(6)     NOT NULL,
    updated_at                        DATETIME(6)     NOT NULL,
    deleted_at                        DATETIME(6)     NULL,
    INDEX idx_trade_preparation_owner_id (owner_id),
    UNIQUE KEY uk_trade_preparation_owner_active (active_key),
    CONSTRAINT fk_trade_preparation_owner FOREIGN KEY (owner_id) REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
