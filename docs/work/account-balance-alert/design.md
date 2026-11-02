# 실계정 알림 "자산" 칸 + Discord 2칸 행 폭 수정 — Design (#105)

- 이슈: GitHub #105
- 브랜치: `feat/issue-105-account-balance-alert` (base `origin/dev`)
- 산출물: 이 파일 · [dod.md](dod.md) · [plan.md](plan.md)
- 선행 설계: [live-account-pnl-alert](../live-account-pnl-alert/design.md) · [live-account-pnl-manual-binance](../live-account-pnl-manual-binance/design.md) ·
  [alert-message-template](../alert-message-template/design.md)(#102 — §3.3 규칙 2 와 §6.2 U5 를 이 문서가 개정한다)

## 0. 목표와 합의 범위

실계정 수익·정시 알림(메일·Discord)에 "자산" 묶음을 추가한다. 표시 값은 총 자산, 바이낸스 증거금+손익,
잔여 금액(빗썸·바이낸스), USDT 원화가다. 함께 Discord 2칸 행이 1/3 폭으로 렌더되어 바이낸스 손익·평가액의
`(… USDT)` 가 줄바꿈되는 문제를 고친다.

사용자와 합의한 범위(③)는 그대로 계약이다. 이 문서는 그 범위 안에서 위치·타입·실패 경로만 정한다.

| # | 합의 | 이 문서의 위치 |
|---|---|---|
| 1 | 빗썸 KRW 잔고 = 이미 부르는 `/v1/accounts` 의 `currency=KRW` 행 `balance`(`locked` 제외), 추가 호출 없음 | §4.1 |
| 2 | USDT 원화가 = 빗썸 `KRW-USDT` 최근 체결가. 기존 `/v1/ticker` 호출에 `KRW-USDT` 를 붙여 한 번에 받는다. 표시 전용 | §4.1 |
| 3 | 바이낸스 잔고 = `/fapi/v3/account` signed GET 1회, `assets[]` 의 `USDT` 항목 `marginBalance`·`availableBalance`. 레버리지 없음 | §4.2 |
| 4 | 총 자산(원) = 빗썸 BTC 평가액(`KoreaLegBreakdown.valueKrw`) + 빗썸 KRW 잔고 + `marginBalance × fxRate` | §3.3 |
| 5 | 수익·정시 둘 다. 메일 `■ 자산` 섹션, Discord 순손익 행 뒤 2행 | §5 |
| 6 | 잔고·USDT 시세 실패는 순손익·발송을 막지 않고, 그 칸과 의존하는 총 자산만 `없음`. 중단 정책 미반영. 로그 남김 | §4.3·§7 |
| 7 | 수동(MANUAL_ENV) 모드는 `/fapi/v3/account` 를 부르지 않고 바이낸스 두 칸 `수동 모드 — 표시 안 함`, 총 자산 `없음` | §3.3·§5.3 (#102 U5 개정 — §12) |
| 8 | `EmbedDocument` 의 inline SPACER 를 보이지 않는 non-inline 칸으로 바꾼다. 공통 렌더러라 시장·운영 알림에도 적용된다 | §6 |
| 9 | message-spec 갱신, 바이낸스 key 권한 runbook 기록. http 샘플·API 변경 없음 | §10 |
| 10 | 제외: Flyway, 레버리지 표시, 기존 알림 문구 구조 변경, 수동 모드 잔고 env 입력 | §8 |

## 1. 확인한 현재 구조 (코드 근거)

| 사실 | 근거 |
|---|---|
| 수익·정시 문구는 `AccountPnlAlertContent.pnlMessage(pnl: AccountNetPnl, …)` 하나가 만들고, 표시 값은 `AccountPnlBreakdown.of(pnl)` 가 계산한다 | `domain/.../accountpnl/AccountPnlAlertContent.kt:133`, `AccountPnlBreakdown.kt:65` |
| `AccountNetPnl.position` 은 두 snapshot(`KoreaSpotAccountSnapshot`·`ForeignPerpAccountSnapshot`)을 그대로 들고 있다. 그래서 snapshot 에 붙인 값은 Job 시그니처를 바꾸지 않고 `pnlMessage` 까지 도달한다 | `AccountHedgePosition.kt:5`, `AccountNetPnl.kt:34` |
| snapshot 은 수량·평단만 상태(`PositionLegs`)로 옮겨 저장한다. 잔고 필드는 상태·outbox·`account_pnl_latest` 어디에도 저장되지 않는다 | `AccountAlertState.kt:18,40,53`, `AccountPnlLatest` |
| `BithumbPrivateAccountAdapter.readAccount()` 는 `/v1/accounts` 를 부르고 BTC 행만 쓴다. KRW 행은 응답에 이미 있다(fixture `accounts.json` 첫 행) | `BithumbPrivateAccountAdapter.kt:68`, `src/test/resources/fixtures/bithumb-private/accounts.json` |
| `readLastPrice()` 는 `/v1/ticker?markets=KRW-BTC` 의 **첫 행** `trade_price` 를 쓴다. 시장을 `market` 필드로 고르지 않는다 | `BithumbPrivateAccountAdapter.kt:98` |
| 빗썸 `getJson` 은 `block()` 중 인터럽트를 `RuntimeException` 분기에서 `UNEXPECTED` 로 바꾼다(원인 체인에만 남음) | `BithumbPrivateAccountAdapter.kt:199-202` |
| 바이낸스 private adapter 는 `positionRisk → (이력) → commissionRate → price → depth` 순서이고 depth 가 마지막이어야 한다(호가 skew 5초) | `BinanceFuturesPrivateAccountAdapter.kt:34`, 테스트 `paths.last() == "/fapi/v1/depth"` |
| `BinanceFuturesReadClient.get` 은 모든 I/O 실패(인터럽트 포함)를 `AccountReadException` 으로 정규화한다. 인터럽트는 `UNEXPECTED` 범주에 원인 체인으로만 남는다 | `BinanceFuturesReadClient.kt:45-55` |
| Job 은 `korea.read()`·`foreign.read()` 예외를 실패 범주로 바꿔 `MonitoringOutagePolicy` 에 넣는다. 취소(Interrupted/Cancellation)는 원인 체인으로 판별한다 | `AccountPnlMonitorJob.kt:182-208,384` |
| Discord 칸 배치는 enqueue 시점에 `EmbedDocument.toDiscordMessage()` 로 **한 번** 렌더되어 `account_pnl_mail.discord_layout` JSON 으로 저장되고, 발송기는 저장된 layout 을 그대로 보낸다(재렌더링 없음) | `OwnerAlertRows.kt:15`, `OwnerAlertOutboxRows.kt:52`, `OwnerAlertDispatcher.kt:101` |
| `PrivateAccountReadOnlyArchitectureTest` 가 `*PrivateAccountAdapter.kt`·`exchange/*AccountAdapter.kt`·`*ReadClient.kt` 원문에서 GET 외 호출과 `(?i)withdraw`·`deposit`·`transfer` 등을 금지한다(주석 포함) | `architecture-tests/.../PrivateAccountReadOnlyArchitectureTest.kt` |
| #102 는 수익·정시 본문에 바이낸스 포지션 출처(api/manual)를 쓰지 않는다(U5) | `docs/work/alert-message-template/design.md:316`, runbook `account-pnl-alert.md:350` |
| 실행 주기 60초, lock `lock:account-pnl:monitor`, lease 50s, timeout 40s | `apps/batch/src/main/resources/application.yml:49,72` |
| detekt `LongMethod` 100, `TooManyFunctions` 20, `ReturnCount` 4 (CI 의 detekt jar 로만 돈다 — Gradle task 없음) | `config/detekt/detekt.yml`, `.github/workflows/quality-gate.yml:142` |

## 2. 설계 결정

### D1. 잔고는 기존 snapshot 의 nullable 필드로 싣고, 실패는 adapter 안에서 흡수한다 (별도 port 를 만들지 않는다)

| 대안 | 판단 |
|---|---|
| (a) 별도 port(`AccountBalanceReadPort`)를 Job 이 따로 호출 | 빗썸 KRW 잔고·USDT 시세는 **이미 부르는 응답**에서 나온다(합의 1·2). 별도 port 로 떼면 같은 `/v1/accounts`·`/v1/ticker` 를 한 번 더 부르거나 adapter 간 캐시 공유가 필요해 합의 1 "추가 호출 없음" 과 충돌한다. 바이낸스만 떼면 두 거래소의 실패 흡수 위치가 갈린다. 구현도 거래소마다 하나뿐이라 interface 의 대체 가치가 없다(`architecture.md` "습관적으로 interface 를 만들지 않는다") |
| **(b) snapshot nullable 필드 + adapter 안에서 실패 흡수 — 채택** | 같은 응답에서 값을 꺼내고, 바이낸스 추가 호출 1건만 adapter 안에서 `try` 로 감싼다. Job·port·Configuration 시그니처 무변경. 값이 `AccountNetPnl.position` 을 통해 `pnlMessage` 까지 이미 흐른다(§1). 실패 정규화는 원래 adapter 책임이다(`batch.md` "외부 API 는 infrastructure 가 담당") |

결과: 잔고 실패는 `read()` 밖으로 나가지 않으므로 Job 의 관측 실패 경로·`MonitoringOutagePolicy` 에 **구조적으로** 도달하지 않는다(합의 6). 예외는 취소 하나다 — 취소는 흡수하지 않고 다시 던진다(§4.3).

### D2. 바이낸스 잔고 상태는 "값 있음 / null" 두 상태이고, 수동 모드 구분은 기존 `positionSource` 가 한다

`null` 의 의미는 출처로 갈린다 — `EXCHANGE_API` 면 "읽지 못함"(표시 `없음`), `MANUAL_ENV` 면 "조회하지 않음"(표시
`수동 모드 — 표시 안 함`). 세 번째 상태용 sealed type 을 만들지 않는다. 수동 adapter 는 코드 변경 없이 기본값 `null` 을 낸다.
수동 모드에서 snapshot 에 값이 들어와도 Domain 이 무시한다(§3.3) — 표시 규칙을 adapter 구현에 기대지 않는다.

### D3. 총 자산 계산은 Domain 순수 값 객체 `AccountAssetBreakdown` 이 소유하고 `AccountPnlBreakdown.assets` 로 노출한다

`AccountPnlBreakdown` 은 이미 "수익·정시 알림의 값" 의 단일 계산 지점이다(#102 design §6.1). 자산도 같은 알림의 값이므로
여기에 붙이되, 필드 7개를 `AccountPnlBreakdown` 에 평평하게 늘리지 않고 값 객체 하나로 묶는다(`KoreaLegBreakdown`·
`ForeignLegBreakdown` 과 같은 모양). 계산 중 반올림 없음 — 금액은 전체 정밀도로 들고, 표시 반올림은 `AlertFormat`(HALF_UP)이 한다.
기존 규칙(`AccountNetPnl` KDoc "계산 중에는 반올림하지 않는다")과 같다.

### D4. 실계정 금액을 담는 data class 는 모두 `toString()` 을 override 한다

새 필드는 실계정 잔고다. 자동 `toString()` 이 로그·예외 메시지·assertion 출력에 금액을 흘리지 않게 한다. 기존 snapshot 두 개는
지금도 자동 `toString()` 이 수량·평단을 노출하는데, 이번에 잔고가 같은 출력에 합류하므로 함께 override 한다(전문은 §3.1).
이 override 는 snapshot 을 품는 `AccountHedgePosition`·`AccountNetPnl` 의 자동 `toString()` 출력도 함께 줄인다(둘은 이번에 고치지 않는다).

### D5. 표시 서식은 기존 `AlertFormat` 함수만 쓴다

원 금액 = `krwPrice`(부호 없음, `#,##0`, HALF_UP), USDT 금액 = `usdt2`(`#,##0.00 USDT`), 바이낸스 금액 = 기존 평가액과 같은
`"{krwPrice(krw)} ({usdt2(usdt)})"`. USDT 원화가 = `krwPrice`. `krwPrice`·`usdt2` 는 `abs()` 를 쓰므로 음수가 들어오면 부호가
사라진다 — 그래서 음수 잔고는 Domain 불변식으로 막고 adapter 가 `null` 로 내린다(§3.1, §4).

### D6. 행 끊기 칸은 non-inline U+200B 칸이다 (규칙 2 개정)

§6 참조.

### D7. 실패 로그는 adapter 공용 helper 하나가 같은 키로 남긴다

빗썸·바이낸스 두 adapter 가 같은 WARN 형식을 쓰도록 `infrastructure:batch` 의 `exchange` 패키지에 internal object
`AccountBalanceLog` 를 둔다. 키·값 전부는 §4.3. 파일명이 `*AccountAdapter.kt`·`*ReadClient.kt` 가 아니므로 조회 전용
architecture 스캔 대상에 들어가지 않지만, 이 파일도 HTTP 를 부르지 않는다.

## 3. Domain 계약

### 3.1 snapshot 확장 (`domain/.../accountpnl/AccountSnapshots.kt`)

```kotlin
/** 바이낸스 USDⓈ-M 계정의 USDT 자산 잔고. 둘 다 >= 0 (adapter 가 음수를 null 로 내린다). 실계정 금액이므로 toString 은 이름만. */
data class ForeignPerpBalance(
    val marginBalanceUsdt: BigDecimal,     // /fapi/v3/account assets[asset=USDT].marginBalance — "바이낸스 증거금+손익"
    val availableBalanceUsdt: BigDecimal,  // /fapi/v3/account assets[asset=USDT].availableBalance — "바이낸스 잔여"
) {
    init {
        require(marginBalanceUsdt.signum() >= 0) { "marginBalanceUsdt must not be negative." }
        require(availableBalanceUsdt.signum() >= 0) { "availableBalanceUsdt must not be negative." }
    }

    override fun toString(): String = "ForeignPerpBalance"
}

data class KoreaSpotAccountSnapshot(
    val quantity: BigDecimal,
    val averageBuyPrice: BigDecimal,
    val averageBuyPriceModified: Boolean,
    val entryFeeKrw: BigDecimal?,
    val sellFeeRate: BigDecimal,
    val bidLevels: List<PriceLevel>,
    val lastPrice: BigDecimal,
    val bidObservedAt: Instant,
    /** 빗썸 KRW 잔고(`balance`, `locked` 제외). null = 읽지 못함. KRW 행이 없으면 0. */
    val krwBalance: BigDecimal? = null,
    /** 빗썸 KRW-USDT 최근 체결가. 표시 전용 — 어떤 계산에도 쓰지 않는다. null = 읽지 못함. */
    val usdtLastPriceKrw: BigDecimal? = null,
) {
    init {
        require(krwBalance == null || krwBalance.signum() >= 0) { "krwBalance must not be negative." }
        require(usdtLastPriceKrw == null || usdtLastPriceKrw.signum() > 0) { "usdtLastPriceKrw must be positive." }
    }

    override fun toString(): String = "KoreaSpotAccountSnapshot(bidObservedAt=$bidObservedAt)"
}

data class ForeignPerpAccountSnapshot(
    val signedQuantity: BigDecimal,
    val entryPrice: BigDecimal,
    val entryCommissionUsdt: BigDecimal?,
    val fundingUsdt: BigDecimal?,
    val takerFeeRate: BigDecimal,
    val askLevels: List<PriceLevel>,
    val lastPrice: BigDecimal,
    val askObservedAt: Instant,
    val positionSource: ForeignPerpPositionSource = ForeignPerpPositionSource.EXCHANGE_API,
    /** USDT 자산 잔고. null = EXCHANGE_API 에서 읽지 못함, 또는 MANUAL_ENV(조회하지 않음). */
    val balance: ForeignPerpBalance? = null,
) {
    override fun toString(): String =
        "ForeignPerpAccountSnapshot(positionSource=$positionSource, askObservedAt=$askObservedAt)"
}
```

- 새 필드는 모두 **기본값 `null` 인 마지막 파라미터**다. 기존 생성자 호출 43곳(23개 파일, `AccountSnapshots.kt` 의 선언 2건 제외)은 그대로 컴파일된다.
- `toString()` 노출 프로퍼티(전문): `ForeignPerpBalance` = 없음, `KoreaSpotAccountSnapshot` = `bidObservedAt`,
  `ForeignPerpAccountSnapshot` = `positionSource`·`askObservedAt`. 금액·수량·가격·잔고는 노출하지 않는다.
- `equals`/`hashCode` 는 자동 생성 그대로(모든 프로퍼티) 둔다.

### 3.2 port

새 port 없음. `KoreaSpotAccountReadPort.read()`·`ForeignPerpAccountReadPort.read()` 시그니처 무변경. 계약만 하나 추가된다:
**잔고·USDT 시세의 실패는 `read()` 를 실패시키지 않는다(해당 필드 `null`). 취소는 예외로 전파한다.** port KDoc 에 이 한 줄을 적는다.

### 3.3 `AccountAssetBreakdown` (`domain/.../accountpnl/AccountPnlBreakdown.kt` 에 추가)

```kotlin
/** 수익·정시 알림 "자산" 묶음 값 (#105). 금액은 전체 정밀도(표시 반올림은 템플릿). null = 표시 `없음` 또는 수동 모드 숨김. */
@Suppress("LongParameterList")
data class AccountAssetBreakdown(
    val totalKrw: BigDecimal?,
    val bithumbKrwBalance: BigDecimal?,
    val marginBalanceUsdt: BigDecimal?,
    val marginBalanceKrw: BigDecimal?,
    val availableBalanceUsdt: BigDecimal?,
    val availableBalanceKrw: BigDecimal?,
    val usdtLastPriceKrw: BigDecimal?,
) {
    override fun toString(): String = "AccountAssetBreakdown"

    companion object {
        fun of(
            korea: KoreaSpotAccountSnapshot,
            foreign: ForeignPerpAccountSnapshot,
            koreaValueKrw: BigDecimal,
            fxRate: BigDecimal,
        ): AccountAssetBreakdown
    }
}
```

`of` 규칙(값 전부):

| 출력 | 값 |
|---|---|
| `bithumbKrwBalance` | `korea.krwBalance` |
| `usdtLastPriceKrw` | `korea.usdtLastPriceKrw` (계산에 쓰지 않는다) |
| 바이낸스 잔고 `bal` | `foreign.positionSource == MANUAL_ENV` 이면 `null`(snapshot 값 무시), 아니면 `foreign.balance` |
| `marginBalanceUsdt` / `availableBalanceUsdt` | `bal?.marginBalanceUsdt` / `bal?.availableBalanceUsdt` |
| `marginBalanceKrw` / `availableBalanceKrw` | 각 USDT 값 `× fxRate` (반올림 없음), `bal == null` 이면 `null` |
| `totalKrw` | `bithumbKrwBalance != null && bal != null` 이면 `koreaValueKrw + bithumbKrwBalance + marginBalanceKrw`, 아니면 `null` |

- `fxRate` 는 `AccountNetPnl.fxRate`(USD/KRW, 신선도 검사 통과값)다. USDT 원화가로 환산하지 않는다(합의 4).
- `koreaValueKrw` 는 `KoreaLegBreakdown.valueKrw` = 판정 청산가(빗썸 매수호가 VWAP) × 보유 수량(`locked` 포함)이다.
- USDT 시세 `null` 은 `totalKrw` 에 영향이 없다.

`AccountPnlBreakdown` 에 마지막 프로퍼티 하나를 더한다.

```kotlin
data class AccountPnlBreakdown(
    // … 기존 13개 프로퍼티 그대로 …
    val calculatedAt: Instant,
    val assets: AccountAssetBreakdown,
)
```

`AccountPnlBreakdown.of(pnl)` 이 `AccountAssetBreakdown.of(position.korea, position.foreign, korea.valueKrw, fx)` 로 채운다.
`AccountPnlBreakdown.toString()` 은 기존대로 `AccountPnlBreakdown(calculatedAt=…)` — `assets` 를 노출하지 않는다.

## 4. Infrastructure 계약 (`infrastructure:batch`)

### 4.1 빗썸 (`exchange/bithumb/BithumbPrivateAccountAdapter.kt`)

호출 목록·순서 무변경: `/v1/accounts` → `/v1/orders/chance` → `/v1/ticker` → (`/v1/orders`) → `/v1/orderbook`.

**KRW 잔고** — `readAccount()` 가 이미 받은 `/v1/accounts` 배열에서 `currency == "KRW"` 첫 행의 `balance` 를 읽는다.

| 입력 | `krwBalance` | 로그 |
|---|---|---|
| KRW 행 있음, `balance` 십진수 `>= 0` | 그 값(`locked` 더하지 않음) | 없음 |
| KRW 행 없음 | `0` | 없음 `[ASSUMPTION A1]` |
| `balance` 없음 / 숫자 아님 | `null` | `item=BITHUMB_KRW, reason=NOT_NUMBER` |
| `balance < 0` | `null` | `item=BITHUMB_KRW, reason=NEGATIVE` |

KRW 행 해석은 예외를 던지지 않는다. BTC 행 규칙(음수·평단 0 → `UNEXPECTED`)은 그대로다.

**최근 체결가 + USDT 원화가** — `/v1/ticker` query 를 `markets=KRW-BTC,KRW-USDT` 로 바꾸고, 응답 배열에서 **`market` 필드로**
행을 고른다(지금의 "첫 행" 규칙을 대체한다).

| 경우 | `lastPrice` | `usdtLastPriceKrw` | 로그 |
|---|---|---|---|
| 정상 | `market=KRW-BTC` 행 `trade_price` | `market=KRW-USDT` 행 `trade_price` | 없음 |
| 200 응답이 배열 아님 / `KRW-BTC` 행 없음·숫자 아님·`<= 0` | 지금과 같이 `UNEXPECTED` 로 `read()` 실패 (**fallback 없음**) | — | — |
| `KRW-USDT` 행 없음 | 정상 | `null` | `item=BITHUMB_USDT_PRICE, reason=MISSING` |
| `KRW-USDT` 의 `trade_price` 숫자 아님 | 정상 | `null` | `reason=NOT_NUMBER` |
| `KRW-USDT` 의 `trade_price <= 0` | 정상 | `null` | `reason=NOT_POSITIVE` |
| 묶음 요청이 `getJson` 단계에서 `UNEXPECTED` 로 실패(HTTP 응답 오류 400/404 등·빈 본문·JSON 해석 실패)하고 취소가 아님 | `markets=KRW-BTC` 로 **한 번 더** 요청한 결과(그 실패는 지금과 같이 전파) | `null` | `reason=TICKER_FALLBACK, category=UNEXPECTED, error={원인 체인 맨 끝 예외 simpleName}` `[ASSUMPTION A2]` |
| 묶음 요청이 `getJson` 단계에서 `NETWORK`·`BITHUMB_AUTH` | 지금과 같이 `read()` 실패(재시도 없음) | — | — |
| 묶음 요청 중 취소(`AccountBalanceLog.isCancellation`) | 재요청·로그 없이 그대로 다시 던진다 | — | — |

fallback 경계(한 줄 확정): **fallback 은 `getJson` 이 던진 `UNEXPECTED`(HTTP·본문·JSON 해석 실패)에만 걸린다. `getJson` 이
성공한 뒤의 해석 실패(배열 아님, `KRW-BTC` 행 문제)는 fallback 없이 `read()` 실패다.** 취소 판별은 재요청 직전에 한다 —
`getJson`(199-202행)이 인터럽트를 `UNEXPECTED` 로 바꾸므로 이 검사가 없으면 취소된 회차가 재요청을 보낸다.

A2 근거: 빗썸 v1 ticker 는 목록의 시장 하나가 유효하지 않으면 요청 전체를 4xx 로 거절할 수 있다. 그러면 표시 전용 값 하나 때문에
BTC 체결가까지 실패해 합의 6("USDT 시세 실패는 알림 발송을 막지 않는다")이 깨진다. 재시도는 실패 경로에서만 일어나며 정상
경로의 호출 수는 1회로 합의 2 와 같다.

### 4.2 바이낸스 API 모드 (`exchange/binance/BinanceFuturesPrivateAccountAdapter.kt`)

`read()` 순서: `positionRisk` → (이력: `userTrades`·`income`) → `commissionRate` → **`/fapi/v3/account`** → `ticker/price` → `depth`.
depth 는 여전히 마지막이다(호가 skew 5초 계약).

요청: 기존 `signedGet("/fapi/v3/account", "")` — 서명 query `recvWindow=…&timestamp=…&signature=…`, 헤더 `X-MBX-APIKEY`,
`-1021` 이면 기존 규칙대로 서버 시각 재측정 후 1회 재시도.

응답(바이낸스 공식 문서 `GET /fapi/v3/account` "Account Information V3"): 최상위 객체의 `assets` 배열, 항목 필드
`asset`·`walletBalance`·`unrealizedProfit`·`marginBalance`·`availableBalance`(그 밖 필드도 오지만 읽지 않는다). 숫자는 문자열이다.
이 adapter 는 `asset == "USDT"` 항목의 `marginBalance`·`availableBalance` 만 읽는다. `walletBalance`·`unrealizedProfit` 은
읽지 않는다(`marginBalance = walletBalance + unrealizedProfit` 이므로 중복).

| 경우 | `balance` | 로그 |
|---|---|---|
| 정상, 두 값 `>= 0` | `ForeignPerpBalance(marginBalance, availableBalance)` | 없음 |
| 요청 실패(`AccountReadException`, 취소 아님 — 401/`-2015` 등 인증·429·5xx·timeout·깨진 JSON) | `null` | `item=BINANCE_USDT_ASSET, reason=REQUEST_FAILED, category={범주}, error={원인 체인 맨 끝 예외 simpleName}` |
| `assets` 없음·배열 아님, 또는 `USDT` 항목 없음 | `null` | `reason=MISSING` |
| 두 필드 중 하나라도 없음·숫자 아님 | `null` | `reason=NOT_NUMBER` |
| 둘 중 하나라도 `< 0` | `null` | `reason=NEGATIVE` |
| 요청 중 취소 | 로그 없이 그대로 다시 던진다 — 이후 `price`·`depth` 를 부르지 않는다 | — |

- 응답 해석은 예외를 던지지 않고 사유를 돌려준다(`requireDecimal` 처럼 던지는 helper 를 쓰지 않는다). 요청 실패만 `catch (AccountReadException)` 으로 잡는다.
- 잔고 조회 실패(취소 제외)는 이 회차의 나머지 호출(`price`·`depth`)을 막지 않는다.
- weight: 공식 문서 기준 `GET /fapi/v3/account` request weight **5**. 회차 60초마다 1회 → 분당 +5. USDⓈ-M IP 한도 2,400/분 대비
  무시할 수준이다.
- 회차 시간 상한: 이번 변경으로 늘어나는 최악 시간은 바이낸스 `/fapi/v3/account` 1회(client timeout 5초)와 빗썸 ticker
  fallback 1회(client timeout 5초)를 더한 **+10초**다. 실행 timeout 40초(`application.yml:72`)는 기존 호출 전체와 공유한다 —
  기존 호출이 이미 timeout 근처까지 걸리는 회차(이력 탐색이 긴 회차)에서는 이 10초가 timeout 을 넘길 수 있고, 그때는 기존과 같이
  `JobExecutor` 가 취소해 저장 없이 끝난다(다음 회차 재시도). 정상 경로는 바이낸스 1회 추가분(수백 ms)만 늘어난다.
- 조회 전용 architecture 스캔: `/fapi/v3/account` 는 금지 패턴이 아니다. 단 응답의 `maxWithdrawAmount` 같은 필드명을 소스·주석에
  쓰면 `(?i)withdraw` 규칙에 걸린다 — 읽지도 언급하지도 않는다.

### 4.2.1 바이낸스 수동 모드 (`BinanceFuturesManualAccountAdapter.kt`)

코드 변경 없음. `balance` 는 기본값 `null` 이고 `/fapi/v3/account` 를 부르지 않는다(이 adapter 는 서명 수단 자체가 없다 —
`PrivateAccountReadOnlyArchitectureTest` 의 수동 모드 규칙이 서명·API key·`recvWindow` 를 금지한다).

### 4.3 실패 로그와 취소 (`exchange/AccountBalanceLog.kt`, 신규 internal object)

```kotlin
internal enum class BalanceItem { BITHUMB_KRW, BITHUMB_USDT_PRICE, BINANCE_USDT_ASSET }

internal enum class BalanceUnavailableReason { MISSING, NOT_NUMBER, NEGATIVE, NOT_POSITIVE, REQUEST_FAILED, TICKER_FALLBACK }

internal object AccountBalanceLog {
    /** WARN 한 줄. category·errorClass 가 없으면 "-". */
    fun unavailable(
        item: BalanceItem,
        reason: BalanceUnavailableReason,
        category: AccountReadFailureCategory? = null,
        errorClass: String? = null,
    )

    /** 원인 체인(최대 16단계)에 InterruptedException·CancellationException 이 있거나 현재 스레드가 interrupted 면 true. */
    fun isCancellation(error: Throwable): Boolean

    /** 원인 체인(최대 16단계, 자기 참조 cause 에서 멈춤)의 맨 끝 예외 단순 클래스명. 체인이 자기 하나면 자기 이름. */
    fun rootErrorClass(error: Throwable): String
}
```

로그 키(값 전부): `Account balance unavailable: item={BalanceItem}, reason={BalanceUnavailableReason}, category={AccountReadFailureCategory 이름 | -}, error={rootErrorClass | -}`.
logger 이름은 `AccountBalanceLog` 다. 레벨 WARN, 회차마다(60초) 실패하면 회차마다 남는다.

`error` 값 규칙: `REQUEST_FAILED`·`TICKER_FALLBACK` 은 잡은 `AccountReadException` 의 `rootErrorClass` 다(바깥 예외는 항상
`AccountReadException` 이라 정보가 없다). 예: HTTP 401 → `Unauthorized`, 404 → `NotFound`, 503 → `ServiceUnavailable`
(`WebClientResponseException` 하위 클래스), 깨진 JSON → `JsonParseException`. 응답 해석 사유(`MISSING`·`NOT_NUMBER`·`NEGATIVE`·
`NOT_POSITIVE`)는 예외가 없으므로 `category=-`, `error=-`.

**담지 말아야 할 값(전부)**: 잔고·가격·수량 등 모든 금액, API key, secret, 서명(`signature`), 요청 query·URL, 응답 본문,
예외 메시지, 예외 객체(스택). 예외는 `error`(단순 클래스명)로만 남긴다 — `WebClientResponseException` 의 메시지는 서명이 붙은
요청 URL 을 담을 수 있다.

**취소**: 두 adapter 는 잔고·USDT 경로에서 잡은 `AccountReadException` 이 `isCancellation` 이면 흡수·로그·재시도 없이 그대로 다시
던진다. 빗썸 ticker fallback(A2)도 재요청 전에 이 검사를 한다. Job 은 기존처럼 취소를 판별해 회차를 저장 없이 끝낸다.

## 5. 표시 계약

### 5.1 메일 (`■ 합산` 다음 섹션)

```text
■ 자산
총 자산               57,396,267원
바이낸스 증거금+손익  14,000,700원 (10,000.50 USDT)
잔여 금액
  - 빗썸      1,234,567원
  - 바이낸스  11,200,350원 (8,000.25 USDT)
USDT 원화가           1,412원
```

- `MailBlock.Section("자산", listOf(MailBlock.Pairs(…)))`, 항목 순서 고정: 총 자산 / 바이낸스 증거금+손익 / 잔여 금액(자식 빗썸·바이낸스) / USDT 원화가.
- `잔여 금액` 부모 값은 빈 문자열이다 — `MailDocument` 가 줄 끝 공백을 지워 라벨만 남는다. 두 잔여의 합계는 만들지 않는다 `[ASSUMPTION A3]`.
- 정렬 폭은 `MailDocument` 규칙(라벨 표시 폭 최댓값 + 2)이 정한다. 위 예시는 그 규칙의 출력이다.
- 메일은 Discord 배치안(§5.2 A/B)과 무관하게 같다.

### 5.2 Discord (순손익 행 뒤 2행) — ⑦ 결정: **B안 채택** (2026-11-02)

두 안 모두 칸 이름·값 문자열(§5.3)은 같고 칸 배치만 다르다. 기존 1~5행은 무변경.

**A안 (합의 5 원안)**

```text
행 6: [총 자산] 57,396,267원 | [바이낸스 증거금+손익] 14,000,700원 (10,000.50 USDT) | [USDT 원화가] 1,412원
행 7: [잔여 금액] 빗썸 1,234,567원 ⏎ 바이낸스 11,200,350원 (8,000.25 USDT)
```

- rows `[3,2,3,2,1,3,1]`, `AlertMessageSpecConformanceTest` 수익·정시 `cellsPerRow` = `[3,2,3,2,1]` + `[3,1]`.
- fields = 칸 15 + 행 끊기 1(2번째 행 뒤) = **16개**. 4번째 행(2칸) 뒤는 다음 행이 1칸이라 끊기 없음. 행 6 은 3칸이라 끊기 없음.
- 위험: 행 6 은 1/3 폭이라 `바이낸스 증거금+손익` 값(약 29자)이 줄바꿈될 가능성이 높다(Q1).

**B안 (Q1 해소 제안)**

```text
행 6: [총 자산] 57,396,267원 | [바이낸스 증거금+손익] 14,000,700원 (10,000.50 USDT)
행 7: [잔여 금액] 빗썸 1,234,567원 ⏎ 바이낸스 11,200,350원 (8,000.25 USDT) | [USDT 원화가] 1,412원
```

- rows `[3,2,3,2,1,2,2]`, `AlertMessageSpecConformanceTest` 수익·정시 `cellsPerRow` = `[3,2,3,2,1]` + `[2,2]`.
- fields = 칸 15 + 행 끊기 2(2번째 행 뒤, 6번째 행 뒤 — 2칸 행 다음이 2칸 행이므로 §6.2 규칙으로 끊기 칸 1개) = **17개**(한도 25).
  5번째 행(순손익 1칸, non-inline)이 앞을 이미 끊으므로 행 6 앞에는 끊기 칸이 없다.
- 두 행 모두 1/2 폭. `잔여 금액` 칸은 2줄이라 같은 행의 `USDT 원화가` 칸 아래가 비어 보인다.
- 합의 5 의 칸 구성을 바꾸므로 사용자 승인이 필요하다.

대체 평문(`description`)에는 두 안 모두 칸마다 한 줄이 더 붙는다(4줄, 순서는 rows 순서).

### 5.3 값 → 문자열 (값 전부)

| 칸 | 값 있음 | `null` (API 모드) | 수동 모드(`b.foreign.source == MANUAL_ENV`) |
|---|---|---|---|
| 총 자산 | `krwPrice(totalKrw)` | `없음` | `없음` |
| 바이낸스 증거금+손익 | `"{krwPrice(marginBalanceKrw)} ({usdt2(marginBalanceUsdt)})"` | `없음` | `수동 모드 — 표시 안 함` |
| 잔여 금액 — 빗썸 | `krwPrice(bithumbKrwBalance)` | `없음` | (빗썸은 모드 무관) 값 또는 `없음` |
| 잔여 금액 — 바이낸스 | `"{krwPrice(availableBalanceKrw)} ({usdt2(availableBalanceUsdt)})"` | `없음` | `수동 모드 — 표시 안 함` |
| USDT 원화가 | `krwPrice(usdtLastPriceKrw)` | `없음` | (모드 무관) 값 또는 `없음` |

`—` 는 U+2014(기존 `모니터링 중단 — …` 과 같은 문자). 문자열 상수 `"없음"`·`"수동 모드 — 표시 안 함"` 은 `AccountPnlAlertContent` 의
private const 다. 이 문구는 `AlertMessageSpecConformanceTest` 금지어(`수동 입력\(env\)`·`체결가` 등)에 걸리지 않는다.
`수동 모드 — 표시 안 함` 은 수익·정시 본문에 바이낸스 출처를 드러낸다 — #102 U5 개정이다(§12).

### 5.4 코드 배치

`pnlMessage` 는 detekt `LongMethod`(100) 경계에 있다. 자산 문자열은 private 함수 두 개가 만든다 — `pnlMessage` 에는 섹션 1줄·
행 결합 1줄만 더한다.

```kotlin
private fun assetSection(b: AccountPnlBreakdown, manual: Boolean): MailBlock.Section
private fun assetRows(b: AccountPnlBreakdown, manual: Boolean): List<List<EmbedCell>>
```

두 함수가 공유하는 문자열 묶음이 필요하면 private **일반 class**(data class 아님 — 자동 `toString()` 이 금액 문자열을 노출한다)로 둔다.
A/B 안의 차이는 `assetRows` 반환값 하나에만 있다.

## 6. Discord 행 끊기 (#102 design §3.3 규칙 2 개정)

### 6.1 원인

Discord 는 inline 칸을 한 줄에 최대 3개 흘리고, 한 줄의 inline 칸 수로 폭을 나눈다. 지금 규칙 2 는 "2칸 행 다음 행이 2칸 이상이면
inline 빈 칸(SPACER)을 붙여 3칸으로 채운다" 이다. 채움 칸이 그 줄을 3칸으로 만들어 실제 두 칸이 각각 1/3 폭이 된다 —
바이낸스 칸의 `손익 -1,191,680원 (-851.20 USDT)` 가 줄바꿈되는 원인이다.

### 6.2 개정 규칙 2

> 칸이 2개인 inline 행 **다음에 칸 2개 이상인 행이 오면** 보이지 않는 **non-inline** 칸 `EmbedDocument.ROW_BREAK` =
> `DiscordField(ZWSP, ZWSP, inline = false)` 하나를 덧붙인다. non-inline 칸이 inline 흐름을 끊으므로 위 행은 2칸 = 1/2 폭이 되고
> 다음 행 첫 칸이 위로 올라가지 않는다. 2칸 행이 마지막이거나 다음 행이 1칸(이미 non-inline)이면 붙이지 않는다.

- 조건은 그대로, 덧붙이는 칸의 `inline` 만 `true → false` 다. `SPACER` 상수는 삭제하고 `ROW_BREAK` 로 대체한다(이름이 의미를 바꾼다).
- 끊기 칸은 이름·값이 ZWSP 라 보이지 않지만 non-inline 칸 한 줄 높이의 세로 여백이 생긴다. 사람 확인(dod AC8)에서 함께 본다.
- 대체 평문(`description`)은 rows 에서 만들므로 영향 없다. fields 수는 바뀌지 않는다(채움 1개 → 끊기 1개).

### 6.3 영향 범위 (공통 렌더러 — 사용자 합의)

| 대상 | 영향 | 처리 |
|---|---|---|
| `EmbedDocumentTest` | SPACER 단정 4곳(`fields[5]`, `count`, 상수 검사, `[2,2]` 케이스) | `ROW_BREAK`·`inline=false` 로 갱신. inline 단정 목록의 6번째 값 `true → false` |
| `GoldenFields.kt` `GOLDEN_FILLER` | 구현과 독립인 기대값 상수(5행 KDoc 에 `SPACER` 언급) | `GOLDEN_ROW_BREAK = DiscordField(ZWSP, ZWSP, false)` 로 교체, KDoc 도 `ROW_BREAK` 로 |
| `AccountPnlAlertContentTest` | `profitFields` 의 `GOLDEN_FILLER` 1곳 | `GOLDEN_ROW_BREAK` |
| `OwnerMarketAlertContentTest:347` (일일 요약 `[3,2,2,1]`) | `GOLDEN_FILLER` 1곳 | `GOLDEN_ROW_BREAK` |
| `AccountAlertContentTest`·`OwnerOpsAlertContentTest` 등 그 밖 golden | `GOLDEN_FILLER` 를 쓰지 않음(grep 확인 — 2칸 행 뒤가 1칸이거나 마지막) | 무변경 |
| `AlertMessageSpecConformanceTest` | 행별 칸 수만 단정. 수익·정시 2행의 기대 `[3,2,3,2,1]` 은 자산 행 때문에 A안 `[3,2,3,2,1,3,1]` / B안 `[3,2,3,2,1,2,2]` 로 바뀐다(§5.2) | 그 2곳만 갱신 |
| `DiscordLayoutTest` | `DiscordField(zwsp, zwsp, true)` 생성 가능 여부만 검사 | 무변경 |
| `DiscordLayoutJsonTest`·`OwnerAlertDiscordLayoutStoreIntegrationTest`·`V23…MigrationIntegrationTest` | 손으로 만든 fields 직렬화·저장 | 무변경 |
| `OwnerAlertDiscordLayoutIntegrationTest` | 시장 급등(1행 3칸)만 보내 field 이름을 단정, 채움 칸 없음 | 무변경 |
| `DiscordWebhookSenderAdapterTest` | 손으로 만든 layout | 무변경 |

`SPACER`·`GOLDEN_FILLER` 를 담은 `.kt` 파일은 정확히 5개다(`EmbedDocument.kt`·`EmbedDocumentTest.kt`·`GoldenFields.kt`·
`AccountPnlAlertContentTest.kt`·`OwnerMarketAlertContentTest.kt` — `grep GOLDEN_FILLER\|SPACER` 확인).

### 6.4 이미 저장된 outbox 행

layout 은 enqueue 시 한 번 렌더되어 `account_pnl_mail.discord_layout` 에 저장되고 발송기는 저장값을 그대로 보낸다(§1).
따라서 배포 전에 쌓인 `PENDING` 행은 **옛 inline 채움 칸 그대로** 발송된다. 정시 보고는 같은 시간 버킷 eventKey 를 outbox 가
무시하므로, 배포한 시간대에 이미 저장된 정시 보고 행이 있으면 그 시간의 보고는 옛 배치로 간다. 데이터 손상·발송 실패는 없고
조치도 필요 없다. JSON 형식(`v`)·V23 열은 바꾸지 않는다.

## 7. 실패 처리와 중단 정책 분리

| 실패 | 발생 위치 | 순손익·발송 | `MonitoringOutagePolicy` | 표시 |
|---|---|---|---|---|
| 빗썸 KRW 행 해석 실패 | 빗썸 adapter 안 | 영향 없음 | 반영 안 됨(`read()` 성공) | 빗썸 잔여 `없음`, 총 자산 `없음` |
| KRW-USDT 시세 실패 | 빗썸 adapter 안 | 영향 없음 | 반영 안 됨 | USDT 원화가 `없음` |
| `/fapi/v3/account` 실패·해석 실패 | 바이낸스 adapter 안 | 영향 없음 | 반영 안 됨 | 증거금+손익·바이낸스 잔여 `없음`, 총 자산 `없음` |
| 위 경로의 취소 | adapter → Job | 기존 취소 처리(저장 없이 `Skipped(cancelled)`) | 반영 안 됨 | — |
| `/v1/accounts`·BTC 체결가·호가 등 기존 실패 | 기존 그대로 | 기존 그대로 | 기존 그대로 | 기존 그대로 |

Job 코드 변경 없음. 위 분리는 회귀 방어선 테스트 `AccountPnlMonitorJobBalanceTest`(dod R5)로 고정한다.

## 8. 설계 완결성 체크

| 항목 | 이 변경 |
|---|---|
| Facade 이름과 주입 대상 | 해당 없음 — API 변경 없음. batch 진입은 기존 `AccountPnlMonitorScheduler → AccountPnlMonitorJob` 그대로 |
| Domain port 와 구현 모듈 | 새 port 없음(D1). 기존 `KoreaSpotAccountReadPort`(`BithumbPrivateAccountAdapter`)·`ForeignPerpAccountReadPort`(`BinanceFuturesPrivateAccountAdapter`·`BinanceFuturesManualAccountAdapter`) — 모두 `infrastructure:batch` |
| MarketPair identity | 해당 없음 — 실계정 모니터는 `MarketPair` 를 쓰지 않고 adapter 상수 `KRW-BTC`·`BTCUSDT` 로 BTC 한 쌍만 다룬다(기존). `KRW-USDT` 는 표시 전용 시세이고 identity·캐시 키·DB row 에 들어가지 않는다 |
| 시간·범위 계약 | 새 시각·범위 없음. 잔고·USDT 시세는 관측 시각을 갖지 않고 신선도 판정 대상이 아니다(표시 전용). `completedAt` 측정 시점 무변경(잔고 조회는 `read()` 안, depth 앞) |
| DB·캐시 순서 | 해당 없음 — 잔고는 저장하지 않는다. outbox 에는 렌더된 문구만 들어간다(기존) |
| Flyway | 불필요 — `account_pnl_latest` 는 요약만 저장(확인), 잔고 저장 없음. `discord_layout` JSON 형식 무변경 |
| 배치 lock·lease·timeout·JobResult | 무변경 — `lock:account-pnl:monitor` / 50s / 40s. `JobResult` 분기(Success / Skipped(`state-version-conflict`·`cancelled`) / Failure(상태 저장소 예외)) 무변경. 잔고 실패는 어떤 분기도 만들지 않는다. 최악 +10초(§4.2) |
| 알림 event identity·claim/fencing | 무변경 — eventKey(`account-pnl-profit-{ms}`·`account-pnl-hourly-{정시 ms}`)·outbox claim 경로 그대로. 문구만 늘어난다 |
| 공개 endpoint | 해당 없음 — `PublicEndpointPolicy`·`auth-security.md` 무변경 |
| Redis key/TTL/payload | 해당 없음 |
| Domain dependency | 추가 없음 — `BigDecimal`·`Instant` 만 쓴다 |

## 9. 테스트 전략

| 계층 | 무엇을 | 테스트 |
|---|---|---|
| Domain 렌더러 | 행 끊기 칸 `inline=false`, 조건(2칸→2칸 이상), 마지막·1칸 다음 미삽입 | `EmbedDocumentTest` (domain unit) |
| Domain 불변식 | snapshot 새 필드 기본값 null, 음수·0 거부, `toString()` 노출 프로퍼티 | `AccountBalanceSnapshotTest` (신규, domain unit) |
| Domain 계산 | 총 자산 공식·`null` 전파·수동 모드 무시·USDT 독립·정밀도 무손실 | `AccountAssetBreakdownTest` (신규, domain unit) |
| Domain 문구 | 수익·정시 메일 `■ 자산`·Discord 2행 golden, `없음`·수동 모드 변형, 반올림 | `AccountPnlAlertContentTest` (domain unit), `AlertMessageSpecConformanceTest` 칸 수 |
| adapter | 빗썸 KRW·USDT 해석, query, fallback 경계, fallback 취소, 로그 키·`error` 값·비노출. 바이낸스 `/fapi/v3/account` 서명·순서·해석·실패 흡수·취소 전파·로그 | `BithumbPrivateAccountAdapterTest`·`BinanceFuturesPrivateAccountAdapterTest` (MockWebServer, 고정 `Clock`, `OutputCaptureExtension`) |
| 수동 모드 | `/fapi/v3/account` 미호출, `balance == null` | `BinanceFuturesManualAccountAdapterTest` |
| Job | 잔고 null 이어도 수익·정시 행 enqueue, 5분 넘게 이어져도 중단 알림 없음 | `AccountPnlMonitorJobBalanceTest` (신규, apps:batch unit, `MutableClock`, `Asia/Seoul`) |
| 경계 | 조회 전용 스캔·모듈 경계 | `./gradlew architectureTest` |
| 통합 | outbox·Discord layout 저장/발송 회귀 | `:apps:batch:integrationTest`·`:infrastructure:common:integrationTest` |

테스트는 실 거래소 주소·실 credential 을 쓰지 않는다. 시각은 고정 `Clock`, zone 은 명시적 `Asia/Seoul`.

취소 테스트 방식(확정): MockWebServer dispatcher 가 대상 요청(`/fapi/v3/account`, 빗썸은 `/v1/ticker`)을 받는 순간 미리 캡처한 테스트
스레드를 `interrupt()` 하고 `setBodyDelay` 로 응답을 지연한다. 그 앞의 호출은 인터럽트 전이라 정상으로 끝나므로 정확히 잔고 경로의
취소를 잰다.

## 10. 문서 변경

| 문서 | 변경 |
|---|---|
| `docs/work/alert-message-template/message-spec.md` "실계정 순손익" 수익·정시 | Discord rows 6·7(⑦ 결정 안), 메일 `■ 자산` 섹션, `없음`·수동 모드 표기 메모. message-spec 은 이동하지 않았다 — 도메인 KDoc·runbook 3곳이 이 경로를 정본으로 가리킨다(grep 확인) |
| `docs/work/alert-message-template/design.md` §3.3 | 규칙 2 아래 개정 메모 1단락(#105, 이 문서 §6.2 링크). 본문 규칙 문장은 고치지 않고 메모로 대체 관계를 적는다 |
| `docs/work/alert-message-template/design.md` §6.2 (316행 U5) | U5 줄 아래 개정 메모 1단락 — 자산 칸의 바이낸스 값만 수동 모드에서 `수동 모드 — 표시 안 함` 으로 출처가 드러난다(#105, 합의 7, 이 문서 §12) |
| `docs/runbooks/account-pnl-alert.md` (실계정 모니터 runbook) | Key 발급 표 바이낸스 행: `Enable Reading` 이 `/fapi/v3/account`(계정 잔고) 조회를 포함함을 명시. "알림 메시지 예" 메일·Discord 설명에 자산 묶음. `## 바이낸스 수동 입력 모드 (manual)` → `### 메일 표기` 350행·358행 문장 수정(자산 칸만 예외적으로 manual 모드가 드러남)과 표에 자산 행(열 머리와 맞춤). 동작 요약 표에 "자산 표시" 행. 중단 범주 표 아래 "잔고·USDT 시세 실패는 중단 알림이 아니다" 한 줄. #105 Discord 확인 체크리스트 절 |
| http 샘플·API 문서 | 변경 없음(공개 API 무변경) |

`docs/check-documentation.sh` 의 정본 목록에 `account-pnl-alert.md` 는 없다. 하네스 파일(`.claude/**`, `CLAUDE.md`)은 건드리지 않는다.

## 11. 가정·결정 필요·열린 질문

- (⑦ 2026-11-02: A1~A4 사용자 수용) `[ASSUMPTION A1]` 빗썸 `/v1/accounts` 에 KRW 행이 없으면 KRW 잔고 0 으로 본다(BTC 행 부재 = 수량 0 과 같은 규칙). 0 원이 실제로 행 생략인지는 운영 key 로 확인하지 않았다.
- `[ASSUMPTION A2]` 묶음 ticker 요청이 `getJson` 단계에서 `UNEXPECTED` 로 실패하면 `KRW-BTC` 단독으로 1회 재요청한다(§4.1). 합의 2("한 번에 받는다")는 정상 경로 기준으로 지킨다. 합의 6 보호가 목적이다.
- `[ASSUMPTION A3]` 메일 `잔여 금액` 부모 줄은 값 없이 라벨만 둔다(합계를 만들지 않는다). Discord 칸도 합계 없이 두 줄이다.
- `[ASSUMPTION A4]` 음수 잔고(`marginBalance`·`availableBalance`·KRW `balance`)는 표시하지 않고 `없음` + WARN `reason=NEGATIVE` 다. 기존 서식(`krwPrice`·`usdt2`)이 부호를 지우므로 틀린 양수를 보이는 것보다 낫다고 판단했다.
- `[DECIDED ⑦ 2026-11-02: B안]` Q1 — Discord 자산 행 배치 A안(합의 원안, 3칸 + 1칸, fields 16) / B안(2칸 + 2칸, fields 17)(§5.2). A안은 `바이낸스 증거금+손익` 값이 1/3 폭에서 줄바꿈될 가능성이 높다. B안은 합의 5 의 칸 구성을 바꾼다. plan 은 A안으로 쓰고 B안 채택 시 바뀌는 지점을 각주로 둔다.
- **열린 질문 Q2** — 끊기 칸(non-inline ZWSP)이 만드는 세로 여백이 시장 일일 요약·실계정 수익 embed 에서 수용 가능한지는 실제 Discord 에서만 판정된다(dod AC8).

## 12. 합의 범위와의 충돌 점검

합의를 뒤집는 사실은 찾지 못했다. 다만 합의가 **선행 계약을 개정**하는 지점 하나와, 합의끼리 부딪히는 지점 두 개가 있다.

1. **#102 U5 개정** — #102 design §6.2(316행)는 "바이낸스 포지션 출처(거래소 API/수동 env)는 수익·정시 본문에 쓰지 않는다(U5)" 이다.
   합의 7 의 `수동 모드 — 표시 안 함` 은 자산 칸에서 manual 모드를 드러내므로 U5 를 개정한다. 사용자가 합의 7 로 승인한 변경이다.
   범위는 자산 칸의 바이낸스 두 값뿐이고 기존 칸(진입 수수료·펀딩비의 `미반영 (수동 미입력)` 등)은 그대로다. #102 design 316행 아래와
   runbook `### 메일 표기` 350·358행에 개정을 적는다(§10, plan Task 9).
2. 합의 2 의 "한 번에 받는다" 와 합의 6 "USDT 실패가 발송을 막지 않는다" 는, 빗썸이 묶음 요청 전체를 거절하는 경우 서로 부딪힌다 → A2(실패 경로 재요청).
3. 합의 5 의 3칸 행은 이번 이슈가 고치려는 증상(1/3 폭 줄바꿈)을 새 칸에서 재현할 수 있다 → Q1(A/B안, ⑦ 사용자 결정).

## 변경 이력

| 날짜 | 변경 | 사유 |
|---|---|---|
| 2026-11-02 | 초안 | orchestrator ④ |
| 2026-11-02 | §1 근거 2행, §3.1 호출 수 43곳, §4.1 fallback 경계·취소·`error` 값, §4.2 취소 행·최악 +10초, §4.3 `rootErrorClass`, §5.2 A/B안, §5.3·§12 #102 U5 개정, §6.3 grep 5파일, §9 취소 테스트 방식, §10 문서 대상 추가, §11 Q1 → DECISION_NEEDED | ⑥ 스펙 리뷰 11건 반영 + Q1 해소안 |
