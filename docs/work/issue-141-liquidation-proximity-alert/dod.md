---
feature: 청산 근접 단계 알림 — 진입가→청산가 진행률 50%부터 10% 단위
slug: issue-141-liquidation-proximity-alert
status: DRAFT
frozen_at:
verdict_commit:
source: https://github.com/geonyeop123/premium-spread/issues/141
---

## 범위

**포함**

- 도메인 순수 정책 `LiquidationProximityPolicy`(진행률·경계·재무장·초기화, design D1~D5)와 상태 `LiquidationProximityState`
- `AccountRound` 에 #138 위험 지표(`foreignRisk`·`foreignConfig`) 전달, `AccountAlertPolicy` 조합
- `OwnerAlertKind.LIQUIDATION_PROXIMITY`(메일 + Discord PNL·SOUND)와 yml 덮어쓰기 블록
- 메일·Discord 문구와 eventKey(design D8), 문구 정본 `message-spec.md` 절 추가
- migration V27(nullable 열 2개 + CHECK)과 `JdbcAccountPnlAlertStore` 저장·복원
- runbook `account-pnl-alert.md` 동작·한계·체크리스트

**제외** *(명시적으로 하지 않는 것 — scope creep 차단선)*

- 회복(하락) 방향 알림, 증거금 추가·포지션 축소 같은 대응 동작
- 관측 실패 회차의 별도 위험 조회, 청산가 자체 계산
- #139 관측 이력 테이블, #140 자산 표기

