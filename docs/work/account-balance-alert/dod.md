---
feature: 실계정 알림 자산 칸 + Discord 2칸 행 폭 수정
slug: account-balance-alert
status: FROZEN
frozen_at: 2026-11-02
verdict_commit:
source: GitHub issue #105, orchestrator ③ 합의 범위(design.md §0)
---

## 범위

**포함**

- 빗썸 `/v1/accounts` KRW 행 `balance`(locked 제외)를 `KoreaSpotAccountSnapshot.krwBalance` 로 싣는다(추가 호출 없음).
- 빗썸 `/v1/ticker?markets=KRW-BTC,KRW-USDT` 로 BTC 최근 체결가와 USDT 원화가(`usdtLastPriceKrw`)를 함께 받는다. 시장은 `market` 필드로 고른다.
- 바이낸스 API 모드에서 `/fapi/v3/account` signed GET 1회로 USDT 자산 `marginBalance`·`availableBalance` 를 `ForeignPerpAccountSnapshot.balance` 로 싣는다.
- Domain `AccountAssetBreakdown`(총 자산 = BTC 평가액 + KRW 잔고 + marginBalance × fxRate)과 `AccountPnlBreakdown.assets`.
- 수익·정시 메일 `■ 자산` 섹션, Discord 순손익 뒤 2행(design §5.2 **B안** — ⑦ 결정).
- 잔고·USDT 시세 실패의 adapter 내 흡수(해당 칸·총 자산 `없음`), WARN 로그 키, 취소 전파.
- 수동 모드 표시 `수동 모드 — 표시 안 함`, 총 자산 `없음` (#102 U5 개정 — 합의 7).
- `EmbedDocument` 행 끊기 칸을 non-inline U+200B 칸으로 교체(공통 렌더러).
- message-spec·#102 design §3.3·§6.2 개정 메모·`docs/runbooks/account-pnl-alert.md` 갱신.

**제외** *(명시적으로 하지 않는 것 — scope creep 차단선)*

- Flyway migration, 잔고 저장(상태·latest·outbox 스키마).
- 레버리지 조회·표시.
- 기존 알림 문구 구조 변경(기존 섹션·칸·순서·서식은 그대로. 행 끊기 칸의 inline 값만 바뀐다).
- 바이낸스 수동 모드에서 잔고 env 입력.
- 새 Domain port, Job·Configuration·JobConfig 변경, 공개 API·http 샘플 변경.
- 잔고·USDT 시세의 신선도 판정, 두 잔여 금액의 합계.

## 기존 gate 조사

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있음 — root `architectureTest` → `:architecture-tests:architectureTest`. `PrivateAccountReadOnlyArchitectureTest` 가 `*PrivateAccountAdapter.kt`·`exchange/*AccountAdapter.kt`·`*ReadClient.kt` 원문의 GET 외 호출·`withdraw`/`deposit`/`transfer` 등을 금지하고, 수동 adapter 의 서명·API key·`recvWindow` 를 금지 | 예 — R1 (새 `/fapi/v3/account` 호출이 조회 전용 규칙 안에 있는지) |
| 문구 적합성 테스트 | 있음 — `io.premiumspread.domain.alertmessage.AlertMessageSpecConformanceTest`(22종 행별 칸 수·금지어·Discord 한도) | 예 — R2 |
| lint 규칙 | ktlint·detekt 는 CI(`quality-gate.yml` 5단계) jar 로만 돈다. Gradle `lint` task 없음. detekt `LongMethod` 100·`TooManyFunctions` 20 | CI 에서만 — R9(로컬 명령 없음, CI job 결과로 판정) |
| custom gradle task | `test`, `architectureTest`, `:apps:batch:integrationTest`, `:infrastructure:common:integrationTest`, `jacocoTestCoverageVerification`(overall 70·domain 85·application 80), `verifyTestIsolationPolicy`, `harnessCheck`(하네스 파일만 — 이번 변경 대상 아님) | 예 — R6·R7·R8 |
| CI job | `quality-gate.yml`: compileKotlin·architectureTest·verify*·harnessCheck, `docs/check-documentation.sh`(정본 문서만 — `account-pnl-alert.md`·`docs/work/**` 미포함), ktlint·detekt, test·integrationTest·coverage | 예 — R9 |

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | `EmbedDocument.toDiscordMessage()` 는 칸 2개 행 다음 행이 칸 2개 이상일 때 그 사이에 `DiscordField(U+200B, U+200B, inline=false)` 한 개를 넣고, 다음 행이 1칸이거나 2칸 행이 마지막이면 넣지 않으며, 결과 fields 에 이름·값이 U+200B 인 `inline=true` 칸이 하나도 없다. 행 `[3,2,3,2,1]` 이면 fields 12개이고 inline 값 목록이 `T,T,T,T,T,F,T,T,T,T,T,F` 다 | 2칸 행이 1/3 폭으로 렌더되는 원인 제거 | `요구` (합의 8) | T1 | `기존` `io.premiumspread.domain.alertmessage.EmbedDocumentTest` | `./gradlew :domain:test --tests io.premiumspread.domain.alertmessage.EmbedDocumentTest --offline --no-daemon` | exit 0 |
| AC2 | `KoreaSpotAccountSnapshot`·`ForeignPerpAccountSnapshot` 의 새 필드(`krwBalance`·`usdtLastPriceKrw`·`balance`)는 생략 시 `null` 이고, `krwBalance < 0`·`usdtLastPriceKrw <= 0`·`ForeignPerpBalance` 의 음수 값은 `IllegalArgumentException` 이며, 세 타입의 `toString()` 이 design §3.1 의 노출 프로퍼티 외 값(금액 sentinel `987654.321`)을 담지 않는다 | 실계정 잔고의 로그 노출 차단·서식 abs() 로 인한 부호 소실 방지 | `리뷰` (orchestrator ⑤ 보정 항목, design D4·D5) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.AccountBalanceSnapshotTest` | `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.AccountBalanceSnapshotTest --offline --no-daemon` | exit 0 |
| AC3 | `AccountAssetBreakdown.of` 는 KRW 잔고·바이낸스 잔고가 모두 있으면 `totalKrw = koreaValueKrw + krwBalance + marginBalanceUsdt × fxRate` 를 반올림 없이(`compareTo == 0`) 내고, KRW 잔고 또는 바이낸스 잔고가 `null` 이면 `totalKrw == null`, `MANUAL_ENV` 이면 snapshot 에 잔고가 있어도 바이낸스 4필드와 `totalKrw` 가 `null`, USDT 시세 `null` 은 `totalKrw` 를 바꾸지 않으며, `toString()` 은 `"AccountAssetBreakdown"` 이다. `AccountPnlBreakdown.of(pnl).assets` 가 같은 값을 낸다 | 총 자산 공식(합의 4)·실패 전파(합의 6)·수동 모드(합의 7) | `요구` (합의 4·6·7) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.AccountAssetBreakdownTest` | `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.AccountAssetBreakdownTest --offline --no-daemon` | exit 0 |
| AC4 | 수익·정시 알림의 메일 본문이 `■ 합산` 다음에 design §5.1 의 `■ 자산` 섹션을, Discord rows 가 순손익 뒤 design §5.2 의 ⑦ 확정 안 두 행(A안 fields 16개 / B안 fields 17개)을 `isEqualTo` 로 같게 내고, KRW 잔고 `null` / 바이낸스 잔고 `null` / USDT 시세 `null` / `MANUAL_ENV` 각 변형에서 design §5.3 표의 문자열(`없음`, `수동 모드 — 표시 안 함`)을 낸다. 기존 섹션·칸의 문자열은 바뀌지 않는다 | 표시 계약(합의 5·6·7) | `요구` (합의 5·6·7) | T1 | `기존` `io.premiumspread.domain.accountpnl.AccountPnlAlertContentTest` | `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.AccountPnlAlertContentTest --offline --no-daemon` | exit 0 |
| AC5 | 빗썸 adapter 는 `/v1/ticker` 를 query `markets=KRW-BTC,KRW-USDT` 로 정상 회차에 1회만 부르고, `market` 필드로 고른 `KRW-BTC`·`KRW-USDT` 의 `trade_price` 를 `lastPrice`·`usdtLastPriceKrw` 로, `/v1/accounts` KRW 행 `balance`(locked 미포함)를 `krwBalance` 로 낸다. KRW 행 없음 → `0`, KRW `balance` 음수·숫자 아님 → `null`, USDT 행 없음·숫자 아님·0 이하 → `null`, 묶음 요청 404 → `markets=KRW-BTC` 재요청 후 `read()` 성공·`usdtLastPriceKrw == null`·WARN `reason=TICKER_FALLBACK, category=UNEXPECTED, error=NotFound` 이고, 이 경우들에서 `read()` 가 예외 없이 끝나며 WARN `Account balance unavailable: item=…, reason=…` 이 남고 출력에 테스트 금액·access key·secret 이 없다. `/v1/ticker` 요청 중 테스트 스레드가 interrupt 되면 `read()` 가 `AccountReadException` 을 던지고 `/v1/ticker` 요청은 1건이며 `TICKER_FALLBACK` 로그가 없다. 200 응답에 `KRW-BTC` 행이 없으면 재요청 없이 `UNEXPECTED` 로 실패한다 | 합의 1·2·6, design §4.1·§4.3 | `요구` (합의 1·2·6) + `추론` (A1 KRW 행 없음 → 0, A2 fallback) + `리뷰` (fallback 취소·경계) | T1 | `기존` `io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbPrivateAccountAdapterTest` | `./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbPrivateAccountAdapterTest --offline --no-daemon` | exit 0 |
| AC6 | 바이낸스 API adapter 는 회차마다 `/fapi/v3/account` 를 `X-MBX-APIKEY`·`signature` 붙인 GET 으로 1회 부르고(`commissionRate` 뒤·`ticker/price` 앞, depth 는 마지막 유지), `assets[asset=USDT]` 의 `marginBalance`·`availableBalance` 를 `balance` 로 낸다. 401(`-2015`)·503·깨진 JSON·USDT 항목 없음·숫자 아님·음수이면 `balance == null` 이고 `read()` 는 예외 없이 나머지 값을 내며 WARN `item=BINANCE_USDT_ASSET` 과 design §4.2·§4.3 의 `reason`·`category`·`error`(401 → `Unauthorized`, 503 → `ServiceUnavailable`, 깨진 JSON → `JsonParseException`)가 남고 출력에 API key·secret·`signature=`·테스트 금액이 없다. `/fapi/v3/account` 요청 중 테스트 스레드가 interrupt 되면 `read()` 가 `AccountReadException` 을 던지고 `/fapi/v1/depth` 요청이 0건이며 `reason=REQUEST_FAILED` WARN 이 없다 | 합의 3·6, design §4.2·§4.3 | `요구` (합의 3·6) + `리뷰` (취소 전파 — Job 취소 계약 보존, `error` 값) | T1 | `기존` `io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapterTest` | `./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapterTest --offline --no-daemon` | exit 0 |
| AC7 | `docs/work/alert-message-template/message-spec.md` 수익·정시 절이 `■ 자산` 섹션과 Discord 행 `[총 자산]`·`[잔여 금액]` 을 담고, `docs/runbooks/account-pnl-alert.md` 가 `/fapi/v3/account` 와 Enable Reading 권한 관계와 자산 칸의 manual 모드 예외를 적고, `docs/work/alert-message-template/design.md` §3.3(규칙 2)과 §6.2(U5)에 #105 개정 메모가 있다 | 합의 9 + #102 U5 개정 기록 | `요구` (합의 9) + `리뷰` (U5 개정 기록) | T2 | `신규스크립트` 없음 — 아래 재현 명령 4개 | `grep -c "■ 자산" docs/work/alert-message-template/message-spec.md` / `grep -n "/fapi/v3/account" docs/runbooks/account-pnl-alert.md` / `grep -n "#105" docs/work/alert-message-template/design.md` / `grep -n "수동 모드 — 표시 안 함" docs/runbooks/account-pnl-alert.md` | 각각 `2` 이상 / 1줄 이상(Key 발급 표 행 포함) / 2줄 이상(§3.3 구간 1·§6.2 구간 1) / 1줄 이상(`### 메일 표기` 구간) |
| AC8 | 실제 Discord webhook(pnl 채널)으로 받은 수익 또는 정시 embed 에서 (a) `빗썸 현물`·`바이낸스 선물 숏` 두 칸이 한 줄에 각각 절반 폭이고 (b) 바이낸스 칸의 `손익 … (… USDT)`·`평가액 … (… USDT)` 가 각각 한 줄이며 (c) `잔여 금액` 칸의 `바이낸스 … (… USDT)` 가 한 줄이다. (d) 자산 행의 `바이낸스 증거금+손익` 값의 줄바꿈 여부(A안이면 1/3 폭 행 6, B안이면 1/2 폭 행 6)와 (e) 끊기 칸의 세로 여백을 스크린샷으로 기록한다((d)(e)는 관찰 기록이며 결과에 따라 `변경 요청` 으로 올린다) | 이슈 증상은 Discord 클라이언트 렌더에서만 관찰된다 | `요구` (합의 8) | T4 | 사람 육안 — PR 코멘트(스크린샷) | — | (a)(b)(c) 충족 + (d)(e) 기록 |
| AC9 | 운영 바이낸스 API key(Enable Reading)로 로컬 batch 를 띄운 회차에 batch 로그에 `item=BINANCE_USDT_ASSET` WARN 이 없고, 받은 알림의 `바이낸스 증거금+손익`·`잔여 금액 — 바이낸스` USDT 값이 바이낸스 선물 지갑 화면의 Margin Balance·Available Balance 와 같으며, `잔여 금액 — 빗썸` 이 빗썸 KRW 보유(주문 잠금 제외)와 같다 | 실 key 권한·실 응답 shape 는 mock 으로 증명할 수 없다 | `요구` (합의 3·작업 지시 AWAITING_HUMAN) | T4 | 사람 육안 — PR 코멘트 | — | 세 항목 일치 |

**`출처`** — `요구`(사용자 요구 원문) / `상위`(상위 동결 문서의 ID) /
`리뷰`(리뷰에서 파생) / `추론`(근거 없음).
`리뷰`와 `추론`은 Gate 1에서 같은 무게로 검토한다.

**기준 개수**: 9개

**분할하지 않는 이유**: AC1~AC6 은 하나의 사용자 가시 변경(수익·정시 알림의 자산 칸)이 Domain 불변식 → 계산 → 문구 → 두 거래소 adapter 로 이어지는 한 줄기이고, 어느 하나만 배포하면 칸이 항상 `없음` 이거나 값이 표시되지 않는다. AC1(행 끊기)은 기술적으로 떼어낼 수 있지만, 사용자가 같은 이슈(#105)에서 함께 고치기로 합의했고 AC4 의 Discord golden(fields 16/17개)이 새 끊기 칸을 전제한다. AC7~AC9 는 같은 변경의 문서·사람 확인이다.

**Gate 1 결정 (2026-11-02)**: Q1 = **B안** → AC4 fields 17개, R2 수익·정시 `cellsPerRow` = `[3,2,3,2,1,2,2]`, AC8 (d) 관찰 대상은 1/2 폭 행 6. 가정 A1~A4 수용. AC7 은 T2 로 확정.

**티어 강등 사유** *(T1이 아닌 항목만. Gate 1에서 사람이 함께 승인한다)*

- AC7 → T2: 문서 내용 계약이라 저장소에 이를 재는 이름 붙은 산출물이 없다. 재현 명령과 기대 출력을 적었다. Gate 1 에서 T2 로 확정했다(문서 1회성 변경).
- AC8 → T4: Discord 클라이언트의 inline 칸 폭 계산은 우리 코드 밖이다. 렌더 결과는 실제 클라이언트에서만 관찰된다.
- AC9 → T4: 운영 key 권한과 실 계정 응답은 테스트에 넣을 수 없다(`testing.md` "실제 거래소 주소를 test 에 넣지 않는다").

## 검사 산출물

해당 없음 — `신규스크립트` 를 쓰지 않는다.

## 회귀 방어선

이번 변경으로 깨질 수 있는 기존 동작. RED 면제 대상이다.

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | private 계정 adapter 는 조회 전용(GET 만, 금지 경로·단어 없음), 수동 adapter 는 서명·API key·서버 시각 없음. 모듈·import 경계 | `./gradlew architectureTest --offline --no-daemon` |
| R2 | 22종 알림 spec 구조 적합성 — 수익·정시 행별 칸 수 A안 `[3,2,3,2,1,3,1]` / B안 `[3,2,3,2,1,2,2]`, 나머지 20종 무변경, 금지어 없음, Discord 한도 | `./gradlew :domain:test --tests io.premiumspread.domain.alertmessage.AlertMessageSpecConformanceTest --offline --no-daemon` |
| R3 | 시장 알림 golden(일일 요약 fields 9개, 끊기 칸만 inline=false) | `./gradlew :domain:test --tests io.premiumspread.domain.ownermarket.OwnerMarketAlertContentTest --offline --no-daemon` |
| R4 | 수동 모드 adapter 는 한 회차에 ticker·depth 두 요청만 보내고 `balance == null` | `./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesManualAccountAdapterTest --offline --no-daemon` |
| R5 | 잔고·USDT 시세가 모두 `null` 이어도 수익·정시 행이 enqueue·발송되고, 그 상태가 6분 이어져도 `account-pnl-outage-` 행이 없으며 `JobResult.Success` | `./gradlew :apps:batch:test --tests io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobBalanceTest --offline --no-daemon` |
| R6 | 전체 unit/context/contract | `./gradlew test --parallel --offline --no-daemon` |
| R7 | outbox·Discord layout 저장·발송 통합(`OwnerAlertDiscordLayoutIntegrationTest`·`AccountPnlAlertDurabilityIntegrationTest`·`OwnerAlertDiscordLayoutStoreIntegrationTest` 등) | `./gradlew :apps:batch:integrationTest :infrastructure:common:integrationTest --offline --no-daemon` |
| R8 | coverage gate(overall 70 · domain 85 · application 80) | `./gradlew jacocoTestCoverageVerification --offline --no-daemon` |
| R9 | ktlint·detekt(`LongMethod` 100·`TooManyFunctions` 20 포함) | CI `quality-gate.yml` 5단계 job 결과(해당 커밋 SHA) |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

### 기준선 (Task 0)

```
$ ./gradlew test architectureTest --parallel --offline --no-daemon
BUILD SUCCESSFUL in 1m 33s (68 actionable tasks: 68 executed), exit 0
$ grep -rln "GOLDEN_FILLER\|SPACER" --include=*.kt domain apps infrastructure
5 files (EmbedDocument.kt, EmbedDocumentTest.kt, GoldenFields.kt, AccountPnlAlertContentTest.kt, OwnerMarketAlertContentTest.kt) — 예상과 일치
$ grep -rln "KoreaSpotAccountSnapshot(\|ForeignPerpAccountSnapshot(" ... → 24 files
$ grep -rn  "KoreaSpotAccountSnapshot(\|ForeignPerpAccountSnapshot(" ... → 45 lines — 예상과 일치
```

### AC1

```
RED (stub: ROW_BREAK 상수만 추가, toDiscordMessage 는 SPACER 유지)
$ ./gradlew :domain:test --tests ...EmbedDocumentTest --tests ...AccountPnlAlertContentTest --tests ...OwnerMarketAlertContentTest --offline --no-daemon
38 tests completed, 7 failed (모두 AssertionFailedError)
  EmbedDocumentTest 행 3-2-3-2-1 ... (EmbedDocumentTest.kt:29), 2칸 행 뒤에 2칸 행이 오면 (:68),
  2칸 행 뒤에 3칸 행이 오면 끊는다 (:76), 결과 fields 에 U+200B inline 칸이 없다 (:83),
  AccountPnlAlertContentTest 수익 알림 golden / 정시 보고 golden, OwnerMarketAlertContentTest 김프 일일 요약 golden
GREEN
$ 같은 명령 → BUILD SUCCESSFUL, exit 0
$ ./gradlew :domain:test --offline --no-daemon → BUILD SUCCESSFUL, exit 0
$ grep -rn "GOLDEN_FILLER\|SPACER" --include=*.kt domain apps infrastructure → 출력 없음
```

### AC2

```
```

### AC3

```
```

### AC4

```
```

### AC5

```
```

### AC6

```
```

### AC7

```
```

## 사람 확인 (T4)

> 판정 주체는 사람뿐이다. AI가 이 표를 채우지 않는다.
> 앵커는 이 파일 밖에 있어야 한다 — PR 코멘트 URL, 커밋 SHA, 이슈 링크.

| # | 확인 사항 | 확인자 | 날짜 | 앵커 |
|---|---|---|---|---|
| AC8 | Discord 2칸 행 1/2 폭·바이낸스 칸 줄바꿈 없음·잔여 금액 한 줄, 자산 행·끊기 여백 기록 | | | |
| AC9 | 운영 바이낸스 key 로 `/fapi/v3/account` 권한·잔고 값 일치, 빗썸 KRW 잔고 일치 | | | |

## 변경 요청

> 동결 후 기준을 바꿔야 할 때만 작성. 사람이 승인하기 전까지 구현을 중단한다.

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|

## 최종 판정

> 아래 수는 전부 위 수용기준 표에서 센 값이다. 표와 어긋나면 판정을 낼 수 없다.
> 판정이 가리키는 SHA와 브랜치 최종 SHA가 다르면 그 판정은 만료다.

```
DoD VERDICT: account-balance-alert @ <commit SHA>
  수용기준 표:     9개  (T1 6 · T2 1 · T3 0 · T4 2)
  T1/T2 자동:      7개 중 <p>개 PASS
  T3 기록 제출:    0개 중 0건
  T4 사람 확인:    2개 중 <r>건 완료, <2-r>건 대기
  변경 요청:       <k>건
  =>
```

**사람 확인이 필요한 항목**

- AC8 실제 Discord 렌더 확인
- AC9 운영 바이낸스·빗썸 key 로 잔고 값 확인

## 변경 이력

| 날짜 | 변경 | 사유 |
|---|---|---|
| 2026-11-02 | 초안(DRAFT) | orchestrator ④ |
| 2026-11-02 | AC4·R2 A/B안 기대값, AC5 fallback 경계·취소·`error`, AC6 취소 방식·`error`, AC7 U5 메모·manual 예외 grep, AC8 (d) A/B, 범위에 #102 U5 개정 | ⑥ 스펙 리뷰 반영 |
