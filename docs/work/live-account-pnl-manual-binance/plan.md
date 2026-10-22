# 실계정 순손익 알림 — 바이낸스 수동 입력 모드 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 바이낸스 API key 없이도 실계정 순손익 알림을 받도록, 바이낸스 leg 를 `mode=api`(기존) | `manual`(env 입력 + 공개 호가) 로 선택해 구동한다.

**Architecture:** Domain `ForeignPerpAccountSnapshot` 에 출처(`positionSource`)를 싣고 메일만 출처별 문구를 낸다. `infrastructure:batch`
에서 바이낸스 공개 호가·체결가 조회와 GET 실패 정규화를 `BinanceFuturesReadClient` 로 추출해 기존 private adapter 와 새
`BinanceFuturesManualAccountAdapter` 가 공유한다. 두 adapter 는 같은 `ForeignPerpAccountReadPort` 를 구현하고 모드 조건으로 정확히
하나만 bean 이 된다. 신규 설정은 `String?` 으로 바인딩해 Properties 안에서 직접 해석·검증한다. Job·정책·outbox·Flyway 는 무변경.

**Tech Stack:** Kotlin 2.0, Spring Boot 3.5 (`@ConfigurationProperties` constructor binding, `Condition`), WebFlux `WebClient`, Jackson,
JUnit5 + AssertJ, okhttp3 MockWebServer, `ApplicationContextRunner` + `OutputCaptureExtension`.

**Spec:** `docs/work/live-account-pnl-manual-binance/design.md` · **DoD:** `docs/work/live-account-pnl-manual-binance/dod.md`

## Global Constraints

- base `0a04546`. 직전 단위 `docs/work/live-account-pnl-alert/*` 는 수정하지 않는다.
- `api` 모드 결과·메일은 바이트 불변 (Task 1 golden 이 모든 후속 태스크에서 초록이어야 한다).
- `BinanceFuturesPrivateAccountAdapterTest`·`AccountNetPnlTest`·`PnlAlertPolicyTest`·`MonitoringOutagePolicyTest`·`AccountPnlMonitorJobTest`·
  `BithumbPrivateAccountAdapterTest` 는 **수정 금지** (dod R8a). 확장하는 기존 테스트 클래스의 기존 fixture·메서드도 수정하지 않는다
  (예외는 dod R9 의 허용 목록).
- manual adapter 는 GET 만, 서명·`X-MBX-APIKEY`·`/fapi/v1/time`·`recvWindow` 없음. 주문·출금·이체 경로 없음.
- 설정 값(key·secret·수신 주소·manual 수량·가격·수수료·펀딩·taker 율·mode 원문)을 로그·예외 메시지·예외 원인 체인·`toString()` 에 남기지
  않는다. 신규 설정은 **`String?` 으로 바인딩해 직접 해석**하고(design §3.1 옵션 (b)), 해석 실패 예외는 property key 와 규칙만 담으며
  `cause` 를 연결하지 않는다. 누락 WARN 은 env 이름만. (예외: 바인딩 실패 시 Spring Boot 기동 실패 리포트(`BindFailureAnalyzer`)가 찍는 property 값 한 줄은 우리 코드 밖 잔여 노출로 수용 — design §3.1)
- Flyway·Redis·Job·`JobConfig`·`PublicEndpointPolicy` 무변경.
- Domain 에 Spring/HTTP/Jackson 의존 추가 금지. 앱(`apps:batch`)은 infrastructure 타입을 import 하지 않는다.
- 테스트는 실 거래소·실 SMTP·실 credential 금지. MockWebServer·fixture·고정 `Clock`.
- 컴파일 실패를 RED 로 쓰지 않는다. RED 는 stub 또는 기존 코드에서의 assertion 실패다.
- 커밋: `<type>: <subject>` + 한글 bullet, `Co-Authored-By` 금지. 모든 gradle 명령은 `--offline --no-daemon`.

## Review Focus

1. **api 모드 회귀** — 추출 리팩터링(Task 3)이 오류 매핑·`-1021` 재시도·depth 를 마지막에 읽는 순서·`timeout`·BigDecimal 정밀도를 바꾸지 않는가.
   무수정 테스트가 지킨다. 유일한 의도된 차이는 비서명 `-1021` 정규화(design §8.1, 관측 범주 불변).
2. **조건 이중화** — app 조건과 infra 조건이 모드·누락 키·대소문자·빈 값에서 같은 답을 내는가 (Task 5 행렬 테스트).
3. **빈 env 해석** — `BINANCE_MODE=`·`BINANCE_MANUAL_*=` 처럼 빈 값이 "미입력"으로 해석되는가. `String?` 직접 해석이라 Spring 변환기에
   의존하지 않는다 (Task 5 빈 값 케이스).
4. **값 누출** — `ManualPosition`·`BinanceCredentials` 의 자동 `toString()`(바인딩 타입이 `String?` 이라 원문을 찍는다), 해석 실패 예외의
   메시지와 **원인 체인**(`NumberFormatException` 메시지는 입력 문자를 담는다 — cause 미연결), 누락 WARN 에 입력 값이 새지 않는가.
   실제 `bootRun` 의 `BindFailureAnalyzer` 리포트는 잔여 노출로 수용(design §3.1).
5. **arch 대상 공백** — HTTP 호출이 `ReadClient` 로 옮겨 간 뒤 조회 전용 검사가 그 파일을 보는가, 기존 대상이 좁아지지 않는가 (Task 7).

---

## File Structure

| 파일 | 책임 | 태스크 |
|---|---|---|
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountSnapshots.kt` | `ForeignPerpPositionSource`, Snapshot 필드 | 2 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlMailContent.kt` | 출처별 문구 | 6 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceFuturesReadClient.kt` (신규) | GET·실패 정규화·공개 depth/price·`ObjectMapper` | 3 |
| `.../exchange/binance/BinanceFuturesPrivateAccountAdapter.kt` | 공유 클래스 사용으로 리팩터링 | 3 |
| `.../exchange/binance/BinanceFuturesManualAccountAdapter.kt` (신규) | manual port 구현 | 4 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/accountpnl/AccountPnlMonitorProperties.kt` | mode·manual `String?` 바인딩·해석·검증·toString | 5 |
| `.../accountpnl/AccountPnlMonitorAdapterConfiguration.kt` | 모드 조건·bean | 5 |
| `apps/batch/src/main/kotlin/io/premiumspread/config/AccountPnlMonitorConfiguration.kt` | 앱 조건 모드화·누락 WARN | 5 |
| `apps/batch/src/main/resources/application.yml` | env placeholder | 5 |
| `architecture-tests/src/architectureTest/kotlin/io/premiumspread/architecture/PrivateAccountReadOnlyArchitectureTest.kt` | 대상 합집합·무서명 규칙 | 7 |
| `docs/runbooks/account-pnl-alert.md` | 모드·manual env·한계 | 8 |
| tests: `AccountPnlMailContentTest`·`AccountHedgePositionTest`(domain), `BinanceFuturesManualAccountAdapterTest`(infra, 신규), `AccountPnlMonitorWiringTest`(apps:batch) | | 1·2·4·5·6 |

