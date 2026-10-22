---
feature: 실계정 순손익 알림 — 바이낸스 수동 입력 모드 (Live Account PnL Manual Binance)
slug: live-account-pnl-manual-binance
status: FROZEN
frozen_at: 2026-10-22T09:38:21+09:00
verdict_commit:
source: docs/work/live-account-pnl-manual-binance/design.md (APPROVED 2026-10-22)
---

## 범위

**포함**

- 설정 `account-pnl-monitor.binance.mode` = `api`(기본) | `manual`, manual 입력 env 5종의 `String?` 바인딩과 직접 해석·검증 (design §3)
- 모드별 기동 조건, 필수값 누락 시 bean 0 + WARN(변수 이름만), app·infra 조건 일치 (§3.3, §4)
- `ForeignPerpAccountSnapshot.positionSource` (§5.1)
- 메일 본문 출처 표기 — 수동 입력 / 수동 미입력 (§5.4)
- 바이낸스 공개 호가·체결가 조회 공유 클래스 추출 (§8.1)
- manual 모드 adapter — 공개 GET 만, 서명·API key·서버 시각 없음 (§7, §8.1)
- 조회 전용 아키텍처 테스트 대상 확장 (§8.2)
- runbook 갱신 (§7)

**제외** *(scope creep 차단선)*

- 바이낸스 key 발급, 빗썸 수동 모드, 웹 화면·endpoint (§1.2)
- master spec·직전 단위 산출물(`live-account-pnl-alert/*`) 수정 (§1.2)
- Flyway migration (§6)
- 펀딩 자동 누적·포지션 변경 자동 감지, 공개 fundingRate 기반 펀딩 추정(후속) (§1.2, §7)
- 계정/호가 Domain port 분리 (§1.2)
- 순손익 공식·알림·중단 정책·outbox·Job 변경

## 기존 gate 조사

> Gate 1 필수. 작성 시점에는 문서·소스만 읽었다. 실행 기준선은 plan Task 0 이 아래 증거 로그에 남긴다.

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | **있다** — `architecture-tests/` source set. `PrivateAccountReadOnlyArchitectureTest`(직전 AC10, 금지 규칙 20개·자기검증) | **확장해서 쓴다** (AC7). 루트 `architectureTest` 는 `--tests` 를 받지 않으므로 `:architecture-tests:architectureTest` 로 실행 |
| lint 규칙 | 있다 — ktlint·detekt (CI `quality-gate.yml`). 로컬 gradle task 없음 | 회귀 방어선(CI)으로만 |
| custom gradle task | 있다 — `architectureTest`, `*:integrationTest`, `verifyMigrations`, `harnessCheck` | 그대로 쓴다 |
| 동결 계약 테스트 | 있다 — `TradePreparationWiringContractTest` (거래 준비 AC20) | R2 |
| 직전 단위 수용기준 테스트 | 있다 — AC1~AC11 의 11개 클래스 | R8 (무수정 통과) · 일부는 이 단위가 확장 |
| api 메일 바이트 불변 증명 | **없다** — 기존 `AccountPnlMailContentTest` 는 `contains` 단언이라 문구 추가를 잡지 못한다 | 신규 golden 특성화 테스트를 **먼저** 만든다 (AC2) |
| 외부 HTTP mock 패턴 | 있다 — `BinanceFuturesPrivateAccountAdapterTest` (MockWebServer, fixture `binance-private/depth.json`·`ticker-price.json`) | AC3 가 같은 fixture·패턴을 쓴다 |

