# 실계정 순손익 알림 — 바이낸스 수동 입력 모드 — 설계

| 항목 | 값 |
|---|---|
| 문서 역할 | orchestrator ④ 설계 문서 |
| slug | `live-account-pnl-manual-binance` |
| base | `origin/dev` `0a04546` (직전 단위 `live-account-pnl-alert` PR #83 merge) |
| branch | `feat/live-account-pnl-manual-binance` |
| 상태 | `DRAFT` |
| 선행 단위 | `docs/work/live-account-pnl-alert/` (design `APPROVED`, dod `FROZEN`) — 이 단위는 그 위에 새 slug 로 얹는다. 동결된 계약은 고치지 않는다 |

## 0. 이 단위의 성격

owner 는 바이낸스 API key 를 발급받지 못했다. 그래서 직전 단위의 바이낸스 leg(서명 REST 로 포지션·진입 수수료·펀딩·
taker 율을 읽는 것)를 쓸 수 없다. 이 단위는 바이낸스 leg 를 **설정으로 두 모드 중 하나로** 구동한다.

| 모드 | 바이낸스 포지션·수수료·펀딩·taker 율 | 바이낸스 호가·최근 체결가 |
|---|---|---|
| `api` (기본) | 기존 서명 REST (변경 없음) | 기존 공개 REST |
| `manual` | **환경변수로 owner 가 입력** | 기존과 같은 공개 REST (`/fapi/v1/depth`, `/fapi/v2/ticker/price`) |

빗썸 leg, 순손익 공식, 알림·중단 정책, outbox, Job, 메일 제목 형식은 바꾸지 않는다. `api` 모드의 계산 결과와 메일은
**바이트 단위로 불변**이다(§9 회귀 경계).

이 단위도 **주문을 제출하지 않는다.** `manual` 모드는 오히려 서명 호출 자체가 없다.

## 1. 범위

### 1.1 포함

| 항목 | 위치 |
|---|---|
| Domain `ForeignPerpAccountSnapshot` 에 포지션 출처(`positionSource`) 추가 | `domain` |
| 메일 본문의 출처 표기 (수동 입력 / 수동 미입력) | `domain` (`AccountPnlMailContent`) |
| 바이낸스 공개 호가·체결가 조회와 HTTP 실패 정규화를 공유 클래스로 추출 | `infrastructure:batch/exchange/binance` |
| `manual` 모드 adapter (`ForeignPerpAccountReadPort` 구현) | `infrastructure:batch/exchange/binance` |
| 모드 설정·manual 입력 바인딩·검증, 모드별 조건부 bean | `infrastructure:batch/accountpnl`, `apps:batch/config` |
| 필수값 누락 WARN(변수 이름만) | `apps:batch/config` |
| 조회 전용 아키텍처 테스트 대상 확장 + manual adapter 무서명 규칙 | `architecture-tests` |
| runbook 갱신 | `docs/runbooks/account-pnl-alert.md` |

### 1.2 제외

| 제외 | 이유 |
|---|---|
| 바이낸스 key 발급 자체 | owner 영역 |
| 빗썸 leg 의 수동 모드 | 요구 밖 (빗썸 key 는 있음) |
| 웹 화면·REST endpoint | 요구 밖. `PublicEndpointPolicy` 무변경 |
| master spec(`private-live-autotrader`) 수정 | 직전 단위 §0.1 결정 유지 |
| Flyway migration | §6 근거 — 필요 없음 |
| 직전 단위 `dod.md`(FROZEN)·`design.md` 수정 | 새 slug 원칙 |
| 펀딩 자동 누적·포지션 변경 자동 감지 (manual 모드) | key 없이 불가. env 수정 + 재시작으로 반영 (§7) |
| 직전 단위가 후속으로 남긴 계정/호가 port 분리 | 범위 밖. 이 단위의 공유 클래스는 infrastructure 내부 추출이며 Domain port 를 늘리지 않는다 |

## 2. ④ 설계 체크리스트

| 항목 | 이 단위 |
|---|---|
| Facade | **해당 없음.** API 경로 변경 없음. batch 경로는 `Scheduler → AccountPnlMonitorJob` 그대로 |
| Domain port | 새 port 없음. 기존 `ForeignPerpAccountReadPort` 를 **두 번째 구현**(`BinanceFuturesManualAccountAdapter`, `infrastructure:batch`)이 구현한다. 런타임에는 모드에 따라 둘 중 정확히 하나만 bean 이다. 대체 구현이 실제로 생겼으므로 기존 interface 가 처음으로 경계 가치를 갖는다 |
| MarketPair identity | **해당 없음(변경 없음).** 이 기능은 직전 단위부터 빗썸 `KRW-BTC` / 바이낸스 `BTCUSDT` 고정이며 `MarketPair` read model·캐시 키·premium row 를 쓰지 않는다. manual adapter 도 `BTCUSDT` 고정 |
| 시간·범위 계약 | 바이낸스 호가 관측 시각 = depth 응답 `T`(ms) → `Instant` (기존 그대로, 공유 클래스로 이동만). 신선도 판정(10초·skew 5초)·`calculatedAt` 은 Job 이 하며 무변경. 새 range 없음 |
| DB·캐시 순서 | 변경 없음. 상태·outbox 테이블(V18)의 쓰기 경로·순서 그대로. Redis 무변경 → `modules:redis`·`docs/runbooks/redis-contract.md` 무변경 |
| Flyway | **불필요.** §6 |
| batch lock | **변화 없음.** `JobId.ACCOUNT_PNL_MONITOR`, `batch.jobs.account-pnl-monitor`(lock `lock:account-pnl:monitor`, lease 50s, timeout 40s), `JobResult` 분기(관측 실패 = Success / version 충돌 = Skipped / store 예외 = Failure) 그대로. manual adapter 는 서명·이력 탐색이 없어 회차 시간이 오히려 짧아진다 |
| 알림 event identity | 변경 없음. eventKey `account-pnl-{profit\|outage\|recovered}-<epochMilli>`, fingerprint 공식 그대로 (§5.3) |
| 공개 endpoint | **무변경.** `PublicEndpointPolicy`·`docs/runbooks/auth-security.md` 갱신 없음 |
| 테스트 전략 | §10 |

## 3. 설정 계약

### 3.1 키 전체

모든 키는 기존 `account-pnl-monitor` 블록 아래에 둔다. 환경변수 이름은 기존 관례(`BINANCE_API_KEY` 처럼 접두 없는 거래소명)를
따른다. `apps/batch/src/main/resources/application.yml` 에 아래 placeholder 를 추가한다(값 기본값은 두지 않는다 — 단
`mode` 만 `api`).

| property key | env | Kotlin 타입 (`AccountPnlMonitorProperties.binance.*`) | 기본값 | 필수 | 검증 (위반 시 기동 실패) |
|---|---|---|---|---|---|
| `account-pnl-monitor.binance.mode` | `BINANCE_MODE` | `BinanceLegMode?` (`API`·`MANUAL`, 대소문자 무관) | yml `api`, 빈 값이면 `API` | 아니오 | `api`·`manual` 외 값 → 조건 평가에서 `IllegalStateException` (§3.3) |
| `account-pnl-monitor.binance.manual.short-quantity` | `BINANCE_MANUAL_SHORT_QUANTITY` | `BigDecimal?` | 없음 | `manual` 에서 예 | 숫자 형식, `>= 0`. `0` = 포지션 없음 |
| `account-pnl-monitor.binance.manual.entry-price` | `BINANCE_MANUAL_ENTRY_PRICE` | `BigDecimal?` | 없음 | `manual` 에서 예 | 숫자 형식, `> 0` |
| `account-pnl-monitor.binance.manual.entry-fee-usdt` | `BINANCE_MANUAL_ENTRY_FEE_USDT` | `BigDecimal?` | 없음(= 미입력) | 아니오 | 숫자 형식, `>= 0` |
| `account-pnl-monitor.binance.manual.funding-usdt` | `BINANCE_MANUAL_FUNDING_USDT` | `BigDecimal?` | 없음(= 미입력) | 아니오 | 숫자 형식. **부호 제한 없음**(받은 것 `+`, 낸 것 `-`) |
| `account-pnl-monitor.binance.manual.taker-fee-rate` | `BINANCE_MANUAL_TAKER_FEE_RATE` | `BigDecimal?` | 없음 → 코드 상수 `0.0005` | 아니오 | 숫자 형식, `0 <= x < 1` |
| (기존) `account-pnl-monitor.binance.api-key` | `BINANCE_API_KEY` | `String` | `""` | `api` 에서만 예 | — |
| (기존) `account-pnl-monitor.binance.secret-key` | `BINANCE_SECRET_KEY` | `String` | `""` | `api` 에서만 예 | — |
| (기존) `enabled`·`recipient`·`bithumb.*`·`binance.base-url` | 기존 | 기존 | 기존 | 기존 | 기존 |

- 숫자 형식 오류는 Spring 바인딩 변환 실패로, 범위 위반은 `ManualPosition` 의 `init { require(...) }` 로 기동을 실패시킨다.
  `require` 메시지는 **property key 와 규칙만** 담는다(값은 담지 않는다). 예: `account-pnl-monitor.binance.manual.short-quantity must be >= 0`.
- 빈 문자열 env 는 `BigDecimal?` 로 `null` 이 된다(= 미입력).
- **manual 값은 `api` 모드에서도 바인딩·검증된다** — Properties 는 모드와 무관하게 한 번 바인딩되기 때문이다. `api` 모드에서
  manual 값은 쓰지 않지만 형식이 틀리면 기동 실패다. *(내 판단 — 조용히 무시하는 것보다 낫다. runbook 에 명시)*
- 기본값의 정본: `mode` 는 yml `api` + 코드 `resolvedMode()` 의 `?: API`, taker 율은 코드 상수 `DEFAULT_TAKER_FEE_RATE = 0.0005`
  하나(yml 은 빈 placeholder).

### 3.2 Properties 타입 (전문)

`infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/accountpnl/AccountPnlMonitorProperties.kt`

```kotlin
@ConfigurationProperties(prefix = "account-pnl-monitor")
data class AccountPnlMonitorProperties(
    val enabled: Boolean = false,
    val recipient: String = "",
    val bithumb: BithumbCredentials = BithumbCredentials(),
    val binance: BinanceCredentials = BinanceCredentials(),
) {
    // toString 무변경

    data class BithumbCredentials(/* 무변경 */)

    data class BinanceCredentials(
        val baseUrl: String = "https://fapi.binance.com",
        val apiKey: String = "",
        val secretKey: String = "",
        val mode: BinanceLegMode? = null,                 // 신규
        val manual: ManualPosition = ManualPosition(),    // 신규
    ) {
        fun resolvedMode(): BinanceLegMode = mode ?: BinanceLegMode.API
        override fun toString(): String  // §3.4
    }

    enum class BinanceLegMode { API, MANUAL }

    data class ManualPosition(
        val shortQuantity: BigDecimal? = null,
        val entryPrice: BigDecimal? = null,
        val entryFeeUsdt: BigDecimal? = null,
        val fundingUsdt: BigDecimal? = null,
        val takerFeeRate: BigDecimal? = null,
    ) {
        init { /* §3.1 검증 — require, 메시지에 값 없음 */ }
        fun resolvedTakerFeeRate(): BigDecimal = takerFeeRate ?: DEFAULT_TAKER_FEE_RATE
        override fun toString(): String  // §3.4

        companion object {
            val DEFAULT_TAKER_FEE_RATE: BigDecimal = BigDecimal("0.0005")
        }
    }
}
```

`BinanceCredentials` 라는 이름은 이제 credential 만 담지 않지만 **바꾸지 않는다** — 바인딩 키(`binance`)와 무관하고, 기존
wiring 테스트·문서가 참조한다. *(내 판단)*

### 3.3 기동 조건 (모드별 필수 키)

| 모드 | 필수 (비어 있지 않음) |
|---|---|
| 공통 | `account-pnl-monitor.enabled=true`(대소문자 무관), `recipient`, `bithumb.access-key`, `bithumb.secret-key`, `notification.email.enabled=true`(app) / `EmailSender` bean(infra), scheduling 활성 |
| `api` | 공통 + `binance.api-key`, `binance.secret-key` |
| `manual` | 공통 + `binance.manual.short-quantity`, `binance.manual.entry-price` (바이낸스 key 불필요) |

모드 해석 규칙(app·infra 동일): `binance.mode` 가 없거나 공백이면 `api`, `api`/`manual`(대소문자 무관)이면 그 모드,
그 밖이면 `IllegalStateException("account-pnl-monitor.binance.mode must be api or manual")` — **값은 메시지에 넣지 않는다.**
모드 해석은 `enabled=true` 일 때만 한다(꺼져 있으면 잘못된 mode 도 무시).

**두 조건의 키 목록 중복은 유지한다.** app `@Configuration` 은 auto-configuration 보다 먼저 평가되어 infra bean 에
`@ConditionalOnBean` 을 걸 수 없다(직전 단위 understanding §7 함정). 대신 wiring 테스트가 모드 × 누락 키 행렬에서 두 쪽의
결과가 같음을 고정한다(AC5).

| 쪽 | 클래스 | 변경 |
|---|---|---|
| infra | `AccountPnlCredentialsPresentCondition` (이름 유지) | 모드별 필수 키로 판정. 로그 없음 |
| infra | `BinanceApiModeCondition`, `BinanceManualModeCondition` (신규, 같은 파일) | 바이낸스 adapter bean 선택 |
| infra | `internal object AccountPnlMonitorRequirements` (신규, 같은 파일) | `const val MODE_KEY`, `fun enabled(env: PropertyResolver): Boolean`, `fun binanceMode(env: PropertyResolver): BinanceLegMode`, `fun missingKeys(env: PropertyResolver): List<String>` — infra 세 조건이 공유 (전문은 plan Task 5) |
| app | `AccountPnlMonitorEnabledCondition` (이름 유지) | 모드별 필수 키 + env 이름 표를 자체 보유. 누락 WARN(§4) |

infra 구성(`AccountPnlMonitorAdapterConfiguration`) bean:

| bean | 조건 | 타입 |
|---|---|---|
| `bithumbPrivateAccountAdapter` | 구성 클래스 조건 | `BithumbPrivateAccountAdapter` (무변경) |
| `binanceFuturesPrivateAccountAdapter` | + `@Conditional(BinanceApiModeCondition::class)` | `BinanceFuturesPrivateAccountAdapter` |
| `binanceFuturesManualAccountAdapter` | + `@Conditional(BinanceManualModeCondition::class)` | `BinanceFuturesManualAccountAdapter` |
| `accountPnlMailSender` | 구성 클래스 조건 | 무변경 |

`binanceFuturesManualAccountAdapter` 는 `checkNotNull(manual.shortQuantity) { "account-pnl-monitor.binance.manual.short-quantity is required in manual mode" }`
(진입가도 같은 형식)로 값을 꺼낸다 — 조건이 non-blank 를 보장하고 바인딩이 형식을 보장하므로 정상 경로에서 실패하지 않는다.

### 3.4 `toString()` 이 노출하는 프로퍼티 (전문)

manual 값은 credential 이 아니지만 **계좌 포지션 크기·가격이라 로그·예외에 남기지 않는다**. 출력은 존재 여부만이다.
*(내 판단 — 보수적 선택)* 기존 마스킹(`mask()` → `***`/`<empty>`)은 그대로다.

| 타입 | `toString()` 전문 |
|---|---|
| `AccountPnlMonitorProperties` | `AccountPnlMonitorProperties(enabled=$enabled, recipient=${mask(recipient)}, bithumb=$bithumb, binance=$binance)` — **무변경** |
| `BithumbCredentials` | **무변경** |
| `BinanceCredentials` | `BinanceCredentials(baseUrl=$baseUrl, apiKey=${mask(apiKey)}, secretKey=${mask(secretKey)}, mode=${resolvedMode()}, manual=$manual)` |
| `ManualPosition` | `ManualPosition(shortQuantity=${presence(shortQuantity)}, entryPrice=${presence(entryPrice)}, entryFeeUsdt=${presence(entryFeeUsdt)}, fundingUsdt=${presence(fundingUsdt)}, takerFeeRate=${takerFeeRate?.let { "set" } ?: "default"})` — `presence(x) = if (x == null) "<empty>" else "set"` |

`ManualPosition` 의 자동 생성 `toString()` 은 숫자 값을 그대로 노출하므로 **반드시 override** 한다. `BinanceCredentials` 의
override 에 `manual` 을 추가할 때 `$manual` 은 위 override 를 거친다.

### 3.5 application.yml 블록 (전문)

```yaml
account-pnl-monitor:
  enabled: ${ACCOUNT_PNL_MONITOR_ENABLED:false}
  recipient: ${ACCOUNT_PNL_ALERT_RECIPIENT:}
  bithumb:
    access-key: ${BITHUMB_ACCESS_KEY:}
    secret-key: ${BITHUMB_SECRET_KEY:}
  binance:
    mode: ${BINANCE_MODE:api}
    api-key: ${BINANCE_API_KEY:}
    secret-key: ${BINANCE_SECRET_KEY:}
    manual:
      short-quantity: ${BINANCE_MANUAL_SHORT_QUANTITY:}
      entry-price: ${BINANCE_MANUAL_ENTRY_PRICE:}
      entry-fee-usdt: ${BINANCE_MANUAL_ENTRY_FEE_USDT:}
      funding-usdt: ${BINANCE_MANUAL_FUNDING_USDT:}
      taker-fee-rate: ${BINANCE_MANUAL_TAKER_FEE_RATE:}
```

## 4. 로그 계약

| 로그 | 레벨 | 발생 위치 | 담는 키 | 담지 말 값 |
|---|---|---|---|---|
| `account-pnl-monitor enabled but required settings missing; monitor not started: binanceMode={api\|manual}, missing=[ENV_NAME, ...]` | WARN | app `AccountPnlMonitorEnabledCondition`, `metadata.className == AccountPnlMonitorConfiguration` 일 때만 (기존 email WARN 과 같은 가드) | 해석된 모드, 비어 있는 **env 이름** 목록(§3.1 순서: 공통 → 모드별) | 어떤 설정 값도 — key·secret·수신 주소·수량·가격 |
| `account-pnl-monitor enabled but email disabled; monitor not started` | WARN | 기존 | 기존 | 기존 — **문구 무변경**. 필수값이 다 있을 때만 이 줄로 간다 |
| `account-pnl-monitor binance leg: mode=manual, entryFeeUsdt={set\|unset}, fundingUsdt={set\|unset}, takerFeeRate={set\|default}` | INFO | infra `binanceFuturesManualAccountAdapter` bean 생성 시 1회 | 모드, 선택 입력의 존재 여부 | 수량·진입가·수수료·펀딩·taker 율 값 |
| 기존 Job 로그 (`Account PnL observation failed: category=`, `No account hedge position: {reason}` 등) | 기존 | 기존 | 기존 | 기존 — 무변경 |

- WARN 은 한 줄에 누락 이름을 모두 담는다(키마다 한 줄이 아니다). 조건 평가 단계가 여럿이면 기존 email WARN 처럼 같은 줄이 반복될
  수 있다 — 횟수는 계약이 아니다.
- 누락 WARN 은 **`api` 모드에도 적용된다**(직전 단위에서는 key 누락 시 조용히 꺼졌다). 결과·메일이 아니라 로그만 바뀐다.
  *(내 판단 — 같은 조건 코드를 두 모드가 공유하므로 한쪽만 경고하는 것이 더 이상하다)*
- env 이름을 쓰는 이유: owner 가 고치는 대상이 env 다. property key ↔ env 대응은 §3.1·runbook 이 정본이다.

## 5. Domain 변경

### 5.1 Snapshot 시그니처 (전문)

`domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountSnapshots.kt`

```kotlin
/** 바이낸스 leg 의 포지션·수수료·펀딩·taker 율이 어디서 왔는가. 호가·최근 체결가는 두 경우 모두 거래소 공개 API 다. */
enum class ForeignPerpPositionSource {
    EXCHANGE_API,
    MANUAL_ENV,
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
    val positionSource: ForeignPerpPositionSource = ForeignPerpPositionSource.EXCHANGE_API,  // 신규, 마지막
)
```

- **마지막 파라미터 + 기본값 `EXCHANGE_API`**: 기존 생성 지점(api adapter, 직전 단위 테스트 전부)이 손대지 않고 컴파일되며
  `api` 모드 의미가 그대로다. manual adapter 만 `MANUAL_ENV` 를 명시한다. *(내 판단)*
- `entryCommissionUsdt`·`fundingUsdt` 의 `null` 의미는 출처별로 다르다 — `EXCHANGE_API` 면 "이력 미확보", `MANUAL_ENV` 면
  "owner 미입력". 계산(`AccountNetPnl`)은 둘을 구분하지 않는다(0 + `*Unrecovered=true`). 구분은 메일만 한다.
- `AccountNetPnl`, `AccountHedgePosition`, `PnlAlertPolicy`, `MonitoringOutagePolicy`, `AccountPnlAlertStore` 시그니처 **무변경**.

### 5.2 왜 Snapshot 에 싣나

메일(Domain 순수함수)이 출처를 알아야 한다. 대안은 (a) Job 이 설정을 읽어 메일 함수에 모드를 넘기는 것, (b) 메일 함수가 별도
인자를 받는 것이다. (a)는 app 이 infra 설정을 알아야 하고, (b)는 Snapshot 과 모드가 어긋날 수 있다(설정은 manual 인데 api
Snapshot). 값의 출처는 값을 만든 adapter 가 가장 정확히 안다 → Snapshot 이 운반한다.

### 5.3 fingerprint 와 에피소드

`AccountHedgePosition.fingerprint` 공식(양 leg 수량·평단/진입가)은 **무변경**이고 출처를 포함하지 않는다.

- manual 모드에서 owner 가 수량·진입가 env 를 바꾸고 재시작하면 fingerprint 가 바뀌어 기존 정책대로 새 에피소드다.
- 진입 수수료·펀딩·taker 율만 바꾸면 fingerprint 는 같다 → 에피소드 유지(재발송 타이머 유지). 이는 api 모드에서 펀딩이 쌓일 때와
  같은 동작이다.
- 모드만 바꾸고(api ↔ manual) 수량·진입가가 같으면 에피소드가 유지된다. *(내 판단 — 같은 포지션이므로 맞다)*
- env 의 `0.2660` 과 `0.266` 은 `stripTrailingZeros` 로 같은 fingerprint 다.

### 5.4 메일 문구 (exact copy)

제목 형식·중단 메일·복구 메일은 **무변경**. 수익 메일 본문은 `foreign.positionSource == MANUAL_ENV` 일 때만 아래 세 곳이
바뀐다. `EXCHANGE_API` 면 현재 코드와 **바이트 단위로 같다**.

| 위치 | `EXCHANGE_API` (현재, 무변경) | `MANUAL_ENV` |
|---|---|---|
| 바이낸스 진입 수수료 — 미입력/미확보 | `  - 바이낸스: 진입 수수료(바이낸스) 미반영(이력 없음)` | `  - 바이낸스: 진입 수수료(바이낸스) 미반영(수동 미입력)` |
| 바이낸스 진입 수수료 — 값 있음 | `  - 바이낸스: -8.9 USDT (-11,824원)` | `  - 바이낸스: -8.9 USDT (-11,824원) 수동 입력` |
| 누적 펀딩비 — 미입력/미확보 | `누적 펀딩비: 0원  펀딩비 미반영(이력 없음)` | `누적 펀딩비: 0원  펀딩비 미반영(수동 미입력)` |
| 누적 펀딩비 — 값 있음 | `누적 펀딩비: +4,251원 (+3.2 USDT)` | `누적 펀딩비: +4,251원 (+3.2 USDT) 수동 입력` |
| `[바이낸스 선물 숏]` 바로 다음 줄 | (없음) | `바이낸스 포지션: 수동 입력(env)` |

- 위 숫자는 형식 예시다(기존 `AccountPnlMailContentTest` fixture: commission 8.9, funding 3.2, fx 1328.5). 금액 포맷 함수는 기존
  `costUsdt`·`cost`·`krwOrZero`·`signedUsdt` 그대로이고 접미 ` 수동 입력`(앞에 공백 1칸)만 붙는다.
- 빗썸 진입 수수료 줄(`  - 빗썸: 진입 수수료(빗썸) 미반영(이력 없음)`)은 출처와 무관하게 **무변경** — 빗썸은 항상 API 다.
- 바이낸스 수량·진입가 줄의 라벨은 그대로다. 수동 입력임은 `바이낸스 포지션: 수동 입력(env)` 한 줄로 표기한다.

## 6. Flyway 불필요 근거

- 영속 상태(`account_pnl_monitor_state`)는 fingerprint 문자열·에피소드·시각만 저장한다. 출처는 저장하지 않으며 저장할 필요가 없다
  — 출처는 매 회차 Snapshot 이 다시 운반한다.
- outbox(`account_pnl_mail`)는 완성된 제목·본문을 저장한다. 문구 변경은 본문 문자열만 바뀌며 스키마 영향이 없다.
- 따라서 `infrastructure/common/src/main/resources/db/migration/` 에 파일을 추가하지 않는다. 회귀 방어선 R5 가 이를 확인한다.

## 7. manual 모드 동작과 한계

| 항목 | 동작 |
|---|---|
| 회차마다 | 공개 `GET /fapi/v2/ticker/price?symbol=BTCUSDT` → 최근 체결가, 그 다음 **마지막에** `GET /fapi/v1/depth?symbol=BTCUSDT&limit=20` → ask 레벨(가격 오름차순) + `T` |
| 포지션 | `signedQuantity = shortQuantity.negate()` (env 는 양수), `entryPrice` = env |
| 진입 수수료 | env `entry-fee-usdt` 그대로(USDT). BNB 로 냈다면 owner 가 USDT 로 환산해 넣는다. 미입력 = `null` |
| 펀딩 | env `funding-usdt` 그대로. **자동 누적되지 않는다** — owner 가 갱신하고 재시작해야 반영된다. 미입력 = `null` |
| taker 율 | env 또는 기본 `0.0005` |
| 수량 0 | Snapshot 은 정상으로 내고(호가도 조회), Domain `resolve` 가 포지션 없음으로 판정 → 기존 `foreign-flat` 경로 |
| 서명·API key 헤더·서버 시각 측정 | **없음** |
| 상태 | 캐시 없음(무상태). 같은 입력·같은 응답이면 같은 Snapshot |
| 실패 | 공개 호출 실패는 기존 매핑(`429`·`418`·`5xx`·연결·타임아웃 → `NETWORK`, 해석 실패·그 밖 4xx → `UNEXPECTED`). `BINANCE_AUTH` 는 사실상 나타나지 않는다 |
| 포지션 변경 | env 수정 + batch 재시작. 수정하지 않으면 **틀린 수량·진입가로 계산하고 틀린 알림이 갈 수 있다** — runbook 에 굵게 적는다 |

## 8. 모듈 배치

| 파일 | 모듈·패키지 | 변경 |
|---|---|---|
| `AccountSnapshots.kt` | `domain` `accountpnl` | `ForeignPerpPositionSource` 추가, Snapshot 필드 추가 |
| `AccountPnlMailContent.kt` | `domain` `accountpnl` | 출처별 문구 |
| `BinanceFuturesReadClient.kt` (신규) | `infrastructure:batch` `exchange.binance` | GET 호출·실패 정규화·JSON 숫자 해석·공개 depth/price 조회 (전문 §8.1) |
| `BinanceFuturesPrivateAccountAdapter.kt` | 같음 | 자체 `send`·`mapHttpError`·`readDepth`·`readPrice`·JSON 헬퍼를 지우고 공유 클래스 사용. **공개 생성자 시그니처 무변경** |
| `BinanceFuturesManualAccountAdapter.kt` (신규) | 같음 | manual 모드 port 구현 |
| `AccountPnlMonitorProperties.kt` | `infrastructure:batch` `accountpnl` | §3.2 |
| `AccountPnlMonitorAdapterConfiguration.kt` | 같음 | §3.3 조건·bean |
| `AccountPnlMonitorConfiguration.kt` | `apps:batch` `config` | 앱 조건 모드화 + WARN |
| `AccountPnlMonitorScheduler.kt` | `apps:batch` `interfaces.scheduling` | 무변경 (같은 앱 조건 사용) |
| `application.yml` | `apps:batch` | §3.5 |
| `PrivateAccountReadOnlyArchitectureTest.kt` | `architecture-tests` | §8.2 |
| `account-pnl-alert.md` | `docs/runbooks` | §7·§3 반영 |

앱에 기술 구현 패키지를 추가하지 않는다. 앱은 infra 타입을 import 하지 않는다(조건은 환경 키만 읽는다). Domain 에 새 의존성 없음.

### 8.1 공유 클래스 (전문)

공개 호가·체결가 조회와 GET 실패 정규화를 **한 곳**에 둔다. 서명은 하지 않는다 — 서명 query 는 private adapter 가 만들어 넘긴다.

```kotlin
// infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceFuturesReadClient.kt

/** 바이낸스 USDⓈ-M REST GET 과 실패 정규화. 모든 실패는 AccountReadException 이다(아래 TimestampRejected 예외 제외). */
internal class BinanceFuturesReadClient(
    private val webClient: WebClient,
    private val timeout: Duration = Duration.ofSeconds(5),
) {
    /**
     * [query] 를 그대로 붙여 GET 한다. [apiKey] 가 null 이 아니면 `X-MBX-APIKEY` 헤더를 붙인다.
     * HTTP 오류 코드 `-1021` 은 [apiKey] 가 있을 때만 [BinanceTimestampRejectedException] 으로, 없으면 일반 매핑으로 간다.
     */
    fun get(path: String, query: String, apiKey: String? = null): JsonNode

    /** 공개 `GET /fapi/v1/depth?symbol=$symbol&limit=20` — ask 레벨 가격 오름차순, `T` → observedAt. */
    fun askDepth(symbol: String): BinanceAskDepth

    /** 공개 `GET /fapi/v2/ticker/price?symbol=$symbol` 의 `price` (양수). */
    fun price(symbol: String): BigDecimal
}

internal data class BinanceAskDepth(val levels: List<PriceLevel>, val observedAt: Instant)

internal class BinanceTimestampRejectedException(cause: Throwable) : RuntimeException(cause)

internal fun JsonNode.requireDecimal(field: String): BigDecimal   // 기존 private `dec`
internal fun JsonNode.toDecimal(name: String): BigDecimal         // 기존 private `decimal`
internal fun binanceUnexpected(message: String): Nothing          // AccountReadException(UNEXPECTED, message)
```

- HTTP 상태·코드 → 범주 매핑은 기존 `mapHttpError` 그대로 이동한다: `401` 또는 코드 `-2014`·`-2015`·`-1022` → `BINANCE_AUTH`,
  `429`·`418`·`>=500` → `NETWORK`, 그 밖 → `UNEXPECTED`. `WebClientRequestException`·타임아웃 → `NETWORK`, 빈 본문·깨진 JSON → `UNEXPECTED`.
  예외 메시지는 기존처럼 `Binance $path ...` 형식이며 query·헤더 값을 담지 않는다.
- private adapter 는 `signedGet` 의 재시도 신호를 private `TimestampRejected` 대신 `BinanceTimestampRejectedException` 으로 받는다.
  서버 시각 측정은 `client.get("/fapi/v1/time", "")` 으로 한다.
- `/fapi/v1/depth`·`/fapi/v2/ticker/price` 문자열은 이 파일에만 남는다(AC4 중복 검사).

manual adapter (전문):

```kotlin
// .../exchange/binance/BinanceFuturesManualAccountAdapter.kt

/** owner 가 env 로 입력한 바이낸스 숏 포지션 + 공개 호가·체결가. 서명·API key 를 쓰지 않는다. */
class BinanceFuturesManualAccountAdapter(
    webClient: WebClient,
    private val shortQuantity: BigDecimal,       // >= 0
    private val entryPrice: BigDecimal,          // > 0
    private val entryFeeUsdt: BigDecimal?,
    private val fundingUsdt: BigDecimal?,
    private val takerFeeRate: BigDecimal,
    timeout: Duration = Duration.ofSeconds(5),
) : ForeignPerpAccountReadPort {
    override fun read(): ForeignPerpAccountSnapshot  // positionSource = MANUAL_ENV
}
```

`BinanceFuturesPrivateAccountAdapter` 공개 생성자 (무변경, 확인용):
`(webClient: WebClient, apiKey: String, secretKey: String, clock: Clock, recvWindowMillis: Long = 10_000, timeout: Duration = Duration.ofSeconds(5))`.

### 8.2 조회 전용 아키텍처 테스트

| 항목 | 현재 | 변경 |
|---|---|---|
| 대상 판정 | 파일명 `*PrivateAccountAdapter.kt`, `/src/main/kotlin/` | 경로에 `/infrastructure/batch/src/main/kotlin/` 와 `/exchange/` 를 포함하고 파일명이 `AccountAdapter.kt` 또는 `ReadClient.kt` 로 끝나는 production 소스 |
| 자기검증 (대상) | 2개 미만이면 실패 | 대상 파일명 집합이 `BithumbPrivateAccountAdapter.kt`, `BinanceFuturesPrivateAccountAdapter.kt`, `BinanceFuturesManualAccountAdapter.kt`, `BinanceFuturesReadClient.kt` 를 **모두 포함**하지 않으면 실패 (이름이 바뀌거나 스캐너가 놓치면 빨강) |
| 금지 규칙 | 20개 | 무변경 |
| 신규 테스트 | — | `수동 모드 adapter 는 서명·API key·서버 시각을 쓰지 않는다` — 대상 `*ManualAccountAdapter.kt`(1개 이상 필수), 금지: `BinanceRequestSigner`, `X-MBX-APIKEY`, `signature`, `/fapi/v1/time`, `apiKey`, `secretKey`, `recvWindow` (7개, 이름·위반 표본 포함 자기검증) |

`ReadClient` 를 대상에 넣는 이유: 추출 후 실제 `webClient.get()` 호출이 그 파일로 옮겨 간다. adapter 파일만 보면 HTTP 호출이
검사 밖으로 빠진다.

## 9. 회귀 경계

| 지켜야 할 것 | 방어 |
|---|---|
| `api` 모드 수익 메일 제목·본문 바이트 불변 | 이 단위 첫 태스크에서 현재 코드로 만든 **golden 특성화 테스트**(전체 문자열 `isEqualTo`)를 먼저 커밋하고, 이후 모든 태스크에서 초록 |
| `api` 모드 바이낸스 adapter 동작 불변 | `BinanceFuturesPrivateAccountAdapterTest` **파일 무수정**(`git diff --exit-code 0a04546 -- <파일>`) + 통과 |
| `api` 모드 배선 불변 | 기존 `AccountPnlMonitorWiringTest` 케이스 전부 유지·통과 (헬퍼 `assertNoBeans` 에 manual adapter 단언만 추가) |
| 계산·정책·저장 불변 | 직전 단위 AC1~AC6 테스트 클래스 무수정 통과 |
| 조회 전용 | 기존 금지 규칙 20개 유지, 대상 확장 |
| 거래 준비 AC20 | R2 `TradePreparationWiring*` |
| migration 무변경 | R5 |

## 10. 테스트 전략

| 계층 | 테스트 | 무엇을 |
|---|---|---|
| Domain | `AccountPnlMailContentTest` (확장) | api golden(특성화), manual 문구 5곳, 빗썸 줄 불변, 제목 불변 |
| Domain | `AccountHedgePositionTest` (확장) | 출처가 달라도 fingerprint 동일, Snapshot 기본 출처 `EXCHANGE_API` |
| adapter | `BinanceFuturesManualAccountAdapterTest` (신규, MockWebServer) | 값 매핑·출처·무서명·요청 순서·실패 매핑 |
| adapter | `BinanceFuturesPrivateAccountAdapterTest` (무수정) | 추출 리팩터링 회귀 |
| 배선 | `AccountPnlMonitorWiringTest` (확장, `ApplicationContextRunner`) | 모드별 bean, 누락 WARN, app↔infra 조건 일치 행렬, 바인딩 검증 실패, toString |
| 아키텍처 | `PrivateAccountReadOnlyArchitectureTest` (확장) | 대상 확장 자기검증, manual 무서명 규칙 |
| 실계정 | owner (T4) | manual 모드 로컬 실행 → 메일 수신 → 바이낸스 화면 대조 |

외부 연결은 MockWebServer 만. 실 거래소·실 SMTP·실 credential 금지. Job 은 port 만 보므로 Job 테스트는 바꾸지 않는다.

## 11. 결정 기록

| 결정 | 누가 |
|---|---|
| 바이낸스 leg 를 `mode=api`(기본)\|`manual` 로 선택 | 오케스트레이터 판단 |
| manual 입력: 숏 수량(양수, 0 = 포지션 없음)·진입가 필수, 진입 수수료·펀딩·taker 율(기본 0.0005) 선택 | 오케스트레이터 판단 |
| manual 의 호가·체결가는 기존과 같은 공개 endpoint, 서명·서버 시각·API key 없음, 공개 조회 코드는 api adapter 와 공유 | 오케스트레이터 판단 |
| 모드별 기동 조건, 누락 시 bean 0 + WARN(변수 이름만), 형식 오류·음수는 기동 실패 | 오케스트레이터 판단 |
| app·infra 조건 키 목록 중복 유지 + wiring 테스트로 모드별 일치 고정 | 오케스트레이터 판단 |
| 메일에 수동 입력·수동 미입력 출처 표기, 제목 형식 불변 | 오케스트레이터 판단 |
| 조회 전용 아키텍처 테스트에 manual adapter 포함 | 오케스트레이터 판단 |
| 포지션 변경은 env 수정 + 재시작, fingerprint 변경 시 새 에피소드 | 오케스트레이터 판단 |
| env 이름 `BINANCE_MODE`, `BINANCE_MANUAL_*` | 내 판단 (기존 `BINANCE_API_KEY` 관례) |
| 출처는 `ForeignPerpAccountSnapshot.positionSource`(마지막 파라미터, 기본 `EXCHANGE_API`) | 내 판단 (§5.2) |
| fingerprint 에 출처를 넣지 않음 (모드만 바꾸면 에피소드 유지) | 내 판단 (§5.3) |
| `mode`·`takerFeeRate` 를 nullable + `resolved*()` 로 — 빈 env 를 기본값으로 확정적으로 해석 | 내 판단 |
| 알 수 없는 mode 는 조건 평가에서 기동 실패(값은 메시지에 없음) | 내 판단 — 조용히 꺼지면 owner 가 원인을 못 찾는다 |
| 진입가 `> 0`, taker 율 `0 <= x < 1`, 진입 수수료 `>= 0`, 펀딩은 부호 자유 | 내 판단 (요구 "음수면 실패"의 구체화. 펀딩은 지급이 음수라 예외) |
| manual 값은 api 모드에서도 형식 검증 | 내 판단 (§3.1) |
| manual 값은 `toString`·로그에 존재 여부만 | 내 판단 (§3.4) |
| 누락 WARN 은 api 모드에도 적용, env 이름으로 표기 | 내 판단 (§4) |
| 수량 0 이어도 호가를 조회 (api adapter 와 같은 동작) | 내 판단 |
| 공유 클래스는 Domain port 가 아니라 infrastructure 내부 `internal` 클래스 | 내 판단 — capability 는 이미 `ForeignPerpAccountReadPort` 가 표현한다. 대체 구현이 없는 내부 협력자라 interface 를 만들지 않는다 |
| `BinanceCredentials` 이름 유지 | 내 판단 (§3.2) |
| api 메일 불변을 golden 특성화 테스트로 먼저 고정 | 내 판단 (기존 테스트는 `contains` 단언이라 바이트 불변을 증명하지 못한다) |

`[ASSUMPTION]` 목록:

- `[ASSUMPTION]` Spring Boot 바인딩은 빈 문자열을 `BigDecimal?`·enum `?` 에 `null` 로 넣는다(`StringToNumberConverterFactory`·
  `LenientStringToEnumConverterFactory` 의 빈 문자열 처리). 깨지면 AC5 의 "빈 값" 케이스가 빨강으로 알려 준다.
- `[ASSUMPTION]` Kotlin `init { require }` 실패는 Spring 이 바인딩 예외로 감싸 context 기동을 실패시킨다. AC5 가 `hasFailed()` 로 잰다.
- `[ASSUMPTION]` 현재 `infrastructure/batch/src/main/kotlin` 에서 `/fapi/v1/depth`·`/fapi/v2/ticker/price` 문자열을 가진 파일은
  `BinanceFuturesPrivateAccountAdapter.kt` 하나다(WebSocket 수집은 stream endpoint 를 쓴다). plan Task 0 이 기준선으로 확인한다.
- `[ASSUMPTION]` `infrastructure/batch/src/main/kotlin/**/exchange/` 아래 `*AccountAdapter.kt`·`*ReadClient.kt` 는 이 단위가
  만드는 4개 외에 없다. 더 있으면 그 파일도 조회 전용 규칙을 받는다(의도된 확장). Task 0 기준선으로 확인한다.

`[DECISION_NEEDED]`: 없음.
