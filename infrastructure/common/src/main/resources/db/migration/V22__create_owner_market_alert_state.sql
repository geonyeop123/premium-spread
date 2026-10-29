-- owner 시장 알림(#90) 신호별 지속 상태. 한 신호 = 한 행(쿨다운 시각·기준선 구역·일일 요약 일자).
-- 상태 갱신과 outbox(account_pnl_mail) 행 삽입은 같은 트랜잭션에서 행별 version 조건부로 한다.
-- 비밀값(webhook URL·수신 주소·API key)과 알림 본문은 저장하지 않는다.
CREATE TABLE owner_market_alert_state (
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
CREATE TABLE account_pnl_latest (
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