**결론.** 모든 T1 수용기준을 `신규테스트` 또는 `기존`(확장)으로 덮는다. AC4 의 중복 검사는 셸 명령 한 줄이며 별도 스크립트 파일을 만들지 않는다.

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | `ForeignPerpAccountSnapshot` 를 출처 인자 없이 만들면 `positionSource == EXCHANGE_API` 다. 수량·평단·진입가가 같고 출처만 `EXCHANGE_API`/`MANUAL_ENV` 로 다른 두 포지션의 `fingerprint` 는 같다 | design §5.1, §5.3 | `요구` (Domain 출처 운반, 기존 결과 불변) + fingerprint 무관은 `추론` (design §11 내 판단) | T1 | `신규테스트` (기존 클래스 확장) `io.premiumspread.domain.accountpnl.AccountHedgePositionTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountHedgePositionTest' --offline --no-daemon` | exit 0 |
| AC2 | (a) 출처 `EXCHANGE_API` 수익 메일의 eventKey·제목·본문 전체가 base `0a04546` 코드로 만든 golden 문자열과 **완전히 같다** — 모든 항목 확보 fixture 1건, 진입 수수료(양 leg)·펀딩 미확보 fixture 1건. (b) 출처 `MANUAL_ENV` 면 본문에 `바이낸스 포지션: 수동 입력(env)` 줄이 `[바이낸스 선물 숏]` 바로 다음에 있고, 바이낸스 진입 수수료·펀딩이 있으면 각 줄 끝에 ` 수동 입력`, 없으면 `진입 수수료(바이낸스) 미반영(수동 미입력)`·`펀딩비 미반영(수동 미입력)` 이며 `(이력 없음)` 은 바이낸스 줄에 나타나지 않는다. (c) 출처 `MANUAL_ENV` 여도 빗썸 미반영 줄은 `진입 수수료(빗썸) 미반영(이력 없음)` 그대로이고 제목은 같은 입력의 `EXCHANGE_API` 제목과 같다. manual 변형은 기존 fixture 를 고치지 않고 `copy(positionSource = MANUAL_ENV)` 로 만든다 | design §5.4, §9 | `요구` (수동 입력 표기, 제목 불변, api 메일 바이트 불변) | T1 | `신규테스트` (기존 클래스 확장) `io.premiumspread.domain.accountpnl.AccountPnlMailContentTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountPnlMailContentTest' --offline --no-daemon` | exit 0 |
| AC3 | MockWebServer 에서 `BinanceFuturesManualAccountAdapter.read()` 가 (a) 입력 수량 `0.266` → `signedQuantity == -0.266`, 진입가·진입 수수료·펀딩·taker 율을 입력 그대로, `positionSource == MANUAL_ENV` 로 내고, 미입력(null) 수수료·펀딩은 null 이며, 수량 `0` 이면 `signedQuantity` 0 을 낸다. (b) ask 레벨은 depth fixture 의 asks 를 가격 오름차순으로, `askObservedAt` 은 `T`, `lastPrice` 는 `/fapi/v2/ticker/price` 의 `price` 다. 가격이 JSON number 로 와도 `BigDecimal` 정밀도(`toPlainString` 일치)를 보존한다. (c) 한 회차 요청은 `GET /fapi/v2/ticker/price?symbol=BTCUSDT` 다음 `GET /fapi/v1/depth?symbol=BTCUSDT&limit=20` 두 건뿐이고, 어느 요청에도 `X-MBX-APIKEY` 헤더·`signature`·`timestamp`·`recvWindow` 파라미터가 없으며 `/fapi/v1/time` 요청이 없다. 두 번 `read()` 하면 4건이다. (d) 429·503·연결 거부(서버 종료)·timeout 초과(`timeout=200ms` + 응답 지연 1초) → `NETWORK`, 깨진 JSON·asks 빈 배열·200 빈 본문 → `UNEXPECTED` 의 `AccountReadException` 이고 메시지에 query 문자열이 없다 | design §7, §8.1 | `요구` (공개 API 로 호가·체결가, 서명·key 없음) + 공유 클래스 실패 경로는 `리뷰` (⑥ R1) | T1 | `신규테스트` `io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesManualAccountAdapterTest` | `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesManualAccountAdapterTest' --offline --no-daemon` | exit 0 |
| AC4 | 공개 호가·체결가 조회 코드가 한 곳에만 있다: `infrastructure/batch/src/main/kotlin` 에서 `/fapi/v1/depth` 또는 `/fapi/v2/ticker/price` 를 담은 파일은 `BinanceFuturesReadClient.kt` 하나뿐이다. 그리고 `BinanceFuturesPrivateAccountAdapterTest` 는 base 대비 무수정으로 통과한다 | design §8.1, §9 | `요구` (공개 조회 코드 공유·중복 금지, api 모드 불변) | T1 | `기존` (무수정 테스트) + 셸 검사 | `test "$(grep -rl -e '/fapi/v1/depth' -e '/fapi/v2/ticker/price' infrastructure/batch/src/main/kotlin)" = "infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceFuturesReadClient.kt" && git diff --exit-code 0a04546 -- infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceFuturesPrivateAccountAdapterTest.kt && ./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapterTest' --offline --no-daemon` | exit 0 |
| AC5 | `ApplicationContextRunner` 에서: (a) `mode=manual` + 수신자·빗썸 key 2개·수량·진입가 + 메일·scheduling 활성이면 Job·Scheduler·빗썸 adapter·`BinanceFuturesManualAccountAdapter` 각 1개, `BinanceFuturesPrivateAccountAdapter` 0개 (바이낸스 key 가 설정돼 있어도 0개). (b) `mode` 미설정·빈 값·`api`·`API` 면 기존과 같이 private adapter 1개, manual adapter 0개이고, api 모드 필수값 전부 + manual 키 다섯 개 모두 빈 값이어도 `hasNotFailed` + private adapter 1개다. (c) 모드별 필수 키(design §3.3)를 하나씩 비우면 Job·Scheduler·세 adapter·메일 adapter 가 모두 0개이고 context 는 기동하며, 출력에 `required settings missing` WARN 이 그 env 이름(예 `BINANCE_MANUAL_ENTRY_PRICE`)을 담고 어떤 설정 값(테스트 key·수신 주소·수량·진입가 문자열)도 담지 않는다. (d) 모드 {미설정, `api`, `manual`, `MANUAL`} × {필수 키 전부, 각 필수 키 하나 누락, 다른 모드의 키만 있음} 의 모든 조합에서 먼저 `hasNotFailed()` 이고, 그다음 `AccountPnlMonitorJob` bean 수와 `ForeignPerpAccountReadPort` bean 수가 같다(0 또는 1, app 조건 = infra 조건). (e) 알 수 없는 mode(구별 값 `fooModeX7`)면 기동 실패이고 원인 체인 메시지에 `account-pnl-monitor.binance.mode` 가 있으며 원인 체인 전체와 출력 어디에도 그 값이 없다. (f) manual 입력 형식 오류(`qtyBADx9`)·음수 수량(`-0.98765`)·진입가 0(`0.000000`)·음수 진입 수수료(`-1.234567`)·taker 율 1 이상(`1.000007`)은 각각 기동 실패이며 원인 체인 메시지 중 하나에 해당 property key 가 있고 **실패 출력과 원인 체인 전체(모든 `cause` 의 `message`) 어디에도 그 입력 값이 없다**. api 모드 필수값 전부 + `manual.short-quantity` 음수(`-1.98765`)도 구성이 바인딩되므로 기동 실패이고 값이 출력·원인 체인에 없다. 펀딩 음수(`-3.2`)와 수량 `0` 은 기동 성공. (g) manual 모드에서 `taker-fee-rate`·`entry-fee-usdt` 를 빈 값으로 주면 바인딩된 `resolvedTakerFeeRate()` 가 `0.0005`, `resolvedEntryFeeUsdt()` 가 null 이다 | design §3, §4 | `요구` (모드별 기동 조건, WARN 변수 이름만, 형식 오류·음수 기동 실패, 조건 일치 고정) + 진입가 0·taker 율 상한·알 수 없는 mode 실패는 `추론` (design §11 내 판단) + 빈 값·값 비노출 원인 체인·api 모드 검증 전제는 `리뷰` (⑥ R1, 결정 (b)) | T1 | `신규테스트` (기존 클래스 확장) `io.premiumspread.config.AccountPnlMonitorWiringTest` | `./gradlew :apps:batch:test --tests 'io.premiumspread.config.AccountPnlMonitorWiringTest' --offline --no-daemon` | exit 0 |
| AC6 | manual 모드로 기동한 context 의 `AccountPnlMonitorProperties.toString()` 에 수량·진입가·진입 수수료·펀딩 입력 문자열과 테스트 key·수신 주소가 없고 `mode=MANUAL` 과 `set` 이 있다. 기본 생성 `ManualPosition().toString()` 에 `<empty>` 와 `takerFeeRate=default` 가 있다. 같은 기동의 출력(startup 로그)에 `binance leg: mode=manual` INFO 가 있고 위 값 문자열이 없다 | design §3.4, §4 | `상위` (직전 단위 `LIVE-2` 비노출 원칙의 연장) + 값 미노출은 `추론` (design §11 내 판단) | T1 | `신규테스트` (기존 클래스 확장) `io.premiumspread.config.AccountPnlMonitorWiringTest` | `./gradlew :apps:batch:test --tests 'io.premiumspread.config.AccountPnlMonitorWiringTest' --offline --no-daemon` | exit 0 |
| AC7 | `PrivateAccountReadOnlyArchitectureTest` 가 (a) 기존 규칙(모든 모듈 `/src/main/kotlin/` 의 `*PrivateAccountAdapter.kt`)과 신규 규칙(`infrastructure/batch` production `exchange` 아래 `*AccountAdapter.kt`·`*ReadClient.kt`)의 **합집합**을 대상으로 삼아 대상 이름 집합에 `BithumbPrivateAccountAdapter.kt`·`BinanceFuturesPrivateAccountAdapter.kt`·`BinanceFuturesManualAccountAdapter.kt`·`BinanceFuturesReadClient.kt` 가 모두 있음을 확인하고, 기존 금지 규칙 20개 위반이 0건이며, (b) `*ManualAccountAdapter.kt` 에 `BinanceRequestSigner`·`X-MBX-APIKEY`·`signature`·`/fapi/v1/time`·`apiKey`·`secretKey`·`recvWindow` 가 없음을 검사하고 그 7개 규칙 각각이 위반 표본에 매치함을 자기검증한다 | design §8.2 | `상위` (master spec `LIVE-3`·`LIVE-4`, 직전 AC10) + 무서명 규칙은 `요구` (서명·API key 헤더 없음) + 합집합은 `리뷰` (⑥ R1) | T1 | `신규테스트` (기존 클래스 확장) `io.premiumspread.architecture.PrivateAccountReadOnlyArchitectureTest` | `./gradlew :architecture-tests:architectureTest --tests 'io.premiumspread.architecture.PrivateAccountReadOnlyArchitectureTest' --offline --no-daemon` | exit 0 |
| AC8 | `docs/runbooks/account-pnl-alert.md` 에 (a) 모드 선택(`BINANCE_MODE`)과 모드별 필수 env, (b) manual env 5종의 이름·필수 여부·단위·부호·기본값 표, (c) 한계 — **펀딩을 갱신하지 않으면 장기 보유 시 순손익이 과대평가돼 1% 알림이 실제보다 일찍 갈 수 있다**(굵게), 펀딩은 자동 누적되지 않아 env 갱신+재시작 필요, 포지션을 바꾸면 env 수정+재시작(안 하면 틀린 알림), BNB 수수료는 USDT 환산 입력, taker 기본 0.0005 는 BNB 할인 미반영, manual 값은 구성이 바인딩될 때(기능 켜짐·현재 모드 필수값 전부·메일·scheduling 켜짐) api 모드에서도 형식 검증, 기동 실패 리포트의 잔여 값 노출, (d) 누락 WARN 문구, (e) manual 모드 owner 검산 체크리스트가 있다. `docs/check-documentation.sh` 가 통과한다 | design §7, §3, §4 | `요구` (runbook 에 모드·env 표·한계) + 과대평가 경고는 `리뷰` (⑥ R1 리뷰어 의견) | T3 | 기록 문서 `docs/runbooks/account-pnl-alert.md` + `bash docs/check-documentation.sh` | `bash docs/check-documentation.sh` | exit 0 + (a)~(e) 존재를 사람 사후 검토 |
| AC9 | owner 가 로컬에서 `BINANCE_MODE=manual` 과 실제 포지션 값으로 batch 를 실행했을 때 (a) 기동 로그에 `monitor not started` WARN 이 없고 `binance leg: mode=manual` INFO 가 있으며, (b) 첫 수익 메일(순수익률 1% 도달 시)의 바이낸스 수량·진입가가 입력과 같고 `바이낸스 포지션: 수동 입력(env)` 표기가 있으며, 바이낸스 청산가(호가 VWAP)·최근 체결가가 바이낸스 화면과 합리적으로 맞음을 owner 가 확인한다. 알림을 받으려고 env 값을 실제와 다르게 바꾸지 않는다(에피소드 상태가 DB 에 남는다) | design §0, §7 | `요구` (key 없이 실제 알림 수신) | T4 | owner 확인 | — | PR 코멘트에 owner 확인 기록 |