## 기존 gate 조사

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있음 — root `architectureTest`(모듈 경계·domain 의존 금지·조회 전용 GET) | 예. 회귀 방어선 |
| 종류 열거 테스트 | 있음 — `OwnerAlertRoutingTest`(종류 이름·key 순서 고정), `OwnerAccountAlertKindTest` | 예. 새 종류를 더하면 갱신해야 한다(AC8) |
| yml 계약 | 있음 — `io.premiumspread.config.OwnerAlertYamlContractTest`(routes key 집합 = OwnerAlertKind, 종류별 env 연결) | 예. AC8 의 검증 수단 |
| migration gate | 있음 — `:infrastructure:common:verifyMigrations`(버전 유일성·파괴적 SQL 금지) | 예. 회귀 방어선 |
| 저장소 통합 테스트 | 있음 — `JdbcAccountPnlAlertStoreIntegrationTest`(Testcontainers MySQL) | 예. AC6 테스트를 같은 클래스에 추가 |
| lint | 있음 — CI `quality-gate.yml` ktlint·detekt | 예. 회귀 방어선 (로컬 jar 없음, CI 확인) |
| 문서 검사 | 있음 — `docs/check-documentation.sh`(정본 문서) | 예. 회귀 방어선 |

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | 같은 숏 포지션에서 진행률이 45→55→65→95% 로 오르는 4회차를 넣으면 정책이 경계 50·60·90 알림 3건만 내고, 마지막 상태 level 은 90 이다 | 이슈 수용 기준 1, design D3 | `요구` | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.LiquidationProximityPolicyTest` (`45 55 65 95 는 50 60 90 세 건이다`) | `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.LiquidationProximityPolicyTest --offline` | exit 0 |
| AC2 | 진행률 55→49→55 는 첫 50 알림 뒤 추가 알림 0건이고, 55→39→55 는 50 을 한 번 더 알린다(한 단계 아래 경계 밑으로 내려가야 재무장) | 이슈 수용 기준 1(진동), design D3 | `요구` | T1 | `신규테스트` 같은 클래스 (`진동은 재무장 전까지 다시 알리지 않는다`, `한 단계 아래 경계 밑으로 내려가면 재무장한다`) | AC1 과 같음 | exit 0 |
| AC3 | 진행률은 `(mark − entry)/(liq − entry) × 100` 이다: 숏 entry 100·liq 200·mark 160 → 60, 롱 entry 100·liq 50·mark 70 → 60, 숏 mark 90(유리) → 알림 0건. 49.99999% 는 50 경계로 알리지 않는다 | 이슈 결정(진행률), design D1 | `요구` | T1 | `신규테스트` 같은 클래스 (`숏 롱 진행률은 같은 식이다`, `유리한 쪽과 경계 직전은 알리지 않는다`) | AC1 과 같음 | exit 0 |
| AC4 | 관측 실패(round null)·수동 모드·위험 지표 null·청산가 null·liq == entry·청산가가 진입가 반대쪽인 회차는 알림 0건이고 상태가 입력과 같다 | 이슈 범위(판정 안 함), design D2 마지막 행은 추론 | `요구` | T1 | `신규테스트` 같은 클래스 (`판정하지 않는 회차는 상태를 바꾸지 않는다`) | AC1 과 같음 | exit 0 |
| AC5 | 실계정 회차에서 바이낸스 leg 가 flat 이면 상태가 INITIAL 이 되고, 다음에 연 숏이 55% 면 50 을 다시 알린다. 저장된 side 와 다른 방향이 판정되면 level 0 에서 판정한다. 같은 포지션에서 진입가·청산가가 바뀌면 level 을 유지한다 | 이슈 "기준 초기화"(계약 결정), design D5 | `요구` | T1 | `신규테스트` 같은 클래스 (`바이낸스 flat 이면 초기화한다`, `방향이 바뀌면 처음부터 판정한다`, `같은 포지션의 진입가 청산가 변경은 단계를 유지한다`) | AC1 과 같음 | exit 0 |
| AC6 | `save` 한 `(side, level)` 이 `load` 로 그대로 복원되고, 열이 NULL 인 행은 INITIAL 로 복원되며, side 만 있고 level 이 NULL 인 행은 CHECK 위반으로 거부된다 | 이슈 수용 기준 2(재시작 후 유지), design D6 | `요구` | T1 | `신규테스트` `io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPnlAlertStoreIntegrationTest` (`청산 근접 단계 상태를 저장하고 복원한다`) | `./gradlew :infrastructure:common:integrationTest --tests io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPnlAlertStoreIntegrationTest --offline` | exit 0 |
| AC7 | Job 회차에서 숏 진행률이 경계를 넘으면 `store.save` 한 번의 호출에 `account-pnl-liquidation-` eventKey 의 EMAIL 행 1개와 DISCORD(PNL·SOUND) 행 1개가 상태(level)와 함께 들어가고, 수동 모드·청산가 null 회차는 그 행이 0개다 | 이슈 범위(같은 원자 저장, 라우팅 기본값) | `요구` | T1 | `신규테스트` `io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobLiquidationTest` | `./gradlew :apps:batch:test --tests io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobLiquidationTest --offline` | exit 0 |
| AC8 | `OwnerAlertKind.LIQUIDATION_PROXIMITY` 의 key 가 `liquidation-proximity`, 기본 경로가 메일 + Discord PNL·SOUND 이고, yml `owner-alert.routes.liquidation-proximity` 의 email·discord·push 가 `OWNER_ALERT_LIQUIDATION_PROXIMITY_*` env 에 기본값 없이 연결된다 | 이슈 "라우팅" | `요구` | T1 | `기존` `io.premiumspread.config.OwnerAlertYamlContractTest` + `신규테스트` `io.premiumspread.domain.owneralert.OwnerAccountAlertKindTest` (`청산 근접 기본 경로는 메일과 PNL 소리다`) | `./gradlew :apps:batch:test --tests io.premiumspread.config.OwnerAlertYamlContractTest :domain:test --tests io.premiumspread.domain.owneralert.OwnerAccountAlertKindTest --offline` | exit 0 |
| AC9 | 알림 문구에 진행률(소수 1자리 %)·마크가·청산가·진입가·레버리지(`5x`)·마진 모드(`격리`)가 메일과 Discord 양쪽에 있고, symbolConfig 가 null 이면 레버리지·마진 모드가 `확인 불가`이며, eventKey 는 `account-pnl-liquidation-{level}-{observedAt ms}` 이다 | 이슈 "문구", design D8 | `요구` | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.LiquidationProximityContentTest` | `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.LiquidationProximityContentTest --offline` | exit 0 |

**기준 개수**: 9개

**분할하지 않는 이유**: AC1~AC5 는 한 순수 정책의 분기이고, AC6(저장)·AC7(Job 조립)·AC8(라우팅)·AC9(문구)는 그 정책이 만든
상태와 알림을 실어 나르는 경로다. 나누면 상태 열은 있는데 판정이 없거나, 판정은 있는데 재시작하면 단계를 잊는 중간 상태가
dev 에 머지된다(이슈 수용 기준 2 위반). 예상 diff 는 테스트 포함 약 900줄로 #131(기준 포지션 단계) 과 같은 규모다.

**티어 강등 사유**: 없음 (전부 T1). AC6 은 Docker(Testcontainers)가 필요하다 — 실행 못 하면 BLOCKED 로 기록하고 green 으로 쓰지 않는다.

## 검사 산출물

해당 없음 (신규스크립트 없음)

## 회귀 방어선

- `./gradlew test architectureTest --parallel --offline --no-daemon`
- `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon`
- `./gradlew :apps:batch:integrationTest --offline --no-daemon` (계정 알림·Discord 레이아웃 통합 테스트)
- `docs/check-documentation.sh`
- CI `quality-gate.yml` (ktlint·detekt 포함)

## 증거 로그

(구현 중 RED/GREEN 을 붙인다)
