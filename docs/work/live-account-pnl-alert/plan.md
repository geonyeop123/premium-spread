# 실계정 순손익 알림 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 빗썸·바이낸스 read-only 계정을 1분마다 읽어 "지금 전량 청산하면 남는" 순수익률을 계산하고, 1% 이상이면 이메일을 보낸다.

**Architecture:** `infrastructure:batch` 의 두 private REST adapter 가 계정·호가 Snapshot 을 Domain port 로 내고, Domain
순수함수(포지션 판정 → 순손익 → 알림·중단 정책)가 발송 결정을 낸다. `apps:batch` Job 이 이를 조합해 상태(낙관적 버전)와
발송할 메일을 `infrastructure:common` 의 전용 테이블(V18)에 한 트랜잭션으로 저장한 뒤, `PROCESSING` claim(SKIP LOCKED,
claim token fencing)을 commit 하고 SMTP 로 보낸다(at-least-once). 기존 premium 알림 outbox(`notification_delivery`)는
subscription FK 에 묶여 있어 재사용하지 않지만 같은 claim·fencing 계약을 따른다.

**Tech Stack:** Kotlin 2.0, Spring Boot 3.5, WebFlux `WebClient`(기존 `ExchangeWebClientConfig` 패턴), JdbcTemplate, Flyway,
JUnit5 + AssertJ, okhttp3 MockWebServer, Testcontainers(MySQL).

**Spec:** `docs/work/live-account-pnl-alert/design.md` · **DoD:** `docs/work/live-account-pnl-alert/dod.md`

## Global Constraints

- 판정: `netPercent ≥ 1.00` (호가 기준). 재무장: `netPercent < 0.90`. 재발송 간격: 60분. 중단 판정: 마지막 계산 성공 후 5분 초과.
- 청산가: 빗썸 매수호가·바이낸스 매도호가 깊이를 각 leg 수량만큼 소화한 VWAP. 깊이 부족이면 계산 실패(`INSUFFICIENT_DEPTH`).
- 시세 신선도: 판정 호가(빗썸 bid, 바이낸스 ask) 관측이 10초 초과이거나 두 호가 관측 시각 차이가 5초 초과면 실패(`STALE_MARKET_DATA`).
- 모든 조회·해석·신선도·계산 실패는 `AccountReadFailureCategory` 로 정규화해 중단 정책에 입력한다(미분류 = `UNEXPECTED`). 저장소 실패만 `JobResult.Failure`.
- outbox: SMTP 전 `PROCESSING` claim commit, claim token fencing, stale `PROCESSING` 5분 → `PENDING`, 최대 5회 시도 후 `FAILED`(redrive 없음). 환율 신선도: `FX_INGESTION` 마지막 성공 후 2시간 초과면 실패.
- 순손익 공식은 design §3 그대로. `netPercent` 분모 = 빗썸 청산가(bid) × 빗썸 수량. 표시 반올림: % 소수 둘째 자리 HALF_UP, 금액 원 단위 HALF_UP. 계산 중에는 반올림하지 않는다.
- 진입 수수료·펀딩 이력 미확보 → 0 + 항목별 `unrecovered` 플래그, 알림은 그대로.
- 분할 매매 미고려 (전량 진입·전량 청산).
- 조회 전용: GET 외 HTTP method 금지, 주문·출금·이체 경로 금지.
- credential 은 환경변수로만. `toString()`·로그·예외 메시지·메일·DB·metric tag 에 남기지 않는다. 기능 기본값 꺼짐.
- 거래 준비 포트 `BalanceSnapshotReadPort`·`VerifiedBalanceReadPort` 를 구현하지 않는다 (R2).
- `Tracking`·`TrackingGrossPnl` 은 수정하지 않는다 (R3).
- 테스트는 실 거래소·실 SMTP·실 credential 금지. 고정 `Clock`. `@Disabled` 금지.
- 커밋: `<type>: <subject>` + 한글 bullet, `Co-Authored-By` 금지.
- 모든 gradle 명령은 `--offline --no-daemon`.

## Review Focus

1. **로컬 PC 시계 오차** — 신선도는 거래소 응답 timestamp 와 `clock.instant()` 의 차이다. WSL 시계가 수 초 밀리면 매 회차가 실패한다. 미래 timestamp(음수 나이)는 신선으로 보고, 바이낸스 `recvWindow` 는 10000ms. → Task 10 테스트 `미래 시각 호가는 신선으로 본다`.
2. **빗썸 잠긴 수량** — 매도 주문을 걸어 두면 `balance` 가 줄고 `locked` 가 는다. 수량 = balance + locked. → Task 8 테스트.
3. **에피소드 중 포지션 교체** — 한 시간 안에 청산 후 새로 진입하면(1분 사이 flat 을 관측 못 할 수 있다) 이전 에피소드의 재발송 타이머가 새 포지션에 붙는다. 어느 leg 든 수량·평단(진입가)이 바뀌면 새 포지션으로 보고 에피소드를 초기화한다. → Task 4 테스트.
4. **SMTP 실패** — 발송 실패한 메일은 다음 회차에 재시도되고, 재발송 타이머는 발송 결정 시각 기준이라 SMTP 장애가 1시간 재발송을 앞당기거나 늦추지 않는다. → Task 10 테스트.
5. **청산 후 dust** — 전량 매도 후 빗썸에 0.00001 BTC 같은 잔량이 남으면 포지션이 열려 있는 것으로 보면 안 된다. 빗썸 수량 `< 0.0001` BTC 는 0 으로 본다(내 판단, design §2 dust 위임). → Task 2 테스트.

---

## File Structure