**기준 개수**: 9개

**분할하지 않는 이유**: 출처 운반(AC1) → 표기(AC2) → 공유 추출(AC4) → manual 조회(AC3) → 배선(AC5·AC6) → 경계(AC7) → 문서·실측(AC8·AC9)
은 "key 없이 메일을 받는다" 하나의 수직 경로다. 배선 없이 adapter 만 머지하면 소비자 없는 클래스가 되고, 표기 없이 배선만
머지하면 owner 가 수동 값으로 계산된 메일임을 구분하지 못한다. AC9 는 전 경로가 있어야만 검증된다.

**티어 강등 사유** *(T1 이 아닌 항목만. Gate 1 에서 사람이 함께 승인한다)*

- AC8 → T3: runbook 내용(한계 설명이 owner 에게 충분한가)은 exit code 로 잴 수 없다. `check-documentation.sh` 는 형식만 본다.
- AC9 → T4: 실 포지션 값·실 메일 수신은 테스트·CI 에서 쓰지 않는다(testing 규칙). owner 만 바이낸스 화면과 대조할 수 있다.

## 회귀 방어선

이번 변경으로 깨질 수 있는 기존 동작. RED 면제 대상이다.

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 모듈 경계·import·관측 cardinality·조회 전용 아키텍처 규칙 전체 | `./gradlew architectureTest --offline --no-daemon` |
| R2 | 거래 준비 AC20 — production 잔고 포트 구현은 `DeclaredBalanceAdapter` 뿐 | `./gradlew :apps:api:test --tests '*TradePreparationWiring*' --offline --no-daemon` |
| R3 | 기존 `Tracking.grossPnl` 결과 불변 | `./gradlew :domain:test --tests 'io.premiumspread.domain.tracking.TrackingTest' --offline --no-daemon` |
| R4 | 전체 unit test | `./gradlew test --offline --no-daemon` |
| R5 | migration 무변경·검증 | `git diff --exit-code 0a04546 -- infrastructure/common/src/main/resources/db/migration && ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` |
| R6 | 실계정 알림 재시작 내구성(직전 AC6)·기존 premium durable delivery | `./gradlew :apps:batch:integrationTest --offline --no-daemon` |
| R7 | 정본 문서·하네스 정합성 | `./gradlew harnessCheck --offline --no-daemon` |
| R8a | 직전 단위 AC1·AC3·AC4·AC5·AC7·AC8 테스트 클래스 **무수정** | `git diff --exit-code 0a04546 -- domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountNetPnlTest.kt domain/src/test/kotlin/io/premiumspread/domain/accountpnl/PnlAlertPolicyTest.kt domain/src/test/kotlin/io/premiumspread/domain/accountpnl/MonitoringOutagePolicyTest.kt apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJobTest.kt infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange/bithumb/BithumbPrivateAccountAdapterTest.kt infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceFuturesPrivateAccountAdapterTest.kt` |
| R8b | 직전 단위 AC1~AC5·AC7~AC9·AC11 통과 | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.*' :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.exchange.*' :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest' --tests 'io.premiumspread.config.AccountPnlMonitorWiringTest' --offline --no-daemon` |
| R8c | 직전 단위 AC6 (재시작 내구성) | `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.application.job.AccountPnlAlertDurabilityIntegrationTest' --offline --no-daemon` |
| R8d | 직전 단위 AC10 (조회 전용) | `./gradlew :architecture-tests:architectureTest --tests 'io.premiumspread.architecture.PrivateAccountReadOnlyArchitectureTest' --offline --no-daemon` |
| R9 | 확장한 기존 테스트 클래스(`AccountHedgePositionTest`·`AccountPnlMailContentTest`·`AccountPnlMonitorWiringTest`·`PrivateAccountReadOnlyArchitectureTest`)에서 base 의 테스트 메서드·fixture 가 지워지거나 바뀌거나 단언이 약해지지 않았다 | `git diff 0a04546 -- <네 파일>` 의 삭제(`-`) 줄을 ⑨ 리뷰가 확인 (사람 판정. 허용 예외: `assertNoBeans` 헬퍼에 단언 추가, 아키텍처 테스트의 대상 판정·최소 개수 단언 교체(design §8.2), **아키텍처 테스트 클래스 KDoc 갱신**) |

