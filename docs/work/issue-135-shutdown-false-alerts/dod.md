---
feature: batch 종료 시 운영 알림 오탐 정리
slug: issue-135-shutdown-false-alerts
status: FROZEN
frozen_at: 2026-11-12
verdict_commit: 0a52411
source: https://github.com/geonyeop123/premium-spread/issues/135 · design.md
---

## 범위

**포함**

- WebSocket 클라이언트(빗썸·바이낸스): `ContextClosedEvent`에서 정지하고, 정지 뒤 payload 는 ingestion·sink 로 넘기지 않는다 (design D1·D2)
- `JobExecutor`: 종료 신호 뒤 새 실행과 종료 중 끝난 `Failure`를 `Skipped("shutdown")`로 기록하고 CRITICAL 알림을 보내지 않는다 (D1·D3)
- runbook: 모니터 TOCTOU 를 고치지 않는 근거(D4)와, 종료 중 결과가 SKIPPED 로 기록된다는 점

**제외** *(명시적으로 하지 않는 것 — scope creep 차단선)*

- `JobResult.Cancelled`·메트릭 outcome 추가
- 모니터 Job 저장 commit fencing
- `NotificationDeliveryJob`·`OwnerAlertDeliveryJob` 내부 executor, `BoundedOperatorAlertAdapter`의 종료 처리
- 종료가 아닐 때의 실패 분류 변경

## 기존 gate 조사

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있음 — `./gradlew architectureTest` (모듈 의존, 앱의 infrastructure import 금지) | 회귀 방어선. 새 의존이 없으므로 기준으로 세우지 않는다 |
| lint 규칙 | 로컬 `lint` task 없음. ktlint·detekt 는 CI `quality-gate.yml` | CI 에서만 |
| 기존 단위 테스트 | `io.premiumspread.application.common.JobExecutorTest`(종료 신호 없는 실패·timeout·lock 분류), `WebSocketConnectionManagerRegressionTest`(`stop does not increment reconnect attempts` 등 정지 후 재연결 없음) | 예 — R1(종료가 아닐 때 분류 불변), 재연결 차단은 기존 테스트가 이미 고정 |
| CI job | `.github/workflows/quality-gate.yml` | 최종 SHA 회귀 확인 |
| 종료 순서 테스트 | 없음 | 신규테스트로 만든다 (AC3) |

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | `JobExecutor`가 `ContextClosedEvent`를 받은 뒤: (a) 새 `execute`는 `Skipped("shutdown")`을 돌려주고 lock 획득·action 을 호출하지 않는다. (b) 실행 중이던 action 이 그 뒤 `RuntimeException`·`InterruptedException`으로 끝나거나 timeout 에 걸려도 결과는 `Skipped("shutdown")`이고, recorder 에 SKIPPED 로 기록되며, `OperatorAlert.send`는 0회이고, action 종료 뒤 lock 을 해제한다. (c) 대기 중인 호출 스레드가 interrupt 되면 `Skipped("shutdown")`을 돌려주고 호출 스레드의 interrupt 플래그는 true 다. (d) 같은 예외라도 이벤트가 없으면 `Failure`, FAILED, `send` 1회다. (e) `GenericApplicationContext`에 등록한 `JobExecutor`는 phase `Integer.MAX_VALUE` lifecycle 빈의 `stop()` 시점에 이미 `Skipped("shutdown")`을 돌려준다 | 이슈 범위 2·3, design D1·D3 | `요구` · `추론`(원인 무관 분류, 새 실행 생략) | T1 | `신규테스트` `io.premiumspread.application.common.JobExecutorShutdownTest` | `./gradlew :apps:batch:test --tests io.premiumspread.application.common.JobExecutorShutdownTest --offline --no-daemon` | exit 0 |
| AC2 | 빗썸·바이낸스 클라이언트가 `ContextClosedEvent`를 받은 뒤 유효한 시세 payload 를 `handlePayload`에 넣으면 ingestion `onMessage`와 외부 sink `accept`가 0회 호출된다. 이벤트 전에는 같은 payload 가 1회 전달된다 | 이슈 범위 1, design D2 | `요구` | T1 | `신규테스트` `io.premiumspread.infrastructure.batch.exchange.WebSocketClientShutdownTest` | `./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.WebSocketClientShutdownTest --offline --no-daemon` | exit 0 |
| AC3 | `GenericApplicationContext`에 바이낸스 클라이언트와 phase `Integer.MAX_VALUE` lifecycle 빈(Redis 대역)을 등록하고 `close()`하면, 대역의 `stop()` 시점에 클라이언트에 넣은 유효 payload 가 ingestion 으로 전달되지 않는다 | 실측 원인(Redis 가 WebSocket 보다 먼저 정지), design D1 | `요구` · `추론`(순서 고정) | T1 | `신규테스트` `io.premiumspread.infrastructure.batch.exchange.WebSocketShutdownOrderingTest` | `./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.WebSocketShutdownOrderingTest --offline --no-daemon` | exit 0 |
| AC4 | 로컬 batch 를 기동해 1분 이상 실행한 뒤 SIGTERM 으로 종료하면, 그 로그에서 `Commencing graceful shutdown` 이후 `[ALERT][CRITICAL]` 줄이 0건이다 (변경 전 기준선 12건) | 이슈 실측 | `요구` | T3 | 기록된 관찰 — 로그 파일 `$CLAUDE_JOB_DIR/tmp/batch-135-shutdown.log`의 해당 구간 건수 | — | 증거 로그에 건수와 명령을 기록, owner 사후 검토 |

