# owner 운영 알림 Runbook

owner 가 batch 의 지속 장애를 메일·Discord 로 받는 기능이다(#91). 환율 수집 지연, 거래소 시세 수집 끊김, Job 연속 실패 3종을 30초마다 판정해
**장애가 시작될 때 한 번, 이어지면 6시간마다, 해소되면 한 번** 알린다. 채널·outbox 는 실계정 순손익 알림과 같은 것을 쓴다
([account-pnl-alert.md](account-pnl-alert.md)). PnL 모니터를 켜지 않아도 동작한다.

## 무엇을 알리나

| 신호 | 장애 판정 기준 | 경계 | 정상 판정 | 알림 종류 |
|---|---|---|---|---|
| 환율 수집(`FX_INGESTION_DELAYED`) | 마지막 수집 성공 후 **30분 초과** (`FxIngestionFreshness`) | 공백 정확히 30분은 정상, 30분 + 1ns 부터 장애 | 마지막 성공이 30분 이내 | 통화쌍 `USD/KRW` 1개 |
| 시세 수집(`MARKET_STREAM_DOWN`) | 마지막 tick(거래소 시각)이 **5분 이상** 전 | 5분 − 1ms 는 정상, 정확히 5분부터 장애 | tick 이 5분 이내 | 기본 market 의 한국 쌍(`BITHUMB BTC/KRW`)과 해외 쌍(`BINANCE BTC/USD`) 2개 |
| Job 연속 실패(`JOB_CONSECUTIVE_FAILURES`) | **3회 이상 연속 실패 AND 첫 실패 후 5분 이상** | 3회·5분 − 1ms 는 정상, 2회·5분 이상도 정상, 3회 ∧ 정확히 5분부터 장애 | 연속 실패가 없고 마지막 성공이 있음 | 감시 대상 Job 마다 1개 |

- FX 임계값(30분)은 env 로 바꿀 수 없다. 수집 주기·캐시 TTL 과 테스트로 묶여 있어 한 곳(`FxIngestionFreshness`)만 쓴다. PnL 모니터의 `STALE_FX` 판정과 같은 순간에 같은 답을 낸다.
- 시세는 설정된 quote 의 tick 만 본다. 다른 quote 의 tick 으로 보정하지 않는다(없으면 "없음"으로 판정한다).
- Job 신호는 `OWNER_OPS_SIGNAL`·`OWNER_ALERT_DELIVERY` 를 뺀 모든 Job(`JobId` 선언 순서)을 본다. 시장 알림 Job(`owner_market_signal`, [owner-market-alert.md](owner-market-alert.md))도 자동으로 포함되어 3회·5분 연속 실패하면 `JOB_CONSECUTIVE_FAILURES`(신호 key `job-failures.owner_market_signal`)로 알린다. 연속 실패는 `FAILED` 로만 세고, `SUCCEEDED` 가 카운터를 지우며, `SKIPPED`(lock 미획득·취소 등)는 바꾸지 않는다.
- 같은 신호의 알림은 하나의 "장애"로 묶인다. 장애 식별은 신호 key + 장애 감지 시각이다.

## 재알림과 복구

- 장애가 감지되면 즉시 첫 알림(1번째)을 보낸다. 장애가 이어지면 **마지막 알림에서 6시간(이상)이 지날 때마다** 같은 종류로 다시 알린다(제목에 `(계속, N번째 알림)`). 6시간 − 1ms 는 아직 보내지 않는다.
- 해소되면 `OPS_RECOVERED` 를 **한 번** 보낸다. 장애 동안 보낸 알림 수가 본문에 있다.
- 복구 뒤 같은 신호가 다시 나빠지면 새 장애다(새 감지 시각 = 새 eventKey, 1번째 알림부터).
- 상태는 DB(`owner_alert_signal_state`)에 있어 batch 를 재시작해도 6시간 간격과 장애 상태가 이어진다.
- 알림 하나는 켜진 채널 수만큼 outbox 행(`account_pnl_mail`)이 되고 채널별로 따로 재시도한다. 신호 상태 갱신과 outbox 행 삽입은 한 트랜잭션이다.
- 전달은 두 곳이 한다: PnL 회차 안 디스패처(PnL 모니터가 켜진 경우)와 독립 전달 Job(60초 주기). 둘이 같은 outbox 를 claim 해도 `SKIP LOCKED` 와 `claim_token` fencing 으로 한 행은 한 디스패처만 집어 이중 발송이 없다. 발송 자체는 at-least-once 다(성공 직후 프로세스 종료 시 드물게 한 번 더).
- 재시도 예산: 재시도 가능한 전달 실패는 다음 회차에 다시 시도하고 행당 5회면 `FAILED` 다. 전달 Job 이 60초 주기이므로 약 4~5분 안에 소진된다(PnL 모니터가 켜져 있으면 두 디스패처가 함께 시도해 약 2~5분).

## 라우팅

| 알림 | Discord 대상 | 알림 방식 | 메일 |
|---|---|---|---|
| 환율 수집 지연(`FX_INGESTION_DELAYED`) | ops | 소리 | **아니오** |
| 시세 수집 끊김(`MARKET_STREAM_DOWN`) | ops | 소리 | **아니오** |
| Job 연속 실패(`JOB_CONSECUTIVE_FAILURES`) | ops | 소리 | 예 |
| 운영 복구(`OPS_RECOVERED`) | ops | 무음 | **아니오** |

- **Discord 가 꺼져 있으면 4종 모두 메일로 온다.** 복구와 6시간 재알림도 메일이다. owner 가 `OWNER_ALERT_{KIND}_EMAIL=false` 를 명시했을 때만 빠진다.
- Discord 가 켜져 있으면 환율·시세·복구는 Discord 로만 오고, Job 연속 실패만 메일도 함께 온다.
- 재알림은 장애 알림과 같은 종류(소리)다. 복구만 `OPS_RECOVERED`(무음)다.
- override env 는 `OWNER_ALERT_{KIND}_EMAIL`·`_DISCORD`·`_PUSH` 12개(`{KIND}` = `FX_INGESTION_DELAYED`, `MARKET_STREAM_DOWN`, `JOB_CONSECUTIVE_FAILURES`, `OPS_RECOVERED`)다.
  값과 의미는 [account-pnl-alert.md](account-pnl-alert.md#종류별-override-env)와 같다. 두 채널이 모두 꺼지도록 override 한 알림은 행을 만들지 않는다.

## 환경변수

| env | property | 기본 | 의미 |
|---|---|---|---|
| `OWNER_ALERT_OPS_ENABLED` | `owner-alert.ops.enabled` | `true` | 운영 신호 Job on/off. 빈 값 = true, 그 밖은 trim 후 `equalsIgnoreCase("true")` |
| `OWNER_ALERT_OPS_MARKET_STALE_AFTER` | `owner-alert.ops.market-stale-after` | `5m` | 시세 장애 나이(이상) |
| `OWNER_ALERT_OPS_JOB_FAILURE_COUNT` | `owner-alert.ops.job-failure-count` | `3` | Job 연속 실패 횟수(이상) |
| `OWNER_ALERT_OPS_JOB_FAILURE_DURATION` | `owner-alert.ops.job-failure-duration` | `5m` | 첫 실패 후 경과(이상) |
| `OWNER_ALERT_OPS_REALERT_INTERVAL` | `owner-alert.ops.realert-interval` | `6h` | 재알림 간격(이상) |
| `OWNER_ALERT_DELIVERY_FIXED_RATE` | `batch.scheduling.owner-alert-delivery.fixed-rate` | `60s` | 전달 Job 주기 |
| `OWNER_OPS_SIGNAL_FIXED_RATE` | `batch.scheduling.owner-ops-signal.fixed-rate` | `30s` | 운영 신호 Job 주기 |
| `OWNER_ALERT_DELIVERY_LOCK_KEY`·`_LEASE`·`_TIMEOUT` | `batch.jobs.owner-alert-delivery.*` | `lock:owner-alert:delivery` · `50s` · `40s` | 전달 Job lock |
| `OWNER_OPS_SIGNAL_LOCK_KEY`·`_LEASE`·`_TIMEOUT` | `batch.jobs.owner-ops-signal.*` | `lock:owner-ops:signal` · `20s` · `10s` | 운영 신호 Job lock |
| `OWNER_ALERT_{KIND}_EMAIL`·`_DISCORD`·`_PUSH` | `owner-alert.routes.{key}.*` | 빈 값 | `{KIND}` ∈ `FX_INGESTION_DELAYED`, `MARKET_STREAM_DOWN`, `JOB_CONSECUTIVE_FAILURES`, `OPS_RECOVERED` (12개) |

채널 env(`OWNER_ALERT_EMAIL_ENABLED`·`OWNER_ALERT_DISCORD_ENABLED`·`OWNER_ALERT_DISCORD_WEBHOOK_*`·`ACCOUNT_PNL_ALERT_RECIPIENT`·SMTP)는
[account-pnl-alert.md](account-pnl-alert.md)와 같다. `owner-alert.ops.*` 잘못된 값(`0s`, `0` 등)은 기동 실패다. 실제 webhook URL·수신 주소는 이 문서에 쓰지 않는다.

## 기동 판정과 로그

채널 준비(`ready`) 규칙:

```text
emailRequested    = owner-alert.email.enabled 가 비었거나 없으면 true, 아니면 trim 후 equalsIgnoreCase("true")
emailEffective    = emailRequested ∧ notification.email.enabled equalsIgnoreCase "true"
discordRequested  = owner-alert.discord.enabled trim 후 equalsIgnoreCase "true"
webhookConfigured = webhook.pnl / .market / .ops 중 하나라도 공백 아님
discordEffective  = discordRequested ∧ webhookConfigured
recipientMissing  = emailEffective ∧ ACCOUNT_PNL_ALERT_RECIPIENT 공백
discordMisconfigured = discordRequested ∧ ¬webhookConfigured
ready             = ¬recipientMissing ∧ ¬discordMisconfigured ∧ (emailEffective ∨ discordEffective)
```

`notification.email.enabled` 는 trim 없이 `true`(대소문자 무시)와 비교한다. `" true"` 처럼 공백이 붙으면 꺼진 것으로 본다.

| 구성 요소 | 뜨는 조건 |
|---|---|
| 채널 bean (Discord·메일 sender) | batch scheduling 켜짐 ∧ ready (PnL 모니터와 무관) |
| 독립 전달 Job·Scheduler | batch scheduling 켜짐 ∧ ready |
| 운영 신호 Job·Scheduler | batch scheduling 켜짐 ∧ ready ∧ `OWNER_ALERT_OPS_ENABLED` 가 꺼지지 않음 |

기동 로그 (env 이름과 boolean 만 찍힌다. 위에서부터 먼저 맞는 행 하나):

| 상황 | 등급 | 로그 |
|---|---|---|
| owner 가 Discord 를 켰는데 webhook 이 없음 | **WARN** | `owner-alert channel not ready; owner alert delivery and ops alerts not started: missing={}, discordWebhookMissing=true` (`missing` = `[ACCOUNT_PNL_ALERT_RECIPIENT]` 또는 `[]`) |
| SMTP 는 켜졌지만 owner 수신 주소가 없음 | **INFO** | `owner-alert email channel has no owner recipient; owner alert delivery and ops alerts not started: missing=[ACCOUNT_PNL_ALERT_RECIPIENT]` |
| 채널을 아무것도 켜지 않음(로컬 기본) | 없음 | — |
| ready 인데 `OWNER_ALERT_OPS_ENABLED=false` | INFO | `owner-alert ops alerts disabled by OWNER_ALERT_OPS_ENABLED` |

- **수신 주소 누락이 WARN 이 아니라 INFO 인 이유:** SMTP(`NOTIFICATION_EMAIL_ENABLED`)는 회원 김프 구독 알림과 공유된다. SMTP 를 켰다고 owner 알림을 의도한 것은 아니므로 정상 구성일 수 있다. owner 알림을 받으려면 `ACCOUNT_PNL_ALERT_RECIPIENT` 를 채우거나 Discord 를 구성한다.
- Discord 를 켰는데 webhook 이 없는 것은 명백한 구성 실수라 WARN 이다. 이때 전달 Job 과 운영 알림은 뜨지 않는다.
- **채널 구성이 ready 면 Discord 켜짐 여부와 무관하게 webhook 형식(`https` URL)과 `OWNER_ALERT_*_EMAIL/_DISCORD/_PUSH` override 값을 검증해, 틀리면 기동 실패한다.** Discord 를 꺼 둔 상태여도 마찬가지다. 오류에는 key 만 찍히고 값은 찍히지 않는다.
- 종류별 최종 경로는 기동 INFO `owner-alert routes:` 한 줄에 나온다. 운영 알림 4종도 PnL 모니터가 꺼져 있어도 이 줄에 보인다.

## 한계

- **재시작:** Job 연속 실패 카운터는 프로세스 메모리다. batch 를 재시작하면 0 부터 다시 센다. 재시작 직전까지 실패하던 Job 은 재시작 뒤 다시 3회·5분을 채워야 알린다.
  FAILED 시 `lastFailureAt` 은 늦은 값으로 유지해 동시 완료가 역순으로 도착해도 되감기지 않는다.
- **`Unknown` 대기:** 재시작 직후에는 FX 성공 기록·tick·카운터가 비어 있다. 이 상태는 정상도 장애도 아닌 `Unknown` 이라 DB 에 남은 장애를 닫지도(가짜 복구) 열지도 않는다.
  이 batch 인스턴스의 첫 실행부터 FX 는 30분, 시세는 5분이 지나도 근거가 없으면 그때 장애로 본다. Job 신호에는 대기 규칙이 없다(카운터가 비면 실패가 없었거나 재시작이다).
- **다중 인스턴스:** 카운터와 tick 은 인스턴스마다 따로 센다. 이 기능은 batch 한 인스턴스를 전제한다.
- 이 경로의 판정 지연: 운영 신호 Job 주기 30초 + 전달 주기 60초가 더해진다.
- `batch.market` 을 바꿔 감시 대상에서 빠진 신호 행은 남는다. 장애 중이었으면 복구 알림이 오지 않는다(아래 "상태 확인과 정리").

## 이 경로로 알릴 수 없는 장애

| 실패하는 것 | 처리 | 대신 알리는 경로 |
|---|---|---|
| `OwnerOpsSignalJob` 자신 | 감시 대상에서 뺀다. 이 Job 이 실패하면 판정·저장이 안 된다 | Slack `BATCH_JOB_FAILED` |
| `OwnerAlertDeliveryJob` | 감시 대상에서 뺀다. 전달 Job 이 죽으면 실패 알림이 outbox 에 쌓일 뿐 나가지 않는다 | Slack `BATCH_JOB_FAILED`. PnL 모니터가 켜져 있으면 PnL 회차 안 디스패처가 같은 outbox 를 계속 비운다 |
| Redis 장애(모든 JobExecutor Job 이 lock 획득 실패) | 운영 신호 Job 도 lock 을 못 잡아 실패한다. 장애 동안은 이 경로로 알릴 수 없다 | Slack `BATCH_JOB_FAILED` |
| MySQL 장애 | 운영 신호 Job 의 조회·저장 예외로 실패한다. 알림을 저장할 수 없다 | Slack `BATCH_JOB_FAILED` |

- Slack `BATCH_JOB_FAILED`(기존 `OperatorAlert`)는 그대로다. 그쪽은 순간 이벤트용이고, 이 기능은 **지속 상태와 복구**용이다. 같은 Job 실패가 양쪽에서 올 수 있다.
- FX 수집 실패는 `JOB_CONSECUTIVE_FAILURES(fx_ingestion)`(5분 주기라 3번째 실패 = 약 10분)와 `FX_INGESTION_DELAYED`(30분)가 함께 올 수 있다. 원인 Job 의 실패와 환율 값 신뢰 불가라는 다른 정보라 둘 다 보낸다.
- Job 메트릭 tag(`job=owner_alert_delivery`, `job=owner_ops_signal`)의 outcome·last-success age 로 이 두 Job 자체를 본다.

## 알림 메시지 예

문구 정본은 [message-spec](../work/alert-message-template/message-spec.md) 이다. 고정 시각 `2026-10-28T00:00:00Z`, 표시 zone `Asia/Seoul` 기준이며 모든 시각은 KST 다.
메일 subject 는 `[운영] ` + 핵심이고 본문 마지막 줄은 `기준 시각 …` 이다. Discord 는 author `운영`, 제목 앞 장애 `⚠️`·복구 `✅`, 칸·footer·timestamp 로 가며 색은 장애 주황·복구 회색이다.

Job 연속 실패 재알림 (메일 subject `[운영] Job 연속 실패 premium_realtime · 2번째 알림`. Discord 칸은 `연속 실패 4회`·`첫 실패 02:55`·`지속 6시간`):

```text
premium_realtime Job이 4회 연속 실패했습니다.

연속 실패  4회
첫 실패    2026-10-28 02:55 KST
장애 감지  2026-10-28 03:00 KST
지속       6시간

기준: 3회 연속이고 5분 이상. 해소될 때까지 6시간마다 다시 알립니다.

기준 시각 2026-10-28 09:00 KST
```

시세 복구 (메일 subject `[운영] 복구 — 시세 끊김 바이낸스 BTC/USD`, 무음, Discord footer 없음):

```text
바이낸스 BTC/USD 시세 끊김이 해소되었습니다.

장애 감지  2026-10-27 21:00 KST
복구 확인  2026-10-28 09:00 KST
지속       12시간
보낸 알림  2회

기준 시각 2026-10-28 09:00 KST
```

본문에 담는 것은 신호 종류·통화쌍·거래소 한글 표기·`JobId.tag`·횟수·시각·임계값·회차뿐이다. webhook URL, 수신 주소, API key, Job 예외 메시지, 가격·환율 값은 담지 않는다. 조치 절차는 이 runbook 에서 찾는다(알림에는 runbook 경로를 싣지 않는다).

## 상태 확인과 정리

신호 상태 한 행이 신호 하나다. 현재 상태를 본다.

```sql
SELECT signal_key, alert_kind, active, incident_started_at FROM owner_alert_signal_state;
```

`batch.market` 을 바꿔 더 이상 관측하지 않는 시세 신호(`market-stream.{exchange}.{base}-{currency}`)가 `active = TRUE` 로 남아 있으면 복구 알림이 오지 않는다.
해당 key 를 확인하고 아래로 정상 상태로 되돌린다(행은 지우지 않는다).

```sql
UPDATE owner_alert_signal_state SET active = FALSE, incident_started_at = NULL, last_notified_at = NULL, notify_count = 0, version = version + 1, updated_at = UTC_TIMESTAMP(6) WHERE signal_key = '<key>';
```

장애가 열린 Job 을 설정으로 끈 채 재시작하면 그 신호는 `Unknown` 으로 남아 복구 알림이 오지 않는다. 같은 정리 SQL 로 닫는다.

## 배포

- **V21(`owner_alert_signal_state` 신규 테이블)은 additive 다.** 순수 `CREATE TABLE` 이라 구 binary 와 호환되고(구 binary 는 이 테이블을 모른다) **롤백 SQL 은 없다.** 스키마는 되돌리지 않는다.
- **API 를 먼저 배포한다.** Flyway 는 API 만 실행한다. 신 batch 가 V21 적용 전에 뜨면 첫 회차 상태 조회가 테이블 없음으로 실패한다.
- 신 → 구 batch 로 되돌리면 운영 알림만 멈춘다. 상태 행은 마지막 값으로 남고, 다시 올리면 이어서 판정한다.
- Redis key·TTL·payload, 공개 endpoint 는 바뀌지 않는다. 새 lock key 두 개(`lock:owner-alert:delivery`, `lock:owner-ops:signal`)는 기존 `lock:*` 경로의 Job 설정 값이다.

## Discord 수신 체크리스트 (owner-ops-alerts AC14)

자기 Discord 서버에 ops 채널 webhook 을 만들어 로컬 batch 를 띄운 뒤 확인하고, 결과를 **PR 코멘트**로 기록한다.

- [ ] (a) PnL 모니터를 끈 상태에서도 기동 INFO `owner-alert routes:` 에 운영 알림 4종이 기본 경로로 보인다.
- [ ] (b) FX API key 를 일부러 비워 30분 넘게 두면 ops 채널에 **소리**로 환율 수집 지연 알림이 오고(Job 연속 실패 알림도 소리 + 메일), key 를 되돌리면 **무음** 복구 알림이 1회씩 온다.
- [ ] (c) 네트워크를 끊어 시세가 5분 넘게 끊기면 거래소별 알림이 오고 복구 시 무음 알림이 온다. 관찰 기회가 없으면 "미관찰"로 기록한다.