| 파일 | 책임 |
|---|---|
| `domain/.../accountpnl/AccountSnapshots.kt` | 계정·호가 Snapshot, 실패 범주, 조회 port 2개 |
| `domain/.../accountpnl/AccountHedgePosition.kt` | 계정 상태 → 김프 포지션 판정 |
| `domain/.../accountpnl/AccountNetPnl.kt` | 순손익 계산 |
| `domain/.../accountpnl/PnlAlertPolicy.kt` | 수익 알림 상태기계 |
| `domain/.../accountpnl/MonitoringOutagePolicy.kt` | 중단·복구 상태기계 |
| `domain/.../accountpnl/AccountPnlMailContent.kt` | 메일 제목·본문 생성 (순수함수) |
| `domain/.../accountpnl/AccountPnlAlertStore.kt` | 상태·메일 outbox port, 메일 발송 port |
| `domain/.../job/JobPorts.kt` (수정) | `JobId.ACCOUNT_PNL_MONITOR`, `JobLastSuccessReadPort` |
| `infrastructure/common/.../db/migration/V18__create_account_pnl_alert.sql` | 상태·outbox 테이블 |
| `infrastructure/common/.../persistence/jdbc/accountpnl/JdbcAccountPnlAlertStore.kt` | store adapter |
| `infrastructure/batch/.../exchange/bithumb/BithumbPrivateAccountAdapter.kt` (+ `BithumbJwtSigner.kt`) | 빗썸 조회 |
| `infrastructure/batch/.../exchange/binance/BinanceFuturesPrivateAccountAdapter.kt` (+ `BinanceRequestSigner.kt`) | 바이낸스 조회 |
| `infrastructure/batch/.../accountpnl/AccountPnlMonitorProperties.kt`, `AccountPnlMonitorAdapterConfiguration.kt` | 설정·조건부 bean |
| `infrastructure/batch/.../notification/AccountPnlEmailSenderAdapter.kt` | 메일 발송 port 구현 |
| `infrastructure/batch/.../job/RedisJobLastSuccessReadAdapter.kt` | 마지막 성공 시각 조회 |
| `apps/batch/.../application/job/accountpnl/AccountPnlMonitorJob.kt` | 조합 |
| `apps/batch/.../config/AccountPnlMonitorConfiguration.kt`, `interfaces/scheduling/AccountPnlMonitorScheduler.kt` | 배선 |
| `architecture-tests/.../PrivateAccountReadOnlyArchitectureTest.kt` | 조회 전용 강제 |
| `docs/runbooks/account-pnl-alert.md` | 로컬 실행·key 발급·장애 대응 |
| `docs/work/live-account-pnl-alert/understanding.md` | API 조사 기록 |

(`domain/...` = `domain/src/main/kotlin/io/premiumspread/domain`, `infrastructure/batch/...` = `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch`, `infrastructure/common/...` = `infrastructure/common/src/main/...` 의 해당 경로, `apps/batch/...` = `apps/batch/src/main/kotlin/io/premiumspread`.)

---

### Task 0: 외부 API 조사 기록 (AC12)

**Files:**
- Create: `docs/work/live-account-pnl-alert/understanding.md`
- Create: `infrastructure/batch/src/test/resources/fixtures/bithumb-private/*.json`, `.../fixtures/binance-private/*.json` (공식 문서 응답 예시를 그대로 옮긴 기록 응답)

- [ ] **Step 1:** 공식 문서(빗썸 `apidocs.bithumb.com` v1, 바이낸스 `developers.binance.com` USDⓈ-M Futures)를 읽고 design §7 의 8개 항목마다 `URL · 확인일 · 결론` 을 표로 적는다. 결론에는 최소한 다음을 확정한다:
  - 빗썸 JWT payload 필드(`access_key`, `nonce`, `timestamp`, `query_hash`, `query_hash_alg`)와 query hash 대상 문자열 규칙
  - 빗썸 `GET /v1/accounts` 의 `balance`·`locked`·`avg_buy_price`, `avg_buy_price` 가 수수료를 포함하는지
  - 빗썸 체결 완료 주문 목록 endpoint 의 `paid_fee`·`executed_volume`·`side`, 조회 가능 기간·페이지 크기
  - 빗썸 `GET /v1/orders/chance` 의 매도 수수료율 필드, 공개 `GET /v1/orderbook`(최우선 bid·timestamp)·`GET /v1/ticker`(trade_price)
  - 바이낸스 `GET /fapi/v3/positionRisk`(`positionAmt`·`entryPrice`·`positionSide`), `GET /fapi/v1/userTrades`(`commission`·`commissionAsset`·`qty`·`side`·`time`, 조회 기간 한도), `GET /fapi/v1/income?incomeType=FUNDING_FEE`(기간·`limit` 한도), `GET /fapi/v1/commissionRate`(`takerCommissionRate`), `GET /fapi/v1/ticker/bookTicker`(`askPrice`·`time`)·`GET /fapi/v1/ticker/price`
  - 두 거래소 rate limit, 바이낸스 signature 규칙과 문서 예제 벡터(secret·query·signature 3값)
- [ ] **Step 2:** 문서 응답 예시를 fixture JSON 으로 저장한다 (값은 예시 그대로, 실계정 값 금지).
- [ ] **Step 3:** **문서 결론이 이 plan 의 가정과 다르면 멈추고 보고한다** (예: `avg_buy_price` 가 수수료 포함 → Task 8 의 빗썸 `entryFeeKrw` 는 0 + `unrecovered=false` 로 바뀐다; 이력 기간이 부족 → unrecovered 경로가 기본이 된다).
- [ ] **Step 4: Commit** — `docs: 실계정 순손익 알림 외부 API 조사 기록`

### Task 1: 계정 Snapshot 과 조회 port

**Files:** Create `domain/.../accountpnl/AccountSnapshots.kt`

**Interfaces — Produces:**

```kotlin
enum class AccountReadFailureCategory {
    BITHUMB_AUTH, BINANCE_AUTH, NETWORK /* 연결·타임아웃·5xx·429 */, STALE_MARKET_DATA, STALE_FX, INSUFFICIENT_DEPTH, UNEXPECTED,
}

data class PriceLevel(val price: BigDecimal, val quantity: BigDecimal)

class AccountReadException(val category: AccountReadFailureCategory, message: String) : RuntimeException(message)

data class KoreaSpotAccountSnapshot(
    val quantity: BigDecimal,            // balance + locked, BTC
    val averageBuyPrice: BigDecimal,     // KRW
    val entryFeeKrw: BigDecimal?,        // null = 이력 미확보
    val sellFeeRate: BigDecimal,         // 0.0004 = 0.04%
    val bidLevels: List<PriceLevel>,     // 가격 내림차순
    val lastPrice: BigDecimal,
    val bidObservedAt: Instant,
)

data class ForeignPerpAccountSnapshot(
    val signedQuantity: BigDecimal,      // 숏 < 0, BTC
    val entryPrice: BigDecimal,          // USDT
    val entryCommissionUsdt: BigDecimal?, // null = 이력 미확보. BNB 수수료는 USDT 로 환산된 값
    val fundingUsdt: BigDecimal?,        // 받은 것 +, null = 미확보
    val takerFeeRate: BigDecimal,
    val askLevels: List<PriceLevel>,     // 가격 오름차순
    val lastPrice: BigDecimal,
    val askObservedAt: Instant,
)

fun interface KoreaSpotAccountReadPort { fun read(): KoreaSpotAccountSnapshot }      // 실패 시 AccountReadException
fun interface ForeignPerpAccountReadPort { fun read(): ForeignPerpAccountSnapshot }
```