**기준 개수**: 4개

**회귀 방어선** (기준 아님, RED 면제): `./gradlew test architectureTest :apps:batch:integrationTest :infrastructure:common:integrationTest --offline --no-daemon` 전체 통과. 기존 `JobExecutorTest`와 `WebSocketConnectionManagerRegressionTest`가 무수정 통과하는 것이 "종료가 아닐 때 분류 불변"의 증거다.

## 증거 로그

### Gate 1 기준선
- 2026-11-12 `batch-run-05.log` 종료 구간(09:25:29.749 `Commencing graceful shutdown` 이후): `[ALERT][CRITICAL]` 12건(전부 `[binance] WebSocket ingestion hash 저장 5회 연속 실패`). AC4 의 기준선이다.
- 2026-11-12 AC1~AC3 검증 명령을 작성 시점에 실행했다. 세 명령 모두 exit 1(`No tests found for given includes`)이다. 테스트 클래스가 없어서 생긴 실패라 RED 가 아니다. RED 는 구현 중 assertion 실패로 따로 남긴다.

### RED (구현 전, assertion 수준)
- **AC1** (`onApplicationEvent` 빈 stub, 95e1a7a 이전): `JobExecutorShutdownTest` 7건 중 6건이 실패했다. 대조군 1건은 통과했다.
  - 실패 예: `expected Skipped(reason=shutdown) but was: Success`, `but was: Failure(RuntimeException: LettuceConnectionFactory is STOPPING)`, `but was: Failure(JobExecutionTimeoutException: fx_ingestion exceeded execution timeout 100ms)`.
- **AC1 자식 컨텍스트:** 자식 컨텍스트 종료를 무시하는 테스트는 리스너가 모든 이벤트를 받던 상태에서 실패했다(5b605d7 전).
- **AC2** (43af740 이전): 종료 뒤 전달 금지 테스트 4건이 `onMessage(any())) should not be called`로 실패했다. 종료 전 전달 테스트 2건은 기대대로 통과했다.
- **AC3** (43af740 이전): phase `Integer.MAX_VALUE` lifecycle `stop()` 안에서 `BinanceTickerIngestion.onMessage`가 호출돼 실패했다.

### GREEN (Gate 2 @ 0a52411, `--rerun-tasks`)
- **AC1** `./gradlew :apps:batch:test --tests io.premiumspread.application.common.JobExecutorShutdownTest --offline --no-daemon` → exit 0, BUILD SUCCESSFUL
- **AC2** `./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.WebSocketClientShutdownTest --offline --no-daemon` → exit 0
- **AC3** `./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.WebSocketShutdownOrderingTest --offline --no-daemon` → exit 0
- **회귀 방어선** `test architectureTest :apps:batch:integrationTest :infrastructure:common:integrationTest --continue` → exit 0. JUnit 결과는 1,947건 실행, 실패·에러·skip 0이다.
  - 5b605d7(`ApplicationContextAware` 판) 에서는 같은 명령이 wiring 테스트 17건 실패로 exit 1 이었다. strict `mockk<JobExecutor>` 빈에 `setApplicationContext`가 호출됐기 때문이다.
  - 0a52411 에서 판정을 루트 컨텍스트(`parent == null`)로 바꿔 해소했다. 기존 테스트는 수정하지 않았다.
- **ktlint** (CI 패턴 `**/src/**/*.kt`) 위반 0. detekt 는 로컬 bootstrap 이 CI 전용이라 PR CI 에서 확인한다.

### AC4 (T3 기록된 관찰)
- 명령: `$CLAUDE_JOB_DIR/tmp/ac4-135.sh`. 순서는 다음과 같다.
  1. 실계정 env 로 `SPRING_PROFILES_ACTIVE=local ./gradlew :apps:batch:bootRun`을 실행한다.
  2. `Started` 이후 75초를 기다린다.
  3. JVM 에 `kill -TERM`을 보낸다.
  4. `Commencing graceful shutdown` 이후 구간의 줄을 센다.
- 로그: `$CLAUDE_JOB_DIR/tmp/batch-135-shutdown.log` (로컬 보관).
- 결과 @ 0a52411 (2026-11-12 14:19:39): 종료 구간 `[ALERT][CRITICAL]` 0건, `[ALERT]` 0건, `ingestion hash save failed` 0건이다.
  - `Stopping Binance/Bithumb WebSocket client`가 14:19:39.153 에 각 1회 찍혔다. graceful shutdown(.155)보다 앞선다.
  - 같은 측정을 5b605d7 에서도 했고(14:01:23) 결과가 같았다.
- 기준선(변경 전 09:25:31) 은 12건이다.

## 판정

```
DoD VERDICT: issue-135-shutdown-false-alerts @ 0a52411
  수용기준 표:     4개  (T1 3 · T2 0 · T3 1 · T4 0)
  T1/T2 자동:      3개 중 3개 PASS
  T3 기록 제출:    1개 중 1건
  T4 사람 확인:    0개
  변경 요청:       0건
  => VERIFIED
```

## 변경 요청

(없음)
