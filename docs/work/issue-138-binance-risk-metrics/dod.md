---
feature: 바이낸스 위험 지표 조회 — 레버리지·마진 모드·청산가·증거금
slug: issue-138-binance-risk-metrics
status: FROZEN
frozen_at: 2026-11-12 (사용자 계약 승인 — AC7 흡수안 포함)
verdict_commit: 708dc47
source: https://github.com/geonyeop123/premium-spread/issues/138
---

## 범위

**포함**

- `BinanceFuturesPrivateAccountAdapter`가 `/fapi/v3/positionRisk`의 7개 값과 `/fapi/v1/symbolConfig`의 `leverage`·`marginType`을 GET 으로 읽어 스냅샷에 싣는다
- 도메인 값 타입 `ForeignPerpPositionRisk`, `ForeignPerpSymbolConfig`, `ForeignPerpMarginType` 추가와 `ForeignPerpAccountSnapshot` 필드 2개 추가
- symbolConfig 부분 실패 흡수(값 null + WARN), positionRisk 위험 필드 해석 실패 흡수(design D3)
- 포지션 없음·청산가 0 처리, 수동 모드 미조회
- `PrivateAccountReadOnlyArchitectureTest`에 새 읽기 경로 포함 단정

**제외** *(명시적으로 하지 않는 것 — scope creep 차단선)*

