-- owner 알림 신호별 지속 상태(#91 운영 알림: 환율 수집 지연·시세 수집 끊김·Job 연속 실패. #90 이 재사용한다). 한 신호 = 한 행.
-- 장애 중이면 active = TRUE 이고 incident 3열이 채워진다. 복구되면 active = FALSE 로 비운다. 행은 지우지 않는다.
-- 상태 갱신과 outbox(account_pnl_mail) 행 삽입은 같은 트랜잭션에서 행별 version 조건부로 한다.
-- 비밀값(webhook URL·수신 주소·API key)과 알림 본문은 저장하지 않는다.
CREATE TABLE owner_alert_signal_state (
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