---

### Task 0: Gate 1 기준선

**Files:** 없음 (dod `## 증거 로그` 에 append)

- [ ] **Step 1:** 기준선 실행·기록.
  ```bash
  ./gradlew architectureTest --offline --no-daemon                                         # R1
  ./gradlew :apps:api:test --tests '*TradePreparationWiring*' --offline --no-daemon       # R2
  ./gradlew test --offline --no-daemon                                                     # R3·R4·R8b
  ./gradlew :architecture-tests:architectureTest --tests 'io.premiumspread.architecture.PrivateAccountReadOnlyArchitectureTest' --offline --no-daemon   # R8d
  grep -rl -e '/fapi/v1/depth' -e '/fapi/v2/ticker/price' infrastructure/batch/src/main/kotlin
  find infrastructure/batch/src/main/kotlin -path '*/exchange/*' \( -name '*AccountAdapter.kt' -o -name '*ReadClient.kt' \)
  find . -path '*/src/main/kotlin/*' -name '*PrivateAccountAdapter.kt' -not -path '*/build/*'
  ```
- [ ] **Step 2: 예상 결과.** gradle 4건 exit 0. `grep` 출력은 `.../exchange/binance/BinanceFuturesPrivateAccountAdapter.kt` 한 줄.
  두 `find` 출력은 각각 `BithumbPrivateAccountAdapter.kt`·`BinanceFuturesPrivateAccountAdapter.kt` 두 줄. **다르면 멈추고 보고한다**
  (design `[ASSUMPTION]` 2·3 이 깨진 것이다 — AC4 명령·AC7 대상 규칙을 조정해야 한다). `--offline` 이 cache 부재로 실패하면 온라인 1회
  실행으로 cache 를 채우고 그 사실을 기록한다.
- [ ] **Step 3: Commit** — `docs: 바이낸스 수동 모드 Gate 1 기준선 기록` (dod 증거 로그만)

### Task 1: api 수익 메일 golden 특성화 (AC2-a)

**Files:** Modify `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountPnlMailContentTest.kt` (테스트 추가만, 기존 메서드·fixture 무수정)

**Interfaces:** 없음 (production 무변경).

**테스트가 재는 것:**
- `api 출처 수익 메일은 기준 golden 과 같다 - 모든 항목 확보` — 기존 `pnl()` fixture(netKrw `456789.4`, netPercent `1.23`, zone `Asia/Seoul`)로
  `AccountPnlMailContent.profit` 결과의 `eventKey`·`subject`·`body` 를 각각 **전체 문자열 `isEqualTo`** 로 비교. golden 은 이 태스크 시점(=
  base 코드)의 실제 출력을 테스트 안 상수(raw string)로 옮겨 적은 것이다.
- `api 출처 수익 메일은 기준 golden 과 같다 - 진입 수수료와 펀딩 미확보` — `pnl(koreaFeeUnrecovered = true, foreignFeeUnrecovered = true, fundingUnrecovered = true)`
  로 같은 방식. golden 안에 `진입 수수료(빗썸) 미반영(이력 없음)`·`진입 수수료(바이낸스) 미반영(이력 없음)`·`펀딩비 미반영(이력 없음)` 이 있다.
- golden 상수는 이후 태스크에서 **고치지 않는다.** 고쳐야 할 상황이면 api 메일이 바뀐 것이므로 멈추고 보고한다.

- [ ] **Step 1:** 두 테스트 작성 (golden 은 base 출력에서 복사).
- [ ] **Step 2: RED 확인 (변이).** 특성화라 첫 실행은 초록이다. RED 는 `AccountPnlMailContent.profit` 의 `[바이낸스 선물 숏]` 다음에 임시 줄
  `add("x")` 를 넣고 `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountPnlMailContentTest' --offline --no-daemon`
  → 두 golden 테스트 FAILED 확인 후 `git checkout -- domain/src/main` 으로 되돌리고 `git diff --stat -- domain/src/main` 이 빈 출력임을 기록.
- [ ] **Step 3:** 같은 명령 → BUILD SUCCESSFUL. 증거 로그 AC2-a.
- [ ] **Step 4: Commit** — `test: 실계정 수익 메일 api 본문 golden 특성화 테스트`

### Task 2: Snapshot 출처 (AC1)

**Files:** Modify `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountSnapshots.kt`; Test `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountHedgePositionTest.kt` (추가만)

**Interfaces — Produces (전문):**

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
    val positionSource: ForeignPerpPositionSource = ForeignPerpPositionSource.EXCHANGE_API,
)
```

`AccountHedgePosition`·`AccountNetPnl`·`fingerprint` 공식 무변경. `BinanceFuturesPrivateAccountAdapter` 는 출처 인자를 넘기지 않는다(기본값).

**테스트가 재는 것:**
- `출처 인자 없이 만든 바이낸스 Snapshot 은 EXCHANGE_API 다`
- `출처만 다른 두 포지션의 fingerprint 는 같다` — 빗썸 0.266/113016278, 바이낸스 -0.266/83549.4 로 `EXCHANGE_API`·`MANUAL_ENV` 각 1개 →
  `fingerprint` 동일, 둘 다 `resolve` 결과 non-null.

- [ ] **Step 1:** enum 과 필드를 추가하되 **기본값을 `MANUAL_ENV` 로 둔 stub** 상태에서 테스트 작성.
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountHedgePositionTest' --offline --no-daemon` →
  `출처 인자 없이 ...` FAILED (RED). fingerprint 테스트는 공식 무변경의 특성화라 처음부터 초록일 수 있다 — 그대로 기록.
- [ ] **Step 3:** 기본값을 `EXCHANGE_API` 로. 같은 명령 → PASS. `./gradlew :domain:test --offline --no-daemon` → PASS (Task 1 golden 포함). 증거 로그 AC1.
- [ ] **Step 4: Commit** — `feat: 바이낸스 Snapshot 에 포지션 출처 추가`

### Task 3: 바이낸스 공개 조회 공유 클래스 추출 (AC4)

**Files:** Create `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceFuturesReadClient.kt`; Modify `.../exchange/binance/BinanceFuturesPrivateAccountAdapter.kt`

**Interfaces — Produces (전문):**

```kotlin
internal class BinanceFuturesReadClient(
    private val webClient: WebClient,
    private val timeout: Duration = Duration.ofSeconds(5),
) {
    fun get(path: String, query: String, apiKey: String? = null): JsonNode
    fun askDepth(symbol: String): BinanceAskDepth
    fun price(symbol: String): BigDecimal
}

internal data class BinanceAskDepth(val levels: List<PriceLevel>, val observedAt: Instant)

internal class BinanceTimestampRejectedException(cause: Throwable) : RuntimeException(cause)

internal fun JsonNode.requireDecimal(field: String): BigDecimal
internal fun JsonNode.toDecimal(name: String): BigDecimal
internal fun binanceUnexpected(message: String): Nothing
```