이 Task 는 타입만 만든다. 테스트는 Task 2 가 소비하며 함께 커밋해도 된다 (`feat: 실계정 Snapshot 과 조회 port 추가`).

### Task 2: 포지션 판정 (AC2)

**Files:** Create `domain/.../accountpnl/AccountHedgePosition.kt`; Test `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountHedgePositionTest.kt`

**Interfaces — Produces:**

```kotlin
data class AccountHedgePosition(val korea: KoreaSpotAccountSnapshot, val foreign: ForeignPerpAccountSnapshot) {
    val koreaQuantity: BigDecimal          // korea.quantity
    val foreignQuantity: BigDecimal        // foreign.signedQuantity.abs()
    val fingerprint: String                // "${korea.quantity.stripTrailingZeros().toPlainString()}|${korea.averageBuyPrice.stripTrailingZeros().toPlainString()}|${foreign.signedQuantity.stripTrailingZeros().toPlainString()}|${foreign.entryPrice.stripTrailingZeros().toPlainString()}"
    companion object {
        val KOREA_DUST: BigDecimal = BigDecimal("0.0001")
        fun resolve(korea: KoreaSpotAccountSnapshot, foreign: ForeignPerpAccountSnapshot): AccountHedgePosition?  // null = 포지션 없음
    }
}
```

- [ ] **Step 1: 실패 테스트** — 각 케이스 하나씩:
  - `빗썸 수량 0 이면 포지션 없음` → `resolve(...)` == null
  - `빗썸 수량이 dust 미만이면 포지션 없음` (0.00009999) → null; `0.0001 이면 포지션` → not null
  - `바이낸스 수량 0 이면 포지션 없음`
  - `바이낸스 롱이면 포지션 없음` (signedQuantity = 0.266)
  - `빗썸 0.266 과 바이낸스 -0.265 이면 각 leg 수량을 보존한다` → koreaQuantity 0.266, foreignQuantity 0.265
  - `fingerprint 는 양 leg 수량과 평단·진입가로 정해지고 빗썸 평단만 바뀌어도 달라진다`
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountHedgePositionTest' --offline --no-daemon` → assertion 실패(RED). dod 증거 로그 AC2 에 붙인다.
- [ ] **Step 3:** 구현. 롱이면 경고 로그는 Domain 에서 남기지 않는다(Job 이 null 사유를 로그) — `resolve` 는 순수.
- [ ] **Step 4:** 같은 명령 → PASS. 증거 로그 GREEN.
- [ ] **Step 5: Commit** — `feat: 실계정 김프 포지션 판정 추가`

### Task 3: 순손익 계산 (AC1)

**Files:** Create `domain/.../accountpnl/AccountNetPnl.kt`; Test `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountNetPnlTest.kt`

**Interfaces — Consumes:** Task 2 `AccountHedgePosition`. **Produces:**

```kotlin
data class AccountNetPnl(
    val grossKrw: BigDecimal, val entryFeeKrw: BigDecimal, val fundingKrw: BigDecimal, val exitFeeEstimateKrw: BigDecimal,
    val netKrw: BigDecimal, val netPercent: BigDecimal,          // 호가 기준, scale 2
    val netKrwAtLast: BigDecimal, val netPercentAtLast: BigDecimal, // 체결가 기준, scale 2
    val koreaEntryFeeUnrecovered: Boolean, val foreignEntryFeeUnrecovered: Boolean, val fundingUnrecovered: Boolean,
    val position: AccountHedgePosition, val fxRate: BigDecimal, val fxObservedAt: Instant, val calculatedAt: Instant,
) {
    companion object {
        fun calculate(position: AccountHedgePosition, fxRate: BigDecimal, fxObservedAt: Instant, calculatedAt: Instant): AccountNetPnl
    }
}
```

호가 기준 청산가는 `vwap(levels, quantity)` — 앞 레벨부터 남은 수량만큼 소화한 수량가중 평균. 소화하지 못하면
`AccountReadException(INSUFFICIENT_DEPTH, ...)` 를 던진다. VWAP 는 계산 중 반올림하지 않는다(`MathContext.DECIMAL128`).
결과에 `koreaExitPrice`·`foreignExitPrice`(VWAP) 를 보존한다. 체결가 기준은 같은 공식에서 청산가만 `lastPrice` 로 바꾼
것이다 (exitFee 도 lastPrice 기준). 미확보 항목(null)은 0 으로 계산한다.

