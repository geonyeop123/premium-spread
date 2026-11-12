# #138 구현 계획 (결정 수준)

설계: `design.md`, 계약: `dod.md`(FROZEN). 태스크는 순서대로 한다. 각 태스크는 테스트를 먼저 쓰고 기대한 assertion 실패(RED)를 확인한 뒤 구현한다. RED/GREEN 출력은 `dod.md` 증거 로그에 append 한다.

## Task 1: 도메인 값 타입 (AC8)

**파일**
- 수정 `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountSnapshots.kt`
- 신규 `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/ForeignPerpRiskMetricsTest.kt`

**공개 시그니처**
- `enum class ForeignPerpMarginType { CROSSED, ISOLATED }`
- `data class ForeignPerpPositionRisk(val liquidationPrice: BigDecimal?, val markPrice: BigDecimal, val isolatedMargin: BigDecimal, val isolatedWallet: BigDecimal, val maintMargin: BigDecimal, val positionInitialMargin: BigDecimal, val notional: BigDecimal)` — `init`: markPrice > 0, liquidationPrice null 또는 > 0, 증거금 4종 >= 0. `toString() = "ForeignPerpPositionRisk"`
- `data class ForeignPerpSymbolConfig(val leverage: Int, val marginType: ForeignPerpMarginType)` — `init`: leverage >= 1. `toString`은 `marginType`만 노출해도 되나 leverage 는 노출하지 않는다
- `ForeignPerpAccountSnapshot`에 `val positionRisk: ForeignPerpPositionRisk? = null`, `val symbolConfig: ForeignPerpSymbolConfig? = null` 을 **마지막 파라미터로** 추가(기존 호출부 무변경)

**테스트** `ForeignPerpRiskMetricsTest`
- `마크가가 0 이하이면 거부한다`, `청산가는 null 이거나 양수다`, `증거금 4종이 음수이면 거부한다`(각 필드별), `notional 은 음수를 허용한다`, `레버리지가 1 미만이면 거부한다`, `toString 에 금액이 없다`

**검증** `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.ForeignPerpRiskMetricsTest --offline`

## Task 2: 어댑터 (AC1~AC7)

**파일**
- 수정 `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceFuturesPrivateAccountAdapter.kt`
- 수정 `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/AccountBalanceLog.kt` — `BalanceItem`에 `BINANCE_POSITION_RISK`, `BINANCE_SYMBOL_CONFIG`; `BalanceUnavailableReason`에 `UNKNOWN`
- 수정 `infrastructure/batch/src/test/kotlin/.../binance/BinanceFuturesPrivateAccountAdapterTest.kt`
- 신규 픽스처 `infrastructure/batch/src/test/resources/fixtures/binance-private/symbol-config.json` (`[{"symbol":"BTCUSDT","marginType":"ISOLATED","isAutoAddMargin":"false","leverage":5,"maxNotionalValue":"1000000"}]`), 기존 `position-risk*.json`과 테스트 헬퍼 `positionRisk(...)` 표본에 7개 위험 필드를 문자열로 추가

**결정**
- `Position` 내부 타입에 `risk: ForeignPerpPositionRisk?`를 싣는다. `readPosition()`은 필수 필드 해석 후, 수량 ≠ 0 일 때만 위험 필드를 별도 해석한다. 해석 실패(누락·비숫자·도메인 `init` 위반)는 `AccountBalanceLog.unavailable(BINANCE_POSITION_RISK, MISSING|NOT_NUMBER|NEGATIVE)` 후 null. `liquidationPrice` 가 0 이면 null(청산가 없음), 음수면 NEGATIVE 로 묶음 전체 null.
- `read()` 순서: position → history → fee → **symbolConfig(수량 ≠ 0 일 때만)** → balance → lastPrice → depth.
- `readSymbolConfig()`: `readBalance()`와 같은 형태. `signed.send("/fapi/v1/symbolConfig", "symbol=$SYMBOL", BALANCE_TIMEOUT)`, `BinanceTimestampRejectedException` → NETWORK `AccountReadException` 으로 바꿔 흡수, 취소면 rethrow, 해석은 예외 없이 sealed 결과(`Ok`/`Unavailable(reason)`)로. 응답이 배열이 아니거나 BTCUSDT 행이 없으면 MISSING, leverage 가 정수가 아니면 NOT_NUMBER, 1 미만이면 NOT_POSITIVE, marginType 이 CROSSED/ISOLATED 가 아니면 UNKNOWN.
- 경로 문자열 `"/fapi/v1/symbolConfig"`는 이 파일에 리터럴로 둔다(아키텍처 테스트가 찾는다).

**테스트(신규, 이름 고정 — dod 와 일치)**
- `positionRisk 위험 지표를 숏 롱 hedge 에서 읽는다` — 세 표본 각각 7개 값 `isEqualByComparingTo`
- `청산가 0 이면 청산가만 없음이다`
- `symbolConfig 레버리지와 마진 모드를 서명 GET 한 건으로 읽는다` — ISOLATED/5 와 CROSSED/20, 요청 1건·GET·X-MBX-APIKEY·signature, depth 마지막
- `포지션이 없으면 위험 지표가 없고 symbolConfig 를 부르지 않는다` — `[]`와 `positionAmt=0`
- `symbolConfig 실패는 그 항목만 null 로 흡수하고 사유만 WARN 한다` — 401·503·깨진 JSON·-1021(요청 1건, `/fapi/v1/time` 재측정 없음)·NO_RESPONSE·행 없음·leverage 비숫자·marginType 미지. 각각 다른 필드가 정상 케이스와 같음, 로그에 `item=BINANCE_SYMBOL_CONFIG`와 reason, `assertSecretsAbsent` + 레버리지 값 미노출
- `symbolConfig 요청 중 취소되면 흡수하지 않고 던진다`
- `positionRisk 위험 필드 해석 실패는 그 항목만 null 로 흡수한다` — markPrice 누락, maintMargin 비숫자, isolatedMargin 음수

**검증** `./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapterTest --offline`

## Task 3: 아키텍처 테스트 (AC9)

**파일** 수정 `architecture-tests/src/architectureTest/kotlin/io/premiumspread/architecture/PrivateAccountReadOnlyArchitectureTest.kt`

**결정** `REQUIRED_READ_PATHS = setOf("/fapi/v3/positionRisk", "/fapi/v1/symbolConfig")`. 새 테스트 `위험 지표 읽기 경로는 GET 전용 스캔 대상 안에 있다`: 각 경로가 `targetSources()` 중 하나의 원문에 `"<path>"`(따옴표 포함)로 나타나는지 확인하고, 없는 경로 목록을 메시지에 담아 실패한다. T2 전에 쓰면 symbolConfig 미존재로 RED 가 난다(T3 을 T2 보다 먼저 써서 RED 확보).

**검증** `./gradlew architectureTest --offline`

## 최종 검증

`./gradlew test architectureTest --parallel --offline --no-daemon` (R4), `./gradlew :apps:batch:test --offline` (R3).
