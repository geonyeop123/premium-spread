# Durable notification delivery 운영 런북

## 상태와 기본 retry 정책

```text
PENDING → PROCESSING → SENT
              ├──────→ PENDING(next_attempt_at + backoff)
              └──────→ FAILED(max attempts)
FAILED  ──manual redrive(actor/reason)──→ PENDING
stale PROCESSING ──recovery──→ PENDING
```

| 설정 | 기본값 |
|---|---:|
| poll interval | 5초 |
| claim batch / concurrency | 10 / 2 |
| hard send deadline | 30초 |
| stale threshold | 5분 |
| max attempts | 5 |
| retry delays | 1분, 5분, 30분, 2시간(마지막 값을 반복 사용) |
| retry jitter | 10% |
| dedupe cooldown window | 1시간 |
| SENT PII retention | 30일 |

`ceil(batchSize / concurrency) × hardSendDeadline + dbQueueSafetyMargin < staleThreshold`를 startup에서
검증한다. poller는 실제 concurrency permit을 확보한 수만큼만 claim하며, 각 row에는 worker ID와 별도 UUID
claim token을 저장한다. mark sent/retry/failed는 둘이 모두 일치해야 성공하므로 stale owner가 새 claim 상태를
바꿀 수 없다.

## 보장 수준과 중복 가능성

알림은 MySQL `notification_delivery` 큐를 기준으로 **at-least-once** 전달한다. `event_key`
unique 제약이 동일 구독/설정 revision/쿨다운 window의 중복 enqueue를 막지만, 이메일
exactly-once를 보장하지는 않는다.

worker는 SMTP 성공 후 claim token으로 `SENT`를 표시한다. SMTP 서버가 메시지를 수락한 뒤
DB `markSent` transaction이 실패하면 같은 claim token으로 retry 전이를 시도한다. retry 전이가
성공하면 row는 `PENDING`, DB 장애로 그 전이도 실패하면 `PROCESSING`에 남아 stale recovery 후
다시 발송될 수 있다. 두 경우 모두 수신자가 중복 메일을 받을 수 있다. 모든 durable 메일은
`delivery_id`로 만든 고정 SMTP 헤더 `Message-ID: <deliveryId@premium-spread.local>`를 사용하므로,
메일 제공자의 deduplication과 추적에 활용할 수 있다. 단, 제공자가 고정 `Message-ID`를
중복 제거한다고 가정하지 않는다.

worker는 실제 실행 가능한 concurrency slot 수만큼만 DB row를 claim한다. hard deadline이
지나면 SMTP 실행 thread에 interrupt를 요청하지만, transport가 interrupt를 무시하면 해당 작업이
실제로 종료할 때까지 slot과 기존 `PROCESSING` claim을 유지한다. 따라서 실행 대기 상태로
timeout 취소된 row가 `PROCESSING`에 고립되거나, 살아 있는 SMTP 작업 수를 넘겨 다음 row를
claim하지 않는다. 비정상 작업이 stale recovery 이후 완료되더라도 이전 claim token은 상태를
변경할 수 없다.

## 장애 확인

운영 DB에는 개발자 개인 계정이 아닌 인증된 운영 계정과 TLS로만 접속한다. 조회 결과와
터미널 history에 recipient/subject/payload를 남기지 않도록 기본 조회는 식별자와 상태만 사용한다.

```bash
mysql --ssl-mode=VERIFY_IDENTITY \
  --host="$DB_HOST" --user="$DB_OPERATION_USER" --password \
  --database="$DB_NAME"
```

```sql
SELECT id,
       delivery_id,
       subscription_id,
       status,
       attempt_count,
       next_attempt_at,
       last_error,
       created_at,
       updated_at
FROM notification_delivery
WHERE status = 'FAILED'
ORDER BY updated_at ASC
LIMIT 100;
```

`last_error`는 bounded 예외 분류만 보존해야 하며 recipient, subject, payload를 포함하지
않아야 한다. 대시보드에서 `FAILED` 증가와 stale recovery 증가를 먼저 확인하고 SMTP
크레덴셜, network, timeout 설정을 점검한다.

## FAILED 수동 redrive

redrive는 HTTP endpoint로 노출하지 않는다. 장애 원인이 해소되고 수신자/페이로드가 유효함을
확인한 후, 인증된 offline MySQL CLI에서 다음 transaction을 실행한다. `actor`는 운영자
식별자나 변경 티켓 ID, `reason`은 장애 원인과 재처리 근거를 써야 하며 빈 값을
허용하지 않는다.