- [ ] **Step 1: 실패 테스트** — 고정 입력: 빗썸 qty 0.266, 평단 113016278, bidLevels [(109500000, 1.0)], last 109575000, entryFeeKrw 12000, sellFeeRate 0.0004; 바이낸스 signedQty -0.266, entry 83549.4, askLevels [(81580, 5.0)], last 81577, entryCommission 8.9, funding 3.2, taker 0.0005; fx 1328.5.
  - `최우선 잔량이 부족하면 다음 레벨까지 VWAP 로 청산가를 계산한다` — bidLevels [(109500000, 0.1), (109490000, 0.2)] → 빗썸 청산가 = (109500000×0.1 + 109490000×0.166) / 0.266
  - `호가 깊이가 수량보다 부족하면 INSUFFICIENT_DEPTH` — bidLevels [(109500000, 0.1)]
  - `호가 기준 순손익을 공식대로 계산한다` — 기대값은 테스트 안에서 `BigDecimal` 로 design §3 을 그대로 전개해 상수로 적는다 (gross = (109500000−113016278)×0.266 + (83549.4−81580)×0.266×1328.5 등). `netPercent` 는 scale 2 HALF_UP.
  - `체결가 기준 값은 lastPrice 로 계산한다`
  - `체결가 기준 gross 는 Tracking.grossPnl 과 같다` — 같은 수량·평단·진입가로 `Tracking.create(TrackingRecordSpec(...))` 를 만들고 `grossPnl(koreaPrice=109575000, foreignPrice=81577, fxRate=1328.5, ...)` 의 `totalGrossPnlKrw` 와 `compareTo == 0`.
  - `진입 수수료 이력 미확보면 0 과 플래그` — entryFeeKrw=null → entryFeeKrw 계산값 0 기여, `koreaEntryFeeUnrecovered == true`; 펀딩 null → `fundingUnrecovered == true`.
  - `펀딩 지급은 손실로 반영한다` — funding −3.2 → fundingKrw = −3.2×1328.5.
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountNetPnlTest' --offline --no-daemon` → RED (assertion). 증거 로그 AC1.
- [ ] **Step 3:** 구현.
- [ ] **Step 4:** 같은 명령 PASS + R3 `./gradlew :domain:test --tests 'io.premiumspread.domain.tracking.TrackingTest' --offline --no-daemon` PASS. 증거 로그.
- [ ] **Step 5: Commit** — `feat: 수수료·펀딩 반영 순손익 계산 추가`

### Task 4: 수익 알림 정책 (AC3)

**Files:** Create `domain/.../accountpnl/PnlAlertPolicy.kt`; Test `.../accountpnl/PnlAlertPolicyTest.kt`

**Interfaces — Produces:**

```kotlin
data class PnlAlertState(val inEpisode: Boolean, val positionFingerprint: String?, val lastSentAt: Instant?) {
    companion object { val INITIAL = PnlAlertState(false, null, null) }
}
sealed interface PnlObservation {
    data class Computed(val pnl: AccountNetPnl) : PnlObservation
    data object NoPosition : PnlObservation
    data object Failed : PnlObservation
}
data class PnlAlertDecision(val next: PnlAlertState, val send: Boolean)
object PnlAlertPolicy {
    val TRIGGER: BigDecimal = BigDecimal("1.00"); val REARM_BELOW: BigDecimal = BigDecimal("0.90")
    val RESEND_INTERVAL: Duration = Duration.ofMinutes(60)
    fun decide(state: PnlAlertState, observation: PnlObservation, now: Instant): PnlAlertDecision
}
```

규칙: 비에피소드 & ≥1.00 → send, 에피소드 시작(lastSentAt=now, fingerprint 기록). 에피소드 & fingerprint 변경 → 에피소드 종료 후 같은 회차를 비에피소드로 다시 판정. 에피소드 & <0.90 → 종료(send=false). 에피소드 & ≥1.00 & `now − lastSentAt ≥ 60분` → send, lastSentAt=now. 에피소드 & 0.90 ≤ x < 1.00 → 유지, send=false. NoPosition → INITIAL, send=false. Failed → 상태 불변, send=false.

- [ ] **Step 1: 실패 테스트** — dod AC3 의 각 경계 하나씩: `0.99에서 1.00으로 오르면 즉시 발송`, `에피소드 중 59분59초에는 재발송하지 않는다`, `에피소드 중 60분에 재발송한다`, `0.95에서는 60분이 지나도 재발송하지 않는다`, `0.89로 내려간 뒤 1.00이면 즉시 발송`, `0.90에서는 재무장하지 않는다`, `포지션 없음이면 초기화`, `계산 실패는 상태를 바꾸지 않는다`, `에피소드 중 바이낸스 쪽이 바뀌어 fingerprint 가 바뀌면 새 에피소드로 즉시 발송`, `에피소드 중 빗썸 쪽만 바뀌어도 새 에피소드로 즉시 발송` (Review Focus 3).
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.PnlAlertPolicyTest' --offline --no-daemon` → RED. 증거 로그 AC3.
- [ ] **Step 3:** 구현. **Step 4:** PASS, 증거 로그. **Step 5: Commit** — `feat: 순수익률 알림 정책 추가`

### Task 5: 중단·복구 정책 (AC4)

**Files:** Create `domain/.../accountpnl/MonitoringOutagePolicy.kt`; Test `.../accountpnl/MonitoringOutagePolicyTest.kt`

**Interfaces — Produces:**

```kotlin
data class OutageState(val lastSuccessAt: Instant?, val monitoringSince: Instant?, val outageNotified: Boolean)
// 첫 실패 범주가 아니라 5분 초과 판정 회차의 범주를 메일에 싣는다
sealed interface OutageNotice {
    data class Outage(val category: AccountReadFailureCategory, val lastSuccessAt: Instant?) : OutageNotice
    data object Recovered : OutageNotice
}
data class OutageDecision(val next: OutageState, val notice: OutageNotice?)
object MonitoringOutagePolicy {
    val THRESHOLD: Duration = Duration.ofMinutes(5)
    fun decide(state: OutageState, failure: AccountReadFailureCategory?, now: Instant): OutageDecision  // failure null = 성공
}
```

기준 시각 = `lastSuccessAt ?: monitoringSince`(첫 회차에 now 로 채움). 실패 & `now − 기준 > 5분` & !notified → Outage 1회. 성공 & notified → Recovered 1회. 포지션 없음은 성공이다.

- [ ] **Step 1: 실패 테스트** — `실패 5분 정각에는 알리지 않는다`, `5분 1초에 원인 범주와 함께 1회 알린다`, `계속 실패해도 추가로 알리지 않는다`, `복구 첫 회차에 1회 복구 알림`, `첫 실행부터 실패면 첫 실행 시각 기준으로 잰다`.
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.MonitoringOutagePolicyTest' --offline --no-daemon` → RED. 증거 로그 AC4.
- [ ] **Step 3–4:** 구현, PASS. **Step 5: Commit** — `feat: 모니터링 중단·복구 정책 추가`

### Task 6: 메일 내용 (AC9)

**Files:** Create `domain/.../accountpnl/AccountPnlMailContent.kt`; Test `.../accountpnl/AccountPnlMailContentTest.kt`

