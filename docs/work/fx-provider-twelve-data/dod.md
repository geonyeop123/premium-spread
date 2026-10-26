---
feature: 환율 제공자 Twelve Data 교체와 FX 캐시 TTL 여유 (issue #93)
slug: fx-provider-twelve-data
status: DRAFT
frozen_at:
verdict_commit:
source: docs/work/fx-provider-twelve-data/design.md (DRAFT)
---

## 범위

**포함**

- Domain `FxIngestionFreshness`(허용 공백 30분) 와 `AccountPnlMonitorJob` 의 사용 (design §3.1)
- `RedisTtl.FX` 40분과 TTL 불변식 테스트, `docs/runbooks/redis-contract.md` (§3.2)
- 수집 주기 기본 5분, `BatchSchedulingProperties.ExchangeRate` 기동 검증, yml 기본값 (§3.3, §6)
- `ExchangeRateClient`·`ExchangeRateClientProperties`·`ExchangeRateResponse` 의 Twelve Data 교체 — 요청·매핑·거절·재시도·비밀값 비노출 (§4, §5)
- `FxIngestionJob` 성공 로그의 `observedAt` (§4.6)
- runbook·env 예시·ARCHITECTURE·PROJECT_STATUS·project-overview·README·낡은 주석 (§9)

**제외** *(scope creep 차단선)*

- `ExchangeRateProvider` 시그니처, 새 port·Job·JobId, `JobConfig`(`lock:fx` 35s/30s)
- `PremiumRealtimeJob` FX freshness, batch `FxRateReadAdapter` DB fallback
- api `TrackingFacade.FX_MAX_AGE` 값·판정 변경(주석만 고친다) — 후속 F1
- 기동 시 이중 실행 제거(F2), 제공자 다중화, 크레딧 metric
- env·클래스·패키지 rename, Redis key·payload 변경, Flyway migration

## 사용자 확정 (③, 2026-10-26)

| # | 확정 | design |
|---|---|---|
| U1 | 제공자를 Twelve Data(Basic 무료)로 교체 | §0, §4 |
| U2 | 수집 주기 5분 | §3.3 |
| U3 | #93 TTL 여유를 이 변경 안에서 함께 해결 | §3.2 |

## 기존 gate 조사

> Gate 1 필수. 작성 시점에는 문서·소스만 읽었다. 실행 기준선은 plan Task 0 이 아래 증거 로그에 남긴다.

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있다 — `architecture-tests/` (`ArchitectureBoundaryTest` 의 batch scheduling 기술 패키지 금지, `TestIsolationArchitectureTest` 의 테스트 소스 외부 URL 금지 등) | 회귀 방어선 R1. 테스트에 Twelve Data URL 을 scheme 포함으로 쓰면 R1 이 잡는다 |
| lint 규칙 | 있다 — ktlint·detekt (CI `quality-gate.yml`). 로컬 gradle task 없음, 로컬 실행 불가 | CI 결과로만 (R8) |
| custom gradle task | 있다 — `architectureTest`, `*:integrationTest`, `:infrastructure:common:verifyMigrations`, `harnessCheck` | 그대로 쓴다 |
| adapter 단위 테스트 | 있다 — `ExchangeRateClientTest` (MockWebServer, 5xx 상한·4xx 비재시도·timeout 상한) | AC4 가 같은 파일을 Twelve Data 계약으로 다시 쓴다(동결 대상 아님) |
| FX E2E | 있다 — `ExchangeRateSchedulerE2ETest` + `BatchTestConfig`(`exchangeRateMockServer`, `maxRetries = 2`) | AC6 이 확장. `BatchTestConfig` 무변경 |
| 허용 공백 경계 테스트 | 있다 — `AccountPnlMonitorJobTest` :218-280 (2시간 경계 3개 + 이름에 "2시간" 4개) | AC5 가 **허용 예외**로 수정 (R5) |
| `JobLastSuccessReadPort` fake | 있다 — 다른 Job·통합 테스트는 모두 `now − 10분` | 30분 안이라 무수정 (R2·R3) |
| yml 문자열 계약 테스트 패턴 | 있다 — `BatchConfigurationContractTest` (`ClassPathResource("application.yml")` + `contains`) | AC3 가 같은 패턴 |
| 로그 캡처 패턴 | 있다 — `DiscordWebhookSenderAdapterTest` (`OutputCaptureExtension`, "URL 과 token 이 없다") | AC4(e) 가 같은 패턴 |
| Redis TTL 테스트 | 없다 — `RedisTtl` 값을 단언하는 테스트 없음 | AC2 신규 |

**결론.** T1 수용기준은 모두 `신규테스트` 또는 `기존`(확장·허용 예외)으로 덮는다. 새 스크립트 파일은 만들지 않는다.

## 수용기준

공통 고정값: 가짜 key `test-key`·`fake-key-SENTINEL-93`, 가짜 환율 `1234.5678`, timestamp `1767225600`(`2026-01-01T00:00:00Z`), 고정 `Instant`/`Clock`. 실제 key·실제 환율·외부 URL 금지.

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | `FxIngestionFreshness.MAX_INGESTION_GAP == Duration.ofMinutes(30)` 이고, `isStale(t0, now)` 가 경과 `0` → false, `30분` → false, `30분 + 1ms` → true, `−1분`(미래 lastSuccess) → false 를 낸다 | design §3.1 | `추론` (2h→30m 근거 §3.1, A7) | T1 | `신규테스트` `io.premiumspread.domain.exchangerate.FxIngestionFreshnessTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.exchangerate.FxIngestionFreshnessTest' --offline --no-daemon` | exit 0 |
| AC2 | `RedisTtl.FX == Duration.ofMinutes(40)` 이고 `RedisTtl.FX.minus(FxIngestionFreshness.MAX_INGESTION_GAP) >= Duration.ofMinutes(5)` 다 | design §3.2 | `요구` (#93: 캐시가 판정보다 먼저 만료되지 않게, U3) | T1 | `신규테스트` `io.premiumspread.infrastructure.common.cache.exchangerate.FxCacheTtlContractTest` | `./gradlew :infrastructure:common:test --tests 'io.premiumspread.infrastructure.common.cache.exchangerate.FxCacheTtlContractTest' --offline --no-daemon` | exit 0 |
| AC3 | (a) `BatchSchedulingProperties.ExchangeRate()` 의 `fixedRate == 5분`, `startupDelay == 5초`. (b) `ExchangeRate(fixedRate = 15분)` 은 생성되고 `ExchangeRate(fixedRate = 15분 + 1ms)` 는 `IllegalArgumentException`(메시지에 `exchangeRate.fixedRate`). (c) batch `application.yml` 이 `fixed-rate: ${EXCHANGE_RATE_FIXED_RATE:5m}`·`max-retries: ${EXCHANGE_RATE_MAX_RETRIES:1}`·`api.twelvedata.com` 을 포함하고 `exchangerate-api.com` 을 포함하지 않는다 | design §3.3, §6 | `요구` (U2 5분) + 기동 검증·yml 기본값은 `추론` | T1 | `신규테스트` `io.premiumspread.config.FxIngestionScheduleContractTest` | `./gradlew :apps:batch:test --tests 'io.premiumspread.config.FxIngestionScheduleContractTest' --offline --no-daemon` | exit 0 |
| AC4 | MockWebServer 로 `ExchangeRateClient` 가 (a) **요청**: 1회, method `GET`, path `/exchange_rate`, `queryParameter("symbol") == "USD/KRW"`, 헤더 `Authorization == "apikey test-key"`, 요청 URL 문자열에 `test-key` 없음. (b) **매핑**: `{"symbol":"USD/KRW","rate":1234.5678,"timestamp":1767225600}` → snapshot `USD`·`KRW`·rate `compareTo 1234.5678 == 0`·`observedAt == 2026-01-01T00:00:00Z`·source `FX_PROVIDER`. (c) **거절**(각각 `ExchangeRateApiException`, 요청 1회): HTTP 200 + `status:"error"`(메시지 `Exchange rate API error: code=429`), symbol `EUR/USD`, symbol 누락, rate 누락, rate `0`, rate `-1`, timestamp 누락, timestamp `0`; 메시지는 design §4.4 표 전문과 같다. (d) **재시도**: 기본값 `maxRetries == 1`·`retryableStatuses == 500..599`·`overallDeadline() == 17초`·`endpoint.host == "api.twelvedata.com"`; 503 연속 → 2회 요청 후 `RetryableExchangeRateException`; 429 → 1회 후 `ExchangeRateHttpException`(Retryable 아님); 401 → 1회; read timeout(`maxRetries=1`, readTimeout 200ms, 본문 지연 2s) → 2회. (e) **누출**: key `fake-key-SENTINEL-93` 로 401 + 본문 `{"code":401,"message":"bad key fake-key-SENTINEL-93","status":"error"}` → 예외 메시지 `== "Exchange rate HTTP 401"`(sentinel·`bad key` 없음), 캡처한 표준 출력에 sentinel 없음, 요청 URL 에 sentinel 없음, `properties.toString()` 에 sentinel 없음·`apiKey=****` 포함 | design §4, §5 | `요구` (U1 제공자 교체, ③ 헤더 인증·key 비노출) + 거절·재시도 세부는 `추론` | T1 | `신규테스트` (기존 파일 재작성) `io.premiumspread.infrastructure.batch.exchange.ExchangeRateClientTest` | `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.exchange.ExchangeRateClientTest' --offline --no-daemon` | exit 0 |
| AC5 | `AccountPnlMonitorJob`(고정 `Clock`) 이 마지막 FX 수집 성공이 (a) `now − MAX_INGESTION_GAP` 이면 계산(수익 메일 발송), (b) `now − MAX_INGESTION_GAP − 1ms` 면 `STALE_FX`(`환율 수집 지연` 중단 메일), (c) 성공 기록이 없으면 첫 실행 시각부터 `MAX_INGESTION_GAP` 까지 신선·`+1ms` 부터 stale. 수정 범위는 dod R5 뿐이다 | design §3.1 | `요구` (#93 STALE_FX 원인 해소, U3) | T1 | `기존` (허용 예외 수정) `io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest` | `./gradlew :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.*' --offline --no-daemon` | exit 0 |
| AC6 | 실제 MySQL·Redis + MockWebServer 로 `FxIngestionJob.run()` 이 (a) Twelve Data 응답(raw JSON, AC4(b) 값) 뒤 `exchange_rate` 에 1행(rate `1234.5678`, `observed_at == 2026-01-01T00:00:00Z`), (b) `fx:usd:krw` Hash 의 `rate == 1234.5678`·`timestamp == 1767225600000`, (c) `fx:usd:krw` TTL 이 35분 초과 40분 이하, (d) 받은 요청의 `Authorization` 헤더가 `apikey ` 로 시작, (e) 500×3 최종 실패 뒤 DB 행·캐시 없음 | design §4, §7 | `요구` (U1, DB-first 유지) | T1 | `신규테스트` (기존 클래스 확장) `io.premiumspread.interfaces.scheduling.ExchangeRateSchedulerE2ETest` | `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.interfaces.scheduling.ExchangeRateSchedulerE2ETest' --offline --no-daemon` | exit 0 (Docker 필요) |
| AC7 | design §9 표의 문서가 갱신됐다: redis-contract `40분`+불변식 한 줄, deployment `### 환율 제공자 (Twelve Data)`(헤더 인증·주기·max-retries·429·크레딧 식과 4분 하한·key 당 batch 하나·지울 override·주말), account-pnl-alert `30분`·`5분 주기` 3곳, env 예시 2개, ARCHITECTURE·PROJECT_STATUS·project-overview·README 주기·제공자, 주석 3곳(`ExchangeRateResponse`·`JdbcExchangeRateWriteRepository`·`TrackingFacade`). 정본 문서에 `exchangerate-api` 언급이 남지 않는다. `docs/check-documentation.sh` 통과 | design §9 | `요구` (③ 문서 갱신 대상) | T3 | 기록 문서 + `bash docs/check-documentation.sh` | `bash docs/check-documentation.sh` | exit 0 + 위 항목 존재를 사람 사후 검토 |
| AC8 | owner 가 Twelve Data key 를 발급해 `EXCHANGE_RATE_API_KEY` 값만 바꾸고(이름 그대로, 구 override 제거) 로컬 운영 batch 를 띄웠을 때 (a) 기동이 성공한다, (b) 30분 이상 `FxIngestionJob` 성공 INFO 로그가 약 5분 간격으로 6줄 이상이고 각 줄에 `observedAt` 이 있다, (c) 평일에 `SELECT observed_at FROM exchange_rate WHERE base_currency='USD' AND quote_currency='KRW' ORDER BY observed_at DESC LIMIT 6` 결과가 최근 30분 안의 서로 다른 분 단위 값이다, (d) batch 로그에 key 값이 없다, (e) (관찰 기회가 있으면) Twelve Data 사용량이 시간당 약 12, 주말 `observed_at` 이 고정되는지 | design §5, §7.1, A1·A6 | `요구` (③ 사람 검증 T4) | T4 | owner 확인 | — | PR 코멘트에 owner 확인 기록. (e) 는 관찰 기회가 없으면 "미관찰" |

**기준 개수**: 8개

**분할하지 않는 이유**: (8개 이하라 필수 아님) 제공자 교체와 TTL·허용 공백 변경은 같은 시간 계약(주기 5분 ↔ 공백 30분 ↔ TTL 40분)의 세 면이다. 제공자만 먼저 바꾸면
30분 주기 + 31분 TTL 의 #93 결함이 남고, TTL 만 먼저 바꾸면 일일 제공자에 5분 주기가 의미 없다.

**티어 강등 사유** *(T1 이 아닌 항목만. Gate 1 에서 사람이 함께 승인한다)*

- AC7 → T3: 문서 설명의 충분성은 exit code 로 잴 수 없다. `check-documentation.sh` 는 형식만 본다.
- AC8 → T4: 실제 key·실제 제공자 호출은 테스트·CI 에 쓰지 않는다(testing 규칙). 실 제공자의 갱신 주기·주말 동작·크레딧 집계는 사람만 관찰할 수 있다.

## 회귀 방어선

이번 변경으로 깨질 수 있는 기존 동작. RED 면제 대상이다. base = `728e3f1` (Task 0 확인).

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 모듈 경계·import·테스트 격리(외부 URL 금지)·관측 cardinality 아키텍처 규칙 전체 | `./gradlew architectureTest --offline --no-daemon` |
| R2 | 전체 unit test | `./gradlew test --offline --no-daemon` |
| R3 | batch 통합 — 실계정 알림 내구성·owner 알림 E2E(모두 FX 성공 `now − 10분` fake), premium·FX E2E | `./gradlew :apps:batch:integrationTest --offline --no-daemon` |
| R4 | 공통 persistence·cache 통합(FX cache reader/writer 포함) | `./gradlew :infrastructure:common:integrationTest --offline --no-daemon` |
| R5 | `AccountPnlMonitorJobTest` 수정은 허용 예외 범위뿐이다 — `:218-280` 의 FX 신선도 테스트 4개에서 (1) `Duration.ofHours(2)` 를 `FxIngestionFreshness.MAX_INGESTION_GAP` 으로, (2) 테스트 이름·`describedAs` 의 "2시간" 을 "허용 공백"으로, (3) import 1줄 추가. 그 밖의 줄·다른 테스트 메서드 무변경 | `git diff 728e3f1 -- apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJobTest.kt` 를 ⑨ 리뷰가 확인 (사람 판정) |
| R6 | Flyway migration 무변경 + 검증 | `git diff --exit-code 728e3f1 -- infrastructure/common/src/main/resources/db/migration` 와 `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` (각각 실행) |
| R7 | 정본 문서·하네스 정합성 | `./gradlew harnessCheck --offline --no-daemon` 와 `bash docs/check-documentation.sh` (각각 실행) |
| R8 | ktlint·detekt (로컬 실행 불가, 우회 금지) | CI `quality-gate.yml` 의 ktlint+detekt job 결과 (PR head SHA) |
| R9 | api 단위 — `TrackingFacade` 주석 변경의 간접 회귀 | `./gradlew :apps:api:test --offline --no-daemon` |

## 스펙 리뷰 라운드 기록 (⑥)

| 라운드 | 지적 | 처리 | 반영 커밋 |
|---|---|---|---|

## 변경 요청

| # | 기준 | 변경 내용 | 사유 | 승인 |
|---|---|---|---|---|

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.