**Consumes:** `BinanceFuturesPrivateAccountAdapter` 공개 생성자 **무변경** —
`(webClient: WebClient, apiKey: String, secretKey: String, clock: Clock, recvWindowMillis: Long = 10_000, timeout: Duration = Duration.ofSeconds(5))`.

**요건 (구현부):**
- `ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)` 설정(현재 `BinanceFuturesPrivateAccountAdapter.kt:30`)을 공유
  클래스로 옮긴다. private adapter 가 응답을 다시 파싱할 일이 남으면(예: 오류 본문) 공유 클래스의 것을 쓴다 — 설정이 다른 mapper 를 두지 않는다.
- `get`: query 가 비어 있지 않으면 `replaceQuery(query)` 로 그대로 붙인다(재인코딩 금지 — 서명 문자열 = 전송 문자열). `apiKey != null` 이면
  `X-MBX-APIKEY` 헤더. 생성자 `timeout` 으로 `.timeout(timeout)`. 실패 매핑:
  - `WebClientResponseException`: 코드 `-1021` 이고 `apiKey != null` 이면 `BinanceTimestampRejectedException`. 그 밖은 `401` 또는 코드
    `-2014`·`-2015`·`-1022` → `BINANCE_AUTH`, `429`·`418`·`>=500` → `NETWORK`, 그 밖 → `UNEXPECTED`.
  - **의도된 변경:** `apiKey == null`(비서명) 에서 `-1021` 은 위 상태 매핑을 따른다(보통 `400` → `UNEXPECTED`). 기존 `send` 는 이 경우 private
    `TimestampRejected`(AccountReadException 아님)를 그대로 던져 adapter 밖으로 raw 예외가 샜다. 관측 범주는 같다 — Job 이 비-`AccountReadException`
    을 `UNEXPECTED` 로 접는다(`AccountPnlMonitorJob.kt:137`). design §8.1.
  - `WebClientRequestException` → `NETWORK`; cause 체인에 `TimeoutException` → `NETWORK`; 그 밖 `RuntimeException` → `UNEXPECTED`;
    `AccountReadException` 은 그대로; 빈 본문(`null`)·깨진 JSON → `UNEXPECTED`. 메시지 형식 `Binance $path ...` (query·헤더 값 없음).
- `askDepth`: `GET /fapi/v1/depth` query `symbol=$symbol&limit=20`. 기존 `readDepth` 검증 그대로(asks 배열·레벨 2원소·양수·비어 있지 않음·`T` 정수) → 가격 오름차순.
- `price`: `GET /fapi/v2/ticker/price` query `symbol=$symbol`, `price` 양수.
- private adapter: 자체 `mapper`·`send`·`mapHttpError`·`readDepth`·`readPrice`·`dec`/`decimal`·`unexpected`·`TimestampRejected` 를 지우고
  `private val client = BinanceFuturesReadClient(webClient, timeout)` — **자기 `timeout` 생성자 인자를 그대로 넘긴다.** 서명·`signedGet` 재시도
  (`BinanceTimestampRejectedException` 1회 → `measureOffset()` → 재시도, 두 번째도 거부면 `NETWORK`)·캐시·이력 탐색은 adapter 에 남긴다.
  서버 시각 측정은 `client.get("/fapi/v1/time", "")`. `read()` 의 호출 순서(포지션 → 이력 → taker 율 → BNB 환산 → 최근 체결가 → **depth 마지막**) 무변경.

**테스트가 재는 것:** 이 태스크에서는 새 테스트를 쓰지 않는다 — 동작 보존 리팩터링이며 기존 `BinanceFuturesPrivateAccountAdapterTest`(23+건,
서명 벡터·`-1021`·오류 매핑·depth 순서 포함)가 **무수정으로** 지킨다. 공유 클래스 고유 동작(연결 거부·타임아웃·빈 본문·number 정밀도)은
Task 4 테스트가 manual adapter 경유로 잰다. AC4 의 셸 검사가 중복 제거를 잰다.

- [ ] **Step 1: RED (AC4 셸 검사).**
  `test "$(grep -rl -e '/fapi/v1/depth' -e '/fapi/v2/ticker/price' infrastructure/batch/src/main/kotlin)" = "infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceFuturesReadClient.kt"; echo exit=$?`
  → `exit=1` (현재는 private adapter 경로가 나온다).
- [ ] **Step 2:** 추출 구현.
- [ ] **Step 3:** dod AC4 검증 명령 전체 → exit 0. 이어서 `./gradlew :infrastructure:batch:test --offline --no-daemon` → BUILD SUCCESSFUL. 증거 로그 AC4.
- [ ] **Step 4: Commit** — `refactor: 바이낸스 공개 호가·체결가 조회를 공유 클래스로 추출`

### Task 4: manual adapter (AC3)

**Files:** Create `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceFuturesManualAccountAdapter.kt`; Test `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceFuturesManualAccountAdapterTest.kt`

**Interfaces — Consumes:** Task 2 `ForeignPerpPositionSource`, Task 3 `BinanceFuturesReadClient`. **Produces (전문):**

```kotlin
/** owner 가 env 로 입력한 바이낸스 숏 포지션 + 공개 호가·체결가. 서명·API key 를 쓰지 않는다. */
class BinanceFuturesManualAccountAdapter(
    webClient: WebClient,
    private val shortQuantity: BigDecimal,
    private val entryPrice: BigDecimal,
    private val entryFeeUsdt: BigDecimal?,
    private val fundingUsdt: BigDecimal?,
    private val takerFeeRate: BigDecimal,
    timeout: Duration = Duration.ofSeconds(5),
) : ForeignPerpAccountReadPort {
    override fun read(): ForeignPerpAccountSnapshot
}
```

**요건:** 생성자에서 `BinanceFuturesReadClient(webClient, timeout)`. `read()` 는 `client.price("BTCUSDT")` 다음 `client.askDepth("BTCUSDT")`
(depth 마지막) → Snapshot(`signedQuantity = shortQuantity.negate()`, `entryPrice`, `entryCommissionUsdt = entryFeeUsdt`, `fundingUsdt`,
`takerFeeRate`, `askLevels`, `lastPrice`, `askObservedAt`, `positionSource = MANUAL_ENV`). 무상태(캐시 없음). 실패는 공유 클래스가 던지는
`AccountReadException` 그대로. 이 파일에는 `BinanceRequestSigner`·`X-MBX-APIKEY`·`signature`·`/fapi/v1/time`·`apiKey`·`secretKey`·`recvWindow`
문자열을 주석 포함 쓰지 않는다(Task 7 규칙). 입력 검증은 하지 않는다(Properties 가 한다 — Task 5).