**Interfaces — Produces:**

```kotlin
data class AccountPnlMail(val eventKey: String, val subject: String, val body: String)
object AccountPnlMailContent {
    fun profit(pnl: AccountNetPnl, zone: ZoneId): AccountPnlMail      // eventKey = "profit|${calculatedAt}"
    fun outage(notice: OutageNotice.Outage, now: Instant, zone: ZoneId): AccountPnlMail  // "outage|$now"
    fun recovered(now: Instant, zone: ZoneId): AccountPnlMail          // "recovered|$now"
}
```

제목 형식 (exact copy): 수익 `[김프 알림] 순수익률 +1.23% · +456,789원`, 중단 `[김프 알림] 모니터링 중단 — <범주 한글>`, 복구 `[김프 알림] 모니터링 복구`. 범주 한글: BITHUMB_AUTH `빗썸 인증 실패(허용 IP 확인)`, BINANCE_AUTH `바이낸스 인증 실패`, NETWORK `네트워크·요청 한도 오류`, STALE_MARKET_DATA `시세 지연`, STALE_FX `환율 수집 지연`, INSUFFICIENT_DEPTH `호가 깊이 부족`, UNEXPECTED `기타 오류(로그 확인)`. 본문의 청산가는 VWAP 값이다. 본문: design §4.3 항목을 줄 단위로, 시각은 `Asia/Seoul` 표기 + ISO-8601 Instant, 미반영 항목은 `진입 수수료(빗썸) 미반영(이력 없음)` 형식.

- [ ] **Step 1: 실패 테스트** — `제목에 순수익률과 순수익금이 있다`(+1.23%, +456,789원 / 음수는 `-`), `본문에 계산 근거가 모두 있다`(gross·수수료·펀딩·예상 청산 수수료·체결가 기준 수익률·수량·평단·청산가·체결가·환율·관측 시각 각 라벨 포함), `미반영 항목을 명시한다`, `중단 메일 제목에 범주가 있다`.
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountPnlMailContentTest' --offline --no-daemon` → RED. 증거 로그 AC9.
- [ ] **Step 3–4:** 구현, PASS. **Step 5: Commit** — `feat: 실계정 순손익 알림 메일 내용 추가`

### Task 7: 상태·outbox 저장 (V18)

**Files:**
- Create `domain/.../accountpnl/AccountPnlAlertStore.kt`
- Create `infrastructure/common/src/main/resources/db/migration/V18__create_account_pnl_alert.sql`
- Create `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcAccountPnlAlertStore.kt`
- Test `infrastructure/common/src/integrationTest/kotlin/.../persistence/jdbc/accountpnl/JdbcAccountPnlAlertStoreIntegrationTest.kt`

**Interfaces — Produces:**

```kotlin
data class AccountPnlMonitorState(val alert: PnlAlertState, val outage: OutageState, val version: Long)  // version 0 = row 없음
data class ClaimedAccountPnlMail(val id: Long, val mail: AccountPnlMail, val claimToken: String, val attemptCount: Int)
interface AccountPnlAlertStore {
    fun load(): AccountPnlMonitorState                                   // row 없으면 INITIAL·version 0
    // 한 트랜잭션: version 일치 시에만 상태 갱신(+1), 중복 eventKey 메일은 무시. 불일치면 아무것도 쓰지 않고 false
    fun save(expectedVersion: Long, state: AccountPnlMonitorState, mails: List<AccountPnlMail>): Boolean
    // 짧은 트랜잭션: PENDING 을 id 오름차순 FOR UPDATE SKIP LOCKED 로 limit 개 잡아 PROCESSING·locked_by·claim_token·locked_at 기록 후 commit
    fun claim(owner: String, limit: Int, now: Instant): List<ClaimedAccountPnlMail>
    fun markSent(id: Long, claimToken: String, sentAt: Instant): Boolean          // token 불일치면 false
    // attempt+1; attempt >= MAX_ATTEMPTS(5) 면 FAILED, 아니면 PENDING. token 불일치면 false
    fun markAttemptFailed(id: Long, claimToken: String, now: Instant): Boolean
    fun recoverStale(now: Instant, staleAfter: Duration): Int                     // locked_at < now−staleAfter 인 PROCESSING → PENDING, token 제거
}
fun interface AccountPnlMailSender { fun send(mail: AccountPnlMail) }    // 수신자는 adapter 설정
```

V18: `account_pnl_monitor_state`(id TINYINT PK 고정 1, in_episode, position_fingerprint, last_sent_at, last_success_at, monitoring_since, outage_notified, updated_at — 시각은 `DATETIME(6)` UTC), `account_pnl_mail`(id BIGINT AUTO_INCREMENT, event_key VARCHAR(128) UNIQUE, subject VARCHAR(255), body TEXT, status VARCHAR(16) DEFAULT 'PENDING' — PENDING/PROCESSING/SENT/FAILED, attempt_count INT DEFAULT 0, locked_by VARCHAR(64) NULL, claim_token CHAR(36) NULL, locked_at DATETIME(6) NULL, created_at, sent_at NULL, INDEX(status,id)). state 테이블에 `version BIGINT NOT NULL`. 수신자 주소는 저장하지 않는다.

- [ ] **Step 1: 실패 테스트** (Testcontainers MySQL, 기존 `JdbcNotificationDeliveryRepository` integrationTest 패턴): `빈 테이블이면 초기 상태 version 0`, `상태와 메일을 저장하고 다시 읽는다`, `version 이 다르면 상태도 메일도 저장하지 않는다`, `같은 eventKey 메일은 한 번만 저장된다`, `동시에 두 claim 해도 한 행은 한쪽만 잡는다`(두 스레드), `claim 은 commit 후 PROCESSING 이다`, `다른 token 의 markSent 는 false 이고 상태를 바꾸지 않는다`, `5번째 실패에서 FAILED`, `stale PROCESSING 은 PENDING 으로 복구되고 이전 token 의 markSent 는 false`.
- [ ] **Step 2:** `./gradlew :infrastructure:common:integrationTest --tests '*JdbcAccountPnlAlertStoreIntegrationTest' --offline --no-daemon` → RED.
- [ ] **Step 3:** migration + adapter (`JdbcTemplate`, `@Repository`, `TransactionTemplate` 로 save·claim 원자성, 상태는 `UPDATE ... WHERE version = ?`(row 없으면 `INSERT` + 중복키면 false), 메일 중복은 unique 위반 catch 로 무시).
- [ ] **Step 4:** PASS + R5 `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` PASS.
- [ ] **Step 5: Commit** — `feat: 실계정 알림 상태·메일 outbox 저장소 추가`

