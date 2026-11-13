---
feature: 레그별 관측 이력 테이블 — 바이낸스 위험·비용 지표, 빗썸 진입 수수료
slug: issue-139-leg-observation
status: FROZEN · VERIFIED
frozen_at: 2026-11-13 (사용자 계약 승인 — 지표 저장 형식 DECIMAL)
verdict_commit: 6fabf74
source: https://github.com/geonyeop123/premium-spread/issues/139
---

## 범위

**포함**

- V28 migration: `account_foreign_leg_observation`, `account_korea_leg_observation` (design D1)
- 도메인 `LegObservations.of` — 레그 행 생성 조건과 "없음" = null (D2·D3)
- `JdbcAccountPositionHistoryStore` 가 HOLDING 관측과 같은 트랜잭션에 레그 행 저장 (D4)
- Job `history(...)` 가 스냅샷에서 레그 관측을 채움 (D3·D4)
- batch fixture `batch-schema.sql` 동기화 (D5)

**제외** *(scope creep 차단선)*

- 청산 알림 판정, 조회 API·화면, 보존 기간 정책
- FLAT·한쪽 레그 회차의 레그 기록
- 기존 V24~V27 테이블·열 변경

## 기존 gate 조사

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| migration 정적 검사 | 있음 — `:infrastructure:common:verifyMigrations`(버전 유일성·파괴적 SQL 금지) | 예. 회귀 방어선 |
| migration 경로 통합 테스트 | 있음 — `V24…`·`V26RealizedPnlMigrationIntegrationTest`(이전 버전 → 최신, 기존 행 보존) 패턴 | 예. 같은 패턴으로 V28 신규테스트 |
| store 통합 테스트 | 있음 — `JdbcAccountPositionHistoryStoreIntegrationTest`(관측 INSERT 실패 시 롤백 포함) | 예. AC2·AC3 을 같은 클래스에 추가 |
| batch fixture 스키마 테스트 | 있음 — `AccountPositionHistoryFixtureSchemaIntegrationTest`(열 목록 고정) | 예. AC6 |
| Job 이력 단위 테스트 | 있음 — `AccountPnlMonitorJobPositionHistoryTest`(round 캡처, best-effort) | 예. AC4 |
| architecture test | 있음 — root `architectureTest` | 예. 회귀 방어선 |
| 문서 검사 | 있음 — `docs/check-documentation.sh`, `./gradlew harnessCheck` | 회귀 방어선 |

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | `LegObservations.of` 는 (a) EXCHANGE_API 양 레그 보유면 바이낸스 레그(레버리지·마진 모드·청산가·mark·격리/유지 증거금·진입 수수료·펀딩 = 스냅샷 값)와 빗썸 레그(진입 수수료 = 스냅샷 값)를 모두 낸다, (b) MANUAL_ENV 면 바이낸스 레그가 null 이고 빗썸 레그는 있다, (c) 수량 0 인 레그는 null 이다, (d) 진입 수수료·펀딩·빗썸 수수료가 null 인 스냅샷이면 해당 필드가 0 이 아니라 null 이다, (e) positionRisk·symbolConfig 가 null 이면 해당 필드 묶음이 null 이다 | 이슈 결정(레그 없음·수동 모드면 행 없음), 고려할 점(미반영은 없음), design D2·D3 | `요구` | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.PositionLegObservationTest` | `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.PositionLegObservationTest --offline` | exit 0 |
| AC2 | 헤지 성립(HOLDING) 회차를 기록하면 관측 1행, 바이낸스 레그 1행, 빗썸 레그 1행이 같은 `(episode_id, observed_at)` 로 생기고 열 값이 입력과 같다. 바이낸스 레그가 null(수동)이면 바이낸스 레그 행이 0행이다. 미반영 값은 NULL 로 저장된다. FLAT 관측 회차에는 레그 행이 0행이다. 같은 round 재기록은 레그 행을 늘리지 않는다 | 이슈 수용 기준 1, design D4 | `요구` | T1 | `신규테스트` `io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPositionHistoryStoreIntegrationTest` (레그 테스트 추가) | `./gradlew :infrastructure:common:integrationTest --tests io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPositionHistoryStoreIntegrationTest --offline` | exit 0 |
| AC3 | 레그 행 INSERT 가 실패하면 예외가 전파되고 같은 회차의 관측·건·사건 행이 하나도 남지 않는다 | 이슈 수용 기준 2(레그 실패면 관측도 없음), design D4 | `요구` | T1 | `신규테스트` AC2 와 같은 클래스 | AC2 와 같음 | exit 0 |
| AC4 | Job 이 Computed 회차를 기록할 때 넘긴 `PositionHistoryRound.legObservations` 가 그 회차 스냅샷의 `LegObservations.of` 값과 같고, 이력 store 예외는 지금처럼 삼켜져 결과·상태·발송이 이력 없는 Job 과 같다 | 이슈 수용 기준 2(회차·알림 무관), design D4 | `요구` | T1 | `신규테스트`+`기존` `io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobPositionHistoryTest` | `./gradlew :apps:batch:test --tests io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobPositionHistoryTest --offline` | exit 0 |
| AC5 | V27 상태에 건·사건·관측 행이 있을 때 V28 을 적용하면 기존 세 테이블의 행 스냅샷이 그대로이고 새 테이블 두 개가 생긴다. 관측 행이 없는 키로 레그 행을 넣으면 FK 위반이고, `margin_type='BAD'`, `position_source='MANUAL_ENV'`, `leverage` 만 채우고 `margin_type` NULL, `mark_price` 만 채우고 `isolated_margin`·`maint_margin` NULL 인 행은 각각 해당 CHECK 이름으로 거부된다 | 이슈 수용 기준 3, design D1·D5 | `요구`(기존 데이터 무영향) + `리뷰`(codex ISSUE-3: 짝 CHECK) | T1 | `신규테스트` `io.premiumspread.infrastructure.common.migration.V28LegObservationMigrationIntegrationTest` | `./gradlew :infrastructure:common:integrationTest --tests io.premiumspread.infrastructure.common.migration.V28LegObservationMigrationIntegrationTest --offline` | exit 0 |
| AC6 | batch fixture `batch-schema.sql` 의 두 레그 테이블 열(이름·타입·NULL 여부)과 제약 이름·종류(PRIMARY KEY·FOREIGN KEY·CHECK) 목록, FK 참조 열 순서가 V28 과 같다 | design D5 — batch 는 Flyway 없이 fixture 로 스키마를 만든다 | `리뷰`(codex 스펙 리뷰 ISSUE-3: 제약 비교) | T1 | `신규테스트` `io.premiumspread.config.AccountPositionHistoryFixtureSchemaIntegrationTest` (테스트 추가) | `./gradlew :apps:batch:integrationTest --tests io.premiumspread.config.AccountPositionHistoryFixtureSchemaIntegrationTest --offline` | exit 0 |

**기준 개수**: 6개

**분할하지 않는 이유**: 해당 없음(8개 이하)

**티어 강등 사유**: 없음

## 회귀 방어선

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 전 모듈 unit + 모듈 경계 | `./gradlew test architectureTest --parallel --offline --no-daemon` |
| R2 | migration 버전·파괴적 SQL 검사 | `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` |
| R3 | common·batch 통합 테스트 전체(기존 이력·정산 포함) | `./gradlew :infrastructure:common:integrationTest :apps:batch:integrationTest --offline --no-daemon` |
| R4 | 정본 문서 검사 | `bash docs/check-documentation.sh` |

## 증거 로그

| # | RED (구현 전) | GREEN |
|---|---|---|
| AC1 | `PositionLegObservationTest` 스텁 `of()=NONE` — `10 tests completed, 7 failed`(값·null 보존·수동·수량 0·toString 어서션) | 10/10 통과 (527079e). 수정 1회 후 12/12 (a59a873, 수동 round 거부·기본값 NONE 테스트 추가 — 수정 전 `12 tests completed, 1 failed`: 예외 미발생) |
| AC2 | `JdbcAccountPositionHistoryStoreIntegrationTest` — `20 tests completed, 6 failed`(레그 행 0개) | 20/20 통과 (86a827b) |
| AC3 | 같은 클래스 롤백 테스트 — `Expecting code to raise a throwable`(레그 INSERT 없음) | 통과 (86a827b): 관측·건·사건·레그 0행, 다음 회차 정상 |
| AC4 | `AccountPnlMonitorJobPositionHistoryTest.kt:114` — `round.legObservations` 가 NONE, `11 tests, 1 failed` | 11/11 통과 (57a39f6) |
| AC5 | `V28LegObservationMigrationIntegrationTest` — `4 tests completed, 4 failed`(테이블 없음). 첫 GREEN 시도에서 `leverage=5, margin_type=NULL` 이 CHECK 를 UNKNOWN 으로 통과한 결함을 잡아 `margin_type IS NOT NULL` 추가(미커밋 V28) | 4/4 통과 (1ef522e) |
| AC6 | `AccountPositionHistoryFixtureSchemaIntegrationTest` — `5 tests completed, 3 failed`(fixture 에 테이블 없음) | 5/5 통과 (1ef522e) |
| R1~R3 | — | `./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon` @ a59a873 → `BUILD SUCCESSFUL in 3m 7s`, exit 0, FAILED 0 |
| CI | — | PR #147 첫 실행에서 `5. ktlint + detekt` 실패: ktlint `argument-list-wrapping` 6건(테스트 87b7cc5 로 수정) → detekt `TooManyFunctions` 20/20(store 레그 INSERT 를 `insertLegs` 하나로 합침, 6fabf74, 동작 불변 — store 통합 테스트 20/20 재실행). 6fabf74 에서 CI 11개 job 전부 pass. lint 도구는 CI 전용이라 로컬 실행 불가 |
| R4 | — | `bash docs/check-documentation.sh` → `documentation check passed (20 files, 15 required paths)` |

## 판정

```
DoD VERDICT: issue-139-leg-observation @ 6fabf74
  수용기준 표:     6개  (T1 6 · T2 0 · T3 0 · T4 0)
  T1/T2 자동:      6개 중 6개 PASS
  T3 기록 제출:    0개
  T4 사람 확인:    0개
  변경 요청:       0건
  => VERIFIED
```