**테스트가 재는 것** (MockWebServer, fixture `fixtures/binance-private/depth.json`·`ticker-price.json` 재사용):
- `입력 수량을 음수 signedQuantity 로 내고 진입가·수수료·펀딩·taker 율은 입력 그대로` — 입력 0.266 / 83549.4 / 8.9 / -3.2 / 0.0005 → `signedQuantity` `compareTo(-0.266)==0` 등
- `출처는 MANUAL_ENV`
- `진입 수수료와 펀딩 미입력이면 null`
- `수량 0 이면 signedQuantity 0 이고 호가는 조회한다` (요청 2건)
- `ask 레벨은 가격 오름차순이고 관측 시각은 depth 의 T` — fixture asks 를 뒤섞은 응답으로
- `최근 체결가는 ticker price 의 price`
- `숫자형 JSON 가격도 정밀도를 보존한다` — depth asks 를 문자열이 아닌 number(`[[81580.123456789012, 1.000000000001]]`), ticker `{"price": 81577.987654321098}`
  로 응답 → `askLevels[0].price` 가 `BigDecimal("81580.123456789012")` 와 `compareTo == 0` **이고** `toPlainString()` 이 같다(double 경유 손실 없음), `lastPrice` 동일 방식
- `한 회차 요청은 ticker 다음 depth 두 건이고 서명·API key·서버 시각이 없다` — `takeRequest` 2건의 path 가 각각 `/fapi/v2/ticker/price?symbol=BTCUSDT`,
  `/fapi/v1/depth?symbol=BTCUSDT&limit=20`, method `GET`, 헤더 `X-MBX-APIKEY` 없음, query 에 `signature`·`timestamp`·`recvWindow` 없음, 3번째 요청 없음(`requestCount == 2`)
- `두 번 읽으면 매번 다시 조회한다` (`requestCount == 4`)
- `429 는 NETWORK`, `503 은 NETWORK`, `깨진 JSON 은 UNEXPECTED`, `ask 레벨이 비면 UNEXPECTED`
- `연결이 거부되면 NETWORK` — `server.shutdown()` 후 그 URL 로 만든 adapter 의 `read()` → `AccountReadException` category `NETWORK`
- `응답이 timeout 보다 늦으면 NETWORK` — adapter `timeout = Duration.ofMillis(200)`, 첫 응답에 `setBodyDelay(1, TimeUnit.SECONDS)` → `NETWORK`
- `200 빈 본문은 UNEXPECTED` — `MockResponse().setResponseCode(200)` 본문 없음 → `UNEXPECTED`
- 모든 실패 케이스에서 예외 메시지는 `Binance ` 로 시작하고 query 문자열을 담지 않는다

- [ ] **Step 1:** `read()` 가 `UnsupportedOperationException` 을 던지는 stub + 테스트 작성.
- [ ] **Step 2:** `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesManualAccountAdapterTest' --offline --no-daemon` → RED (assertion/예상 예외 불일치). 증거 로그 AC3.
- [ ] **Step 3:** 구현. 같은 명령 → PASS. `./gradlew :infrastructure:batch:test --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: 바이낸스 수동 입력 모드 조회 adapter 추가`

### Task 5: 설정·조건·배선 (AC5, AC6)

**Files:**
- Modify `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/accountpnl/AccountPnlMonitorProperties.kt`
- Modify `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/accountpnl/AccountPnlMonitorAdapterConfiguration.kt`
- Modify `apps/batch/src/main/kotlin/io/premiumspread/config/AccountPnlMonitorConfiguration.kt`
- Modify `apps/batch/src/main/resources/application.yml`
- Test `apps/batch/src/test/kotlin/io/premiumspread/config/AccountPnlMonitorWiringTest.kt` (추가 + `assertNoBeans` 에 manual adapter 단언 추가. 기존 메서드 무삭제)

**Interfaces — Produces (전문):**

```kotlin
// AccountPnlMonitorProperties.kt — 바깥 클래스·BithumbCredentials 와 그 toString 은 무변경
data class BinanceCredentials(
    val baseUrl: String = "https://fapi.binance.com",
    val apiKey: String = "",
    val secretKey: String = "",
    val mode: String? = null,
    val manual: ManualPosition = ManualPosition(),
) {
    private val parsedMode: BinanceLegMode = parseMode(mode)   // init 시점 해석
    fun resolvedMode(): BinanceLegMode = parsedMode
    override fun toString(): String =
        "BinanceCredentials(baseUrl=$baseUrl, apiKey=${mask(apiKey)}, secretKey=${mask(secretKey)}, mode=${resolvedMode()}, manual=$manual)"
}

enum class BinanceLegMode { API, MANUAL }   // AccountPnlMonitorProperties 의 nested

// mode 해석: trim 후 비면 API, equalsIgnoreCase "api"/"manual", 그 밖 →
// IllegalStateException("account-pnl-monitor.binance.mode must be api or manual")  (값·cause 없음)

data class ManualPosition(
    val shortQuantity: String? = null,
    val entryPrice: String? = null,
    val entryFeeUsdt: String? = null,
    val fundingUsdt: String? = null,
    val takerFeeRate: String? = null,
) {
    // init 시점 해석 — trim 후 비면 null, 아니면 BigDecimal(trimmed). NumberFormatException·범위 위반은
    // IllegalArgumentException(아래 메시지) 로 던지고 cause 를 연결하지 않는다.
    //   "account-pnl-monitor.binance.manual.short-quantity must be a non-negative decimal"
    //   "account-pnl-monitor.binance.manual.entry-price must be a positive decimal"
    //   "account-pnl-monitor.binance.manual.entry-fee-usdt must be a non-negative decimal"
    //   "account-pnl-monitor.binance.manual.funding-usdt must be a decimal"
    //   "account-pnl-monitor.binance.manual.taker-fee-rate must be a decimal in [0, 1)"
    fun resolvedShortQuantity(): BigDecimal?
    fun resolvedEntryPrice(): BigDecimal?
    fun resolvedEntryFeeUsdt(): BigDecimal?
    fun resolvedFundingUsdt(): BigDecimal?
    fun resolvedTakerFeeRate(): BigDecimal          // 미입력이면 DEFAULT_TAKER_FEE_RATE
    override fun toString(): String =
        "ManualPosition(shortQuantity=${presence(shortQuantity)}, entryPrice=${presence(entryPrice)}, " +
            "entryFeeUsdt=${presence(entryFeeUsdt)}, fundingUsdt=${presence(fundingUsdt)}, " +
            "takerFeeRate=${if (takerFeeRate.isNullOrBlank()) "default" else "set"})"

    companion object {
        val DEFAULT_TAKER_FEE_RATE: BigDecimal = BigDecimal("0.0005")
        private fun presence(value: String?): String = if (value.isNullOrBlank()) "<empty>" else "set"
    }
}
```