### Task 8: 빗썸 adapter (AC7)

**Files:** Create `infrastructure/batch/.../exchange/bithumb/BithumbJwtSigner.kt`, `BithumbPrivateAccountAdapter.kt`; Test `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange/bithumb/BithumbPrivateAccountAdapterTest.kt`

**Interfaces — Consumes:** Task 1. **Produces:** `class BithumbPrivateAccountAdapter(webClient: WebClient, accessKey: String, secretKey: String, clock: Clock) : KoreaSpotAccountReadPort`; `object BithumbJwtSigner { fun sign(accessKey: String, secretKey: String, query: String?, nonce: String, timestampMillis: Long): String }` — HS256 를 `javax.crypto.Mac` 로 직접 (새 JWT 의존성 추가 금지).

동작 (Task 0 결론으로 확정된 endpoint 사용): 계정 → KRW-BTC `balance + locked`, `avg_buy_price`. 호가 → 공개 orderbook 의 bid 레벨 전부(가격·잔량)와 timestamp. 진입 수수료: 체결 완료 주문을 최신부터 거꾸로 걸어 bid 체결량 누적이 보유 수량과 같아지면 그 주문들의 `paid_fee` 합; 이력이 끝나거나 누적이 수량을 넘으면 `null`. 같은 보유(수량·평단) 동안 결과를 메모리에 보관해 매분 이력을 다시 읽지 않는다. 매도 수수료율·bid·last 는 매 회차 조회. 401 → `BITHUMB_AUTH`, 연결·타임아웃·5xx·429 → `NETWORK`, 응답 해석 실패·그 밖의 4xx → `UNEXPECTED`. adapter 는 모든 실패를 `AccountReadException` 으로만 던진다. 예외 메시지에 key·JWT 를 넣지 않는다.

- [ ] **Step 1: 실패 테스트** (MockWebServer + Task 0 fixture): `Authorization 헤더 JWT 를 테스트 secret 으로 검증할 수 있고 payload 에 access_key·nonce·timestamp·query_hash 가 있다`, `잠긴 수량을 포함한다`(Review Focus 2), `체결 주문 수수료를 보유 수량만큼 합산한다`, `이력으로 보유 수량을 채우지 못하면 entryFeeKrw 는 null`, `같은 보유면 두 번째 호출은 주문 이력을 다시 요청하지 않는다`(MockWebServer requestCount), `401 은 BITHUMB_AUTH`, `429 는 NETWORK`, `깨진 JSON 은 UNEXPECTED`, `orderbook bid 레벨을 가격 내림차순으로 낸다`, `예외 메시지에 secret 이 없다`.
- [ ] **Step 2:** `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbPrivateAccountAdapterTest' --offline --no-daemon` → RED. 증거 로그 AC7.
- [ ] **Step 3–4:** 구현, PASS. **Step 5: Commit** — `feat: 빗썸 read-only 계정 조회 adapter 추가`

### Task 9: 바이낸스 adapter (AC8)

**Files:** Create `infrastructure/batch/.../exchange/binance/BinanceRequestSigner.kt`, `BinanceFuturesPrivateAccountAdapter.kt`; Test `.../exchange/binance/BinanceFuturesPrivateAccountAdapterTest.kt`

**Produces:** `class BinanceFuturesPrivateAccountAdapter(webClient: WebClient, apiKey: String, secretKey: String, clock: Clock, recvWindowMillis: Long = 10_000) : ForeignPerpAccountReadPort`; `object BinanceRequestSigner { fun sign(secretKey: String, query: String): String }` (HMAC-SHA256 hex).

동작: positionRisk(BTCUSDT; hedge mode 면 `positionSide=SHORT` 행) → signedQuantity·entryPrice. 진입 시각·수수료: `userTrades` 를 최신부터 거꾸로 걸어 부호 있는 체결량 누적이 `positionAmt` 와 같아지는 지점까지의 `commission` 합(`commissionAsset` USDT 는 그대로, BNB 는 `GET /fapi/v1/ticker/price?symbol=BNBUSDT` 현재가로 환산, 그 밖의 자산이면 `AccountReadException(UNEXPECTED)`), 그 첫 체결 시각 = 진입 시각; 못 찾으면 수수료·펀딩 null. 펀딩: 진입 시각부터 `income?incomeType=FUNDING_FEE` 페이지를 끝까지 합산(첫 계산 후에는 마지막 조회 시각 이후 증분만). 진입 시각·수수료는 fingerprint 동안 메모리 보관. takerFeeRate·`GET /fapi/v1/depth?symbol=BTCUSDT&limit=20` ask 레벨(+`T` 시각)·ticker price 는 매 회차. 401/`-2014`/`-2015`/`-1022` → `BINANCE_AUTH`, 연결·5xx·429·418 → `NETWORK`, 응답 해석 실패·그 밖의 4xx → `UNEXPECTED`. 모든 실패를 `AccountReadException` 으로만 던진다.

- [ ] **Step 1: 실패 테스트**: `서명은 문서 예제 벡터와 같다`(Task 0 에 기록한 secret·query·signature 3값), `X-MBX-APIKEY 헤더와 recvWindow=10000 이 붙는다`, `숏 수량과 진입가를 읽는다`, `hedge mode 면 SHORT 행을 읽는다`, `체결 수수료를 포지션 수량만큼 합산한다`, `BNB 수수료는 BNBUSDT 현재가로 환산해 합산한다`, `USDT·BNB 외 수수료 자산이면 UNEXPECTED`, `펀딩을 여러 페이지에 걸쳐 합산한다`, `두 번째 회차는 펀딩 증분만 요청한다`, `-2015 는 BINANCE_AUTH`, `429 는 NETWORK`, `깨진 JSON 은 UNEXPECTED`, `depth ask 레벨을 가격 오름차순으로 낸다`, `예외 메시지에 secret 이 없다`.
- [ ] **Step 2:** `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapterTest' --offline --no-daemon` → RED. 증거 로그 AC8.
- [ ] **Step 3–4:** 구현, PASS. **Step 5: Commit** — `feat: 바이낸스 선물 read-only 계정 조회 adapter 추가`