## 스펙 리뷰 라운드 기록 (⑥)

| 라운드 | 지적 | 처리 | 반영 커밋 |
|---|---|---|---|
| R1 | 4 Important · 7 Minor — 빈 값 바인딩 가정 미검증, 공유 클라이언트 실패 경로 커버리지(연결 거부·timeout·빈 본문·timeout 전달), 바인딩 실패의 값 노출(→ 오케스트레이터 결정 (b) `String?` 바인딩·직접 해석), R9 허용 예외·fixture 무수정 / 비서명 `-1021` 의도된 변경 명시, `ObjectMapper` 이동·number 정밀도, 아키텍처 대상 합집합, api 모드 manual 검증의 전제 조건, Step 1 stub 컴파일, 행렬 `hasNotFailed` 선행, runbook 절 이름 + 리뷰어 의견(펀딩 미갱신 과대평가 경고·fundingRate 추정 후속) | ACCEPT 11 (+ 리뷰어 의견 문서 반영), 결정 (b) | design §3.3 `e85a03b` (오케스트레이터), 나머지 `3e11a1c` |
| R2 | Minor 3 — Global Constraints 문구에 잔여 노출 예외 명시, 라운드 기록 커밋 갱신, Review Focus 1 의 검증 주체(timeout 전달은 ⑨ 에서 눈으로 확인) | ACCEPT 2 · 1 건은 ⑨ 확인 항목 · verdict APPROVE | 이 커밋 |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