```kotlin
// AccountPnlMonitorAdapterConfiguration.kt (infra) — 같은 파일
internal object AccountPnlMonitorRequirements {
    const val MODE_KEY = "account-pnl-monitor.binance.mode"
    private val COMMON = listOf(
        "account-pnl-monitor.recipient",
        "account-pnl-monitor.bithumb.access-key",
        "account-pnl-monitor.bithumb.secret-key",
    )
    private val API = listOf("account-pnl-monitor.binance.api-key", "account-pnl-monitor.binance.secret-key")
    private val MANUAL = listOf(
        "account-pnl-monitor.binance.manual.short-quantity",
        "account-pnl-monitor.binance.manual.entry-price",
    )
    fun enabled(env: PropertyResolver): Boolean        // account-pnl-monitor.enabled equalsIgnoreCase "true"
    fun binanceMode(env: PropertyResolver): AccountPnlMonitorProperties.BinanceLegMode
    // 공백/없음 → API, "api"/"manual" 대소문자 무관(trim), 그 밖 → IllegalStateException("account-pnl-monitor.binance.mode must be api or manual")
    fun missingKeys(env: PropertyResolver): List<String>   // COMMON + (API|MANUAL) 중 비어 있는 key, 선언 순서
}

class AccountPnlCredentialsPresentCondition : Condition   // enabled && missingKeys.isEmpty()
class BinanceApiModeCondition : Condition                 // enabled && binanceMode == API
class BinanceManualModeCondition : Condition              // enabled && binanceMode == MANUAL
```

infra bean (클래스 조건 무변경):

| bean 메서드 | 추가 조건 | 반환 타입 |
|---|---|---|
| `binanceFuturesPrivateAccountAdapter(properties, clock)` | `@Conditional(BinanceApiModeCondition::class)` | `BinanceFuturesPrivateAccountAdapter` (생성 인자 무변경) |
| `binanceFuturesManualAccountAdapter(properties)` (신규) | `@Conditional(BinanceManualModeCondition::class)` | `BinanceFuturesManualAccountAdapter` — `checkNotNull(manual.resolvedShortQuantity()) { "account-pnl-monitor.binance.manual.short-quantity is required in manual mode" }`, 진입가 같은 형식(`resolvedEntryPrice()`), `entryFeeUsdt = resolvedEntryFeeUsdt()`, `fundingUsdt = resolvedFundingUsdt()`, `takerFeeRate = resolvedTakerFeeRate()`, webClient 는 기존 `client(properties.binance.baseUrl)`. 생성 시 INFO 1줄 (아래) |

```kotlin
// AccountPnlMonitorConfiguration.kt (app) — AccountPnlMonitorEnabledCondition 재작성, 클래스·애노테이션 이름 무변경
class AccountPnlMonitorEnabledCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean
    // 1) enabled 아니면 false (로그 없음)
    // 2) 모드 해석 — infra 와 같은 규칙·같은 IllegalStateException 메시지
    // 3) 누락 있으면 (className 가드) WARN 후 false
    // 4) email 비활성이면 (className 가드) 기존 WARN 후 false, 아니면 true
    private companion object {
        val COMMON = listOf(
            "account-pnl-monitor.recipient" to "ACCOUNT_PNL_ALERT_RECIPIENT",
            "account-pnl-monitor.bithumb.access-key" to "BITHUMB_ACCESS_KEY",
            "account-pnl-monitor.bithumb.secret-key" to "BITHUMB_SECRET_KEY",
        )
        val API = listOf(
            "account-pnl-monitor.binance.api-key" to "BINANCE_API_KEY",
            "account-pnl-monitor.binance.secret-key" to "BINANCE_SECRET_KEY",
        )
        val MANUAL = listOf(
            "account-pnl-monitor.binance.manual.short-quantity" to "BINANCE_MANUAL_SHORT_QUANTITY",
            "account-pnl-monitor.binance.manual.entry-price" to "BINANCE_MANUAL_ENTRY_PRICE",
        )
    }
}
```

로그 (문구 전문 — 테스트가 부분 문자열로 잰다):

| 로그 | 레벨·위치 | 형식 |
|---|---|---|
| 누락 | WARN, app 조건, `metadata.className == AccountPnlMonitorConfiguration` 일 때만 | `account-pnl-monitor enabled but required settings missing; monitor not started: binanceMode={}, missing={}` — `binanceMode` 는 `api`/`manual`, `missing` 은 env 이름 리스트(`[BINANCE_MANUAL_ENTRY_PRICE]`) |
| 메일 꺼짐 | WARN, 기존 | `account-pnl-monitor enabled but email disabled; monitor not started` (무변경) |
| manual 기동 | INFO, infra `binanceFuturesManualAccountAdapter` | `account-pnl-monitor binance leg: mode=manual, entryFeeUsdt={}, fundingUsdt={}, takerFeeRate={}` — 값 `set`/`unset`, `set`/`unset`, `set`/`default` |

어떤 로그도 설정 값(key·secret·수신 주소·수량·가격·수수료·펀딩·taker 율)을 담지 않는다.

`application.yml` `account-pnl-monitor.binance` 블록 (전문):

```yaml
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

**테스트가 재는 것** (`ApplicationContextRunner`, 기존 `runner`·`Stubs`·`enabledProps` 재사용). 새 property 묶음:

```
manualProps = notification.email.enabled=true, account-pnl-monitor.enabled=true,
  account-pnl-monitor.recipient=owner@example.test,
  account-pnl-monitor.bithumb.access-key=BITHUMB-ACCESS-SECRET-VALUE, account-pnl-monitor.bithumb.secret-key=BITHUMB-SECRET-SECRET-VALUE,
  account-pnl-monitor.binance.mode=manual,
  account-pnl-monitor.binance.manual.short-quantity=0.2661, account-pnl-monitor.binance.manual.entry-price=83549.47,
  account-pnl-monitor.binance.manual.entry-fee-usdt=8.91, account-pnl-monitor.binance.manual.funding-usdt=-3.21