### Task 10: 모니터링 Job (AC5)

**Files:**
- Modify `domain/src/main/kotlin/io/premiumspread/domain/job/JobPorts.kt` — `JobId.ACCOUNT_PNL_MONITOR("account-pnl-monitor")`, `fun interface JobLastSuccessReadPort { fun findLastSuccess(jobId: JobId): Instant? }`
- Modify `apps/batch/.../application/common/JobConfig.kt` (`DefaultJobConfigProvider` 분기), `apps/batch/.../config/BatchJobProperties.kt` (`accountPnlMonitor: LockSpec = LockSpec("lock:account-pnl:monitor", Duration.ofSeconds(50), Duration.ofSeconds(40))` + `get()` 분기)
- Create `infrastructure/batch/.../job/RedisJobLastSuccessReadAdapter.kt` — `RedisKeyGenerator.batchLastRunKey(jobId.tag)` 를 읽는다 (Redis 계약 변경 없음, 읽기만)
- Create `apps/batch/.../application/job/accountpnl/AccountPnlMonitorJob.kt`; Test `apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJobTest.kt`

**Produces:**

```kotlin
class AccountPnlMonitorJob(
    korea: KoreaSpotAccountReadPort, foreign: ForeignPerpAccountReadPort, fx: FxRateReadPort,
    lastSuccess: JobLastSuccessReadPort, store: AccountPnlAlertStore, mailSender: AccountPnlMailSender,
    executor: JobExecutor, clock: Clock, zone: ZoneId = ZoneId.of("Asia/Seoul"),
    jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
) { fun run(): JobResult }
```

순서 (executor 안): `store.recoverStale(now, 5분)` → load → **관측 단계**[조회(korea·foreign) → 신선도(호가 나이 = `now − observedAt`, 음수는 0 으로 봄, >10초면 `STALE_MARKET_DATA`; `|koreaBidObservedAt − foreignAskObservedAt| > 5초`도 `STALE_MARKET_DATA`; `findLastSuccess(FX_INGESTION)` 이 null 이거나 `now − 그 값 > 2시간`이면 `STALE_FX`; `fx.findLatest(USD, KRW)` null 도 `STALE_FX`) → resolve → calculate] → 두 정책 → 메일 생성 → `store.save(load 한 version, ...)` (false 면 `JobResult.Skipped("state-version-conflict")`) → `store.claim(owner = "account-pnl-monitor:" + UUID(인스턴스당 1회), 10, now)` 각각 send, 성공 `markSent`, 예외 `markAttemptFailed` 후 다음 메일 계속. **관측 단계의 모든 예외**는 `AccountReadException` 이면 그 범주, 아니면 `UNEXPECTED` 로 정책의 실패 입력이 되고(스택은 warn 로그, 메시지에 secret 없음) Job 결과는 `Success` (매분 CRITICAL operator alert 를 막는다). store 예외만 `Failure` → 기존 `OperatorAlert`. 포지션 null 사유(롱 등)는 info 로그.

- [ ] **Step 1: 실패 테스트** (fake port, 고정 Clock, `JobExecutor` 는 기존 테스트의 fake lock 패턴): `1% 이상이면 메일 1건을 저장하고 보낸다`, `호가가 10초 넘게 오래되면 계산 실패로 보고 수익 메일을 만들지 않는다`, `미래 시각 호가는 신선으로 본다`(Review Focus 1), `두 호가 시각 차이 5초는 계산하고 5초 1밀리초는 STALE_MARKET_DATA`, `환율 수집 마지막 성공이 2시간을 넘으면 STALE_FX`, `환율 값이 오래돼도 수집 성공이 2시간 이내면 계산한다`, `SMTP 실패 메일은 PENDING 으로 남고 다음 회차에 다시 보낸다`, `SMTP 실패가 재발송 타이머를 바꾸지 않는다`(Review Focus 4), `조회 실패는 Job 실패가 아니다`, `분류되지 않은 예외는 UNEXPECTED 로 중단 정책에 들어간다`, `호가 깊이 부족은 INSUFFICIENT_DEPTH 로 중단 정책에 들어간다`, `상태 version 충돌이면 메일을 보내지 않고 Skipped`, `store 예외는 Failure`.
- [ ] **Step 2:** `./gradlew :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest' --offline --no-daemon` → RED. 증거 로그 AC5.
- [ ] **Step 3–4:** 구현, PASS. **Step 5: Commit** — `feat: 실계정 순손익 모니터링 Job 추가`

### Task 11: 설정·배선·비노출 (AC11)

**Files:**
- Create `infrastructure/batch/.../accountpnl/AccountPnlMonitorProperties.kt` — `@ConfigurationProperties("account-pnl-monitor")`: `enabled: Boolean = false`, `recipient: String = ""`, `bithumb: Credentials(baseUrl = "https://api.bithumb.com", accessKey, secretKey)`, `binance: Credentials(baseUrl = "https://fapi.binance.com", apiKey, secretKey)`. `toString()` 은 key 를 `***` 로 출력.
- Create `infrastructure/batch/.../accountpnl/AccountPnlMonitorAdapterConfiguration.kt` — `@ConditionalOnProperty(prefix = "account-pnl-monitor", name = ["enabled"], havingValue = "true")` + 커스텀 `AccountPnlCredentialsPresentCondition`(4개 key·recipient 비어 있지 않음) + `@ConditionalOnBean(EmailSender::class)`; 두 adapter, `RedisJobLastSuccessReadAdapter`, `AccountPnlEmailSenderAdapter(emailSender, recipient)` bean. `BatchInfrastructureAutoConfiguration` 의 `@Import` 에 추가.
- Create `infrastructure/batch/.../notification/AccountPnlEmailSenderAdapter.kt` — `EmailMessage(to = recipient, subject, text = body, deliveryId = eventKey)`
- Create `apps/batch/.../config/AccountPnlMonitorConfiguration.kt` (`@ConditionalOnBean(KoreaSpotAccountReadPort::class)` 로 Job bean), `apps/batch/.../interfaces/scheduling/AccountPnlMonitorScheduler.kt` (`@ConditionalOnBatchScheduling`, `@ConditionalOnBean(AccountPnlMonitorJob::class)`, `fixedRateString = "\${batch.scheduling.account-pnl-monitor.fixed-rate:60000}"`)
- Modify `apps/batch/src/main/resources/application.yml` — `account-pnl-monitor` 블록(`ACCOUNT_PNL_MONITOR_ENABLED:false`, `ACCOUNT_PNL_ALERT_RECIPIENT:`, `BITHUMB_ACCESS_KEY:`, `BITHUMB_SECRET_KEY:`, `BINANCE_API_KEY:`, `BINANCE_SECRET_KEY:`), `batch.jobs.account-pnl-monitor`, `batch.scheduling.account-pnl-monitor.fixed-rate`
- Modify `supports/logging/.../LogMaskingFilter.kt` 와 `logback-spring.xml` 패턴에 `access[_-]?key|secret|api[_-]?secret` 추가 (기존 `api[_-]?key|secret[_-]?key` 는 유지)
- Test `apps/batch/src/test/kotlin/io/premiumspread/config/AccountPnlMonitorWiringTest.kt` (`ApplicationContextRunner`)