### Task 0 — Gate 1 기준선 (2026-10-22, HEAD `cd85a43`, gradle `--offline --no-daemon`, cache 정상 — 온라인 실행 없음)

| 항목 | 명령 | 결과 |
|---|---|---|
| R1 | `./gradlew architectureTest` | exit 0 (BUILD SUCCESSFUL 24s) |
| R2 | `./gradlew :apps:api:test --tests '*TradePreparationWiring*'` | exit 0 |
| R3·R4·R8b | `./gradlew test` | exit 0 (BUILD SUCCESSFUL 1m 46s) |
| R8d | `./gradlew :architecture-tests:architectureTest --tests '...PrivateAccountReadOnlyArchitectureTest'` | exit 0 |
| [ASSUMPTION] 2 | `grep -rl -e '/fapi/v1/depth' -e '/fapi/v2/ticker/price' infrastructure/batch/src/main/kotlin` | 1줄 — `.../exchange/binance/BinanceFuturesPrivateAccountAdapter.kt` (성립) |
| [ASSUMPTION] 3 | `find infrastructure/batch/src/main/kotlin -path '*/exchange/*' \( -name '*AccountAdapter.kt' -o -name '*ReadClient.kt' \)` | 2줄 — `BinanceFuturesPrivateAccountAdapter.kt`, `BithumbPrivateAccountAdapter.kt` (성립) |
| 기존 대상 | `find . -path '*/src/main/kotlin/*' -name '*PrivateAccountAdapter.kt' -not -path '*/build/*'` | 2줄 — 위 두 파일 |
| [ASSUMPTION] 1 | 커밋하지 않은 임시 probe(`ApplicationContextRunner` + nested `data class` init 의 `require` 실패) | `hasFailed()` 성립. 원인 체인 `ConfigurationPropertiesBindException ← BindException ← BeanInstantiationException ← IllegalArgumentException("probe.nested.v must be ok")` — 체인 메시지에 입력 값 없음. probe 파일은 삭제(커밋 없음) |