```

**비노출 단언용 잘못된 입력 값** — 출력에 흔히 나오는 문자열(날짜 `2026-10-20` 의 `-1`·`-10`, 숫자 `0`)과 겹치지 않는 구별 값을 쓴다:

| 케이스 | property | 값 | 깨는 규칙 |
|---|---|---|---|
| 형식 | `manual.short-quantity` | `qtyBADx9` | 십진수 아님 |
| 음수 수량 | `manual.short-quantity` | `-0.98765` | `>= 0` |
| 진입가 0 | `manual.entry-price` | `0.000000` | `> 0` |
| 음수 수수료 | `manual.entry-fee-usdt` | `-1.234567` | `>= 0` |
| taker 상한 | `manual.taker-fee-rate` | `1.000007` | `< 1` |
| api 모드 manual 검증 | `manual.short-quantity` | `-1.98765` | `>= 0` |
| 알 수 없는 mode | `mode` | `fooModeX7` | api/manual 아님 |

- `assertNoBeans` 헬퍼에 `doesNotHaveBean(BinanceFuturesManualAccountAdapter::class.java)` 추가 (기존 케이스 전부 이 단언도 받는다)
- `manual 모드면 Job·Scheduler·빗썸 adapter·manual adapter 각 1개이고 private 바이낸스 adapter 0개` (AC5-a)
- `manual 모드면 바이낸스 key 가 있어도 private 바이낸스 adapter 0개` (`manualProps` + 기존 binance key 2개)
- `mode 미설정·빈 값·api·API 면 private adapter 1개 manual adapter 0개` (AC5-b, 4케이스)
- `api 모드에서 manual 키 다섯 개가 모두 빈 값이어도 기동한다` — `enabledProps` + `manual.short-quantity=`·`entry-price=`·`entry-fee-usdt=`·`funding-usdt=`·`taker-fee-rate=`
  → `hasNotFailed()`, private adapter 1개 (AC5-b)
- `manual 모드 필수값 하나라도 비면 0개이고 WARN 에 env 이름만 있다` — 5개 키(recipient·bithumb 2·short-quantity·entry-price) 각각 빈 값 →
  `assertNoBeans` + 출력에 `required settings missing` 와 해당 env 이름, 출력에 `BITHUMB-ACCESS-SECRET-VALUE`·`owner@example.test`·`0.2661`·`83549.47` 없음 (AC5-c)
- `api 모드 필수값이 비면 WARN 에 BINANCE_API_KEY 가 있다` (AC5-c)
- `app 조건과 infra 조건은 모드별로 같은 답을 낸다` — 모드 {미설정, `api`, `manual`, `MANUAL`} × {필수 키 전부 / 각 필수 키 하나 누락 / 다른 모드의 키만} 전 조합에서
  **먼저 `assertThat(ctx).hasNotFailed()`**, 그다음 `ctx.getBeansOfType(AccountPnlMonitorJob).size == ctx.getBeansOfType(ForeignPerpAccountReadPort).size`
  이고 둘 다 0 또는 1 (AC5-d)
- `알 수 없는 mode 는 기동 실패이고 메시지에 값이 없다` — `mode=fooModeX7` → `hasFailed()`, startup failure 원인 체인 어딘가의 메시지에
  `account-pnl-monitor.binance.mode` 포함, 체인 전체 메시지와 출력 어디에도 `fooModeX7` 없음 (AC5-e)
- `manual 입력이 잘못되면 기동 실패하고 입력 값은 어디에도 없다` — 위 표의 형식·음수 수량·진입가 0·음수 수수료·taker 상한 5케이스(`manualProps` 위에 덮어씀)
  각각 `hasFailed()` + 원인 체인 메시지 중 하나에 해당 property key 포함 + **원인 체인 전체(`generateSequence(failure) { it.cause }` 의 모든
  `message`)와 출력에 그 입력 값 문자열 없음** (AC5-f)
- `api 모드여도 구성이 바인딩되면 manual 값 형식을 검증한다` — `enabledProps` + `manual.short-quantity=-1.98765` → `hasFailed()`, 원인 체인·출력에 `-1.98765` 없음 (AC5-f)
- `펀딩 음수와 수량 0 은 허용` — 각각 `hasNotFailed()` + manual adapter 1개 (AC5-f)
- `taker 율과 진입 수수료가 빈 값이면 미입력으로 해석한다` — `manualProps` + `manual.taker-fee-rate=` + `manual.entry-fee-usdt=` →
  `hasNotFailed()`, `resolvedTakerFeeRate()` `compareTo(0.0005)==0`, `resolvedEntryFeeUsdt()` null (AC5-g)
- `manual Properties toString 에 입력 값이 없다` — `0.2661`·`83549.47`·`8.91`·`-3.21`·key 4종·`owner@example.test` 미포함, `mode=MANUAL`·`set` 포함;
  `ManualPosition().toString()` 에 `<empty>`·`takerFeeRate=default` 포함 (AC6)
- `manual 기동 로그에 모드 INFO 가 있고 입력 값이 없다` — 출력에 `binance leg: mode=manual` 포함, 위 값 문자열 미포함 (AC6)

- [ ] **Step 1:** 테스트가 **컴파일되도록** production 에 시그니처만 먼저 넣는다: `BinanceCredentials.mode: String?`·`manual: ManualPosition`,
  `BinanceLegMode`, `ManualPosition` 다섯 `String?` 필드, 그리고 stub 메서드 `resolvedMode() = BinanceLegMode.API`,
  `resolvedShortQuantity()`·`resolvedEntryPrice()`·`resolvedEntryFeeUsdt()`·`resolvedFundingUsdt()` = `null`, `resolvedTakerFeeRate() = BigDecimal.ZERO`.
  검증·toString override·조건·bean·WARN·yml 은 아직 바꾸지 않는다. `BinanceFuturesManualAccountAdapter` 는 Task 4 에서 이미 있다. 그다음 테스트 작성.
- [ ] **Step 2:** `./gradlew :apps:batch:test --tests 'io.premiumspread.config.AccountPnlMonitorWiringTest' --offline --no-daemon` → RED
  (manual 케이스·WARN·검증 실패·빈 값 해석·toString assertion 실패). 기존 케이스는 초록이어야 한다. 증거 로그 AC5·AC6.
- [ ] **Step 3:** 구현 (Properties 해석·검증·toString → infra 조건·bean → app 조건·WARN → yml).
- [ ] **Step 4:** 같은 명령 → PASS. R2 `./gradlew :apps:api:test --tests '*TradePreparationWiring*' --offline --no-daemon` → PASS.
  `./gradlew :apps:batch:test :infrastructure:batch:test --offline --no-daemon` → PASS.
- [ ] **Step 5: Commit** — `feat: 바이낸스 leg 모드 선택과 수동 입력 설정 배선`

### Task 6: 메일 출처 표기 (AC2-b, AC2-c)

**Files:** Modify `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlMailContent.kt`; Test `.../accountpnl/AccountPnlMailContentTest.kt` (추가만, Task 1 golden·기존 fixture 무수정)

**Interfaces:** `AccountPnlMailContent.profit(pnl: AccountNetPnl, zone: ZoneId): AccountPnlMail` 시그니처 무변경. 분기 기준은
`pnl.position.foreign.positionSource`.

**문구 (exact copy)** — `EXCHANGE_API` 는 현재 코드 그대로, `MANUAL_ENV` 일 때만:

| 위치 | `EXCHANGE_API` (무변경) | `MANUAL_ENV` |
|---|---|---|
| 바이낸스 진입 수수료 null | `  - 바이낸스: 진입 수수료(바이낸스) 미반영(이력 없음)` | `  - 바이낸스: 진입 수수료(바이낸스) 미반영(수동 미입력)` |
| 바이낸스 진입 수수료 있음 | `  - 바이낸스: ${costUsdt(c)} (${cost(c × fx)})` | 같은 문자열 + ` 수동 입력` |
| 펀딩 null | `누적 펀딩비: ${krwOrZero(fundingKrw)}  펀딩비 미반영(이력 없음)` | `누적 펀딩비: ${krwOrZero(fundingKrw)}  펀딩비 미반영(수동 미입력)` |
| 펀딩 있음 | `누적 펀딩비: ${krwOrZero(fundingKrw)} (${signedUsdt(f)})` | 같은 문자열 + ` 수동 입력` |
| `[바이낸스 선물 숏]` 다음 | (없음) | `바이낸스 포지션: 수동 입력(env)` |

빗썸 줄·제목·중단·복구 메일 무변경.

**fixture 규칙:** 기존 `position()`·`pnl()` 헬퍼는 **수정하지 않는다.** manual 변형은 테스트 안에서
`val base = pnl(...); base.copy(position = base.position.copy(foreign = base.position.foreign.copy(positionSource = ForeignPerpPositionSource.MANUAL_ENV)))`
로 만든다(필요하면 테스트 클래스에 private 헬퍼 `manual(pnl: AccountNetPnl): AccountNetPnl` 를 **추가**한다).

**테스트가 재는 것:**
- `수동 출처면 바이낸스 선물 숏 다음 줄에 수동 입력 표기` — 본문 줄 목록에서 `[바이낸스 선물 숏]` 인덱스 +1 이 `바이낸스 포지션: 수동 입력(env)`
- `수동 출처 진입 수수료와 펀딩 입력값에 수동 입력 표기` — `  - 바이낸스: -8.9 USDT (-11,824원) 수동 입력`, `누적 펀딩비: +4,251원 (+3.2 USDT) 수동 입력` 줄 정확 일치
- `수동 출처 미입력은 수동 미입력으로 표기하고 이력 없음을 쓰지 않는다` — `pnl(foreignFeeUnrecovered = true, fundingUnrecovered = true)` 의 manual 변형,
  바이낸스 두 줄 정확 일치, 바이낸스·펀딩 줄에 `이력 없음` 없음
- `수동 출처여도 빗썸 미반영 문구는 이력 없음 그대로` — `pnl(koreaFeeUnrecovered = true)` 의 manual 변형
- `수동 출처여도 제목은 같다` — 같은 `pnl()` 의 원본·manual 변형 subject `isEqualTo`

- [ ] **Step 1:** 테스트 작성.
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountPnlMailContentTest' --offline --no-daemon` → 새 테스트 RED, golden 초록.
- [ ] **Step 3:** 구현. 같은 명령 → PASS(golden 포함). `./gradlew :domain:test --offline --no-daemon` → PASS. 증거 로그 AC2.
- [ ] **Step 4: Commit** — `feat: 실계정 수익 메일에 바이낸스 수동 입력 출처 표기`

