-- 레그(바이낸스·빗썸)별 매분 관측 이력 (#139). account_pnl_observation 의 HOLDING 관측 한 건당 레그 행이 0~1개 붙는다.
-- 스냅샷에 없는 값(null)은 0 이 아니라 NULL 로 저장한다 — "없음"과 "0"을 구분해야 한다.
-- 가격·수량·금액은 조회·집계 대상이라 문자열이 아닌 DECIMAL 로 저장한다(V24 의 정규화 문자열은 동등 비교 목적이었다).
CREATE TABLE account_foreign_leg_observation (
    episode_id            BIGINT         NOT NULL,
    observed_at           DATETIME(6)    NOT NULL,
    leverage              INT            NULL,
    margin_type           VARCHAR(8)     NULL,
    liquidation_price     DECIMAL(30,8) NULL,
    mark_price            DECIMAL(30,8) NULL,
    isolated_margin       DECIMAL(30,8) NULL,
    maint_margin          DECIMAL(30,8) NULL,
    entry_commission_usdt DECIMAL(30,8) NULL,
    funding_usdt          DECIMAL(30,8) NULL,
    position_source       VARCHAR(16)    NOT NULL,
    created_at            DATETIME(6)    NOT NULL,
    CONSTRAINT pk_account_foreign_leg_observation PRIMARY KEY (episode_id, observed_at),
    CONSTRAINT fk_account_foreign_leg_observation_round FOREIGN KEY (episode_id, observed_at)
        REFERENCES account_pnl_observation (episode_id, observed_at),
    CONSTRAINT ck_account_foreign_leg_observation_config CHECK (
        (leverage IS NULL AND margin_type IS NULL)
        OR (leverage IS NOT NULL AND margin_type IS NOT NULL AND margin_type IN ('CROSSED', 'ISOLATED'))
    ),
    CONSTRAINT ck_account_foreign_leg_observation_risk CHECK (
        (mark_price IS NULL AND isolated_margin IS NULL AND maint_margin IS NULL)
        OR (mark_price IS NOT NULL AND isolated_margin IS NOT NULL AND maint_margin IS NOT NULL)
    ),
    CONSTRAINT ck_account_foreign_leg_observation_source CHECK (position_source = 'EXCHANGE_API')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE account_korea_leg_observation (
    episode_id    BIGINT        NOT NULL,
    observed_at   DATETIME(6)   NOT NULL,
    entry_fee_krw DECIMAL(24,4) NULL,
    created_at    DATETIME(6)   NOT NULL,
    CONSTRAINT pk_account_korea_leg_observation PRIMARY KEY (episode_id, observed_at),
    CONSTRAINT fk_account_korea_leg_observation_round FOREIGN KEY (episode_id, observed_at)
        REFERENCES account_pnl_observation (episode_id, observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
