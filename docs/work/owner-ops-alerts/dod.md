---
feature: owner 운영 알림 — 환율 수집 지연·시세 수집 끊김·Job 연속 실패 + 공용 owner 알림 기반 (issue #91, 상위 #87)
slug: owner-ops-alerts
status: FROZEN
frozen_at: 2026-10-28
verdict_commit: bdb09c1
source: docs/work/owner-ops-alerts/design.md (DRAFT)
---

## 범위

**포함**

- `OwnerAlertKind` 4종(`FX_INGESTION_DELAYED`·`MARKET_STREAM_DOWN`·`JOB_CONSECUTIVE_FAILURES`·`OPS_RECOVERED`)과 기본 경로, yml routes 4블록·override env 12개 (design §2)
- 범용 enqueue port `OwnerAlertOutbox` 와 신호 상태 타입, V21 `owner_alert_signal_state`, `JdbcOwnerAlertOutbox`(auto-configuration 등록), outbox INSERT 단일 소유자 추출 (§3·§4)
- 운영 신호 모델·3상태 관측·장애 정책·렌더러 (`domain.ownerops`, §5·§10)
- `JobId` 2종, `JobFailureStreakReadPort` 와 in-memory 카운터 (§6)
- `OwnerAlertDeliveryJob`·`OwnerOpsSignalJob`·Scheduler 2개, JobConfig·BatchJobProperties·BatchSchedulingProperties (§7·§8)
- 채널 bean 3개를 PnL 모니터 구성에서 `OwnerAlertChannelConfiguration` 으로 이동, 앱 기동 조건·배선, `owner-alert.ops.*` (§9)
- runbook 신규·갱신, 아키텍처·프로젝트 상태 문서 (§13)

**제외** *(scope creep 차단선)*

- #90 시장 알림 기능, #89 계정 알림 변경
- 기존 Slack `OperatorAlert` 경로·문구, WebSocket 재연결 로직 변경 (사용자 합의 U5)
- `AccountPnlMonitorJob` 관측·정책·회차 안 전달, `OwnerAlertDispatcher` 동작 변경
- outbox 테이블·Domain 행 타입 rename, outbox 메서드의 상위 interface 승격
- Redis key·TTL·payload, 새 메트릭, 공개 endpoint
- FX 허용 공백 30분 값 변경, 다중 인스턴스 연속 실패 합산
- 직전 단위 산출물(`docs/work/owner-alert-channel/**`·`docs/work/owner-account-alerts/**` 등) 수정

## 사용자 합의 (③)

| # | 합의 | design |
|---|---|---|
| U1 | 공용 기반(범용 enqueue port·독립 전달 Job·채널 bean 분리)을 #91 에서 만든다. PnL 알림 동작(이중 발송 금지, claim 1건씩, 회차 상한) 무회귀. #90 기능은 만들지 않는다 | §0, §3, §7, §9.3 |
| U2 | 환율 = 우리 쪽 마지막 FX 수집 성공(없으면 프로세스 첫 실행) 후 30분. 시세 = 거래소별 최신 tick 나이 5분 이상. Job = 연속 Failure 3회 이상 ∧ 첫 실패 후 5분 이상, Skipped 는 세지도 리셋하지도 않음, Success 면 리셋 | §5.2, §6.2 |
| U3 | 복구: 해소되면 1회, OPS 무음, 메일 false | §2, §5.3 |
| U4 | 재알림: 지속 중 6시간마다 1회. 복구 뒤 재발생은 새 장애 | §5.3 |
| U5 | 기존 Slack `OperatorAlert` 는 그대로 | §1.2, §6.3 |

오케스트레이터 판정: SMTP 는 켜졌지만 owner 수신 주소가 없는 기동은 WARN 이 아니라 INFO(SMTP 가 회원 알림과 공유되기 때문, design §9.2).

## 기존 gate 조사

> Gate 1 필수. 작성 시점에는 문서·소스만 읽었다. 실행 기준선은 plan Task 0 이 아래 증거 로그에 남긴다.

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있다 — root `architectureTest` (모듈 경계·import·bytecode·test isolation·관측 cardinality) | 회귀 방어선 R1. 신규 규칙 없음 |
| lint 규칙 | 있다 — ktlint·detekt (CI `quality-gate.yml`). 로컬 gradle task 없음 | CI 확인 항목 R14 로만. 로컬 우회 금지 |
| custom gradle task | 있다 — `architectureTest`, `:infrastructure:common:integrationTest`, `:apps:batch:integrationTest`, `:apps:api:integrationTest`, `:infrastructure:common:verifyMigrations`, `harnessCheck`, `jacocoTestReport`/`jacocoTestCoverageVerification` | 그대로 쓴다 |
| 문서 검사 | 있다 — `bash docs/check-documentation.sh` (정본 문서·runbook) | AC13 · R12 |
| 동결·직전 단위 테스트 | 있다 — 실계정 Domain·Job·adapter 테스트, #88 산출(디스패처·Discord adapter·채널 판정·Properties·렌더링·V19·E2E), #89 산출(계정 알림 정책·렌더러·V20·E2E) | R3 무수정 |
| enum 개수 고정 단언 | 있다 — `OwnerAlertRoutingTest` 의 종류 목록 `containsExactly` 2곳(#89 R7 이후 개수 중립 이름). `OwnerAlertPropertiesTest` 는 `contains` 부분 문자열 | 허용 예외 R7 — 기대 목록 **끝에** 4개 추가만 (Task 0 이 줄 번호 확정) |
| yml routes = enum 계약 | 있다 — `OwnerAlertYamlContractTest` (yml routes key 집합 = `OwnerAlertKind` key 집합) | AC1 이 무수정 통과로 쓴다 (routes 4블록을 enum 과 같은 커밋에 넣는다) |
| JobId 전수 사용처 | 있다 — `DefaultJobConfigProvider`·`BatchJobProperties` 의 `when` (컴파일이 강제) | AC10. 그 밖 `JobId.entries` 단언은 Task 0 grep |
| 채널 bean 구성을 고정하는 wiring 테스트 | 있다 — `AccountPnlMonitorWiringTest` 의 runner 구성 목록(`:72-79`)과 `assertNoBeans()`(`:81-91`)의 채널 bean 부재 단언 `:89` `doesNotHaveBean(AccountPnlEmailSenderAdapter)`. 이동 뒤 PnL 꺼짐 + 채널 ready 테스트(`:98-101`, `:103-108`, `:337-361`, `:363-370`, `:372-388`, `:390-398`)에서 깨진다. 이런 부재 단언은 이 파일 하나뿐(infrastructure/batch 테스트엔 없음 — ⑥ R1 확인) | 허용 예외 R8 — 정확한 diff 범위는 R8. 대신 양성 단언 AC15 |
| auto-configuration 등록 방식 | 있다 — `CommonInfrastructureAutoConfiguration.kt:67-81` `CommonJdbcConfiguration` 의 명시적 `@Import`(`JdbcAccountPnlAlertStore` `:74`). component scan 아님 | AC7-i 가 실제 auto-configuration 으로 bean 해석을 잰다 |
| `OwnerAlertProperties` 검증 규칙 | 있다 — `OwnerAlertProperties.kt:15`, `:82-118`(webhook 형식·override 값 검증), 기존 테스트 `:809-822`, `:824-828` | AC11-h 가 PnL 꺼짐·Discord 꺼짐에도 같은 규칙이 적용됨을 잰다 |
| batch fixture 스키마 고정 | 있다 — `AccountPnlFixtureSchemaIntegrationTest` | `account_pnl_*` 두 테이블만 단언하면 무수정(Task 0 확인). 테이블 목록을 단언하면 허용 예외 R9 |
| migration 경로 테스트 패턴 | 있다 — `V19OwnerAlertChannelMigrationIntegrationTest`·`V20OwnerAccountAlertStateMigrationIntegrationTest` | AC6 이 같은 패턴 |
| API Flyway 전체 적용 | 있다 — `apps/api` `FlywayOwnershipIntegrationTest` | 회귀 방어선 R13 (V21 까지 적용) |
| owner 알림 테스트 fake | 있다 — `OwnerAlertTestFakes.kt`(`FakeOwnerAlertStore`·`FakeMailSender`·`FakeDiscordSender`·`MutableClock`) | AC8·AC9 가 **무수정 재사용** |
| 동시 claim·fencing 통합 테스트 | 있다 — `JdbcAccountPnlAlertStoreIntegrationTest` | R10 회귀 방어선. AC12 가 두 디스패처 동시 회차를 추가로 잰다 |

**결론.** T1 수용기준은 모두 `신규테스트` 또는 `기존`(확장·무수정)으로 덮는다. 새 스크립트 파일은 만들지 않는다.
검증 명령은 모듈 task 에 `--tests` 필터를 붙인 단일 gradle 호출이다(여러 모듈이면 `:a:test --tests X :b:test --tests Y` 한 줄 — `&&` 조합 없음).

## 수용기준

공통 고정값: zone `Asia/Seoul`, 고정 `Clock`/`Instant`(기준 `2026-10-28T00:00:00Z`), 테스트 URL 은 `https://discord.example.test/...` 또는 MockWebServer(localhost)만,
수신 주소는 `owner@example.test` 같은 가짜 값. 실제 webhook·API key·주소 금지.

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | (a) `OwnerAlertKind.entries` 이름이 순서대로 기존 7개 + `FX_INGESTION_DELAYED`·`MARKET_STREAM_DOWN`·`JOB_CONSECUTIVE_FAILURES`·`OPS_RECOVERED`, key 는 `fx-ingestion-delayed`·`market-stream-down`·`job-consecutive-failures`·`ops-recovered`. (b) 기본 경로 = `(false, OPS/SOUND)`·`(false, OPS/SOUND)`·`(true, OPS/SOUND)`·`(false, OPS/SILENT)`. (c) 메일+Discord 채널이면 4종 `resolve` = 기본 경로, `EMAIL_ONLY` 이면 4종 모두 `(true, null)`. (d) `OwnerAlertRoutingTest` 는 허용 예외(R7)만으로, `OwnerAlertPropertiesTest`·`OwnerAlertYamlContractTest` 는 무수정으로 통과 | design §2 | `요구` (#88 design §3.2 목표 표, U3) + 복구 종류 추가는 `추론` (K3) + Discord 꺼짐 메일은 `상위` (#88 D1) | T1 | `신규테스트` `io.premiumspread.domain.owneralert.OwnerOpsAlertKindTest` + `기존` | `./gradlew :domain:test --tests 'io.premiumspread.domain.owneralert.*' :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.owneralert.OwnerAlertPropertiesTest' :apps:batch:test --tests 'io.premiumspread.config.OwnerAlertYamlContractTest' --offline --no-daemon` | exit 0 |
| AC2 | `OpsSignals` 가 design §5.2 판정표 전 행을 낸다: (a) FX — 마지막 성공 `now−30m` → `Up`, `now−30m−1ns` → `Down(since)`, 성공 없음 + `firstRunAt = now−30m` → `Unknown`, `now−30m−1ns` → `Down(null)`. (b) 시세 — tick 나이 `5m−1ms` → `Up`, `5m` → `Down(observedAt)`, 미래 observedAt → `Up`, tick 없음 + `firstRunAt = now−5m` → `Down(null)`, `now−5m+1ms` → `Unknown`. (c) Job — `(3회, 첫 실패 now−5m)` → `Down(first, 3)`, `(3회, now−5m+1ms)`·`(2회, now−1h)` → 마지막 성공 있으면 `Up`·없으면 `Unknown`, streak 없음 + 성공 있음 → `Up`, 둘 다 없음 → `Unknown`. (d) 신호 key 가 `fx-ingestion.usd-krw`·`market-stream.bithumb.btc-krw`·`market-stream.binance.btc-usd`·`job-failures.fx_ingestion` 이고 `alertKind` 매핑이 §5.1 과 같다. (e) `OpsAlertSettings` 기본값 `5m·3·5m·6h`, `marketStaleAfter=0`·`jobFailureCount=0`·`jobFailureDuration=-1s`·`realertInterval=0` 은 design 메시지의 `IllegalArgumentException`. (f) `JobFailureStreak(consecutiveFailures = 0)`·`lastFailureAt < firstFailureAt` 은 `IllegalArgumentException` | design §5.1, §5.2, §6.1 | `요구` (U2 기준값·경계 "이상") + FX `>` 경계는 `추론` (K-FX) + 3상태는 `추론` (K4) | T1 | `신규테스트` `io.premiumspread.domain.ownerops.OpsSignalsTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.ownerops.OpsSignalsTest' --offline --no-daemon` | exit 0 |
| AC3 | `OpsIncidentPolicy.decide` 가 design §5.3 전이표 T1~T6 대로 `next`·notice 를 낸다: (a) 정상+`Down` → `incident(now, now, 1)`·`Alert(seq 1)`, next.version = current.version. (b) 정상+`Up`/`Unknown` → `(null, null)`. (c) 장애(마지막 알림 `now−6h`)+`Down` → `notifyCount+1`·`lastNotifiedAt=now`·`startedAt` 유지·`Alert(seq n+1)`, `now−6h+1ms` → `(null, null)`. (d) 장애+`Up` → incident null·`Recovered(notifyCount)`, 장애+`Unknown` → `(null, null)`. (e) 복구 다음 회차 `Down` → 새 `startedAt` 의 `Alert(seq 1)`. (f) signalKey 불일치는 `IllegalArgumentException`. (g) `OwnerAlertSignalState`·`OwnerAlertIncident` 불변식(key `Bad Key`·`a:b`·65자·빈 문자열, version −1, notifyCount 0, lastNotified < started) 거부, `initial` 은 incident null·version 0 | design §3.2, §5.3 | `요구` (U3 복구 1회, U4 6시간·새 장애) + 간격 기준(마지막 알림)은 `추론` | T1 | `신규테스트` `io.premiumspread.domain.ownerops.OpsIncidentPolicyTest` + `io.premiumspread.domain.accountpnl.OwnerAlertSignalStateTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.ownerops.OpsIncidentPolicyTest' --tests 'io.premiumspread.domain.accountpnl.OwnerAlertSignalStateTest' --offline --no-daemon` | exit 0 |
| AC4 | `OwnerOpsAlertContent` 가 design §10.2 템플릿과 같다: (a) 메일 golden(eventKey·제목·본문 전체 `isEqualTo`) — FX 첫 알림(마지막 성공 있음), FX 첫 알림(기록 없음), 시세 빗썸 첫 알림, Job 재알림(seq 2, 4회), 복구(바이낸스 시세, 알림 2회). (b) Discord 같은 시나리오의 제목·설명·색 전체 golden, 색 상수 2개 값 `15105570`·`9807270`. (c) eventKey 가 `owner-ops.{key}.{ms}.alert-{n}`/`.recovered` 이고 `[A-Za-z0-9._-]{1,128}` 에 맞는다. (d) 표기 — `threshold(30m)`=`30분`, `threshold(6h)`=`6시간`, `threshold(90s)`=`90초`, `elapsed(6h0m30s)`=`6시간 0분`, `elapsed(4m59s)`=`4분`. (e) 모든 결과 문자열에 `example.test`·`/api/webhooks/`·`@`·`http` 가 없다 | design §10 | `요구` (알림 본문 금지 값) + 문구 세부는 `추론` | T1 | `신규테스트` `io.premiumspread.domain.ownerops.OwnerOpsAlertContentTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.ownerops.OwnerOpsAlertContentTest' --offline --no-daemon` | exit 0 |
| AC5 | `RedisJobRunRecorderAdapter.findFailureStreak` 가 (a) FAILED 3회(`t0`,`t1`,`t2`) → `(3, t0, t2)`, (b) 중간 SKIPPED 는 무변경, (c) SUCCEEDED → null, 이어 FAILED → `(1, t, t)`, (d) Job 별로 독립, (e) `StringRedisTemplate` 쓰기가 예외를 던지는 SUCCEEDED `completed` 뒤에도 카운터가 갱신돼 있다, (f) 같은 Job 의 동시 FAILED 100건 → 100, (g) 기존 `infrastructure:batch` job 패키지 테스트 무수정 통과 | design §6.2 | `요구` (U2 Skipped·Success 규칙) + Redis 예외 내성·동시성은 `추론` | T1 | `신규테스트` `io.premiumspread.infrastructure.batch.job.RedisJobRunRecorderFailureStreakTest` + `기존` | `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.job.*' --offline --no-daemon` | exit 0 |
| AC6 | (a) 빈 DB → latest 면 버전 1~21 성공, `owner_alert_signal_state` 열이 design §4.1 순서·타입·nullable 과 같고 PK 는 `signal_key`, 테이블 collation `utf8mb4_unicode_ci`. (b) V20 스키마에 기존 `account_pnl_*` 행을 넣고 latest → 기존 행 무변경, 새 테이블 0행. (c) CHECK — `active = TRUE` + `incident_started_at` NULL, `active = FALSE` + `notify_count = 1`, `last_notified_at < incident_started_at`, `version = 0` 은 각각 거부. (d) `verifyMigrations` 통과(R5) | design §4.1 | `요구` (V21 별도 상태 테이블) + CHECK 는 `추론` | T1 | `신규테스트` `io.premiumspread.infrastructure.common.migration.V21OwnerAlertSignalStateMigrationIntegrationTest` | `./gradlew :infrastructure:common:integrationTest --tests 'io.premiumspread.infrastructure.common.migration.V21OwnerAlertSignalStateMigrationIntegrationTest' --offline --no-daemon` | exit 0 (Docker 필요) |
| AC7 | 실제 MySQL 에서 `JdbcOwnerAlertOutbox` 가 (a) 신호 2개(신규 INSERT) + 행 2개 enqueue → true, `loadSignals` 가 version 1·같은 incident 로 읽는다, 행 2개 `PENDING`. (b) version 불일치 신호가 두 번째에 있으면 false 이고 첫 신호·행 모두 미기록. (c) 이미 있는 key 를 version 0 으로 INSERT → false·무기록. (d) `signals = []` + 행 → 행만 들어가고, 같은 (eventKey, channel) 재enqueue 는 행 수 불변. (e) 복구(incident null) 저장 → `active = 0`·NULL·0. (f) 중복 signalKey 입력·256자 subject 행은 `IllegalArgumentException`, DB 무변경. (g) enqueue 한 행을 `JdbcAccountPnlAlertStore.claim` 이 집고 `markSent` 한다. (h) 기존 `JdbcAccountPnlAlertStoreIntegrationTest` 무수정 통과. (i) 실제 `CommonInfrastructureAutoConfiguration`(JDBC auto-configuration 과 함께)으로 띄운 컨텍스트에서 `OwnerAlertOutbox` bean 이 정확히 1개이고 타입이 `JdbcOwnerAlertOutbox` | design §3.2, §4.3, §4.4 | `요구` (U1 범용 enqueue port) + 원자성·롤백은 `추론` (K1) + 등록 방식은 `리뷰` (⑥ R1 Critical 2) | T1 | `신규테스트` `io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcOwnerAlertOutboxIntegrationTest` + `기존` | `./gradlew :infrastructure:common:integrationTest --tests 'io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.*' --offline --no-daemon` | exit 0 (Docker 필요) |
| AC8 | `OwnerOpsSignalJob`(fake port·`FakeOwnerAlertOutbox`·#88 `MutableClock`, fake 의 시각은 모두 clock 기준 상대값, routing 메일+Discord) 이 (a) 모두 정상이면 enqueue 0회·`Success`. (b) FX 마지막 성공 `now−31m` → `FX_INGESTION_DELAYED` DISCORD(OPS/SOUND) 1행(메일 없음), 신호 상태 1개, FX 신호 key 가 `BatchMarket.fxBase/fxQuote` 에서 온다(`fx-ingestion.usd-krw`). (c) 바이낸스 tick 6분 전 → `MARKET_STREAM_DOWN` 1행, 조회한 `(exchange, quote)` 가 `BatchMarket` 의 korea·foreign 두 쌍뿐. (d) `PREMIUM_REALTIME` streak `(3, now−5m)` → `JOB_CONSECUTIVE_FAILURES` EMAIL+DISCORD 2행, `OWNER_OPS_SIGNAL`·`OWNER_ALERT_DELIVERY` streak 은 행을 만들지 않는다. (e) 6시간 뒤 같은 장애 → `alert-2` 행, 그 사이 회차는 enqueue 0회(다른 신호는 계속 정상). (f) 해소 → `OPS_RECOVERED` DISCORD(OPS/SILENT) 1행. (g) 새 Job 인스턴스(재시작) + 저장된 FX 장애 + 성공 기록 없음 → 30분 동안 enqueue 0회(가짜 복구 없음). (h) `EMAIL_ONLY` → 4종 모두 EMAIL 1행. (i) JobResult — `loadSignals` 예외 `Failure`, `enqueue` 예외 `Failure`, false `Skipped("state-version-conflict")`, 저장 전 interrupt `Skipped("cancelled")`, 한 신호 관측 예외 → 나머지 신호는 판정되고 `Success`. (j) 로그에 `example.test`·수신 주소·관측 예외 메시지가 없다. (k) 관측 port 가 `InterruptedException` 을 감싼 예외(또는 `CancellationException`)를 던지면 `run()` 안의 action 이 그 예외를 다시 던지고(결과로 삼키지 않음), interrupt 플래그가 서 있고, enqueue 0회 | design §5, §8 | `요구` (U2·U3·U4, PnL 과 독립) + 감시 제외·관측 예외 처리는 `추론` (K7) + 취소 재전파는 `리뷰` (⑥ R1 Minor 5) | T1 | `신규테스트` `io.premiumspread.application.job.ownerops.OwnerOpsSignalJobTest` | `./gradlew :apps:batch:test --tests 'io.premiumspread.application.job.ownerops.*' --offline --no-daemon` | exit 0 |
| AC9 | `OwnerAlertDeliveryJob`(#88 `FakeOwnerAlertStore`·`FakeMailSender`·`FakeDiscordSender` 재사용) 이 (a) 매 회차 `recoverStale(now, 5m)` 을 claim 보다 먼저 부르고, 예외면 claim 없이 `Failure`. (b) PENDING 행 12개면 한 회차에 claim 10회·발송 10건, 다음 회차에 2건. (c) claim owner 가 `owner-alert-delivery:` 로 시작하고 64자 이하. (d) Discord 재시도 가능 실패 행은 회차 끝 `markAttemptFailed`, 영구 실패는 즉시 `markFailed`, 결과 `Success`. (e) `claim` 예외 → `Failure`. (f) 기존 `OwnerAlertDispatcherTest`·`AccountPnlMonitorJob*Test` 무수정 통과 | design §7 | `요구` (U1 독립 전달 Job·claim 1건씩·회차 상한) | T1 | `신규테스트` `io.premiumspread.application.notification.owneralert.OwnerAlertDeliveryJobTest` + `기존` | `./gradlew :apps:batch:test --tests 'io.premiumspread.application.notification.owneralert.*' --tests 'io.premiumspread.application.job.accountpnl.*' --offline --no-daemon` | exit 0 |
| AC10 | (a) `DefaultJobConfigProvider` 와 `BatchJobProperties()` 기본값이 `OWNER_ALERT_DELIVERY = (lock:owner-alert:delivery, 50s, 40s)`, `OWNER_OPS_SIGNAL = (lock:owner-ops:signal, 20s, 10s)` 이고 두 provider 가 모든 `JobId` 에서 같은 값을 낸다. (b) yml 을 읽은 `BatchJobProperties`·`BatchSchedulingProperties` 가 같은 값과 주기 60s·30s 를 낸다(변경 요청 #1). (c) 기존 14개 JobId 값 무변경 | design §8.4 | `요구` (lock·lease·timeout 지정) + 값은 `추론` | T1 | `신규테스트` `io.premiumspread.config.OwnerAlertJobConfigTest` | `./gradlew :apps:batch:test --tests 'io.premiumspread.config.OwnerAlertJobConfigTest' --offline --no-daemon` | exit 0 |
| AC11 | `ApplicationContextRunner` 에서 (a) **PnL 모니터 꺼짐** + Discord 켜짐·ops webhook → bean `OwnerAlertRouting`(channels `(false, true)`)·`OwnerAlertDiscordSender`·`OwnerAlertDeliveryJob`·`OwnerOpsSignalJob`·Scheduler 2개 존재, `AccountPnlMonitorJob` 없음. (b) PnL 꺼짐 + SMTP·`EmailSender`·수신 주소 → 메일 sender 존재·Discord 없음·두 Job 존재. (c) 채널 없음(로컬 기본) → 두 Job·채널 bean 없음, WARN·INFO 없음. (d) Discord 켜짐 + webhook 없음 → 없음 + WARN `owner-alert channel not ready; owner alert delivery and ops alerts not started: missing=[], discordWebhookMissing=true`; SMTP 켜짐 + 수신 주소 없음(Discord 꺼짐) → 없음 + **INFO**(WARN 아님) `owner-alert email channel has no owner recipient; owner alert delivery and ops alerts not started: missing=[ACCOUNT_PNL_ALERT_RECIPIENT]`. 로그에 webhook·주소 값 없음. (e) `owner-alert.ops.enabled=false` → 전달 Job 만 있고 INFO `owner-alert ops alerts disabled by OWNER_ALERT_OPS_ENABLED`. (f) `batch.scheduling.enabled=false` → 둘 다 없음. (g) `owner-alert.ops.*` 4개 값이 bean `OwnerOpsAlertProperties.toSettings()` 에 반영되고 `realert-interval=0s` 는 기동 실패(원인에 design 메시지). (h) 채널 ready 면 Discord 켜짐 여부와 무관하게 webhook·override 를 검증해 기동 실패 — PnL 꺼짐 + Discord 켜짐 + `webhook.ops=http://discord.example.test/x` → 실패, **PnL 꺼짐 + Discord 꺼짐 + 메일 ready + `webhook.ops=http://discord.example.test/x` → 실패**, PnL 꺼짐 + 메일 ready + `owner-alert.routes.ops-recovered.push=loud` → 실패. 실패 메시지에 값 없음. (i) app 조건 = infra `ready` 규칙 행렬(email 요청 3 × SMTP 2 × 수신 주소 2 × Discord 요청 2 × webhook 2). (j) PnL 켜짐 구성은 기존 wiring 테스트가 허용 예외(R8)만으로 통과 | design §9 | `요구` (U1(c) PnL 꺼져도 운영 알림) + 조건 이중화·로그는 `추론` + INFO 등급은 `리뷰` (오케스트레이터 판정) + 검증 시점은 `리뷰` (⑥ R1 Major 3) + ops 기본 켜짐은 `추론` (A1) | T1 | `신규테스트` `io.premiumspread.infrastructure.batch.owneralert.OwnerAlertChannelConfigurationTest` + `io.premiumspread.config.OwnerAlertDeliveryWiringTest` + `기존` (허용 예외) | `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.owneralert.*' :apps:batch:test --tests 'io.premiumspread.config.*' --offline --no-daemon` | exit 0 |
| AC12 | 실제 MySQL(batch fixture, V21 포함) + 실제 `JdbcOwnerAlertOutbox`·`JdbcAccountPnlAlertStore` + 기록용 메일·Discord sender + `MutableClock`(fake port 시각은 clock 기준 상대값) 으로 (a) FX 마지막 성공 31분 전 → 운영 신호 회차 후 전달 회차 → `owner-ops.fx-ingestion.usd-krw.{ms}.alert-1` DISCORD 행 `SENT`, sender 1회(OPS/SOUND). (b) 시계 +6h → `alert-2` `SENT`, 다른 신호의 행 없음. (c) 성공 기록 갱신 → `.recovered` DISCORD 행 `SENT`(OPS/SILENT), `owner_alert_signal_state.active = 0`. (d) 장애 중 새 Job 인스턴스(재시작) + 성공 기록 null → 29분 동안 새 행 없음, 상태 행 `active = 1`. (e) PENDING 6행에 대해 전달 Job 과 PnL owner(`account-pnl-monitor:…`) 디스패처가 동시에 dispatch → 행마다 sender 호출 정확히 1회, 모두 `SENT` | design §4, §7.3, §8 | `요구` (U1 이중 발송 금지·PnL 독립, U3, U4) | T1 | `신규테스트` `io.premiumspread.application.job.OwnerOpsAlertIntegrationTest` | `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.application.job.OwnerOpsAlertIntegrationTest' --offline --no-daemon` | exit 0 (Docker 필요) |
| AC13 | (a) 신규 `docs/runbooks/owner-ops-alert.md` 에 design §13 첫 항목 전부(판정 기준·경계, 재알림·복구, 라우팅 표 4종과 **Discord 꺼짐 시 4종 모두 메일**(굵게), override env 12개, `owner-alert.ops.*`·주기·lock env, 기동 판정·WARN/INFO 전문과 수신 주소 누락이 INFO 인 이유, 채널 ready 면 Discord 켜짐과 무관하게 webhook·override 검증, 재시작 한계, 감시 제외와 Slack 대체 경로, 정리 SQL, V21 배포, AC14 체크리스트). (b) `docs/runbooks/account-pnl-alert.md` 에 채널 bean 독립·검증 시점 변화·라우팅 표·override 목록 4종 추가. (c) `.ai/architecture/ARCHITECTURE_DESIGN.md` 운영 알림 단락, `.ai/PROJECT_STATUS.md` `Flyway V1~V21`·V21 행·기능 항목. (d) `docs/check-documentation.sh` 통과 | design §13 | `요구` (문서 갱신 대상 지정) | T3 | 기록 문서 + `기존` `docs/check-documentation.sh` | `bash docs/check-documentation.sh` | exit 0 + (a)~(c) 존재를 사람 사후 검토 |
| AC14 | owner 가 자기 Discord ops 채널 webhook 으로 로컬 batch 를 띄웠을 때 (a) PnL 모니터를 끈 상태에서도 기동 INFO `owner-alert routes:` 에 4종이 기본 경로로 보인다. (b) FX API key 를 일부러 비워 30분 넘게 두면 ops 채널에 **소리**로 환율 수집 지연 알림이 오고(Job 연속 실패 알림도 소리 + 메일), key 를 되돌리면 **무음** 복구 알림이 1회씩 온다. (c) 네트워크를 끊어 시세가 5분 넘게 끊기면 거래소별 알림이 오고 복구 시 무음 알림이 온다(관찰 기회가 없으면 "미관찰") | design §0, §10 | `요구` (실제 Discord 수신·푸시 수준) | T4 | owner 확인 | — | PR 코멘트에 owner 확인 기록. (c) 는 미관찰 허용 |
| AC15 | `AccountPnlMonitorWiringTest` 와 같은 runner 구성(+ `OwnerAlertChannelConfiguration`)에서 채널 bean 은 PnL 모니터와 무관하게 **ready 조건으로만** 뜬다: R8 로 부재 단언을 뺀 기존 6개 시나리오(플래그 false / 계정 key 하나 누락(recipient 제외) / 그 밖 `:337-398` 의 PnL 꺼짐 시나리오)를 같은 property 로 다시 돌렸을 때 `AccountPnlMonitorJob`·계정 adapter 는 없고 `AccountPnlEmailSenderAdapter`·`OwnerAlertRouting` 은 **있다**(채널 ready). 같은 시나리오에서 `account-pnl-monitor.recipient=` 이면 채널 bean 도 없다. `batch.scheduling.enabled=false` 면 채널 bean 도 없다 | design §9.3 | `리뷰` (⑥ R1 Critical 1 — R8 축소의 대가인 양성 단언) | T1 | `신규테스트` `io.premiumspread.config.OwnerAlertChannelIndependenceWiringTest` | `./gradlew :apps:batch:test --tests 'io.premiumspread.config.OwnerAlertChannelIndependenceWiringTest' --offline --no-daemon` | exit 0 |

**기준 개수**: 15개

**분할하지 않는 이유**: 운영 신호 Job 은 범용 enqueue port(AC7)·전달 Job(AC9)·채널 bean 분리(AC11·AC15) 없이는 PnL 모니터가 꺼진 환경에서 한 건도 보낼 수 없다 —
사용자 합의 U1 이 셋을 #91 안에서 만들기로 정했다. 기반만 먼저 머지하면 생산자가 없어 E2E(AC12)로 검증할 수 없고, 운영 신호만 먼저 머지하면 전달 경로가 없다.
V21(AC6)은 신호 상태의 유일한 저장소라 정책(AC3)과 떼어 낼 수 없다.

**티어 강등 사유** *(T1 이 아닌 항목만. Gate 1 에서 사람이 함께 승인한다)*

- AC13 → T3: runbook 설명의 충분성은 exit code 로 잴 수 없다. `check-documentation.sh` 는 형식만 본다.
- AC14 → T4: 실 webhook·실 API key 는 테스트·CI 에 쓰지 않는다(testing 규칙). 푸시 체감과 실제 장애 재현은 사람만 확인한다.

## 회귀 방어선

이번 변경으로 깨질 수 있는 기존 동작. RED 면제 대상이다. base = `20a7484` (Task 0 확인).

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 모듈 경계·import·관측 cardinality·테스트 격리 아키텍처 규칙 전체 (앱 main 에 infra import 없음, Domain 에 새 framework 의존 없음) | `./gradlew architectureTest --offline --no-daemon` |
| R2 | 전체 unit test | `./gradlew test --offline --no-daemon` |
| R3 | 동결·직전 단위 테스트 **무수정** — 기존 파일의 수정·삭제만 본다(같은 디렉터리의 신규 파일은 대상 아님). 대상: `domain/src/test/.../accountpnl/**`, `domain/src/test/.../owneralert/DiscordMessageTest.kt`·`OwnerAccountAlertKindTest.kt`, `apps/batch/src/test/kotlin/io/premiumspread/application/**`, `apps/batch/src/test/.../config/OwnerAlertYamlContractTest.kt`, `apps/batch/src/integrationTest/.../application/job/{AccountPnlAlertDurabilityIntegrationTest,OwnerAlertDeliveryIntegrationTest,OwnerAccountAlertIntegrationTest}.kt`, `infrastructure/batch/src/test/.../{notification,owneralert,exchange,job}/**`, `infrastructure/common/src/integrationTest/.../migration/{V19,V20}*`, `infrastructure/common/src/integrationTest/.../accountpnl/JdbcAccountPnlAlertStoreIntegrationTest.kt` | `git diff --exit-code --diff-filter=MDR 20a7484 -- <위 경로>` 를 ⑨ 리뷰가 실행(정확한 경로 목록은 Task 0 이 확정해 증거 로그에 남긴다). R7·R8·R9 허용 예외 파일은 대상에서 뺀다 |
| R4 | 실계정·owner 알림 Domain·Job·wiring(golden 포함) | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.*' --tests 'io.premiumspread.domain.owneralert.*' :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.*' --tests 'io.premiumspread.application.notification.owneralert.*' --tests 'io.premiumspread.config.*' --offline --no-daemon` |
| R5 | 기존 migration 파일 무변경(추가만) + 검증 | `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` + ⑨ 리뷰가 `git diff --name-only --diff-filter=MDR 20a7484 -- infrastructure/common/src/main/resources/db/migration` 빈 출력 확인 |
| R6 | outbox INSERT 추출 뒤 `JdbcAccountPnlAlertStore` 의 SQL·검증 메시지·상수 무변경 | ⑨ 리뷰가 `git diff 20a7484 -- infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/` 의 이동 전후 문자열을 대조 (사람 판정) + R10 |
| R7 | enum 개수 고정 단언 수정은 허용 예외뿐 — `OwnerAlertRoutingTest` 한 파일에서 종류 이름·key 기대 목록 **끝에** 4개 추가만. 다른 줄 무변경 | `git diff 20a7484 -- domain/src/test/kotlin/io/premiumspread/domain/owneralert/OwnerAlertRoutingTest.kt` 를 ⑨ 리뷰가 확인 (사람 판정) |
| R8 | 채널 bean 이동에 따른 wiring 테스트 수정은 허용 예외뿐 — 대상 파일은 `apps/batch/src/test/kotlin/io/premiumspread/config/AccountPnlMonitorWiringTest.kt` 하나(⑥ R1 확인, Task 0 (g4)·(g14)가 재확인). 허용 diff 는 정확히 둘: (1) runner `withUserConfiguration`(`:72-79`) 목록에 `OwnerAlertChannelConfiguration::class.java` 한 줄 추가와 그 import, (2) `assertNoBeans()`(`:81-91`)에서 옮겨진 채널 bean 부재 단언(`:89` `doesNotHaveBean(AccountPnlEmailSenderAdapter::class.java)` 와, 같은 함수 안에 있다면 `OwnerAlertRouting`·`OwnerAlertDiscordSender`·`DiscordWebhookSenderAdapter` 부재 단언) 삭제와 더 이상 쓰이지 않는 import 삭제 — PnL 전용 bean 부재 단언(`:83-88`)은 그대로. 그 밖 테스트 메서드·fixture·단언 무변경. 지운 단언의 대가는 AC15 양성 단언 | `git diff 20a7484 -- apps/batch/src/test/kotlin/io/premiumspread/config/AccountPnlMonitorWiringTest.kt` 를 ⑨ 리뷰가 확인 (사람 판정) |
| R9 | batch fixture 스키마 고정 테스트 수정은(필요할 때만) 테이블 목록 끝에 `owner_alert_signal_state` 추가뿐 | `git diff 20a7484 -- apps/batch/src/integrationTest/kotlin/io/premiumspread/config/AccountPnlFixtureSchemaIntegrationTest.kt` (사람 판정, 무수정이면 해당 없음) |
| R10 | 공통 persistence·migration 통합 (V12·V16·V17·V19·V20, 기존 store·동시 claim·fencing) | `./gradlew :infrastructure:common:integrationTest --offline --no-daemon` |
| R11 | batch 통합 — 실계정 알림 재시작 내구성·#88 owner 알림 E2E·#89 계정 알림 E2E·premium durable delivery·fixture 스키마 | `./gradlew :apps:batch:integrationTest --offline --no-daemon` |
| R12 | 정본 문서 정합성 | `bash docs/check-documentation.sh` |
| R13 | API Flyway 전체 적용(V21 까지)과 api 통합 테스트 | `./gradlew :apps:api:integrationTest --offline --no-daemon` (Docker 필요) |
| R14 | ktlint·detekt | CI `quality-gate.yml` 결과 (로컬 실행 불가, 우회 금지) |
| R15 | coverage 게이트(overall 70%·Domain 85%·Application 80%) | `./gradlew jacocoTestReport jacocoTestCoverageVerification --offline --no-daemon` (최종 증거는 CI artifact) |

## 변경 요청

| # | 날짜 | 기준 | 변경 내용 | 사유 | 승인 |
|---|---|---|---|---|---|
| 1 | 2026-10-28 | AC10 (b) | 독립 전달 Job(`OWNER_ALERT_DELIVERY`) 주기 기본값 15s → **60s** (yml `OWNER_ALERT_DELIVERY_FIXED_RATE:60s`, `BatchSchedulingProperties.ownerAlertDelivery` 기본값, Scheduler ms fallback 60000). lock·lease 50s·timeout 40s 는 그대로 | ⑨ code-reviewer Important: 재시도 가능 실패는 백오프 없이 다음 회차에 재시도되고 5회(`MAX_ATTEMPTS`)면 FAILED 다. 15s 전달 Job 이 같은 outbox 를 집으면 행당 재시도 예산이 PnL 회차(60s) 기준 약 4~5분에서 약 1분으로 줄어 기존 PnL 알림이 회귀한다(사용자 합의 U1 위반). 60s 면 PnL 꺼짐 기준 예산이 기존과 같고, 켜짐이면 두 디스패처로 약 2~5분. 대가: 운영 알림 도착이 최대 약 1분 늦어짐 | owner 승인 2026-10-28 (⑨ AskUserQuestion "전달 주기 60초") |

## 스펙 리뷰 라운드 기록 (⑥)

| 라운드 | 지적 | 처리 | 반영 커밋 |
|---|---|---|---|
| R1 | REQUEST_CHANGES 9건 — (1 Critical) R8 이 `assertNoBeans()` 의 채널 bean 부재 단언을 다루지 못함, (2 Critical) `JdbcOwnerAlertOutbox` 등록은 `CommonJdbcConfiguration` `@Import`, (3 Major) 채널 ready 면 Discord 꺼짐에도 webhook·override 검증, (4) FX 신호 통화쌍 출처, (5) 관측 중 취소 재전파, (6) fake 상대 시각, (7) V21·fixture 테이블 옵션·`IF NOT EXISTS`, (8) 수신 주소 누락 INFO, (9) R15 명령 플래그 | ACCEPT 9 — (1) 기존 gate 조사·R8·AC15 신설, (2) 기존 gate 조사·AC7-i, (3) 기존 gate 조사·AC11-h·AC13, (4) AC8-b, (5) AC8-k, (6) AC8·AC12 전문, (7) AC6-a, (8) 사용자 합의 아래 판정·AC11-c·d·AC13, (9) R15 | (오케스트레이터 커밋) |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

### Task 0 — Gate 1 기준선 (HEAD 03dfc4d, merge-base 20a7484, 2026-10-28)

실행 환경: Docker 28.3.2. 모든 gradle 명령 `--offline --no-daemon`. 테스트 수는 각 모듈 `build/test-results/<task>/TEST-*.xml` 의 tests/skipped/failures/errors 합.

| 명령 | exit | 결과 |
|---|---|---|
| `./gradlew test architectureTest` (R1·R2) | 0 | BUILD SUCCESSFUL |
| `./gradlew :infrastructure:common:verifyMigrations` (R5) | 0 | BUILD SUCCESSFUL |
| `./gradlew :infrastructure:common:integrationTest` (R10) | 0 | BUILD SUCCESSFUL |
| `./gradlew :apps:batch:integrationTest` (R11) | 0 | BUILD SUCCESSFUL |
| `./gradlew :apps:api:integrationTest` (R13) | 0 | BUILD SUCCESSFUL |

`test` 태스크 (skipped·failures·errors 전부 0): apps:api 143, apps:batch 205, domain 359, infrastructure:api 31, infrastructure:batch 202, infrastructure:common 45, modules:jpa 7, modules:redis 18, supports:email 12, supports:logging 9, supports:monitoring 16 — 합계 1047.
`architectureTest` (architecture-tests): 35, 전부 통과.
`integrationTest` (skipped·failures·errors 전부 0): infrastructure:common 51, apps:batch 106, apps:api 174 — 합계 331.

grep 사실:
- (g1) 최신 migration `V20__add_owner_account_alert_state.sql` — 예상대로, V21 번호 근거 유지.
- (g3) `PremiumRealtimeJob.kt:39-40` 이 `market.pair.koreaExchange/koreaQuote`, `foreignExchange/foreignQuote` 쌍으로 조회, `TickerFlushJobs.kt:34` 는 `(exchange, quote)`. 파일명은 `TickerFlushJobs.kt`.
- (g4) `AccountPnlMonitorAdapterConfiguration` 참조 테스트: `apps/batch/src/test/kotlin/io/premiumspread/config/AccountPnlMonitorWiringTest.kt` 하나.
- (g5) `OwnerAlertRoutingTest.kt` `containsExactly` 2곳: `:25`(종류 이름 목록), `:35`(key 목록) — R7 대상 줄.
- (g6) `JobId.entries`·`JobId.values()` 매치 없음.
- (g7) `AccountPnlFixtureSchemaIntegrationTest.kt:70` `TABLE_NAME = 'account_pnl_mail'`, `:82` `TABLE_NAME = ?`(열 조회). 테이블 목록 단언 아님, `account_pnl_*` 개별 테이블 열 단언 → R9 불필요(무수정).
- (g8) PnL 꺼짐에서 `OwnerAlertProperties` 미바인딩을 단언하는 테스트 없음. `AccountPnlMonitorWiringTest.kt:767` 은 켜진 경우의 `getBean`, `OwnerAlertPropertiesTest` 는 별도 바인딩 테스트. 예상대로, A5 유지.
- (g10) `OWNER_ALERT_DISCORD_ENABLED` 언급: `docs/runbooks/account-pnl-alert.md` 만 (`.env.example`·`deploy`·`docker` 에는 없음).
- (g11) `account_pnl_monitor` 언급: `.ai/PROJECT_STATUS.md:83,103,105`, `.ai/architecture/ARCHITECTURE_DESIGN.md:114,120`, `docs/runbooks/account-pnl-alert.md:213,389,497`. job tag `job=account_pnl_monitor` 는 runbook `:389`.
- (g12) `@Scheduled(fixedRateString = "${batch.scheduling.<name>.fixed-rate:<ms>}")` 패턴, `interfaces/scheduling` 8개 Scheduler (예: `AccountPnlMonitorScheduler.kt:14` account-pnl-monitor, 60000).
- (g13) `JobExecutorTest.kt:39` `JobExecutor(lock, recorder, alert, clock)` 하나.
- (g14) 6줄 발견 — `:89` 만 R8 대상(`assertNoBeans`), 나머지 5줄(`:584`·`:607`·`:629`·`:630`·`:644`)은 무수정 유지 대상(controller 판정, ledger). 5줄은 PnL 켜짐 + 해당 채널 미준비 시나리오(SMTP 꺼짐, owner 메일 꺼짐, webhook 공백, 채널 없음)의 부재 단언이라 bean 이동 뒤에도 무수정으로 통과해야 하는 회귀 증거. 브리프 예상(`:89` 한 줄)과의 차이를 기록한 것이며 dod 기준 본문은 바꾸지 않는다.
- (g15) `infrastructure/common/src/integrationTest` 에 `CommonInfrastructureAutoConfiguration` 을 쓰는 기존 통합 테스트 없음 → AC7-i 는 신규 패턴.

R3 경로 목록(확정, 기존 파일 수정·삭제·rename 만 대상): dod R3 행에 적힌 경로 그대로 — `domain/src/test/.../accountpnl/**`, `domain/src/test/.../owneralert/{DiscordMessageTest,OwnerAccountAlertKindTest}.kt`, `apps/batch/src/test/kotlin/io/premiumspread/application/**`, `apps/batch/src/test/.../config/OwnerAlertYamlContractTest.kt`, `apps/batch/src/integrationTest/.../application/job/{AccountPnlAlertDurabilityIntegrationTest,OwnerAlertDeliveryIntegrationTest,OwnerAccountAlertIntegrationTest}.kt`, `infrastructure/batch/src/test/.../{notification,owneralert,exchange,job}/**`, `infrastructure/common/src/integrationTest/.../migration/{V19,V20}*`, `infrastructure/common/src/integrationTest/.../accountpnl/JdbcAccountPnlAlertStoreIntegrationTest.kt`. 제외(허용 예외): R7 `OwnerAlertRoutingTest.kt`, R8 `AccountPnlMonitorWiringTest.kt`, R9 해당 없음.

### Task 1 — AC1 OwnerAlertKind 운영 알림 4종

- RED: `./gradlew :domain:test --tests 'io.premiumspread.domain.owneralert.*' --offline --no-daemon` → exit 1, 29 tests 중 2 실패 (`OwnerOpsAlertKindTest` `운영 알림 네 종류의 기본 경로는 설계 표와 같다`·`복구는 무음이고 메일이 없다`). 사유: stub 경로 `(true, null)` 이 설계 표와 다름 (assertion 실패).
- GREEN: AC1 명령 (`:domain:test owneralert.*` + `OwnerAlertPropertiesTest` + `OwnerAlertYamlContractTest`) → BUILD SUCCESSFUL (exit 0). `OwnerAlertRoutingTest` 는 R7 예외(기대 목록 2곳 끝에 값 추가)만, 나머지 두 테스트 무수정 통과.
- `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL (exit 0).

### Task 2 — AC10 JobId 2종·연속 실패 port·lock·주기

- RED: `./gradlew :apps:batch:test --tests 'io.premiumspread.config.OwnerAlertJobConfigTest' --offline --no-daemon` → exit 1, 3 tests 중 3 실패. stub lease/timeout 5s/3s 라서 `expected: ... lease=PT50S, executionTimeout=PT40S but was: ... lease=PT5S, executionTimeout=PT3S` (assertion 실패).
- GREEN: 같은 명령 → BUILD SUCCESSFUL (exit 0). 값을 전문(50s/40s, 20s/10s, 주기 15s/30s)으로 교체. yml 테스트는 `PropertySourcesPlaceholdersResolver` 로 `${ENV:default}` 를 풀어 바인딩.
- `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL (exit 0).
- application.yml: 추가 6줄만, CR 개수 47 (base 와 동일).

### Task 3 — AC3-g 범용 enqueue port 와 신호 상태 타입

- RED: `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.OwnerAlertSignalStateTest' --offline --no-daemon` → exit 1, 7 tests 중 4 실패 (init 을 비운 stub 이라 signalKey 정규식·version 음수·notifyCount·lastNotifiedAt 거부 단언이 예외 미발생으로 실패).
- GREEN: 같은 명령 → BUILD SUCCESSFUL (exit 0, 7 tests 통과). init 을 전문으로 교체.
- `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL (exit 0).

### Task 4 — AC2 운영 신호와 3상태 관측

- RED: `./gradlew :domain:test --tests 'io.premiumspread.domain.ownerops.OpsSignalsTest' --offline --no-daemon` → exit 1, 10 tests 중 6 실패 (FX·시세·Job 판정 단언). 사유: 세 관측 함수가 항상 `Unknown` 인 stub 이라 Up/Down 단언 실패. key·설정·불변식 테스트는 초록.
- GREEN: 같은 명령 → BUILD SUCCESSFUL (exit 0, 10 tests 통과). 판정표 순서대로 구현.
- `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL (exit 0).

### Task 5 — AC3 장애 정책

- RED: `./gradlew :domain:test --tests 'io.premiumspread.domain.ownerops.OpsIncidentPolicyTest' --offline --no-daemon` → exit 1, 9 tests 중 6 실패 (T1·T3·T5·재발생·notice kind 단언 실패, signalKey 불일치는 예외 미발생). 사유: `decide` 가 항상 `(null, null)` 인 stub.
- GREEN: 같은 명령(+ `OwnerAlertSignalStateTest`) → BUILD SUCCESSFUL (exit 0). 전이표 T1~T6 구현, 재알림 경계는 `now − lastNotifiedAt ≥ realertInterval`.
- `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL (exit 0).

### Task 6 — AC4 알림 본문

- RED: `./gradlew :domain:test --tests 'io.premiumspread.domain.ownerops.OwnerOpsAlertContentTest' --offline --no-daemon` → exit 1, 15 tests 중 10 실패 (메일·Discord golden 각 5건). 사유: `mail`/`discord` 가 `"x"` stub 이라 제목·본문·색 단언 실패. eventKey·표기·금지 문자열·EMAIL 채널·색 상수 5건은 초록.
- GREEN: 같은 명령 → BUILD SUCCESSFUL (exit 0, 15 tests 통과). design §10.2 템플릿 구현.
- `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL (exit 0). 첫 실행은 `RedisRefreshSessionStoreIntegrationTest initializationError`(Testcontainers 초기화, 무관 모듈)로 실패했고 clean 재실행에서 통과.

### Task 7 — AC6 V21 migration

- RED: `./gradlew :infrastructure:common:integrationTest --tests 'io.premiumspread.infrastructure.common.migration.V21OwnerAlertSignalStateMigrationIntegrationTest' --offline --no-daemon` → exit 1, 3 tests 모두 실패 (버전 21 부재 assertion, 새 테이블 부재 SQL 오류, CHECK 예외 미발생). 사유: V21 파일 없음.
- GREEN: 같은 명령 → BUILD SUCCESSFUL (exit 0, 3 tests 통과). `verifyMigrations` PASS, `:apps:batch:integrationTest` PASS(R9 불필요), `test architectureTest` PASS.

### Task 8 — AC7 JdbcOwnerAlertOutbox 와 outbox INSERT 단일 소유자

- RED: `./gradlew :infrastructure:common:integrationTest --tests 'io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.*' --offline --no-daemon` → exit 1, 34 tests 중 9 실패 (a·b·c·d·e·f·f2·g·i). 사유: `JdbcOwnerAlertOutbox` 가 `loadSignals = emptyMap()`/`enqueue = true` 무기록 stub 이고 `@Import` 미등록이라 기록·읽기·예외 단언과 bean 1개 단언이 실패. `OwnerAlertOutboxRows` 추출 뒤에도 기존 `JdbcAccountPnlAlertStoreIntegrationTest` 는 초록.
- GREEN: 같은 명령 → BUILD SUCCESSFUL (exit 0). 구현과 `CommonJdbcConfiguration` `@Import` 추가.
- `./gradlew :infrastructure:common:integrationTest --offline --no-daemon` (R10) → BUILD SUCCESSFUL (exit 0).
- `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL (exit 0).
- AC7-i 는 `ApplicationContextRunner` + 실제 MySQL DataSource + DataSource/JdbcTemplate/TransactionManager auto-configuration + `CommonInfrastructureAutoConfiguration` + 고정 `Clock` bean 으로 구성했고 다른 bean 은 필요 없었다.

### Task 9 — AC5 Job 연속 실패 카운터

- RED: `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.job.*' --offline --no-daemon` → exit 1, 10 tests 중 5 실패 (a·b·c·d·f). 사유: stub `findFailureStreak = null`, 카운터 미갱신. (e) 는 SUCCEEDED 뒤 null 을 보므로 stub 에서도 통과한다.
- GREEN: 같은 명령 → BUILD SUCCESSFUL (exit 0). 기존 job 패키지 테스트 무수정 통과.
- `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL (exit 0).

### Task 10 — AC11·AC15 채널 bean 을 PnL 모니터 구성에서 분리

- RED: `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.owneralert.OwnerAlertChannelConfigurationTest' :apps:batch:test --tests 'io.premiumspread.config.OwnerAlertChannelIndependenceWiringTest' --offline --no-daemon --continue` → exit 1, infra 5 tests 중 2 실패(PnL 꺼짐 Discord, 메일 채널), apps 4 tests 중 2 실패(플래그 false, 계정 key 누락). 사유: `OwnerAlertChannelConfiguration` 이 bean 없는 stub 이라 sender·라우팅 존재 단언이 AssertionError. 부재·검증 시나리오는 stub 에서도 통과한다.
- GREEN: 같은 테스트 + `:apps:batch:test --tests 'io.premiumspread.config.*'` → BUILD SUCCESSFUL (exit 0). bean 3개 이동, `@Import` 추가, R8 허용 diff 두 종류만 적용(`:584`·`:607`·`:629`·`:630`·`:644` 부재 단언 무수정 통과).
- `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL (exit 0).

### Task 11 — AC9 독립 전달 Job

- RED: `./gradlew :apps:batch:test --tests 'io.premiumspread.application.notification.owneralert.OwnerAlertDeliveryJobTest' --offline --no-daemon` → exit 1, 9 tests 중 8 실패. 사유: `run` 이 `JobResult.Success` 만 돌려주는 stub 이라 recoverStale 호출·claim/발송·Failure 단언이 AssertionError.
- GREEN: AC9 명령(`owneralert.*` + `job.accountpnl.*`) → BUILD SUCCESSFUL (exit 0). `OwnerAlertDispatcherTest`·`AccountPnlMonitorJob*Test` 무수정 통과.
- `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL (exit 0).

### Task 12 — AC8 운영 신호 Job

- RED: `./gradlew :apps:batch:test --tests 'io.premiumspread.application.job.ownerops.*' --offline --no-daemon` → exit 1, 18 tests 중 16 실패. 사유: `run` 이 `JobResult.Success` 만 돌려주는 stub 이라 행·상태·Failure/Skipped 분기·취소 재전파 단언이 AssertionError. (a) 모두 정상·(g) 재시작 가짜 복구 없음은 쓰지 않는 stub 에서도 통과한다.
- GREEN: 같은 명령 → BUILD SUCCESSFUL (exit 0), 18 tests 0 failures.
- `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL (exit 0).
- (b) 의 "다른 통화쌍" 은 `Currency` enum 에 KRW·USD 만 있어 `EUR/KRW` 대신 base·quote 를 뒤바꾼 `KRW/USD` → `fx-ingestion.krw-usd` 로 확인했다.

### Task 13 — AC11 앱 배선·기동 조건·Scheduler

- RED: 조건의 `ready` 반환을 임시로 `false && ready` 로 막고 `./gradlew :apps:batch:test --tests 'io.premiumspread.config.OwnerAlertDeliveryWiringTest' --offline --no-daemon` → exit 1, 10 tests 중 6 실패 (a·b·e·g 2건·i). 사유: Job/Scheduler bean 부재·INFO 로그 부재·48조합 기대 불일치 assertion 실패.
- GREEN: 같은 명령 → BUILD SUCCESSFUL (10 tests). `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL (exit 0).
- 환경 메모: 중간에 `--offline` 이 build-logic 의존성 해석을 거부(`No cached version ... opentest4j`)해 한 번 `--offline` 없이 `:build-logic:compileJava` 를 실행해 캐시를 복구한 뒤 `--offline` 로 재개했다.

### Task 14 — AC12 E2E 통합

- RED: `OwnerOpsSignalJob` 의 `outbox.enqueue(changed, rows)` 를 `enqueue(changed, emptyList())` 로 임시 변이 후 `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.application.job.OwnerOpsAlertIntegrationTest' --offline --no-daemon` → BUILD FAILED, 3 tests 중 2 실패 ((a)(b)(c) 시나리오 행 개수 assertion :116, (d) 사전 행 assertion). (e) 는 enqueue 를 직접 호출하므로 통과.
- 변이 되돌림: `git checkout -- apps/batch/src/main` 후 `git diff --stat -- apps/batch/src/main` 빈 출력.
- GREEN: 같은 명령 → BUILD SUCCESSFUL (3 tests). R11 `./gradlew :apps:batch:integrationTest --offline --no-daemon` → BUILD SUCCESSFUL (109 tests / 실패 0). `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL.

### Task 15 — AC13 문서

- 신규 `docs/runbooks/owner-ops-alert.md`(절 순서: 무엇을 알리나 → 재알림과 복구 → 라우팅 → 환경변수 → 기동 판정과 로그 → 한계 → 이 경로로 알릴 수 없는 장애 → 알림 메시지 예 → 상태 확인과 정리 → 배포 → Discord 수신 체크리스트). 메시지 예는 `OwnerOpsAlertContentTest` golden 과 같은 값.
- 갱신: `docs/runbooks/account-pnl-alert.md`(채널 bean 독립·검증 시점·라우팅 표·override 11종·전달 Job), `.ai/architecture/ARCHITECTURE_DESIGN.md`(운영 알림 단락), `.ai/PROJECT_STATUS.md`(`Flyway V1~V21`·V21 행·기능 항목). Task 0 (g10)(g11) 파일은 모두 위 목록에 포함(env 예시 파일 없음, job tag 목록 문서 없음).
- 검증: `bash docs/check-documentation.sh` → `documentation check passed (20 files, 15 required paths)`, exit 0. 기존 문서 CR 개수 0 → 변경 후에도 0.

### ⑨ fix wave

- Task 5 RED 정정: 재발생·notice kind 두 테스트는 assertion 이 아니라 `!!` NPE 로 실패했다(stub 이 null 반환).
- A (전달 주기 60s): `OwnerAlertJobConfigTest` 기대값을 60s 로 먼저 바꾸고 `./gradlew :apps:batch:test --tests '*OwnerAlertJobConfigTest' --offline --no-daemon` → `yml 의 jobs 와 scheduling 값()` FAILED(assertion). yml·`BatchSchedulingProperties`·Scheduler fallback 을 60s/60000 으로 바꾼 뒤 BUILD SUCCESSFUL. `application.yml` diff 1/1, CR 개수 47 유지.
- B (firstRunAt 유지): `OwnerOpsSignalJobTest` 에 시세·FX 대기 테스트 추가. 변이 `compareAndSet(null, now)` → `set(now)` 에서 같은 명령 FAILED 3건(시세 5분·FX 30분 30초·다른 quote) 확인 후 원복(`git diff --stat -- apps/batch/src/main` 빈 출력).
- F (MarketPair guard): 다른 quote tick 테스트가 guard 전 FAILED(assertion: enqueue 없음 → `single()` 실패), `OwnerOpsSignalJob.latestTick` 추가 후 BUILD SUCCESSFUL.
- C·D·E·G: KDoc 변경, `RedisJobRunRecorderFailureStreakTest` 역순 FAILED 케이스(기존 `maxOf` 로 통과 — 회귀 방지), `OwnerAlertChannelConfigurationTest` 원인 메시지 단언(검증 강화라 RED 없음), `OpsSignalsTest` Down-before-Up 행, `OwnerOpsAlertContent` requireNotNull. `:domain:test :infrastructure:batch:test :apps:batch:test` BUILD SUCCESSFUL.

### ⑩ 최종 검증 (HEAD bdb09c1, base 20a7484, 2026-10-28)

실행 환경: Docker 가동. 모든 gradle 명령 `--offline --no-daemon`. 테스트 수는 `build/test-results/<task>/TEST-*.xml` 합. `cleanTest`/`cleanIntegrationTest` 후 실행해 UP-TO-DATE 재사용이 아님.

**전체 명령**

| 명령 | exit | 결과 |
|---|---|---|
| `./gradlew test architectureTest` (R1·R2) | 0 | unit 1156 / architecture 35, skipped·failures·errors 0 (2m 02s) |
| `:infrastructure:common:integrationTest` (R10) | 0 | 64 / 실패 0 (6m 52s) |
| `:apps:api:integrationTest` (R13) | 0 | 174 / 실패 0 (2m 55s) |
| `:apps:batch:integrationTest` (R11) | 0 | 109 / 실패 0 (1m 52s) |
| `:infrastructure:common:verifyMigrations` (R5) | 0 | BUILD SUCCESSFUL |
| `bash docs/check-documentation.sh` (R12) | 0 | documentation check passed (20 files, 15 required paths) |
| `jacocoTestReport jacocoTestCoverageVerification` (R15) | 0 | overall·domain·application 게이트 통과 |

**Task 0 기준선 대비 테스트 수**

| 구분 | Task 0 | ⑩ | 증가 |
|---|---|---|---|
| unit 합계 | 1047 | 1156 | +109 (domain 359→405, apps:batch 205→255, infrastructure:batch 202→215, 그 외 동일) |
| architectureTest | 35 | 35 | 0 |
| common IT | 51 | 64 | +13 |
| batch IT | 106 | 109 | +3 |
| api IT | 174 | 174 | 0 |

**커버리지 (LINE, jacoco XML 실측; 게이트는 exclusions 적용 후 통과)**: root 집계 78.9% (6708 covered / 1797 missed, 기준 70%), domain 87.8% (1786/249, 기준 85%), application(apps:api+apps:batch, exclusion 미적용 단순 합) 약 95.5% (2836/134 missed, 기준 80%). 최종 증거는 CI artifact.

**AC 명령 (dod 표 그대로)**

| AC | exit | 테스트 수 |
|---|---|---|
| AC1 | 0 | domain owneralert.* 29, infra:batch OwnerAlertPropertiesTest 9, apps:batch OwnerAlertYamlContractTest 2 |
| AC2 | 0 | OpsSignalsTest 10 |
| AC3 | 0 | domain 16 |
| AC4 | 0 | domain 15 |
| AC5 | 0 | infra:batch job.* 11 |
| AC6 | 0 | common IT 3 |
| AC7 | 0 | common IT accountpnl.* 34 |
| AC8 | 0 | apps:batch ownerops.* 21 |
| AC9 | 0 | apps:batch 88 |
| AC10 | 0 | 3 |
| AC11 | 0 | infra:batch owneralert.* 18, apps:batch config.* 87 |
| AC12 | 0 | batch IT 3 |
| AC13 | 0 | check-documentation.sh exit 0, runbook `docs/runbooks/owner-ops-alert.md` 존재 (T3 사람 사후 검토) |
| AC14 | — | T4, owner 확인 대기 (PR 코멘트 앵커 없음) |
| AC15 | 0 | 6 |
| R4 | 0 | domain 179, apps:batch 175 |

**R3·R5·R6·R7·R8·R9 diff (사람 판정용)**

- R3: `git diff --name-status --diff-filter=MDR 20a7484 -- '*Test*.kt'` 결과 M 2건뿐 — `OwnerAlertRoutingTest.kt`(R7), `AccountPnlMonitorWiringTest.kt`(R8). 동결 대상 경로의 그 밖 수정·삭제·rename 없음. 같은 범위 비테스트 M: `apps/batch/src/integrationTest/resources/batch-schema.sql`(V21 테이블 추가, 테스트 코드 아님).
- R5: migration 디렉터리 MDR 빈 출력, V21 추가만(A).
- R6: `JdbcAccountPnlAlertStore` diff = `mails.forEach(::validate)`→`OwnerAlertOutboxRows::validate`, insertMail 호출→`OwnerAlertOutboxRows.insert`, 메서드·상수 3개 삭제. `OwnerAlertOutboxRows` 의 SQL·검증 메시지·상수 문자열은 원본과 동일(타임스탬프 변환만 `Timestamp.from`).
- R7: 종류 이름·key `containsExactly` 두 줄 끝에 4개 추가만.
- R8: 정확히 import 1줄(OwnerAlertChannelConfiguration) + runner 1줄 + `:89` `doesNotHaveBean(AccountPnlEmailSenderAdapter)` 삭제 1줄. 그 외 줄 없음.
- R9: `AccountPnlFixtureSchemaIntegrationTest.kt` 무수정(해당 없음).

**경계면 대조**

- V21 SQL ↔ `batch-schema.sql` ↔ `JdbcOwnerAlertOutbox` SQL: 8열(signal_key, alert_kind, active, incident_started_at, last_notified_at, notify_count, version, updated_at) 이름·순서 일치, 두 DDL 의 타입·CHECK 동일. INSERT 7 파라미터 + version 리터럴 1.
- application.yml env ↔ runbook env 표: ops 5개, scheduling 2개, jobs lock 6개, routes 12개 일치 (기본값 true/5m/3/5m/6h/60s/30s/50s·40s/20s·10s 일치).
- routes 기본값 ↔ runbook 라우팅 표 ↔ `OwnerAlertKind`: 일치 (FX/MARKET: 메일 X·OPS·SOUND, JOB: 메일 O, RECOVERED: 무음).
- JobConfig ↔ yml jobs ↔ BatchJobProperties ↔ runbook: lock key·lease·timeout 일치. 전달 주기 60s: yml `60s`, `BatchSchedulingProperties` 60s, Scheduler fallback 60000, runbook 60s 일치. 신호 Job 30s/30000 일치.

**미실행·CI 확인**: R14 ktlint·detekt — 로컬 실행 불가, `AWAITING_CI`(UNVERIFIED). 커버리지 최종 증거 CI artifact. 실패·flaky 없음(infrastructure:api `RedisRefreshSessionStoreIntegrationTest` 는 이번 범위 밖 `integrationTest` 미실행, `test` 31 통과).

```
DoD VERDICT: owner-ops-alerts @ bdb09c1
  수용기준 표:     15개  (T1 13 · T3 1 · T4 1)
  T1/T2 자동:      13개 중 13개 PASS
  T3 기록 제출:    1개 중 1건 (AC13, 사람 사후 검토 대기)
  T4 사람 확인:    1개 중 0건 완료, 1건 대기 (AC14)
  변경 요청:       1건
  => AWAITING_HUMAN
```
