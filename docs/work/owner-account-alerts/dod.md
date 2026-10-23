---
feature: owner 계정 알림 — 포지션 변경·순수익률 급변·미반영 리마인더 (issue #89)
slug: owner-account-alerts
status: FROZEN
frozen_at: 2026-10-23
verdict_commit:
source: docs/work/owner-account-alerts/design.md (DRAFT)
---

## 범위

**포함**

- `OwnerAlertKind` 3종(`POSITION_CHANGED`·`NET_PERCENT_SWING`·`UNRECOVERED_REMINDER`)과 기본 경로, override env 9개, yml routes (design §6.1)
- Domain leg·상태·설정 모델, 정책 3개 + 조합기, sealed `AccountAlert` (§3)
- 메일·Discord 렌더링 3종 (§7)
- V20 migration, JDBC store 상태 열, batch fixture (§4)
- `AccountPnlMonitorJob` 의 계정 알림 판정·라우팅·상태 저장 (§5)
- 계정 알림 설정 env 3개와 앱 배선 (§6.2)
- runbook·아키텍처·프로젝트 상태 문서 (§11)

**제외** *(scope creep 차단선)*

- 수익·중단/복구·정시 보고 정책 변경, `position_fingerprint` 의미 변경, 순손익 공식·관측 로그 변경
- 새 Job·Scheduler·JobId·port·테이블, outbox 스키마 변경
- 시장(#90)·운영(#91) 알림, 독립 전달 Job
- API 모드 헤지 수량 불일치 판정, 알림 종류별 on/off env 신설
- 테이블·타입 rename, 직전 단위 산출물(`docs/work/owner-alert-channel/**` 등) 수정

## 사용자 확정 (⑦, 2026-10-23)

| # | 확정 | design |
|---|---|---|
| D1 | 리마인더: 09:00 KST(설정 시각) 이후 **포지션 있는 성공 회차마다** 판정, 항목이 **처음 해당하는 회차**에 발송, KST 하루 최대 1회 — **보냈을 때만** 그날 소비. 포지션 없음 회차는 판정·소비하지 않음. 종일 항목이 없으면 발송 없음 | §3.4 |
| D2 | Discord 꺼짐 → 새 3종 모두 메일(#88 D1 규칙) | §6.1 |
| D3 | 헤지 불일치 경고는 바이낸스 leg 변경 또는 API 출처일 때만 해소 | §3.2 |

오케스트레이터 판정 O1: 리마인더 시작 시각 env `ACCOUNT_PNL_REMINDER_HOUR`(기본 9) — 사용자 합의 항목이 아님.

## 기존 gate 조사

> Gate 1 필수. 작성 시점에는 문서·소스만 읽었다. 실행 기준선은 plan Task 0 이 아래 증거 로그에 남긴다.

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있다 — `architecture-tests/` (경계·import·test isolation·관측 cardinality·조회 전용) | 회귀 방어선 R1. 신규 규칙 없음 |
| lint 규칙 | 있다 — ktlint·detekt (CI `quality-gate.yml`). 로컬 gradle task 없음 | CI 회귀로만 |
| custom gradle task | 있다 — `architectureTest`, `*:integrationTest`, `:infrastructure:common:verifyMigrations`, `harnessCheck` | 그대로 쓴다 |
| 동결 테스트 | 있다 — 실계정 Domain·Job·adapter 테스트(#88 R3 목록) + #88 산출 테스트(디스패처·Job 라우팅·Discord adapter·채널 판정·Properties·렌더링·V19·E2E) | R3 무수정. Job 생성자 끝 인자 추가와 상태 4번째 기본값 프로퍼티로 컴파일 유지 |
| enum 개수 고정 단언 | 있다 — `OwnerAlertRoutingTest`(:24-25, :34-35, 개수를 적은 테스트 이름 :23·:42). `OwnerAlertPropertiesTest`(:111-113)는 부분 문자열 `contains` 라 enum 끝 추가에도 통과 | **허용 예외** R7 — `OwnerAlertRoutingTest` 2곳 기대 목록 끝 추가 + 이름 2개만. `OwnerAlertPropertiesTest` 는 무수정(R3) |
| 확장만 허용 테스트 | 있다 — `JdbcAccountPnlAlertStoreIntegrationTest`, `AccountPnlMonitorWiringTest` | AC9·AC11 이 메서드 추가로 확장 (R6) |
| batch fixture 스키마 고정 | 있다 — `AccountPnlFixtureSchemaIntegrationTest` (`account_pnl_monitor_state` = V18 9열) | V20 으로 바뀌므로 허용 예외 R8 |
| migration 경로 테스트 패턴 | 있다 — `V19OwnerAlertChannelMigrationIntegrationTest` | AC8 이 같은 패턴 |
| API Flyway 전체 적용 | 있다 — `apps/api` `FlywayOwnershipIntegrationTest` | 회귀 방어선 R13 (API 컨텍스트 기동 시 V20 까지 적용 — 테스트 단언은 V13) |
| owner 알림 테스트 fake | 있다 — `OwnerAlertTestFakes.kt`(`FakeOwnerAlertStore`·`FakeMailSender`·`FakeDiscordSender`·`MutableClock`) | AC10 이 **무수정 재사용** |

**결론.** T1 수용기준은 모두 `신규테스트` 또는 `기존`(확장)으로 덮는다. 새 스크립트 파일은 만들지 않는다.

## 수용기준

공통 고정값: zone `Asia/Seoul`, 고정 `Clock`/`Instant`, 수량·가격은 가짜 값(예 빗썸 `0.5` BTC·평단 `100000000`, 바이낸스 `-0.5`·`70000`) — 실제 포지션 값 금지.

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | (a) `OwnerAlertKind.entries` 이름이 순서대로 기존 4개 + `POSITION_CHANGED`·`NET_PERCENT_SWING`·`UNRECOVERED_REMINDER`, key 는 `position-changed`·`net-percent-swing`·`unrecovered-reminder`. (b) 기본 경로 `POSITION_CHANGED=(true, PNL/HERE)`, `NET_PERCENT_SWING=(false, PNL/SOUND)`, `UNRECOVERED_REMINDER=(false, PNL/SILENT)`. (c) 메일+Discord 채널이면 세 종류 `resolve` = 기본 경로, Discord 꺼짐(`EMAIL_ONLY`)이면 세 종류 모두 `(true, null)`. (d) #88 `OwnerAlertRoutingTest` 는 허용 예외(R7) 수정만으로, `OwnerAlertPropertiesTest` 는 무수정으로 통과 | design §6.1 | `요구` (③ 기본 경로, #88 design §3.2 목표 표) + Discord 꺼짐 메일은 `요구` (사용자 확정 D2) | T1 | `신규테스트` `io.premiumspread.domain.owneralert.OwnerAccountAlertKindTest` + `기존` (허용 예외·무수정) | `./gradlew :domain:test --tests 'io.premiumspread.domain.owneralert.*' --offline --no-daemon && ./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.owneralert.OwnerAlertPropertiesTest' --offline --no-daemon` | exit 0 |
| AC2 | (a) `KoreaLeg.of` 는 수량 `0.00009999` → `EMPTY`, `0.0001` → 그대로, `0.50` 과 `0.5` 는 같은 leg(`==` 와 `samePosition` 모두 true), 평단 `100000000.00` 과 `1E+8` 이 `samePosition`. (b) `ForeignLeg.of` 는 수량 `0` 이면 진입가와 무관하게 `FLAT`. (c) `SwingState(null, 0.40, …)`·`SwingState(null, null, true, false)`·`ReminderState(null, 1, null)` 는 `IllegalArgumentException`. (d) `AccountAlertSettings` 기본값 `0.50`·`9`·3일, 임계값 `0`·`-0.1`, 시각 `-1`·`24`, 경과 `0` 은 거부(design §3.7 메시지). (e) `AccountPnlMonitorState(a, o, v)` 3인자 생성의 `account == AccountAlertState.INITIAL` | design §3.1, §3.7 | `추론` (scale 오탐·dust 오탐 방지) + 설정 범위는 `요구` | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.AccountAlertStateTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountAlertStateTest' --offline --no-daemon` | exit 0 |
| AC3 | `PositionChangePolicy.decide` 가 design §3.2 전이표 P1~P8 각 행대로 `next`·알림을 낸다: P1 알림 없음·legs 저장·warned false, P4 경고 true·warned true, P5(이미 warned) 경고 false·warned true, P6 API 경고 false, P7 바이낸스만 변경 → warned false, P8 둘 다 → 경고 false. 빗썸이 원래 값으로 돌아오는 P3 회차는 warned 를 유지한다. 알림의 `before`/`after`·`koreaChanged`/`foreignChanged`·`source`·`detectedAt` 이 입력과 같다. 빗썸 전량 청산(`EMPTY`)도 변경. 수량 scale 만 다른 값(`0.5`↔`0.50`)은 변경 아님. `PositionChanged` 를 변경 없이 만들거나 경고 조건 밖에서 경고 true 로 만들면 `IllegalArgumentException` | design §3.2 | `요구` (leg 별 저장·비교, 수동 모드 1회 경고, 첫 기동 무알림, 청산도 변경, API 모드 경고 없음) + 해소 조건은 `요구` (사용자 확정 D3) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.PositionChangePolicyTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.PositionChangePolicyTest' --offline --no-daemon` | exit 0 |
| AC4 | `NetPercentSwingPolicy` 가 design §3.3 S1~S6 대로 동작한다: (a) `bucketStart` 는 `2026-10-20T01:59:59.999Z`(10:59 KST) → `01:00Z`, `02:00:00Z` → `02:00Z`. (b) 새 버킷 첫 회차는 기준선만 저장(알림 없음), 포지션 없음 회차면 기준선 null. (c) 기준선 `0.40` 에서 `0.89` 알림 없음, `0.90` 상승 1회·`upSent`, 같은 버킷 `1.20` 재상승 없음, 이어서 `-0.10` 하락 1회, 다시 `-0.50` 하락 없음. (d) 다음 버킷 첫 회차는 플래그를 리셋하고 새 기준선. (e) `positionChanged` 이면 `(bucket, null, false, false)` 이고 같은 버킷의 큰 변동에도 알림 없음, 다음 버킷에서 기준선 재설정. (f) 알림의 `delta`·`baselinePercent`·`threshold`·`bucketStart`·`direction` 이 기대값과 같다 | design §3.3 | `요구` (정시 첫 성공 회차 기준선, 0.5%p, 방향당 1회, 포지션 변경 시 초기화) + 경계 포함은 `추론` | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.NetPercentSwingPolicyTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.NetPercentSwingPolicyTest' --offline --no-daemon` | exit 0 |
| AC5 | `UnrecoveredReminderPolicy` 가 design §3.4 대로 동작한다: (a) 펀딩 추적 — API 출처면 `(null, null)`, manual 첫 관측이면 `(값, observedAt)`, 같은 값(`3.2`↔`3.20`)이면 그대로, 다른 값·null↔값이면 `(새 값, observedAt)`. (b) 항목이 있는 회차에서 `2026-10-19T23:59:59Z`(08:59:59 KST) 알림 없음·`lastDayStart` 그대로, `2026-10-20T00:00:00Z`(09:00 KST) 리마인더·`lastDayStart = 2026-10-19T15:00:00Z`, 같은 날 `05:00Z` 재발송 없음, `2026-10-20T15:00:00Z`(다음날 00:00 KST)는 시각 미달, `2026-10-21T00:00:00Z` 다시 리마인더. (c) 항목 순서 `KOREA_ENTRY_FEE, FOREIGN_ENTRY_FEE, FUNDING, MANUAL_FUNDING_STALE`, 해당 없으면 빠진다. (d) 펀딩 경과 `3일 − 1ms` 는 `MANUAL_FUNDING_STALE` 없음, `3일` 은 있음(`days == 3`, 값·변경 시각 일치). (e) **보냈을 때만 소비** — 09:00 KST(`00:00Z`)·12:00 KST(`03:00Z`) 항목 0개 → 알림 없음·`lastDayStart` 그대로(null), 15:00 KST(`06:00Z`) 빗썸 진입 수수료 미반영이 처음 생김 → 그 회차에 리마인더(항목 1개)·`lastDayStart = 2026-10-19T15:00:00Z`, 17:00 KST(`08:00Z`) 펀딩 미반영까지 생겨도 알림 없음. 종일(09:00~23:59 KST 회차들) 항목 0개면 알림 0·`lastDayStart` null. (f) 포지션 없음(`pnl == null`)은 시각·항목과 무관하게 펀딩 추적만 하고 판정·소비하지 않으며, 이어진 포지션 있는 회차에 항목이 있으면 그 회차에 보낸다. (g) `reminderHour = 0` 이면 `15:00:00Z`(00:00 KST)부터 판정 | design §3.4 | `요구` (사용자 확정 D1: 설정 시각 이후 항목이 처음 해당하는 회차에 하루 1회, 보냈을 때만 소비; 3개 플래그 + N일 미갱신; 변경 시각 자동 기록) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.UnrecoveredReminderPolicyTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.UnrecoveredReminderPolicyTest' --offline --no-daemon` | exit 0 |
| AC6 | `AccountAlertPolicy.decide` 가 (a) `round == null` 이면 `next == state`·알림 0. (b) 첫 기동(INITIAL) Computed 회차(`2026-10-20T00:30:00Z`, 미반영 없음)는 알림 0, legs·기준선·펀딩 추적 저장, `reminder.lastDayStart` 는 null 그대로(항목 없음 → 미소비). (c) 빗썸만 바뀐 manual 회차는 [포지션 변경(경고)] 이고 급변 상태가 `(bucket, null, false, false)`. (d) 같은 회차에 급변·리마인더 조건이 겹치면 알림 순서 [급변, 리마인더], 포지션 변경까지 겹치면 [포지션, 리마인더](변경이 있으면 급변은 빠진다). (e) `NoPosition` 회차는 포지션 판정을 하고 리마인더를 판정·소비하지 않는다 | design §3.5 | `요구` (실패 회차 무판정, NoPosition 처리, 사용자 확정 D1) + 순서는 `추론` (A8) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.AccountAlertPolicyTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountAlertPolicyTest' --offline --no-daemon` | exit 0 |
| AC7 | `AccountAlertContent` 가 design §7 템플릿과 같다: (a) 메일 golden(eventKey·제목·본문 전체 `isEqualTo`) — 포지션 변경(빗썸만·manual·경고), 포지션 변경(바이낸스만·API, `숏 … → 없음` 청산), 급변 상승, 급변 하락, 리마인더 4항목(manual), 리마인더 1항목(API `이력 없음`). (b) Discord 같은 시나리오의 제목·설명·색 전체 golden, 색 상수 5개 값. (c) eventKey 가 `account-pnl-position-{ms}`·`account-pnl-swing-up-{ms}`·`account-pnl-swing-down-{ms}`·`account-pnl-reminder-{ms}` 이고 정규식 `[A-Za-z0-9._-]{1,128}` 에 맞는다. (d) 모든 결과 문자열에 `example.test`·`/api/webhooks/` 와 `BINANCE_MANUAL_FUNDING_USDT=` 원문이 없다. (e) 기존 `AccountPnlMailContentTest`·`AccountPnlDiscordContentTest` 무수정 통과 | design §7, §3.6 | `요구` (before→after, 경고 문구, 무음 리마인더 한 메시지) + 문구 세부는 `추론` | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.AccountAlertContentTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountAlertContentTest' --tests 'io.premiumspread.domain.accountpnl.AccountPnlMailContentTest' --tests 'io.premiumspread.domain.accountpnl.AccountPnlDiscordContentTest' --offline --no-daemon` | exit 0 |
| AC8 | (a) 빈 DB → latest 면 버전 1~20 성공, `account_pnl_monitor_state` 열이 기존 9열 뒤에 design §4.2 의 12열(이름:타입:nullable 순서대로), `hedge_mismatch_warned`·`swing_up_sent`·`swing_down_sent` 기본값 0. (b) V19 에서 기존 상태 행(in_episode true, fingerprint 있음, version 7) INSERT 후 latest → 기존 열 값 그대로, 새 열 NULL/0. (c) V20 스키마에서 base 의 `insertState` SQL(9열)이 성공하고, 새 열에 값을 넣은 뒤 base 의 `updateState` SQL 을 실행하면 새 열 값이 그대로다. (d) CHECK — leg 4열 중 일부만 NULL, `swing_bucket_start` NULL + `swing_up_sent = TRUE`, `manual_funding_usdt` 있음 + `manual_funding_changed_at` NULL 은 각각 거부. (e) `verifyMigrations` 통과 | design §4.2, §4.4 | `요구` (V20, additive) + 구 binary 호환은 `추론` (§4.4) | T1 | `신규테스트` `io.premiumspread.infrastructure.common.migration.V20OwnerAccountAlertStateMigrationIntegrationTest` + 기존 task | `./gradlew :infrastructure:common:integrationTest --tests 'io.premiumspread.infrastructure.common.migration.V20OwnerAccountAlertStateMigrationIntegrationTest' --offline --no-daemon && ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` | exit 0 (Docker 필요) |
| AC9 | 실제 MySQL 에서 `JdbcAccountPnlAlertStore` 가 (a) 모든 필드가 채워진 `AccountAlertState`(legs·warned true·기준선 `-0.37`·upSent·리마인더 날짜·펀딩 `-3.2`·변경 시각)를 저장 후 `load()` 하면 `isEqualTo`. (b) `INITIAL` 은 열이 NULL/0 이고 `INITIAL` 로 읽힌다. (c) `0.00012345`·`123456789.123456789` 같은 소수 자릿수가 손실 없이 왕복한다. (d) version 불일치 save 는 새 열을 바꾸지 않는다. (e) 65자 leg 문자열·`10^10` 기준선은 `IllegalArgumentException` 이고 메시지에 값이 없으며 DB 무변경. (f) 기존 테스트 메서드 무수정 통과 | design §4.3 | `요구` (DB 자동 기록, 상태 지속) + 정밀도·검증은 `추론` (K4) | T1 | `신규테스트` (기존 클래스 확장) `io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPnlAlertStoreIntegrationTest` | `./gradlew :infrastructure:common:integrationTest --tests 'io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPnlAlertStoreIntegrationTest' --offline --no-daemon` | exit 0 (Docker 필요) |
| AC10 | `AccountPnlMonitorJob`(#88 `FakeOwnerAlertStore`·`FakeMailSender`·`FakeDiscordSender`·`MutableClock` 재사용, routing 메일+Discord) 이 (a) `accountAlerts` 미지정이면 여러 회차(빗썸 변경·큰 변동·09시 이후 미반영 포함)에서도 새 3종 행 0이고 저장된 `account == 로드한 account`. (b) 설정 시 첫 회차 행 0(정시 보고 꺼짐, 미반영 없음 fixture), legs 저장. (c) 빗썸만 바꾼 manual 회차 → `account-pnl-position-*` EMAIL+DISCORD(PNL/HERE) 2행, 다음 회차(새 Job 인스턴스) 빗썸 재변경 → 경고 없는 변경. (d) 같은 버킷 변동 0.5%p → DISCORD(PNL/SOUND) 1행만(메일 없음), `hourlyReportEnabled = false` 에서도. (e) 09:30 KST 미반영 없음 회차 뒤 같은 날 빗썸 진입 수수료 미반영이 생긴 회차 → 그 회차에 DISCORD(PNL/SILENT) 1행, 이어서 펀딩 미반영까지 생긴 같은 날 회차 → 행 증가 없음. (f) 관측 실패 회차 뒤 `account` 상태 불변·새 행 0. (g) `EMAIL_ONLY` routing 이면 3종 모두 EMAIL 1행씩. (h) version 충돌 회차는 상태·행 미저장이고 다음 회차가 같은 판정으로 행을 만든다. (i) 동결 Job 테스트 3개·#88 Job 테스트 무수정 통과 | design §5 | `요구` (모니터 Job 생산, #88 경로, 기본 라우트, 사용자 확정 D1) + 비활성 기본값은 `추론` (K2) | T1 | `신규테스트` `io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobAccountAlertTest` + `기존` | `./gradlew :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.*' --tests 'io.premiumspread.application.notification.owneralert.*' --offline --no-daemon` | exit 0 |
| AC11 | `ApplicationContextRunner` 에서 (a) env 미설정이면 bean `AccountAlertSettings` 가 `AccountAlertSettings()` 기본값과 같다. (b) `account-pnl-monitor.account-alerts.swing-threshold-percent=0.8`·`reminder-hour=7`·`manual-funding-stale-days=5` 가 반영된다. (c) `reminder-hour=24`·`swing-threshold-percent=0`·`manual-funding-stale-days=0` 은 각각 기동 실패이고 원인 체인에 design §3.7 메시지가 있다. (d) 메일+Discord 구성의 기동 INFO `owner-alert routes:` 가 `position-changed=email+discord(PNL,HERE), net-percent-swing=discord(PNL,SOUND), unrecovered-reminder=discord(PNL,SILENT)` 를 포함하고, 메일만이면 `position-changed=email, net-percent-swing=email, unrecovered-reminder=email` 을 포함한다. (e) `owner-alert.routes.net-percent-swing.email=true` → bean `OwnerAlertRouting.resolve(NET_PERCENT_SWING) == (true, PNL/SOUND)`. (f) #88 `OwnerAlertYamlContractTest` 무수정 통과(yml routes 7 key). (g) 기존 wiring 테스트 메서드 무수정 통과 | design §6 | `요구` (env 임계값, route override env) + 리마인더 시각 env 는 `판정` (O1) | T1 | `신규테스트` (기존 클래스 확장) `io.premiumspread.config.AccountPnlMonitorWiringTest` + `기존` `io.premiumspread.config.OwnerAlertYamlContractTest` | `./gradlew :apps:batch:test --tests 'io.premiumspread.config.AccountPnlMonitorWiringTest' --tests 'io.premiumspread.config.OwnerAlertYamlContractTest' --offline --no-daemon` | exit 0 |
| AC12 | 실제 MySQL(batch fixture, V18+V20 형태) + 실제 `JdbcAccountPnlAlertStore` + 기록용 메일·Discord sender 로 (a) 첫 회차 → 행 0, `korea_quantity` 등 4열 저장. (b) 빗썸 수량 변경(manual) 회차 → `account-pnl-position-*` EMAIL·DISCORD 각 1행 `SENT`, `hedge_mismatch_warned = 1`. (c) 새 Job 인스턴스(재시작) + 바이낸스 변경 → 경고 없는 변경 1건, `hedge_mismatch_warned = 0`. (d) 다음 정시 버킷 첫 회차 → `swing_bucket_start` 갱신·기준선 저장, 같은 버킷 +0.5%p → `account-pnl-swing-up-*` DISCORD 1행. (e) 09:00 KST 이후 항목이 있는 첫 회차 → `account-pnl-reminder-*` DISCORD 1행, `reminder_day_start` = 그날 KST 자정, 같은 날 다음 회차 행 증가 없음. (f) `manual_funding_changed_at` 이 첫 관측 시각으로 기록되고 펀딩 값을 바꾼 재시작 뒤 갱신된다 | design §4, §5 | `요구` (재시작 지속, DB 자동 기록) | T1 | `신규테스트` `io.premiumspread.application.job.OwnerAccountAlertIntegrationTest` + `기존` 허용 예외 `io.premiumspread.config.AccountPnlFixtureSchemaIntegrationTest` | `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.application.job.OwnerAccountAlertIntegrationTest' --tests 'io.premiumspread.config.AccountPnlFixtureSchemaIntegrationTest' --offline --no-daemon` | exit 0 (Docker 필요) |
| AC13 | `docs/runbooks/account-pnl-alert.md` 에 (a) env 3개(`ACCOUNT_PNL_SWING_THRESHOLD_PERCENT`·`ACCOUNT_PNL_REMINDER_HOUR`·`ACCOUNT_PNL_MANUAL_FUNDING_STALE_DAYS`) 행, (b) 기본 라우팅 표 3종과 **Discord 가 꺼져 있으면 3종도 메일로 온다**(굵게), override `{KIND}` 목록 7개, (c) 계정 알림 절 — 급변 기준선·방향당 1회·포지션 변경 시 초기화, 포지션 변경 첫 기동 무알림·청산 포함·헤지 불일치 경고 1회와 해소 조건, 리마인더 "설정 시각 이후 항목이 처음 생긴 회차에 하루 1회, 보냈을 때만 소비, 포지션 없음 회차는 판정 안 함"·수동 펀딩 변경 시각 자동 기록과 "배포 후 N일", 메시지 예 3종, (d) `### 배포와 롤백` 에 V20(additive, 롤백 SQL 없음), (e) `account_pnl_monitor_state` 설명 갱신, (f) AC14 체크리스트. `.ai/PROJECT_STATUS.md` 의 모듈 표 `Flyway V1~V20`(`:13`)과 migration 표 V20 행. `docs/check-documentation.sh` 통과 | design §11 | `요구` (runbook 갱신) + PROJECT_STATUS 는 `리뷰` (⑥ R1 #3) | T3 | 기록 문서 + `bash docs/check-documentation.sh` | `bash docs/check-documentation.sh` | exit 0 + (a)~(f)·PROJECT_STATUS 2곳 존재를 사람 사후 검토 |
| AC14 | owner 가 자기 Discord pnl 채널 webhook 으로 로컬 batch 를 띄웠을 때 (a) 기동 INFO `owner-alert routes:` 에 3종이 기본 경로로 보인다. (b) 첫 기동에 포지션 변경 알림이 없다. (c) 실제 포지션을 바꿨을 때(또는 바꿀 일이 있을 때 — 테스트 목적으로 실제 거래하지 않는다) pnl 채널에 `@here` 로 변경 알림이 오고 메일도 오며, manual 모드에서 빗썸만 바꿨으면 헤지 불일치 경고가 한 번만 붙는다. (d) 순수익률이 한 시간 안에 0.5%p 넘게 움직이면 pnl 채널에 **소리**로 오고 메일은 없다. (e) 미반영 항목이 있으면 09시 이후 그 항목이 확인된 첫 회차에 pnl 채널로 **무음** 리마인더가 오고 그날은 다시 오지 않는다 | design §0, §7 | `요구` (실제 Discord 수신·푸시 수준) | T4 | owner 확인 | — | PR 코멘트에 owner 확인 기록. (c)(d)(e) 는 관찰 기회가 없으면 "미관찰"로 기록 |

**기준 개수**: 14개

**분할하지 않는 이유**: 세 알림은 같은 회차·같은 상태 행·같은 V20·같은 Job 배선을 공유한다. 정책만 먼저 머지하면 생산자가 없고, V20 만 먼저 머지하면 읽는 코드가 없다.
포지션 변경이 급변 기준선을 초기화하므로(§3.3 S1) 두 알림을 나누면 중간 상태의 의미가 틀린다.

**티어 강등 사유** *(T1 이 아닌 항목만. Gate 1 에서 사람이 함께 승인한다)*

- AC13 → T3: runbook 설명의 충분성은 exit code 로 잴 수 없다. `check-documentation.sh` 는 형식만 본다.
- AC14 → T4: 실 webhook·실 계정은 테스트·CI 에 쓰지 않는다(testing 규칙). 푸시 체감과 실제 포지션 변경은 사람만 확인한다.

## 회귀 방어선

이번 변경으로 깨질 수 있는 기존 동작. RED 면제 대상이다. base = `033d513` (Task 0 확인).

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 모듈 경계·import·관측 cardinality·조회 전용·테스트 격리 아키텍처 규칙 전체 | `./gradlew architectureTest --offline --no-daemon` |
| R2 | 전체 unit test | `./gradlew test --offline --no-daemon` |
| R3 | 동결·직전 단위 테스트 **무수정** (기존 파일의 수정·삭제만 본다 — 같은 디렉터리의 신규 테스트 파일은 대상 아님) | `git diff --exit-code --diff-filter=MDR 033d513 -- domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountNetPnlTest.kt domain/src/test/kotlin/io/premiumspread/domain/accountpnl/PnlAlertPolicyTest.kt domain/src/test/kotlin/io/premiumspread/domain/accountpnl/MonitoringOutagePolicyTest.kt domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountHedgePositionTest.kt domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountPnlMailContentTest.kt domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountPnlDiscordContentTest.kt domain/src/test/kotlin/io/premiumspread/domain/accountpnl/OwnerAlertRowsTest.kt domain/src/test/kotlin/io/premiumspread/domain/owneralert/DiscordMessageTest.kt apps/batch/src/test/kotlin/io/premiumspread/application apps/batch/src/test/kotlin/io/premiumspread/config/OwnerAlertYamlContractTest.kt apps/batch/src/integrationTest/kotlin/io/premiumspread/application/job/AccountPnlAlertDurabilityIntegrationTest.kt apps/batch/src/integrationTest/kotlin/io/premiumspread/application/job/OwnerAlertDeliveryIntegrationTest.kt infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/notification infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/owneralert infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/migration/V19OwnerAlertChannelMigrationIntegrationTest.kt` (`infrastructure/batch/.../owneralert` 디렉터리 = `OwnerAlertPropertiesTest`·`OwnerAlertChannelRequirementsTest` 모두 무수정) |
| R4 | 실계정 알림 Domain·Job·wiring (golden 포함) | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.*' --tests 'io.premiumspread.domain.owneralert.*' --offline --no-daemon && ./gradlew :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.*' --tests 'io.premiumspread.application.notification.owneralert.*' --tests 'io.premiumspread.config.*' --offline --no-daemon` |
| R5 | 기존 migration 파일 무변경(추가만) + 검증 | `test -z "$(git diff --name-only --diff-filter=MDR 033d513 -- infrastructure/common/src/main/resources/db/migration)" && ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` |
| R6 | 확장한 기존 테스트(`JdbcAccountPnlAlertStoreIntegrationTest`·`AccountPnlMonitorWiringTest`)에서 base 의 메서드·fixture·단언이 지워지거나 약해지지 않았다 | `git diff 033d513 -- <두 파일> \| grep '^-[^-]'` 출력이 비어 있음을 ⑨ 리뷰가 확인 (사람 판정. 허용 예외 없음) |
| R7 | enum 개수 고정 단언의 수정은 허용 예외 범위뿐이다 — `OwnerAlertRoutingTest` 한 파일에서만: (1) `:24-25`·`:34-35` 기대 목록 **끝에** 3개 추가, (2) 개수가 틀리게 된 테스트 이름 2개 — `:23` `종류는 네 개이고 기본 경로는 설계 표와 같다`, `:42` `EMAIL_ONLY 는 네 종류 모두 메일만` — 를 개수 중립 또는 7종 문구로 rename(예 `종류 목록과 기본 경로는 설계 표와 같다`, `EMAIL_ONLY 는 모든 종류가 메일만`). 단언 변경은 개수에 관한 것뿐이고 이름은 그에 따른다. 다른 줄 무변경. `OwnerAlertPropertiesTest` 는 허용 예외가 아니다(R3) | `git diff 033d513 -- domain/src/test/kotlin/io/premiumspread/domain/owneralert/OwnerAlertRoutingTest.kt` 를 ⑨ 리뷰가 확인 (사람 판정) |
| R8 | batch fixture 스키마 고정 테스트의 수정은 허용 예외 범위뿐이다 — `account_pnl_monitor_state fixture 는 V18 컬럼과 같다` 의 기대 목록 **끝에** V20 12열 추가, 클래스 KDoc 의 경로 목록에 V20 추가. 메서드 이름·`account_pnl_mail` 단언 무변경 | `git diff 033d513 -- apps/batch/src/integrationTest/kotlin/io/premiumspread/config/AccountPnlFixtureSchemaIntegrationTest.kt` 를 ⑨ 리뷰가 확인 (사람 판정) |
| R9 | 공통 persistence·migration 통합 (V12·V16·V17·V19, 기존 store 테스트) | `./gradlew :infrastructure:common:integrationTest --offline --no-daemon` |
| R10 | batch 통합 — 실계정 알림 재시작 내구성·#88 owner 알림 E2E·premium durable delivery·fixture 스키마 | `./gradlew :apps:batch:integrationTest --offline --no-daemon` |
| R11 | 거래 준비 AC20 (production 잔고 포트) — batch 배선 변경의 간접 회귀 | `./gradlew :apps:api:test --tests '*TradePreparationWiring*' --offline --no-daemon` |
| R12 | 정본 문서·하네스 정합성 | `./gradlew harnessCheck --offline --no-daemon && bash docs/check-documentation.sh` |
| R13 | API 의 Flyway 전체 적용(`FlywayOwnershipIntegrationTest` — 컨텍스트 기동 시 V20 까지 적용, 단언은 V13) 과 api 통합 테스트 | `./gradlew :apps:api:integrationTest --offline --no-daemon` (Docker 필요) |

## 스펙 리뷰 라운드 기록 (⑥)

| 라운드 | 지적 | 처리 | 반영 커밋 |
|---|---|---|---|
| R1 | REQUEST_CHANGES — (1 MAJOR) `OwnerAlertPropertiesTest:111-113` 은 부분 문자열 `contains` 라 허용 예외 불필요, (2) Job 클래스 KDoc 의 시각 규칙(`:57-58`)과 계정 알림의 관측 완료 시각, (3) `.ai/PROJECT_STATUS.md:13` `V1~V19`, (4) design §6.2 `@Value` 서술과 plan 의 별도 bean 불일치, (5) `:apps:api:integrationTest`(Flyway 전체 적용) 회귀 행 누락, (6) `OwnerAlertRoutingTest` 의 개수 테스트 이름 2개 | ACCEPT 6 — (1) design K7·§2, dod 기존 gate 조사·AC1(d)·R3·R7, plan Task 1. (2) design §1.1·§5.1, plan Task 10. (3) design §11, dod AC13, plan Task 13. (4) design §6.2, plan Task 11. (5) dod 기존 gate 조사·R13, design §2·§10, plan Task 0·14. (6) dod R7, design K7·§2, plan Task 1 | (오케스트레이터 커밋) |
| ⑦ | 사용자 결정 — D1 리마인더 판정 방식 변경(초안 A3), D2·D3 초안 유지 확정(A7·A6), O1 오케스트레이터 판정 | design §0·§3.2·§3.4·§6.1·§6.2·§7.2·§12, dod `## 사용자 확정`·AC1·AC3·AC5·AC6·AC10·AC11·AC12·AC13·AC14 출처·내용, plan Task 5·6·10·12·13 | (오케스트레이터 커밋) |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.
- 2026-10-23 Task 0 · `git rev-parse --short HEAD` / `git merge-base HEAD origin/dev` → HEAD 958e2b5, merge-base 033d513 (base 일치)
- 2026-10-23 Task 0 · `./gradlew architectureTest --offline --no-daemon` (R1) → exit 0, 35 tests / 실패 0
- 2026-10-23 Task 0 · `./gradlew test --offline --no-daemon` (R2·R4) → exit 0, 945 tests / 실패 0 / skipped 0
- 2026-10-23 Task 0 · `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` (R5) → exit 0
- 2026-10-23 Task 0 · `./gradlew :infrastructure:common:integrationTest --offline --no-daemon` (R9, Docker 28.3.2) → exit 0, 42 tests / 실패 0
- 2026-10-23 Task 0 · `./gradlew :apps:batch:integrationTest --offline --no-daemon` (R10, Docker) → exit 0, 104 tests / 실패 0
- 2026-10-23 Task 0 · `./gradlew :apps:api:integrationTest --offline --no-daemon` (R13, Docker) → exit 0, 174 tests / 실패 0
- 2026-10-23 Task 0 · `ls infrastructure/common/src/main/resources/db/migration | sort -V | tail -1` → V19__add_owner_alert_channel.sql (신규는 V20)
- 2026-10-23 Task 0 · `grep -rn "AccountPnlMonitorJob(" apps` → 호출처 Configuration + 테스트 4 + 통합 2, 생성자 파라미터 13개(기존 순서), 모든 호출처 13개 이내
- 2026-10-23 Task 0 · `grep -rn "AccountPnlMonitorState(" .` → 전 생성처 3인자(alert, outage, version)
- 2026-10-23 Task 0 · `grep -rn "OwnerAlertKind.entries|known: profit"` → enum 개수 고정은 OwnerAlertRoutingTest(:24,:34)뿐, OwnerAlertPropertiesTest:113은 contains 부분 문자열, 그 외 forEach/집합 비교
- 2026-10-23 Task 1 · AC1 RED(stub 경로 `OwnerAlertRoute(email = true, discord = null)`) `./gradlew :domain:test --tests 'io.premiumspread.domain.owneralert.*' --offline --no-daemon` → BUILD FAILED, 24 tests / 실패 1 (`OwnerAccountAlertKindTest > 계정 알림 세 종류의 기본 경로는 설계 표와 같다`, 기본 경로 단언). key·EMAIL_ONLY·both 해석은 stub 에서도 초록(예상된 범위)
- 2026-10-23 Task 1 · AC1 GREEN(전문 경로) 같은 domain 명령 + `:infrastructure:batch:test --tests '...OwnerAlertPropertiesTest'`(무수정) → BUILD SUCCESSFUL; `:apps:batch:test --tests OwnerAlertYamlContractTest --tests AccountPnlMonitorWiringTest`(무수정) → BUILD SUCCESSFUL
- 2026-10-23 Task 1 · `./gradlew test --offline --no-daemon` → BUILD SUCCESSFUL (커밋 전 전체 unit)
- 2026-10-23 Task 2 · AC2 RED(stub: of() 정규화 없음·samePosition `==`) `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountAlertStateTest' --offline --no-daemon` → BUILD FAILED, 11 tests / 실패 5 (dust 경계·0.50↔0.5·평단 scale·samePosition compareTo·FLAT 단언); 불변식·설정·3인자 account 케이스는 stub 에서도 초록(예상된 범위)
- 2026-10-23 Task 2 · AC2 GREEN(전문 구현) 같은 domain 명령 → BUILD SUCCESSFUL, 11 tests; 동결 컴파일 `:domain:test :apps:batch:compileTestKotlin :infrastructure:common:compileIntegrationTestKotlin :apps:batch:compileIntegrationTestKotlin` → BUILD SUCCESSFUL; `./gradlew test --offline --no-daemon` → BUILD SUCCESSFUL (커밋 전 전체 unit)
- 2026-10-23 Task 3 · AC3 RED(stub: decide 가 항상 알림 null, warned 유지) `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.PositionChangePolicyTest' --offline --no-daemon` → BUILD FAILED, 12 tests / 실패 8 (P2·P4~P8·해소 아님·청산); P1·P3·알림 불변식 거부 4건은 stub 에서도 초록(예상된 범위)
- 2026-10-23 Task 3 · AC3 GREEN(전문 구현) 같은 domain 명령 → BUILD SUCCESSFUL, 12 tests
- 2026-10-23 Task 3 · `./gradlew test --offline --no-daemon` → BUILD SUCCESSFUL (커밋 전 전체 unit)
- 2026-10-23 Task 4 · AC4 RED(stub: decide 가 state 그대로·알림 null, bucketStart 는 실제 식) `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.NetPercentSwingPolicyTest' --offline --no-daemon` → BUILD FAILED, 6 tests / 실패 5 (S2 기준선·S4~S6 경계·S1·S2 리셋·알림 필드); bucketStart 경계 1건은 stub 에서도 초록(예상된 범위)
- 2026-10-23 Task 4 · AC4 GREEN(전문 구현) 같은 domain 명령 → BUILD SUCCESSFUL, 6 tests
- 2026-10-23 Task 4 · `./gradlew test --offline --no-daemon` → BUILD SUCCESSFUL (커밋 전 전체 unit)
- 2026-10-23 Task 5 · AC5 RED(stub: decide 가 state 그대로·알림 null, dayStart 는 실제 식) `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.UnrecoveredReminderPolicyTest' --offline --no-daemon` → BUILD FAILED, 9 tests / 실패 7
- 2026-10-23 Task 5 · AC5 GREEN(전문 구현) 같은 domain 명령 → BUILD SUCCESSFUL, 9 tests
- 2026-10-23 Task 5 · `./gradlew test --offline --no-daemon` → BUILD SUCCESSFUL (커밋 전 전체 unit)
- 2026-10-23 Task 6 · AC6 RED(stub: decide 가 state 그대로·알림 빈 리스트) `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountAlertPolicyTest' --offline --no-daemon` → BUILD FAILED, 6 tests / 실패 4 (첫 기동 채움·빗썸 변경 manual·겹침 2종); 실패 회차·NoPosition 불변 2건은 stub 에서도 초록(예상된 범위)
- 2026-10-23 Task 6 · AC6 GREEN(전문 구현) 같은 domain 명령 → BUILD SUCCESSFUL, 6 tests
- 2026-10-23 Task 6 · `./gradlew test --offline --no-daemon` → BUILD SUCCESSFUL (커밋 전 전체 unit)
- 2026-10-23 Task 6 fix · AC6(e) 보강: pnl==null + 빗썸 leg 청산 회차 케이스 추가. 포지션 판정 skip 변이에서 `AccountAlertPolicyTest` 7 tests / 실패 4(신규 케이스 포함), 복원 후 BUILD SUCCESSFUL
