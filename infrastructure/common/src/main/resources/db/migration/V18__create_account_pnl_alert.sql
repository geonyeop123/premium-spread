-- 실계정 순손익 알림의 단일 상태 행. 두 실행이 겹쳐도 늦은 쪽이 덮어쓰지 못하도록 version 조건부로 갱신한다.
CREATE TABLE account_pnl_monitor_state (
    id                   TINYINT      NOT NULL PRIMARY KEY,
    in_episode           BOOLEAN      NOT NULL,
    position_fingerprint VARCHAR(255) NULL,
    last_sent_at         DATETIME(6)  NULL,
    last_success_at      DATETIME(6)  NULL,
    monitoring_since     DATETIME(6)  NULL,
    outage_notified      BOOLEAN      NOT NULL,
    version              BIGINT       NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    CONSTRAINT ck_account_pnl_monitor_state_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 이 기능 전용 durable 메일 outbox. 수신자 주소는 저장하지 않는다(adapter 설정이 소유).
CREATE TABLE account_pnl_mail (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    event_key     VARCHAR(128) NOT NULL,
    subject       VARCHAR(255) NOT NULL,
    body          TEXT         NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempt_count INT          NOT NULL DEFAULT 0,
    locked_by     VARCHAR(64)  NULL,
    claim_token   CHAR(36)     NULL,
    locked_at     DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    sent_at       DATETIME(6)  NULL,
    CONSTRAINT uk_account_pnl_mail_event_key UNIQUE (event_key),
    INDEX idx_account_pnl_mail_status_id (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
