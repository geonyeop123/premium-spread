-- 실계정 포지션 한 건(어느 한 leg 라도 있으면 열림 → 둘 다 비면 닫힘)과 그 안의 사건, 매분 관측 이력 (#129).
-- 수량·평단·금액은 실계정 값이다. credential·알림 본문은 저장하지 않는다.
-- leg·reference 수량·평단은 소수 자릿수 손실 없이 비교하려고 정규화한 10진 문자열로 저장한다(V20 과 같은 이유 — DECIMAL 은 넘친 소수 자리를 반올림한다).
CREATE TABLE account_position_episode (
    id                         BIGINT         NOT NULL AUTO_INCREMENT,
    status                     VARCHAR(8)     NOT NULL,
    opened_at                  DATETIME(6)    NOT NULL,
    closed_at                  DATETIME(6)    NULL,
    korea_quantity             VARCHAR(64)    NOT NULL,
    korea_average_price        VARCHAR(64)    NOT NULL,
    foreign_signed_quantity    VARCHAR(64)    NOT NULL,
    foreign_entry_price        VARCHAR(64)    NOT NULL,
    reference_korea_quantity   VARCHAR(64)    NULL,
    reference_korea_price      VARCHAR(64)    NULL,
    reference_foreign_quantity VARCHAR(64)    NULL,
    reference_foreign_price    VARCHAR(64)    NULL,
    position_source            VARCHAR(16)    NOT NULL,
    event_count                INT            NOT NULL,
    open_marker                TINYINT        AS (CASE WHEN status = 'OPEN' THEN 1 ELSE NULL END) STORED,
    created_at                 DATETIME(6)    NOT NULL,
    updated_at                 DATETIME(6)    NOT NULL,
    CONSTRAINT pk_account_position_episode PRIMARY KEY (id),
    CONSTRAINT uk_account_position_episode_open UNIQUE (open_marker),
    CONSTRAINT ck_account_position_episode_status CHECK (
        (status = 'OPEN' AND closed_at IS NULL) OR (status = 'CLOSED' AND closed_at IS NOT NULL)
    ),
    CONSTRAINT ck_account_position_episode_reference CHECK (
        (reference_korea_quantity IS NULL AND reference_korea_price IS NULL
            AND reference_foreign_quantity IS NULL AND reference_foreign_price IS NULL)
        OR (reference_korea_quantity IS NOT NULL AND reference_korea_price IS NOT NULL
            AND reference_foreign_quantity IS NOT NULL AND reference_foreign_price IS NOT NULL)
    ),
    CONSTRAINT ck_account_position_episode_source CHECK (position_source IN ('EXCHANGE_API', 'MANUAL_ENV'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_account_position_episode_closed ON account_position_episode (status, closed_at);

CREATE TABLE account_position_event (
    id                      BIGINT         NOT NULL AUTO_INCREMENT,
    episode_id              BIGINT         NOT NULL,
    seq                     INT            NOT NULL,
    event_type              VARCHAR(16)    NOT NULL,
    occurred_at             DATETIME(6)    NOT NULL,
    korea_quantity          VARCHAR(64)    NOT NULL,
    korea_average_price     VARCHAR(64)    NOT NULL,
    foreign_signed_quantity VARCHAR(64)    NOT NULL,
    foreign_entry_price     VARCHAR(64)    NOT NULL,
    hedged                  BOOLEAN        NOT NULL,
    position_source         VARCHAR(16)    NOT NULL,
    created_at              DATETIME(6)    NOT NULL,
    CONSTRAINT pk_account_position_event PRIMARY KEY (id),
    CONSTRAINT uk_account_position_event_seq UNIQUE (episode_id, seq),
    CONSTRAINT fk_account_position_event_episode FOREIGN KEY (episode_id) REFERENCES account_position_episode (id),
    CONSTRAINT ck_account_position_event_type CHECK (
        event_type IN ('OPENED', 'INCREASED', 'REDUCED', 'ADJUSTED', 'CLOSED')
    ),
    CONSTRAINT ck_account_position_event_source CHECK (position_source IN ('EXCHANGE_API', 'MANUAL_ENV'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE account_pnl_observation (
    id                        BIGINT         NOT NULL AUTO_INCREMENT,
    episode_id                BIGINT         NOT NULL,
    kind                      VARCHAR(8)     NOT NULL,
    observed_at               DATETIME(6)    NOT NULL,
    net_percent               DECIMAL(12,2)  NULL,
    net_krw                   DECIMAL(20,0)  NULL,
    current_premium_percent   DECIMAL(12,2)  NOT NULL,
    entry_premium_percent     DECIMAL(12,2)  NOT NULL,
    premium_gap_percent_point DECIMAL(12,2)  NOT NULL,
    fx_rate                   DECIMAL(20,8)  NOT NULL,
    korea_exit_price          DECIMAL(30,8)  NOT NULL,
    foreign_exit_price        DECIMAL(30,8)  NOT NULL,
    created_at                DATETIME(6)    NOT NULL,
    CONSTRAINT pk_account_pnl_observation PRIMARY KEY (id),
    CONSTRAINT uk_account_pnl_observation_round UNIQUE (episode_id, observed_at),
    CONSTRAINT fk_account_pnl_observation_episode FOREIGN KEY (episode_id) REFERENCES account_position_episode (id),
    CONSTRAINT ck_account_pnl_observation_kind CHECK (
        (kind = 'HOLDING' AND net_percent IS NOT NULL AND net_krw IS NOT NULL)
        OR (kind = 'FLAT' AND net_percent IS NULL AND net_krw IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_account_pnl_observation_observed_at ON account_pnl_observation (observed_at);