다음 조건을 모두 만족할 때만 redrive한다.

1. 현재 상태가 `FAILED`이고 PII가 scrub되지 않았다.
2. SMTP credential/network/quota 같은 원인이 해소됐다.
3. 중복 메일 가능성을 운영자가 검토하고 change/ticket 승인을 받았다.
4. actor와 reason이 개인 식별 가능한 운영자/티켓으로 남는다.

```sql
START TRANSACTION;

SELECT id, delivery_id, status, attempt_count, scrubbed_at
FROM notification_delivery
WHERE delivery_id = 'REPLACE_WITH_DELIVERY_UUID'
FOR UPDATE;

UPDATE notification_delivery
SET status = 'PENDING',
    attempt_count = 0,
    next_attempt_at = UTC_TIMESTAMP(6),
    locked_at = NULL,
    locked_by = NULL,
    claim_token = NULL,
    last_error = NULL,
    redrive_actor = 'REPLACE_WITH_ACTOR_OR_TICKET',
    redrive_reason = 'REPLACE_WITH_REASON',
    redriven_at = UTC_TIMESTAMP(6),
    updated_at = UTC_TIMESTAMP(6)
WHERE delivery_id = 'REPLACE_WITH_DELIVERY_UUID'
  AND status = 'FAILED'
  AND recipient_email IS NOT NULL
  AND subject IS NOT NULL
  AND payload IS NOT NULL;

SELECT ROW_COUNT() AS redriven_rows;
COMMIT;
```

`redriven_rows = 1`일 때만 성공이다. 0이면 commit 후 반복하지 말고 현재 status, PII scrub
여부, 다른 운영자의 선행 redrive를 확인한다. worker가 다시 claim할 때 새 UUID
`claim_token`을 발급하며, 이전 claim의 worker는 fencing 조건 불일치로 상태를 바꾸지 못한다.
redrive 후에는 아래 audit을 확인하고 변경 티켓에 결과를 기록한다.

```sql
SELECT delivery_id, status, redrive_actor, redrive_reason, redriven_at, updated_at
FROM notification_delivery
WHERE delivery_id = 'REPLACE_WITH_DELIVERY_UUID';
```

## PII retention과 scrub

- `SENT`는 `sent_at` 30일 후 `recipient_email`, `subject`, `payload`를 `NULL`로 만들고
  `scrubbed_at` 시각을 기록한다.
- scrub 후에도 `delivery_id`, `event_key`, status, 생성/발송 시각, redrive audit은 삭제하지
  않는다. 그래야 dedupe와 장애 추적이 유지된다.
- `FAILED` PII는 운영자가 redrive 또는 acknowledge하기 전에 자동 scrub/삭제하지 않는다.
- scrub job은 다음 조건과 동등한 bounded batch를 사용해야 한다. 운영자가 임의로
  delivery row를 삭제하지 않는다.
- 한 scheduler tick에서 `scrub-batch-size` 단위로 반복 drain하며,
  `scrub-max-batches-per-run`에 도달하면 다음 tick에 이어서 처리하고 경고 metric/log를 확인한다.

```sql
status = 'SENT'
AND sent_at < UTC_TIMESTAMP(6) - INTERVAL 30 DAY
AND scrubbed_at IS NULL
```

## 시간 설정 안전 조건

SMTP `connect-timeout`, `read-timeout`, `write-timeout`은 모두 양수이고
세 설정값의 합으로 정의한 구성 예산도 `notification.delivery.hard-send-deadline`보다 크지 않아야 한다.
JavaMail read/write timeout은 socket operation마다 적용되므로 이 합을 실제 전체 실행시간의 상한으로
간주하지 않는다. worker는 별도로 전체 SMTP 호출의 deadline에 interrupt를 요청하고, 실제
종료 전까지 concurrency slot을 반환하지 않는다. 스타트업 검증은 다음 조건도 만족해야 한다.

```text
(ceil(batchSize / concurrency) * hardSendDeadline) + dbQueueSafetyMargin < staleThreshold
```

`notification.email.enabled=false`이면 enqueue/poller가 동작하지 않고 `JavaMailSender`/
`EmailSender` bean도 생성되지 않는다. 이미 저장된 `SENT` row의 PII retention job은 전송
활성화 여부와 독립적으로 계속 동작하며, 전체 batch scheduling을 비활성화한 경우에만 scheduler가 멈춘다.