### Task 7: 조회 전용 아키텍처 테스트 확장 (AC7)

**Files:** Modify `architecture-tests/src/architectureTest/kotlin/io/premiumspread/architecture/PrivateAccountReadOnlyArchitectureTest.kt`

**요건 (값 전부):**
- 대상 = **합집합**, `Files.isRegularFile`·`/build/` 미포함 공통:
  - (기존 규칙, 유지) 정규화 경로에 `/src/main/kotlin/` 포함 + 파일명 `PrivateAccountAdapter.kt` 로 끝남 (모든 모듈)
  - (신규 규칙) 정규화 경로에 `/infrastructure/batch/src/main/kotlin/` 와 `/exchange/` 포함 + 파일명이 `AccountAdapter.kt` 또는 `ReadClient.kt` 로 끝남
- 대상 자기검증(기존 `MINIMUM_TARGETS = 2` 단언 교체): 대상 파일명 집합이 `REQUIRED_TARGETS = setOf("BithumbPrivateAccountAdapter.kt", "BinanceFuturesPrivateAccountAdapter.kt", "BinanceFuturesManualAccountAdapter.kt", "BinanceFuturesReadClient.kt")` 를 포함. 실패 메시지에 찾은 목록과 root.
- `FORBIDDEN` 20개·`MINIMUM_RULES = 19`·`ALLOWED_SAMPLES` 무변경.
- 신규 `MANUAL_FORBIDDEN` (7개, `Rule(name, pattern, violatingSample)`):

  | name | pattern | violatingSample |
  |---|---|---|
  | `BinanceRequestSigner` | `BinanceRequestSigner` | `BinanceRequestSigner.sign(s, q)` |
  | `X-MBX-APIKEY` | `X-MBX-APIKEY` | `set("X-MBX-APIKEY", k)` |
  | `signature` | `(?i)signature` | `&signature=` |
  | `/fapi/v1/time` | `/fapi/v1/time` | `"/fapi/v1/time"` |
  | `apiKey` | `(?i)api[_-]?key` | `apiKey = k` |
  | `secretKey` | `(?i)secret[_-]?key` | `secretKey = s` |
  | `recvWindow` | `recvWindow` | `recvWindow=10000` |

- 신규 테스트:
  - `수동 모드 adapter 는 서명·API key·서버 시각을 쓰지 않는다` — 대상 = 위 대상 중 파일명 `ManualAccountAdapter.kt` 로 끝나는 것(1개 이상, 아니면 실패), `MANUAL_FORBIDDEN` 위반 0건
  - `수동 모드 금지 패턴은 각각 위반 표본에 매치한다` — 7개 전부 매치, 이름 중복 없음, 개수 `>= 7`
- 기존 테스트 메서드 이름·`FORBIDDEN` 검사 로직 유지. 클래스 KDoc 의 대상 설명을 새 합집합 규칙으로 갱신(dod R9 허용 예외).

- [ ] **Step 1:** 대상 판정을 바꾸기 **전에** `REQUIRED_TARGETS` 단언과 신규 두 테스트를 먼저 넣는다.
- [ ] **Step 2:** `./gradlew :architecture-tests:architectureTest --tests 'io.premiumspread.architecture.PrivateAccountReadOnlyArchitectureTest' --offline --no-daemon` →
  RED (대상에 `BinanceFuturesManualAccountAdapter.kt`·`BinanceFuturesReadClient.kt` 없음, 수동 대상 0개). 증거 로그 AC7.
- [ ] **Step 3:** 대상 판정을 합집합으로 교체. 같은 명령 → PASS. 위반이 나오면 production 소스(주석 포함)를 고친다 — 규칙을 완화하지 않는다.
- [ ] **Step 4:** `./gradlew architectureTest --offline --no-daemon` → PASS (R1).
- [ ] **Step 5: Commit** — `test: 조회 전용 아키텍처 테스트에 수동 모드 adapter 와 공유 클라이언트 포함`

### Task 8: runbook 과 회귀 (AC8, R1~R9)

**Files:** Modify `docs/runbooks/account-pnl-alert.md`

**요건 (현재 runbook 의 절 이름 기준):**
- `## 환경변수` 표: `BINANCE_API_KEY`·`BINANCE_SECRET_KEY` 필수 칸을 `api 모드에서만` 으로. `BINANCE_MODE` 행 추가(기본 `api`, `api`|`manual`, 그 밖이면 기동 실패).
  "플래그가 `true` 여도 ... 비면 Job 은 뜨지 않는다" 문장을 모드별 필수 env 로 바꾸고 누락 WARN 문구 전문
  (`account-pnl-monitor enabled but required settings missing; monitor not started: binanceMode=..., missing=[...]`)을 적는다.