## 사람 확인 (T4)

> 판정 주체는 사람뿐이다. AI가 이 표를 채우지 않는다.

| # | 확인 사항 | 확인자 | 날짜 | 앵커 |
|---|---|---|---|---|
| AC9 | manual 모드 로컬 실행 — 기동 로그·메일 수신·바이낸스 값 대조 | | | |

## 변경 요청

> 동결 후 기준을 바꿔야 할 때만 작성. 사람이 승인하기 전까지 구현을 중단한다.

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|

## 최종 판정

(⑩ 에서 작성)

### Task 1 — AC2-a api 수익 메일 golden 특성화 (날짜 2026-10-22)

- RED(변이): `AccountPnlMailContent.profit` 의 `[바이낸스 선물 숏]` 다음에 임시 `add("x")` 삽입 후
  `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountPnlMailContentTest' --offline --no-daemon`
  → `api 출처 수익 메일은 기준 golden 과 같다 - 진입 수수료와 펀딩 미확보() FAILED`, `... - 모든 항목 확보() FAILED`, `14 tests completed, 2 failed`, BUILD FAILED.
- 되돌림: `git checkout -- domain/src/main` 후 `git diff --stat -- domain/src/main` 빈 출력.
- GREEN: 같은 명령 → BUILD SUCCESSFUL (14 tests, 0 failed; 기존 12 + golden 2).