- 이력 테이블 기록, 알림 표기, 청산 알림 (#137 다른 하위 이슈)
- `AccountPnlMonitorJob`의 계산·결과 변경
- hedge mode 행 선택 규칙(SHORT 우선, 없으면 BOTH) 변경

## 기존 gate 조사

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있음 — `PrivateAccountReadOnlyArchitectureTest`(GET 외 호출·주문/출금 경로 금지, 필수 대상 파일 자기검증) | 예. 새 경로가 스캔 대상 파일에 있는지 단정하는 테스트를 같은 클래스에 추가 |
| lint 규칙 | 있음 — CI `quality-gate.yml` ktlint (`.ci-tools/ktlint.jar`) | 예. 회귀 방어선 |
| custom gradle/npm task | 있음 — root `architectureTest`, `:infrastructure:batch:test`, `:domain:test` | 예 |
| CI job | 있음 — `quality-gate.yml`의 `test`·`architectureTest` | 예 (PR 에서 실행) |

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | positionRisk 표본이 숏(BOTH 음수)·롱(BOTH 양수)·hedge SHORT 행일 때 어댑터가 `positionRisk`의 7개 값을 표본 문자열과 `compareTo == 0`으로 같게 낸다 | 이슈 수용기준 1 | `요구` | T1 | `신규테스트` `io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapterTest` (`positionRisk 위험 지표를 숏 롱 hedge 에서 읽는다`) | `./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapterTest --offline` | exit 0 |
| AC2 | 열린 포지션에서 `liquidationPrice`가 `"0"`이면 어댑터가 `positionRisk.liquidationPrice = null`을 내고 나머지 6개 값은 채운다 | 이슈 "청산가 0 이면 청산가 없음" | `요구` | T1 | `신규테스트` 같은 클래스 (`청산가 0 이면 청산가만 없음이다`) | AC1 과 같음 | exit 0 |
| AC3 | symbolConfig 표본 `leverage=5, marginType=ISOLATED`(및 CROSSED)일 때 어댑터가 `symbolConfig`를 그 값으로 내고, 그 요청은 서명된 GET 1건이며 depth 는 여전히 마지막 요청이다 | 이슈 범위 | `요구` | T1 | `신규테스트` 같은 클래스 (`symbolConfig 레버리지와 마진 모드를 서명 GET 한 건으로 읽는다`) | AC1 과 같음 | exit 0 |
| AC4 | 포지션이 없으면(`[]` 또는 `positionAmt=0`) 어댑터가 `positionRisk`·`symbolConfig`를 모두 null 로 내고 `/fapi/v1/symbolConfig`를 요청하지 않는다 | 이슈 "포지션이 없을 때 위험 지표가 없다" | `요구` | T1 | `신규테스트` 같은 클래스 (`포지션이 없으면 위험 지표가 없고 symbolConfig 를 부르지 않는다`) | AC1 과 같음 | exit 0 |
| AC5 | symbolConfig 가 401·503·깨진 JSON·-1021·응답 없음(timeout)·BTCUSDT 행 없음·leverage 비숫자·알 수 없는 marginType 이면 `read()`가 예외 없이 `symbolConfig = null`을 내고 `signedQuantity`·`entryPrice`·`takerFeeRate`·`balance`·`askLevels`·`positionRisk`는 정상 케이스와 같으며, 출력 로그에 `item=BINANCE_SYMBOL_CONFIG`와 사유가 있고 key·secret·`signature=`·레버리지 값이 없다 | 이슈 수용기준 2 (부분 실패) | `요구` | T1 | `신규테스트` 같은 클래스 (`symbolConfig 실패는 그 항목만 null 로 흡수하고 사유만 WARN 한다`) | AC1 과 같음 | exit 0 |
| AC6 | symbolConfig 요청 중 취소(인터럽트)되면 `read()`가 흡수하지 않고 `AccountReadException`을 던지며 depth 를 요청하지 않는다 | 잔고 경로의 취소 계약을 같은 부분 실패 경로에 적용 | `추론` | T1 | `신규테스트` 같은 클래스 (`symbolConfig 요청 중 취소되면 흡수하지 않고 던진다`) | AC1 과 같음 | exit 0 |
| AC7 | positionRisk 의 위험 필드(예: `markPrice` 누락, `maintMargin` 비숫자, `isolatedMargin` 음수)가 깨지면 `read()`가 예외 없이 `positionRisk = null`을 내고 수량·진입가는 그대로이며 로그에 `item=BINANCE_POSITION_RISK`가 있다 | design D3 — 새 값 때문에 기존 회차가 실패하지 않게 | `추론` | T1 | `신규테스트` 같은 클래스 (`positionRisk 위험 필드 해석 실패는 그 항목만 null 로 흡수한다`) | AC1 과 같음 | exit 0 |
| AC8 | 도메인 값 타입이 `markPrice <= 0`, `liquidationPrice <= 0`(null 아님), 증거금 4종 음수, `leverage < 1`이면 `IllegalArgumentException`을 던지고 `toString`에 금액이 없다 | design D1 불변식 | `추론` | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.ForeignPerpRiskMetricsTest` | `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.ForeignPerpRiskMetricsTest --offline` | exit 0 |
| AC9 | `/fapi/v3/positionRisk`·`/fapi/v1/symbolConfig` 각각이 GET 전용 스캔 대상 파일 중 하나에 나타나지 않으면 아키텍처 테스트가 실패한다 | 이슈 수용기준 3 | `요구` | T1 | `신규테스트` `io.premiumspread.architecture.PrivateAccountReadOnlyArchitectureTest` (`위험 지표 읽기 경로는 GET 전용 스캔 대상 안에 있다`) | `./gradlew architectureTest --offline` | exit 0 |

**기준 개수**: 9개

**분할하지 않는 이유**: AC1~AC7 은 같은 어댑터 메서드 한 개(`read()`)의 분기이고 AC8 은 그 값 타입, AC9 는 같은 경로의 GET 전용 단정이다. 나누면 하위 PR 하나가 값 타입만 있고 채우는 코드가 없는 상태로 머지된다. diff 예상 규모는 테스트 포함 약 400줄로 한 PR 리뷰 범위다.

**티어 강등 사유**: 없음 (전부 T1)

## 검사 산출물

해당 없음 (신규스크립트 없음)

## 회귀 방어선

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 기존 바이낸스 어댑터 동작(잔고 흡수, depth 마지막, 서명, 오류 범주) | `./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapterTest --offline` |
| R2 | 수동 모드는 서명·API key 없이 동작하고 새 필드는 null (`BinanceFuturesManualAccountAdapterTest`, 아키텍처 수동 모드 규칙) | `./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesManualAccountAdapterTest --offline` |
| R3 | Job 회차 결과·순손익 계산 불변 | `./gradlew :apps:batch:test --offline` |
| R4 | 전체 unit·architecture | `./gradlew test architectureTest --parallel --offline --no-daemon` |
| R5 | ktlint | `java -jar .ci-tools/ktlint.jar '**/src/**/*.kt' '!**/build/**'` — 로컬에 jar 가 없다(2026-11-12 확인, `ci/bootstrap-quality-tools.sh`가 CI 에서 받는다). PR 의 `quality-gate.yml` 결과로 판정한다 |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

### AC8

```
[RED] 2026-11-12 uncommitted
$ ./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.ForeignPerpRiskMetricsTest --offline
ForeignPerpRiskMetricsTest > 증거금 4종이 음수이면 거부한다() FAILED  (AssertionError at :43)
ForeignPerpRiskMetricsTest > 레버리지가 1 미만이면 거부한다() FAILED  (AssertionError at :58)
ForeignPerpRiskMetricsTest > 마크가가 0 이하이면 거부한다() FAILED  (AssertionError at :26)
ForeignPerpRiskMetricsTest > 청산가는 null 이거나 양수다() FAILED  (AssertionError at :34)
6 tests completed, 4 failed
BUILD FAILED

[GREEN] 2026-11-12 uncommitted
$ ./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.ForeignPerpRiskMetricsTest --offline
BUILD SUCCESSFUL (6 tests passed)
$ ./gradlew :domain:test --offline
BUILD SUCCESSFUL
```

### AC9

```
[RED] 2026-11-12 uncommitted
$ ./gradlew architectureTest --offline
PrivateAccountReadOnlyArchitectureTest > 위험 지표 읽기 경로는 GET 전용 스캔 대상 안에 있다() FAILED
    AssertionFailedError: 위험 지표 읽기 경로 [/fapi/v1/symbolConfig] 가 GET 전용 스캔 대상 어디에도 없다
36 tests completed, 1 failed
BUILD FAILED
(GREEN 은 Task 2 에서 symbolConfig 경로가 들어간 뒤 기록)
```

```
[GREEN] 2026-11-12 uncommitted (Task 2 — 어댑터에 "/fapi/v1/symbolConfig" 리터럴 추가 뒤)
$ ./gradlew :infrastructure:batch:test architectureTest --offline
BUILD SUCCESSFUL
architectureTest: 36 tests, 0 failures, 0 errors (PrivateAccountReadOnlyArchitectureTest 포함)
:infrastructure:batch:test: 279 tests, 0 failures, 0 errors
```

### AC1-AC7

```
[RED] 2026-11-12 uncommitted (테스트·픽스처·로그 enum 만 추가, 어댑터 미변경)
$ ./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapterTest --offline
BinanceFuturesPrivateAccountAdapterTest > symbolConfig 실패는 그 항목만 null 로 흡수하고 사유만 WARN 한다(CapturedOutput) FAILED  (AssertionError)
BinanceFuturesPrivateAccountAdapterTest > symbolConfig 요청 중 취소되면 흡수하지 않고 던진다(CapturedOutput) FAILED  (AssertionError)
BinanceFuturesPrivateAccountAdapterTest > 청산가 0 이면 청산가만 없음이다() FAILED  (AssertionError)
BinanceFuturesPrivateAccountAdapterTest > positionRisk 위험 지표를 숏 롱 hedge 에서 읽는다() FAILED  (AssertionError)
BinanceFuturesPrivateAccountAdapterTest > symbolConfig 레버리지와 마진 모드를 서명 GET 한 건으로 읽는다() FAILED  (AssertionError)
BinanceFuturesPrivateAccountAdapterTest > positionRisk 위험 필드 해석 실패는 그 항목만 null 로 흡수한다(CapturedOutput) FAILED  (AssertionError)
42 tests completed, 6 failed
BUILD FAILED
(AC4 `포지션이 없으면 위험 지표가 없고 symbolConfig 를 부르지 않는다` 는 미구현 상태에서도 참이라 RED 가 없다 — 구현 뒤 회귀 방어선)

[GREEN] 2026-11-12 uncommitted
$ ./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapterTest --offline
BUILD SUCCESSFUL
BinanceFuturesPrivateAccountAdapterTest: 42 tests, 0 failures, 0 errors, 0 skipped
$ ./gradlew :apps:batch:test --offline
BUILD SUCCESSFUL (389 tests, 0 failures)
```

### AC4 (mutation RED — 컨트롤러 보강)

```
[RED] 2026-11-12 708dc47 + 임시 변이(커밋 안 함): 어댑터 44행 `if (open) readSymbolConfig() else null` → `readSymbolConfig()`
$ ./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapterTest --offline
BinanceFuturesPrivateAccountAdapterTest > 포지션이 없으면 위험 지표가 없고 symbolConfig 를 부르지 않는다() FAILED
org.opentest4j.AssertionFailedError: expected: null but was: ForeignPerpSymbolConfig(marginType=ISOLATED)
exit=1
(변이 원복 후 git status 깨끗)

[GREEN] 2026-11-12 708dc47
$ ./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapterTest --offline
exit=0
```

### Gate 2 일괄 실행

```
[GREEN] 2026-11-12 708dc47
$ ./gradlew test architectureTest --parallel --offline --no-daemon --rerun-tasks
exit=0 — test-results XML 집계 1672 tests, 0 failures, 0 errors, 0 skipped (R1~R4 포함)
$ ./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.ForeignPerpRiskMetricsTest --offline  → exit=0 (AC8)
$ ./gradlew architectureTest --offline  → exit=0 (AC9)
$ ./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesManualAccountAdapterTest --offline  → exit=0 (R2)
R5 ktlint: 로컬 jar 없음 — PR quality-gate CI 결과로 판정(미실행을 green 으로 기록하지 않는다)
```

## 사람 확인 (T4)

해당 없음

## 변경 요청

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|

## 최종 판정

```
DoD VERDICT: issue-138-binance-risk-metrics @ 708dc47
  수용기준 표:     9개  (T1 9 · T2 0 · T3 0 · T4 0)
  T1/T2 자동:      9개 중 9개 PASS
  T3 기록 제출:    0개 중 0건
  T4 사람 확인:    0개 중 0건 완료, 0건 대기
  변경 요청:       0건
  => VERIFIED
```
