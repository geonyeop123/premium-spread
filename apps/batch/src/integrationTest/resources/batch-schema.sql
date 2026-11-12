-- Batch 모듈 전용 테이블 (JPA Entity 없이 JdbcTemplate으로 사용)

CREATE TABLE IF NOT EXISTS member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    UNIQUE INDEX uk_member_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS notification_subscription (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    korea_exchange VARCHAR(50) NOT NULL,
    foreign_exchange VARCHAR(50) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    lock_version BIGINT NOT NULL DEFAULT 0,
    direction VARCHAR(10) NOT NULL,
    threshold DECIMAL(10, 4) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    INDEX idx_notification_subscription_active_pair_direction (
        status, symbol, korea_exchange, foreign_exchange, direction
    ),
    INDEX idx_notification_subscription_member_id (member_id),
    CONSTRAINT fk_notification_subscription_member FOREIGN KEY (member_id) REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS notification_delivery (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    delivery_id CHAR(36) NOT NULL,
    subscription_id BIGINT NOT NULL,
    event_key VARCHAR(512) NOT NULL,
    recipient_email VARCHAR(320) NULL,
    subject VARCHAR(500) NULL,
    payload TEXT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    locked_at DATETIME(6) NULL,
    locked_by VARCHAR(100) NULL,
    claim_token CHAR(36) NULL,
    sent_at DATETIME(6) NULL,
    last_error VARCHAR(1000) NULL,
    scrubbed_at DATETIME(6) NULL,
    redrive_actor VARCHAR(100) NULL,
    redrive_reason VARCHAR(500) NULL,
    redriven_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE INDEX uk_notification_delivery_delivery_id (delivery_id),
    UNIQUE INDEX uk_notification_delivery_event_key (event_key),
    INDEX idx_notification_delivery_ready (status, next_attempt_at, id),
    INDEX idx_notification_delivery_stale (status, locked_at, id),
    INDEX idx_notification_delivery_sent_scrub (status, sent_at, scrubbed_at, id),
    INDEX idx_notification_delivery_subscription (subscription_id, created_at),
    CONSTRAINT fk_notification_delivery_subscription
        FOREIGN KEY (subscription_id) REFERENCES notification_subscription(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS exchange_rate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    base_currency VARCHAR(10) NOT NULL,
    quote_currency VARCHAR(10) NOT NULL,
    rate DECIMAL(10,4) NOT NULL,
    observed_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_currency_observed (base_currency, quote_currency, observed_at),
    INDEX idx_observed_at (observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ticker_minute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange VARCHAR(20) NOT NULL,
    symbol VARCHAR(10) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    minute_at DATETIME NOT NULL,
    high DECIMAL(20,2) NOT NULL,
    low DECIMAL(20,2) NOT NULL,
    open DECIMAL(20,2) NOT NULL,
    close DECIMAL(20,2) NOT NULL,
    avg DECIMAL(20,4) NOT NULL,
    count INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_exchange_symbol_minute (exchange, symbol, minute_at),
    INDEX idx_minute_at (minute_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ticker_hour (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange VARCHAR(20) NOT NULL,
    symbol VARCHAR(10) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    hour_at DATETIME NOT NULL,
    high DECIMAL(20,2) NOT NULL,
    low DECIMAL(20,2) NOT NULL,
    open DECIMAL(20,2) NOT NULL,
    close DECIMAL(20,2) NOT NULL,
    avg DECIMAL(20,4) NOT NULL,
    count INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_exchange_symbol_hour (exchange, symbol, hour_at),
    INDEX idx_hour_at (hour_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ticker_day (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange VARCHAR(20) NOT NULL,
    symbol VARCHAR(10) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    day_at DATE NOT NULL,
    high DECIMAL(20,2) NOT NULL,
    low DECIMAL(20,2) NOT NULL,
    open DECIMAL(20,2) NOT NULL,
    close DECIMAL(20,2) NOT NULL,
    avg DECIMAL(20,4) NOT NULL,
    count INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_exchange_symbol_day (exchange, symbol, day_at),
    INDEX idx_day_at (day_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS premium_minute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL,
    korea_exchange VARCHAR(50) NOT NULL,
    foreign_exchange VARCHAR(50) NOT NULL,
    minute_at DATETIME NOT NULL,
    high DECIMAL(10,4) NOT NULL,
    low DECIMAL(10,4) NOT NULL,
    open DECIMAL(10,4) NOT NULL,
    close DECIMAL(10,4) NOT NULL,
    avg DECIMAL(10,4) NOT NULL,
    count INT NOT NULL,
    fx_rate DECIMAL(10,4) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pair_symbol_minute (korea_exchange, foreign_exchange, symbol, minute_at),
    INDEX idx_minute_at (minute_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS premium_hour (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL,
    korea_exchange VARCHAR(50) NOT NULL,
    foreign_exchange VARCHAR(50) NOT NULL,
    hour_at DATETIME NOT NULL,
    high DECIMAL(10,4) NOT NULL,
    low DECIMAL(10,4) NOT NULL,
    open DECIMAL(10,4) NOT NULL,
    close DECIMAL(10,4) NOT NULL,
    avg DECIMAL(10,4) NOT NULL,
    count INT NOT NULL,
    fx_rate DECIMAL(10,4) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pair_symbol_hour (korea_exchange, foreign_exchange, symbol, hour_at),
    INDEX idx_hour_at (hour_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS premium_day (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL,
    korea_exchange VARCHAR(50) NOT NULL,
    foreign_exchange VARCHAR(50) NOT NULL,
    day_at DATE NOT NULL,
    high DECIMAL(10,4) NOT NULL,
    low DECIMAL(10,4) NOT NULL,
    open DECIMAL(10,4) NOT NULL,
    close DECIMAL(10,4) NOT NULL,
    avg DECIMAL(10,4) NOT NULL,
    count INT NOT NULL,
    fx_rate DECIMAL(10,4) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pair_symbol_day (korea_exchange, foreign_exchange, symbol, day_at),
    INDEX idx_day_at (day_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- V18 + V20 (owner 계정 알림 상태) + V25 (기준 포지션 단계 상태)와 같은 스키마. Batch 는 Flyway 를 쓰지 않으므로 fixture 로 만든다.
-- 실계정 순손익 알림의 단일 상태 행. 두 실행이 겹쳐도 늦은 쪽이 덮어쓰지 못하도록 version 조건부로 갱신한다.
CREATE TABLE IF NOT EXISTS account_pnl_monitor_state (
    id                        TINYINT       NOT NULL PRIMARY KEY,
    in_episode                BOOLEAN       NOT NULL,
    position_fingerprint      VARCHAR(255)  NULL,
    last_sent_at              DATETIME(6)   NULL,
    last_success_at           DATETIME(6)   NULL,
    monitoring_since          DATETIME(6)   NULL,
    outage_notified           BOOLEAN       NOT NULL,
    version                   BIGINT        NOT NULL,
    updated_at                DATETIME(6)   NOT NULL,
    korea_quantity            VARCHAR(64)   NULL,
    korea_average_buy_price   VARCHAR(64)   NULL,
    foreign_signed_quantity   VARCHAR(64)   NULL,
    foreign_entry_price       VARCHAR(64)   NULL,
    hedge_mismatch_warned     BOOLEAN       NOT NULL DEFAULT FALSE,
    swing_bucket_start        DATETIME(6)   NULL,
    swing_baseline_percent    DECIMAL(12,2) NULL,
    swing_up_sent             BOOLEAN       NOT NULL DEFAULT FALSE,
    swing_down_sent           BOOLEAN       NOT NULL DEFAULT FALSE,
    reminder_day_start        DATETIME(6)   NULL,
    manual_funding_usdt       VARCHAR(64)   NULL,
    manual_funding_changed_at DATETIME(6)   NULL,
    reference_korea_quantity   VARCHAR(64)  NULL,
    reference_korea_price      VARCHAR(64)  NULL,
    reference_foreign_quantity VARCHAR(64)  NULL,
    reference_foreign_price    VARCHAR(64)  NULL,
    reference_step_phase       VARCHAR(8)   NULL,
    reference_step_level       INT          NULL,
    liquidation_side           VARCHAR(8)   NULL,
    liquidation_entry_price    VARCHAR(64)  NULL,
    liquidation_level          INT          NULL,
    CONSTRAINT ck_account_pnl_monitor_state_singleton CHECK (id = 1),
    CONSTRAINT ck_account_pnl_monitor_state_legs CHECK (
        (korea_quantity IS NULL AND korea_average_buy_price IS NULL
            AND foreign_signed_quantity IS NULL AND foreign_entry_price IS NULL)
        OR (korea_quantity IS NOT NULL AND korea_average_buy_price IS NOT NULL
            AND foreign_signed_quantity IS NOT NULL AND foreign_entry_price IS NOT NULL)
    ),
    CONSTRAINT ck_account_pnl_monitor_state_swing CHECK (
        swing_bucket_start IS NOT NULL
        OR (swing_baseline_percent IS NULL AND swing_up_sent = FALSE AND swing_down_sent = FALSE)
    ),
    CONSTRAINT ck_account_pnl_monitor_state_funding CHECK (
        manual_funding_changed_at IS NOT NULL OR manual_funding_usdt IS NULL
    ),
    CONSTRAINT ck_account_pnl_monitor_state_reference_step CHECK (
        (reference_korea_quantity IS NULL AND reference_korea_price IS NULL
            AND reference_foreign_quantity IS NULL AND reference_foreign_price IS NULL
            AND reference_step_phase IS NULL AND reference_step_level IS NULL)
        OR (reference_korea_quantity IS NOT NULL AND reference_korea_price IS NOT NULL
            AND reference_foreign_quantity IS NOT NULL AND reference_foreign_price IS NOT NULL
            AND reference_step_phase IN ('HOLDING', 'FLAT') AND reference_step_level IS NOT NULL)
    ),
    -- V27 과 같은 스키마
    CONSTRAINT ck_account_pnl_monitor_state_liquidation CHECK (
        (liquidation_side IS NULL AND liquidation_entry_price IS NULL AND liquidation_level IS NULL)
        OR (liquidation_side IS NOT NULL AND liquidation_entry_price IS NOT NULL AND liquidation_level IS NOT NULL
            AND liquidation_side IN ('SHORT', 'LONG') AND liquidation_level IN (0, 50, 60, 70, 80, 90))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- V18 + V19 (owner 알림 채널)과 같은 스키마. Batch 는 Flyway 를 쓰지 않으므로 fixture 로 만든다.
CREATE TABLE IF NOT EXISTS account_pnl_mail (
    id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
    event_key      VARCHAR(128) NOT NULL,
    subject        VARCHAR(255) NOT NULL,
    body           TEXT         NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempt_count  INT          NOT NULL DEFAULT 0,
    locked_by      VARCHAR(64)  NULL,
    claim_token    CHAR(36)     NULL,
    locked_at      DATETIME(6)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    sent_at        DATETIME(6)  NULL,
    channel        VARCHAR(16)  NOT NULL DEFAULT 'EMAIL',
    discord_target VARCHAR(16)  NULL,
    discord_push   VARCHAR(16)  NULL,
    discord_color  INT          NULL,
    discord_layout TEXT         NULL,
    CONSTRAINT uk_account_pnl_mail_event_key_channel UNIQUE (event_key, channel),
    CONSTRAINT ck_account_pnl_mail_channel CHECK (
        (channel = 'EMAIL' AND discord_target IS NULL AND discord_push IS NULL AND discord_color IS NULL)
        OR (channel = 'DISCORD' AND discord_target IS NOT NULL AND discord_push IS NOT NULL AND discord_color IS NOT NULL)
    ),
    -- V23 와 같은 스키마
    CONSTRAINT ck_account_pnl_mail_discord_layout CHECK (discord_layout IS NULL OR channel = 'DISCORD'),
    INDEX idx_account_pnl_mail_status_id (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS owner_alert_signal_state (
    signal_key          VARCHAR(64) NOT NULL,
    alert_kind          VARCHAR(32) NOT NULL,
    active              BOOLEAN     NOT NULL,
    incident_started_at DATETIME(6) NULL,
    last_notified_at    DATETIME(6) NULL,
    notify_count        INT         NOT NULL DEFAULT 0,
    version             BIGINT      NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    CONSTRAINT pk_owner_alert_signal_state PRIMARY KEY (signal_key),
    CONSTRAINT ck_owner_alert_signal_state_incident CHECK (
        (active = FALSE AND incident_started_at IS NULL AND last_notified_at IS NULL AND notify_count = 0)
        OR (active = TRUE AND incident_started_at IS NOT NULL AND last_notified_at IS NOT NULL
            AND notify_count >= 1 AND last_notified_at >= incident_started_at)
    ),
    CONSTRAINT ck_owner_alert_signal_state_version CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- V22 와 같은 스키마. Batch 는 Flyway 를 쓰지 않으므로 fixture 로 만든다.
-- owner 시장 알림(#90) 신호별 지속 상태. 한 신호 = 한 행(쿨다운 시각·기준선 구역·일일 요약 일자).
CREATE TABLE IF NOT EXISTS owner_market_alert_state (
    signal_key         VARCHAR(64)   NOT NULL,
    alert_kind         VARCHAR(32)   NOT NULL,
    last_notified_at   DATETIME(6)   NULL,
    premium_zone       VARCHAR(16)   NULL,
    upper_percent      DECIMAL(10,4) NULL,
    lower_percent      DECIMAL(10,4) NULL,
    hysteresis_percent DECIMAL(10,4) NULL,
    summary_date       DATE          NULL,
    version            BIGINT        NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    CONSTRAINT pk_owner_market_alert_state PRIMARY KEY (signal_key),
    CONSTRAINT ck_owner_market_alert_state_zone CHECK (
        (premium_zone IS NULL AND upper_percent IS NULL AND lower_percent IS NULL AND hysteresis_percent IS NULL)
        OR (premium_zone IN ('ABOVE', 'BETWEEN', 'BELOW') AND hysteresis_percent IS NOT NULL
            AND hysteresis_percent >= 0 AND (upper_percent IS NOT NULL OR lower_percent IS NOT NULL))
    ),
    CONSTRAINT ck_owner_market_alert_state_version CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- 실계정 순손익 모니터가 회차마다 남기는 최신 순수익률(단일 행). 일일 요약(#90)이 읽는다.
-- has_position = FALSE 면 포지션 없음 관측이고 금액 열은 NULL 이다. 수량·가격·수수료·credential 은 저장하지 않는다.
CREATE TABLE IF NOT EXISTS account_pnl_latest (
    id            TINYINT       NOT NULL PRIMARY KEY,
    has_position  BOOLEAN       NOT NULL,
    net_percent   DECIMAL(12,2) NULL,
    net_krw       DECIMAL(20,0) NULL,
    calculated_at DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    CONSTRAINT ck_account_pnl_latest_singleton CHECK (id = 1),
    CONSTRAINT ck_account_pnl_latest_values CHECK (
        (has_position = TRUE AND net_percent IS NOT NULL AND net_krw IS NOT NULL)
        OR (has_position = FALSE AND net_percent IS NULL AND net_krw IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- V24 (실계정 포지션 이력) + V26 (확정 손익 정산 열)과 같은 스키마. Batch 는 Flyway 를 쓰지 않으므로 fixture 로 만든다.
-- leg 수량·평단은 정규화한 10진 문자열이고 정산 금액은 DECIMAL 이다.
CREATE TABLE IF NOT EXISTS account_position_episode (
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
    korea_settlement           VARCHAR(16)    NULL,
    foreign_settlement         VARCHAR(16)    NULL,
    korea_realized_krw         DECIMAL(24,4)  NULL,
    foreign_realized_pnl_usdt  DECIMAL(30,8)  NULL,
    foreign_commission_usdt    DECIMAL(30,8)  NULL,
    foreign_funding_usdt       DECIMAL(30,8)  NULL,
    fx_rate                    DECIMAL(20,8)  NULL,
    fx_observed_at             DATETIME(6)    NULL,
    total_realized_krw         DECIMAL(24,0)  NULL,
    settled_seq                INT            NULL,
    settled_at                 DATETIME(6)    NULL,
    settlement_attempts        INT            NULL,
    settlement_failure         VARCHAR(32)    NULL,
    settlement_next_at         DATETIME(6)    NULL,
    settlement_target_seq      INT            NULL,
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
    CONSTRAINT ck_account_position_episode_source CHECK (position_source IN ('EXCHANGE_API', 'MANUAL_ENV')),
    CONSTRAINT ck_account_position_episode_settlement_status CHECK (
        (korea_settlement IS NULL OR korea_settlement IN ('PENDING', 'CONFIRMED', 'MANUAL'))
        AND (foreign_settlement IS NULL OR foreign_settlement IN ('PENDING', 'CONFIRMED', 'MANUAL'))
    ),
    CONSTRAINT ck_account_position_episode_settlement_failure CHECK (
        settlement_failure IS NULL OR settlement_failure IN (
            'KOREA_READ', 'FOREIGN_READ', 'KOREA_QUANTITY', 'FOREIGN_QUANTITY', 'FX', 'WINDOW_LIMIT'
        )
    ),
    INDEX ix_account_position_episode_closed (status, closed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- V24 와 같은 스키마.
CREATE TABLE IF NOT EXISTS account_position_event (
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
-- V24 와 같은 스키마.
CREATE TABLE IF NOT EXISTS account_pnl_observation (
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
    ),
    INDEX ix_account_pnl_observation_observed_at (observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