- 신규 절 `## 바이낸스 수동 입력 모드 (manual)` (`## 환경변수` 다음): 언제 쓰는가(key 없음), manual env 5종 표(이름·필수·단위·부호·기본값·바이낸스
  화면 어디서 읽는가 — 수량 = 포지션 Size 절댓값 BTC, 진입가 = Entry Price, 진입 수수료 = 진입 체결 수수료 합 USDT(BNB 면 USDT 환산),
  펀딩 = 포지션을 연 뒤 펀딩 합(받은 것 +)), 형식 오류·음수(펀딩 제외)·진입가 0·taker 율 1 이상은 기동 실패이며 오류 메시지에 입력 값은
  나오지 않는다(설정 이름과 규칙만 — 단 Spring Boot 기동 실패 리포트가 값을 한 줄 찍을 수 있다, design §3.1 잔여 노출), 메일 표기 예시(design §5.4 다섯 줄).
- 같은 절의 `### 한계`:
  - **manual 모드에서 펀딩을 갱신하지 않으면 장기 보유 시 순손익이 과대평가돼 1% 알림이 실제보다 일찍 갈 수 있다.** (굵게, 첫 줄)
  - 펀딩은 자동 누적되지 않는다 — 갱신하려면 env 수정 + 재시작.
  - **포지션을 늘리거나 줄이거나 닫으면 반드시 env 수정 + 재시작**(닫으면 수량 `0`). 안 하면 틀린 수량으로 계산해 틀린 알림이 갈 수 있다.
  - 수량·진입가가 바뀌면 새 에피소드(즉시 판정), 수수료·펀딩만 바꾸면 에피소드 유지.
  - taker 기본 `0.0005` 는 BNB 할인 미반영(보수적).
  - manual 값은 **모니터가 기동 조건을 모두 만족해 설정이 바인딩될 때**(기능 켜짐·현재 모드의 필수값 전부·메일 켜짐·scheduling 켜짐) api 모드에서도
    형식 검증된다. 기동 조건이 안 맞으면 검증도 되지 않는다.
  - manual 모드에서는 `바이낸스 인증 실패` 범주가 사실상 나오지 않는다.
- `### 동작 확인` (h3, `## 로컬 실행 순서` 아래)에 manual 기동 INFO 문구 `account-pnl-monitor binance leg: mode=manual, ...` 추가.
- `## AC13 owner 검산 체크리스트` 다음에 `## manual 모드 검산 체크리스트 (manual-binance AC9)` — dod AC9 (a)(b) 항목.
- `## 끄는 법과 데이터 정리` 에 모드 전환(api↔manual)은 env 수정 + 재시작, 수량·진입가 같으면 에피소드 유지 한 줄.
- 실제 값 금지(placeholder 만).

- [ ] **Step 1:** runbook 갱신.
- [ ] **Step 2:** 회귀 일괄 (dod 회귀 방어선 표 그대로):
  ```bash
  ./gradlew test architectureTest --offline --no-daemon                                             # R1·R3·R4
  ./gradlew :apps:api:test --tests '*TradePreparationWiring*' --offline --no-daemon               # R2
  git diff --exit-code 0a04546 -- infrastructure/common/src/main/resources/db/migration && ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon   # R5
  ./gradlew :apps:batch:integrationTest --offline --no-daemon                                       # R6·R8c (Docker)
  ./gradlew harnessCheck --offline --no-daemon                                                      # R7
  # R8a·R8b·R8d: dod 표의 명령 그대로
  bash docs/check-documentation.sh                                                                  # AC8
  ```
  예상: 전부 exit 0. Docker 부재로 R6 를 못 돌리면 green 으로 기록하지 않고 미실행으로 남긴다.
- [ ] **Step 3:** dod AC1~AC7 검증 명령 각각 재실행 → exit 0. 증거 로그 Task 8.
- [ ] **Step 4:** R9 — `git diff 0a04546 -- domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountHedgePositionTest.kt domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountPnlMailContentTest.kt apps/batch/src/test/kotlin/io/premiumspread/config/AccountPnlMonitorWiringTest.kt architecture-tests/src/architectureTest/kotlin/io/premiumspread/architecture/PrivateAccountReadOnlyArchitectureTest.kt | grep '^-[^-]'`
  출력을 증거 로그에 붙인다(⑨ 판정용). 허용 예외(dod R9) 외 삭제가 있으면 멈추고 보고.
- [ ] **Step 5: Commit** — `docs: 실계정 알림 runbook 에 바이낸스 수동 입력 모드 추가`
- [ ] **Step 6 (owner, AC9):** runbook 의 manual 체크리스트대로 로컬 실행 → 기동 로그 확인 → 첫 수익 메일 대조 → PR 코멘트.

## 태스크 의존

```
Task 0 → Task 1 → Task 2 ─┬→ Task 3 → Task 4 → Task 5 ─┐
                          └→ Task 6 ───────────────────┼→ Task 7 → Task 8
```

Task 6 은 Task 2 만 필요하지만 요청 순서(Domain → adapter → 배선 → 메일)를 따라 Task 5 뒤에 실행한다. Task 7 은 Task 3·4 의 파일이 있어야 초록이 된다.

## ⑥ 리뷰 반영 (R1)

| # | 지적 | 반영 위치 |
|---|---|---|
| 1 | 빈 문자열 바인딩 가정 미검증 | Task 5 빈 값 케이스 2건 |
| 2 | 공유 클라이언트 동작 커버리지 | Task 4 연결 거부·타임아웃·빈 본문, Task 3 timeout 전달 |
| 3 | 결정 (b) — `String?` 바인딩·직접 해석·값 미노출 | Task 5 Interfaces·테스트·구별 값 표, Global Constraints, Review Focus 3·4 |
| 4 | R9 허용 예외·fixture 무수정 | Task 6 fixture 규칙, Task 7 KDoc |
| 5 | 비서명 `-1021` 은 의도된 변경 | Task 3 요건, Review Focus 1 |
| 6 | `ObjectMapper` 이동·number 정밀도 | Task 3 요건, Task 4 정밀도 테스트 |
| 7 | 아키텍처 대상 합집합 | Task 7, Task 0 `find` 추가 |
| 8 | api 모드 manual 검증의 전제 조건 | Task 5 케이스, Task 8 한계 문구 |
| 9 | Step 1 stub 으로 컴파일 보장 | Task 5 Step 1 |
| 10 | 행렬은 `hasNotFailed` 먼저 | Task 5 행렬 케이스 |
| 11 | runbook 절 이름 | Task 8 요건 |
| 12 | 펀딩 미갱신 과대평가·후속 과제 | Task 8 한계 첫 줄, design §1.2 |