- [ ] **Step 1: 실패 테스트**: `플래그가 없으면 bean 0개`, `플래그 false 면 0개`, `key 하나라도 비면 0개`, `scheduling 비활성이면 Scheduler 0개`, `플래그·key·EmailSender 가 있으면 Job·Scheduler·두 adapter 각 1개`, `Properties toString 에 secret 값이 없다`, `startup 로그(OutputCaptureExtension)에 secret 값이 없다`.
- [ ] **Step 2:** `./gradlew :apps:batch:test --tests 'io.premiumspread.config.AccountPnlMonitorWiringTest' --offline --no-daemon` → RED. 증거 로그 AC11.
- [ ] **Step 3–4:** 구현, PASS + R2 `./gradlew :apps:api:test --tests '*TradePreparationWiring*' --offline --no-daemon` PASS.
- [ ] **Step 5: Commit** — `feat: 실계정 순손익 모니터링 배선과 credential 비노출`

### Task 12: 재시작 내구성 통합 테스트 (AC6)

**Files:** Test `apps/batch/src/integrationTest/kotlin/io/premiumspread/application/job/AccountPnlAlertDurabilityIntegrationTest.kt`

- [ ] **Step 1: 테스트** (기존 batch integrationTest 의 MySQL/Redis Testcontainers 설정, fake 계정 port 2개, 기록용 fake `AccountPnlMailSender`, 조작 가능한 Clock): 1회차 1.20% → 메일 row 1·전송 1. 새 `AccountPnlMonitorJob` 인스턴스(같은 store) 로 30분 뒤 1.30% → 메일 row 증가 0. 60분 뒤 → 증가 1. 첫 전송에서 sender 예외 → row PENDING, 다음 회차에 전송·SENT. 두 Job 인스턴스를 동시에 실행(같은 시각, 1.20%, lock 우회 fake executor) → 메일 row 1·전송 1(version 충돌 쪽은 Skipped). 발송 중 claim 이 stale 복구된 뒤 늦은 `markSent` → false, 재claim 한 쪽이 SENT.
- [ ] **Step 2:** `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.application.job.AccountPnlAlertDurabilityIntegrationTest' --offline --no-daemon` → Task 10·7 완료 상태에서 첫 실행이 통과하면 RED 가 없으므로, 먼저 store 의 save 를 임시로 무력화한 상태에서 RED 를 확인하고 되돌린다 (증거 로그에 그 사실 기록).
- [ ] **Step 3:** PASS + R6 `./gradlew :apps:batch:integrationTest --offline --no-daemon` PASS. **Commit** — `test: 실계정 알림 재시작 내구성 통합 테스트`

### Task 13: 조회 전용 아키텍처 테스트 (AC10)

**Files:** Test `architecture-tests/src/architectureTest/kotlin/io/premiumspread/architecture/PrivateAccountReadOnlyArchitectureTest.kt`

`VerifiedBalanceNoCacheArchitectureTest` 의 소스 순회 방식을 따른다. 대상: 파일명 `*PrivateAccountAdapter.kt` 인 production 소스. 금지: `.post(`, `.put(`, `.delete(`, `.patch(`, `HttpMethod.POST|PUT|DELETE|PATCH`, 경로 문자열 `/v1/orders"`(POST 용 단수 주문 경로 포함 여부는 Task 0 결과로 확정), `withdraw`, `/order"`, `transfer`. 자기검증: 대상 파일이 2개 미만이면 실패, 금지 패턴 각 대안이 위반 표본 문자열에서 개별로 매치하는지 검사하는 테스트를 같은 클래스에 둔다.

- [ ] **Step 1:** 테스트 작성. **Step 2:** 위반 표본 검사 + 실제 소스 검사 실행 → 위반 표본 테스트가 먼저 RED(검사 함수 미구현) 확인. 증거 로그 AC10.
- [ ] **Step 3:** `./gradlew architectureTest --offline --no-daemon` 전체 PASS (R1 포함). **Commit** — `test: private 계정 adapter 조회 전용 아키텍처 테스트`

### Task 14: runbook 과 마무리 회귀

**Files:** Create `docs/runbooks/account-pnl-alert.md` (key 발급 권한·허용 IP, 환경변수 표, 로컬 실행 명령, 중단 메일 범주별 조치, master spec 이탈 고지 링크). 필요 시 `docs/check-documentation.sh` 의 정본 문서 규칙에 맞춘다.

- [ ] **Step 1:** runbook 작성. **Step 2:** `./gradlew test architectureTest --offline --no-daemon` (R1·R4), `./gradlew harnessCheck --offline --no-daemon` (R7), `bash docs/check-documentation.sh` PASS.
- [ ] **Step 3: Commit** — `docs: 실계정 순손익 알림 runbook 추가`
- [ ] **Step 4 (owner, AC13):** owner 가 runbook 대로 실 key 로 로컬 실행 → 메일 수신 → 거래소 화면 검산 → PR 코멘트로 확인.
