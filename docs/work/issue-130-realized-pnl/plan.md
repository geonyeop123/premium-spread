# #130 확정 손익 — 구현 계획 (결정 수준)

- Spec: `docs/work/issue-130-realized-pnl/design.md` (결정 D1~D9), 완료 기준: `dod.md` (FROZEN 2026-11-11)
- 베이스: `dev` 43a5dd1 · 브랜치 `feat/issue-130-realized-pnl`
- 테스트 명령은 dod.md의 검증 명령을 그대로 쓴다. 로컬은 `--offline --no-daemon`.

## Global Constraints

- **조회 전용:** 거래소 호출은 GET만 쓴다. 주문·취소·출금 경로 문자열을 주석에도 쓰지 않는다(`PrivateAccountReadOnlyArchitectureTest`가 원문을 스캔한다).
- **로그:** 실계정 금액·수량·가격은 로그·예외 메시지·toString에 넣지 않는다. 남기는 것은 건 id·seq·실패 범주·예외 클래스명뿐이다.
- **도메인:** 순수 Kotlin이다(`.ai/rules/architecture.md` 허용 의존만). 앱은 infrastructure 타입을 import하지 않는다.
- **취소:** `InterruptedException`·`CancellationException`(cause 체인 포함)은 삼키지 않는다. interrupt를 되살리고 다시 던진다(기존 Job 관례 `isCancellation()`).
- **금액 계산:** `BigDecimal`과 `MathContext.DECIMAL128`. 반올림은 KRW 합계 저장 시 원 단위 HALF_UP 한 번만 한다.
- **시간:** `Clock`을 주입한다. 테스트는 고정 Clock과 명시적 zone을 쓴다.
- **스타일:** ktlint 1.8.0과 detekt(ReturnCount ≤ 4, TooManyFunctions 20/class, LongParameterList)를 CI가 강제한다. 로컬 확인용 jar는 `/home/yeop/.claude/jobs/387bbede/tmp/tools/ktlint.jar`.
- **Flyway:** append-only다. API만 migration을 실행하고, batch 통합 테스트 스키마는 `apps/batch/src/integrationTest/resources/batch-schema.sql` fixture로 만든다. 새 열은 fixture에도 넣는다(`AccountPnlFixtureSchemaIntegrationTest`가 대조).
- **기존 테스트(R1):** `apps/batch/src/test/.../application/job/accountpnl`, `infrastructure/batch/src/test/.../exchange` 아래 기존 테스트 파일은 수정하지 않는다.

## 공유 계약 (Task 1이 만들고 이후 태스크가 소비)

패키지는 `io.premiumspread.domain.accountpnl`이다.

```kotlin
enum class FillSide { BUY, SELL }
data class KoreaSpotFill(val id: String, val side: FillSide, val quantity: BigDecimal, val fundsKrw: BigDecimal, val feeKrw: BigDecimal, val filledAt: Instant)
data class ForeignPerpFill(val id: Long, val signedQuantity: BigDecimal, val realizedPnlUsdt: BigDecimal, val commissionUsdt: BigDecimal, val filledAt: Instant) // commission >= 0
data class ForeignFunding(val id: Long, val incomeUsdt: BigDecimal, val at: Instant)
data class KoreaRealized(val realizedKrw: BigDecimal)
data class ForeignRealized(val realizedPnlUsdt: BigDecimal, val commissionUsdt: BigDecimal, val fundingUsdt: BigDecimal) { val netUsdt: BigDecimal }
// 위 값 타입 전부: toString 은 타입명(+시각)만

fun interface KoreaSpotFillReadPort { fun read(fromExclusive: Instant, toInclusive: Instant): List<KoreaSpotFill> }
interface ForeignPerpFillReadPort {
    fun fills(fromExclusive: Instant, toInclusive: Instant): List<ForeignPerpFill>
    fun funding(fromExclusive: Instant, toInclusive: Instant): List<ForeignFunding>
}
/** 조회 창·페이지 한도 초과. AccountReadException 과 별개(모니터 장애 범주를 늘리지 않는다). */
class FillHistoryWindowLimitException(message: String) : RuntimeException(message)

enum class SettlementStatus { PENDING, CONFIRMED, MANUAL }
enum class SettlementFailure { KOREA_READ, FOREIGN_READ, KOREA_QUANTITY, FOREIGN_QUANTITY, FX, WINDOW_LIMIT }
data class SettlementTarget(
    val episodeId: Long, val seq: Int, val occurredAt: Instant,
    val legs: PositionLegs,                    // 사건 정규화 leg(롱→FLAT 은 이미 정규화됨)
    val source: ForeignPerpPositionSource,     // 목표 사건의 position_source
    val lowerExclusive: Instant, val attempts: Int,
)
data class AppliedFx(val rate: BigDecimal, val observedAt: Instant)
data class SettlementOutcome(
    val target: SettlementTarget,
    val koreaStatus: SettlementStatus, val korea: KoreaRealized?,
    val foreignStatus: SettlementStatus, val foreign: ForeignRealized?,
    val fx: AppliedFx?, val totalKrw: BigDecimal?,
    val failure: SettlementFailure?, val decidedAt: Instant,
) { val settled: Boolean /* korea CONFIRMED && foreign in (CONFIRMED, MANUAL) */ }

interface AccountRealizedPnlStore {
    fun findTarget(now: Instant, settleDelay: Duration, maxAge: Duration, firstLookback: Duration): SettlementTarget?
    fun findFxAt(at: Instant, tolerance: Duration): AppliedFx?
    /** settled 면 settled_seq·settled_at 을 쓰고 attempts·next_at 을 비운다. 아니면 attempts+1, next_at = [nextAttemptAt]. 단조 조건에 막히면 false. */
    fun record(outcome: SettlementOutcome, nextAttemptAt: Instant?): Boolean
}
object RealizedPnlPolicy {
    fun korea(fills: List<KoreaSpotFill>, expectedQuantity: BigDecimal): KoreaRealized?
    fun foreign(fills: List<ForeignPerpFill>, funding: List<ForeignFunding>, expectedSignedQuantity: BigDecimal): ForeignRealized?
    fun totalKrw(korea: KoreaRealized, foreign: ForeignRealized, fxRate: BigDecimal): BigDecimal // scale 0 HALF_UP
}
```

## Task 1 — 도메인: 값 타입·정책·포트 (AC1)

- **파일:** `domain/.../accountpnl/RealizedPnl.kt`(값 타입·포트·예외·상태 enum·SettlementTarget·Outcome·Store 포트), `RealizedPnlPolicy.kt`
- **정책 결정:**
  - `korea`: id로 `distinctBy`한 뒤 `(filledAt, id)` 순으로 정렬하고 이동평균 원가(수수료 포함)를 적용한다(design D4).
    - 보유 수량이 0인데 매도가 오면 null(원가를 정할 수 없음).
    - 끝에서 `|Σbuy − Σsell − expected| >= KOREA_DUST`이면 null.
    - 매도 원가 = 수량 × 원가합 / 보유수량(DECIMAL128). 원가합·보유수량은 그만큼 줄인다.
  - `foreign`: id로 dedup한 뒤 `Σ signedQuantity.compareTo(expected) != 0`이면 null. 수수료는 `abs()`로 다시 방어한다. 펀딩도 id로 dedup한다.
  - `totalKrw` = korea + net × fx, `setScale(0, HALF_UP)`.
- **테스트** `io.premiumspread.domain.accountpnl.RealizedPnlPolicyTest`:
  - 매수2 → 매도1 → 매수1 → 매도2: 단계별 실현값이 손계산과 같다(부분 정리 값 포함).
  - 전량 정리 값 = `Σsell(funds − fee) − Σbuy(funds + fee)`.
  - 같은 id 체결이 두 번 와도 값이 같다.
  - dust 경계: 0.00009 차이는 확정, 0.0001은 null.
  - 보유 0에서 매도하면 null.
  - 바이낸스: 순 = pnl − |commission| + funding. 음수 commission 입력도 비용이다.
  - 부호 수량이 다르면 null. FLAT 기대(0)인데 롱 잔량이 남으면 null.
  - `totalKrw` 반올림 경계(.5).
  - 값 타입 toString에 숫자가 없다.
- **검증:** `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.RealizedPnlPolicyTest --offline --no-daemon`

## Task 2 — V26·JDBC store·fixture schema (AC3, AC8)

- **파일:**
  - `infrastructure/common/src/main/resources/db/migration/V26__add_realized_pnl_settlement.sql`
  - `infrastructure/common/.../persistence/jdbc/accountpnl/JdbcAccountRealizedPnlStore.kt`(`@Repository`, `AccountRealizedPnlStore`)
  - `apps/batch/src/integrationTest/resources/batch-schema.sql`
  - 기존 migration 테스트의 `take(N)` 같은 버전 개수 단정이 있으면 갱신한다(#131이 V20 테스트를 `take(21)`로 바꾼 전례).
- **V26 열:** design D7 표 그대로이고 모두 NULL 허용이다. 설정하는 CHECK는 둘이다.
  - `ck_account_position_episode_settlement_status`: 두 상태 열은 NULL이거나 PENDING·CONFIRMED·MANUAL 중 하나.
  - `ck_account_position_episode_settlement_failure`: NULL이거나 6개 범주 중 하나.
- **`findTarget` SQL 결정:**
  - 후보 건은 다음 정리 사건 `e`가 있는 건이다.
    - `e.event_type IN ('REDUCED','ADJUSTED','CLOSED')`
    - `e.seq > COALESCE(settled_seq, 0)`
    - `e.occurred_at <= now − delay`
  - 목표 seq = 그 조건을 만족하는 `MAX(e.seq)`.
  - backoff 제외: `settlement_next_at > now AND 목표 seq <= COALESCE(settlement_target_seq, 0)`이면 제외한다. 새 사건이 생겼으면 backoff를 무시한다.
  - `lower`: `closed_at < 이 건 opened_at`인 다른 건 중 가장 늦은 `closed_at`. 없으면 `opened_at − firstLookback`.
  - `lower <= now − maxAge`이면 제외한다.
  - 정렬은 `opened_at, id` 오름차순이고 첫 1건만 돌려준다. 가장 오래된 건이 backoff 중이면 다음 건이 나온다.
  - 목표 사건 행의 leg 4값(문자열 → `KoreaLeg.of`/`ForeignLeg.of`)과 `position_source`로 `SettlementTarget`을 만든다.
  - SQL 2~3문장 + Kotlin 조합으로 나눠도 된다(가독성 우선).
- **`findFxAt`:** `account_pnl_observation`에서 `observed_at = at`인 행(episode 무관)을 먼저 찾는다. 없으면 `observed_at ∈ [at − tolerance, at]` 중 최신 행이다.
- **`record`:** `UPDATE … WHERE id = ? AND (settled_seq IS NULL OR settled_seq < ?)` 한 문장이고 영향 행 수 1이면 true다.
  - 확정 시: 두 상태·값·fx·total·`settled_seq`·`settled_at` 기록, `settlement_attempts`·`settlement_next_at`·`settlement_failure` NULL, `settlement_target_seq` = seq.
  - 미확정 시: 상태·확정된 쪽 값(미확정 쪽 값은 NULL), `settlement_attempts = COALESCE(attempts, 0) + 1`, `settlement_next_at`, `settlement_failure`, `settlement_target_seq` = seq. `settled_seq`는 그대로 둔다.
- **테스트:**
  - `io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountRealizedPnlStoreIntegrationTest`: dod AC3 문장의 각 경우를 메서드 하나씩 둔다.
    - 선정 규칙, 89·91일 경계, backoff와 새 사건, 앞 건 backoff 시 다음 건 반환
    - lower 두 경우
    - 단조 갱신(작은 seq는 false, 행 불변)
    - findFxAt 세 경우
  - `io.premiumspread.infrastructure.common.migration.V26RealizedPnlMigrationIntegrationTest`: V25 상태 행에 V26을 적용하면 새 열이 NULL이고 기존 값이 보존된다. 상태 열에 잘못된 값을 넣으면 CHECK 위반이다. 형식은 V25 테스트 관례를 따른다.
- **검증:** dod AC3·AC8 명령, `:infrastructure:common:verifyMigrations`

## Task 3 — 빗썸 체결 adapter (AC5)

- **파일:**
  - `infrastructure/batch/.../exchange/bithumb/BithumbPrivateReadClient.kt`: 기존 `BithumbPrivateAccountAdapter.getJson`·`statusCategory`·`dec` 계열을 옮긴 공유 서명 GET 클라이언트다. 기존 adapter는 이것을 쓰도록 바꾸고, 기존 테스트는 무수정으로 통과해야 한다.
  - `BithumbFillHistoryPrivateAccountAdapter.kt`: `KoreaSpotFillReadPort`
- **결정:**
  - 목록: `/v1/orders?market=KRW-BTC&states[]=wait&states[]=done&states[]=cancel&order_by=desc&limit=100&page=N`.
    - `executed_volume > 0`이고 `created_at <= toInclusive`인 주문만 상세를 조회한다.
    - 페이지 행이 100 미만이거나, 페이지 마지막 행의 `created_at < fromExclusive − 7일`이면 멈춘다.
    - 20페이지를 다 읽었는데도 멈추지 못하면 `FillHistoryWindowLimitException`.
  - 상세: `/v1/order?uuid=…`의 `side`(bid → BUY, ask → SELL), `paid_fee`, `trades[]`(`uuid`·`volume`·`funds`·`created_at`).
    - 수수료 배분은 주문의 **모든** trade `funds` 비율이다. scale은 `paid_fee.scale() + 8` HALF_DOWN이고, 나머지는 마지막 trade(`created_at`, uuid 순)에 넣는다.
    - 반환은 `fromExclusive < created_at <= toInclusive`인 trade만이다. id = `"$orderUuid:$tradeUuid"`.
  - 시각은 `OffsetDateTime.parse(...).toInstant()`다. 필드가 없거나 숫자가 아니면 `AccountReadException(UNEXPECTED)`.
- **테스트** `io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbFillHistoryPrivateAccountAdapterTest` (MockWebServer):
  - 요청 method가 전부 GET이고 경로가 두 종류뿐이다.
  - 창을 걸친 주문은 창 안 trade만 나온다.
  - 수수료 배분 합 = `paid_fee`.
  - 7일 컷오프에서 다음 페이지를 요청하지 않는다.
  - 20페이지 초과는 WindowLimit, 401은 `BITHUMB_AUTH`, wait 주문 포함.
  - 서명은 query hash를 포함한다(기존 `BithumbOrderQueryHashTest` 관례).
- **검증:** dod AC5 명령, `:infrastructure:batch:test`(R1)

## Task 4 — 바이낸스 체결 adapter (AC6)

- **파일:**
  - `infrastructure/batch/.../exchange/binance/BinanceFuturesSignedReadClient.kt`: 기존 adapter의 `signedGet`·`send`·`measureOffset`·서버 시각 offset을 옮긴다. 기존 adapter가 이를 쓰게 바꾸고, 기존 테스트는 무수정 통과해야 한다. 이름은 `*ReadClient.kt` 규칙이라 아키텍처 스캔 대상이다.
  - `BinanceFillHistoryPrivateAccountAdapter.kt`: `ForeignPerpFillReadPort`
- **결정:**
  - userTrades: `symbol=BTCUSDT&startTime&endTime&limit=1000`.
    - 창은 `[from + 1ms, to]`를 7일(−1ms) 단위로 **앞으로** 나눈다. 한 창이 1000행이면 `FillHistoryWindowLimitException`.
    - 모든 positionSide를 포함한다. 부호는 BUY +, SELL −.
    - 체결 시각으로 `(from, to]`를 다시 거르고 id로 dedup한다.
  - commission: `abs()`. asset이 USDT면 그대로 쓰고, BNB면 `GET /fapi/v1/klines?symbol=BNBUSDT&interval=1m&startTime={분 시작 ms}&limit=1` 첫 행 open을 곱한다(같은 분은 호출 내에서 캐시). 그 밖 asset은 UNEXPECTED.
  - income: `incomeType=FUNDING_FEE&symbol=BTCUSDT&startTime&endTime&limit=1000`, 같은 7일 창이다.
    - 창 안에서 1000행이면 마지막 time부터 이어 읽고 `tranId`로 dedup한다.
    - 응답 `symbol != BTCUSDT`는 버리고, asset이 USDT가 아니면 UNEXPECTED.
- **테스트** `io.premiumspread.infrastructure.batch.exchange.binance.BinanceFillHistoryPrivateAccountAdapterTest`:
  - 20일 창을 7일 3구간으로 요청한다.
  - 창 경계 중복 id는 1회만 담는다. 1000행이면 WindowLimit.
  - income 요청에 symbol 파라미터가 있고, 다른 심볼 행은 제외한다.
  - 음수 commission(USDT·BNB)을 절댓값으로 처리한다.
  - BNB는 1분봉 open으로 환산하고, 두 번 호출해도 같은 값이다.
  - 모든 요청이 GET이다.
- **검증:** dod AC6 명령, `:infrastructure:batch:test`(R1)

## Task 5 — Job·배선·아키텍처 대상 (AC4, AC7, AC9)

- **파일:**
  - `domain/.../job/JobPorts.kt`: `ACCOUNT_REALIZED_PNL("account_realized_pnl")`
  - `apps/batch/.../application/common/JobConfig.kt`: 기본 `lock:account-pnl:settlement`, lease 150s, timeout 120s
  - `apps/batch/.../config/BatchJobProperties.kt`: `accountRealizedPnl` LockSpec
  - `apps/batch/.../application/job/accountpnl/AccountRealizedPnlJob.kt`
  - `apps/batch/.../interfaces/scheduling/AccountRealizedPnlScheduler.kt`: `fixedRateString = "${batch.scheduling.account-realized-pnl.fixed-rate:300000}"`, 조건은 모니터 스케줄러와 같고 `realized-pnl.enabled`를 더한다.
  - `apps/batch/.../config/AccountRealizedPnlConfiguration.kt`: 조건 = `@ConditionalOnBatchScheduling` + `account-pnl-monitor.enabled` + `AccountPnlMonitorEnabledCondition` + `@ConditionalOnProperty(account-pnl-monitor.realized-pnl.enabled, havingValue=true, matchIfMissing=true)`. 바이낸스 port는 `ObjectProvider`로 받는다.
  - `infrastructure/batch/.../accountpnl/AccountPnlMonitorAdapterConfiguration.kt`: 빗썸 체결 adapter bean(항상), 바이낸스 체결 adapter bean(`BinanceApiModeCondition`)
  - `apps/batch/src/main/resources/application.yml`: `account-pnl-monitor.realized-pnl.{enabled,settle-delay,first-episode-lookback,max-age}` 기본값과 env 이름 `ACCOUNT_PNL_REALIZED_PNL_ENABLED`
  - `architecture-tests/.../PrivateAccountReadOnlyArchitectureTest.kt`: `REQUIRED_TARGETS`에 새 adapter 2개 추가
  - JobId를 열거하는 테스트(`OwnerAlertJobConfigTest` 등)가 깨지면 기대 목록에 새 id를 넣는다(목록 갱신만 허용).
- **Job 흐름** (`fun run(): JobResult = executor.execute(jobConfigs.get(ACCOUNT_REALIZED_PNL)) { settle() }`):
  1. `store.findTarget(now, delay, maxAge, lookback)`. null이면 Success.
  2. 빗썸: `koreaFills.read(lower, occurredAt)` → `RealizedPnlPolicy.korea(fills, legs.korea.quantity)`.
     - `AccountReadException`은 KOREA_READ, WindowLimit은 WINDOW_LIMIT, null은 KOREA_QUANTITY.
  3. 바이낸스: source가 MANUAL_ENV이거나 port가 없으면 MANUAL이다. 아니면 fills·funding → `foreign(…, legs.foreign.signedQuantity)`.
     - 예외와 null은 FOREIGN_READ·WINDOW_LIMIT·FOREIGN_QUANTITY로 매핑한다.
  4. 두 쪽이 CONFIRMED면 환율은 `store.findFxAt(occurredAt, 10분)`.
     - 없으면 `FxRateReadPort.findLatest(USD, KRW)`이고, 모니터와 같은 `FxIngestionFreshness` 신선도 판정을 쓴다.
     - 실패하면 FX 사유로 미확정이다(두 쪽 값은 저장, total NULL).
  5. 실패 사유가 둘 이상이면 먼저 난 것(빗썸 → 바이낸스 → FX)을 기록한다.
     - `nextAttemptAt = now + 5분 × 2^(target.attempts)`, 상한 6시간.
     - `store.record`. store 예외는 `JobResult.Failure`, 그 밖은 Success.
- **테스트:**
  - `io.premiumspread.application.job.accountpnl.AccountRealizedPnlJobTest`(가짜 port·store·executor): dod AC4 (a)~(e)
  - `io.premiumspread.config.AccountRealizedPnlWiringTest`(ApplicationContextRunner, 기존 `AccountPnlMonitorWiringTest` 관례): AC9 세 경우
- **검증:** dod AC4·AC7·AC9 명령, `:apps:batch:test`

## Task 6 — 끝 단 통합 테스트 (AC2)

- **파일:** `apps/batch/src/integrationTest/kotlin/io/premiumspread/application/job/AccountRealizedPnlSettlementIntegrationTest.kt`
- **결정:**
  - `BatchIntegrationTestBase`의 MySQL과 MockWebServer를 쓴다.
  - 실제 `BithumbFillHistoryPrivateAccountAdapter`·`BinanceFillHistoryPrivateAccountAdapter`는 테스트 안에서 MockWebServer URL로 직접 생성한다. 실제 `JdbcAccountRealizedPnlStore`는 context bean이거나 직접 생성한다.
  - Job은 테스트 안에서 고정 Clock으로 생성한다. executor는 context의 것이나 즉시 실행 가짜다.
  - 건·사건·관측 행은 SQL fixture로 넣는다.
  - fixture 금액은 손으로 합한 기대값을 테스트에 상수로 적는다.
  - 경우 (a) 전량, (b) REDUCED → CLOSED 누적, (c) 재실행·새 Job 인스턴스·겹친 창 중복 id. dod AC2 문장대로 단언한다.
- **검증:** dod AC2 명령, `:apps:batch:integrationTest`(R3)

## Task 7 — 문서

- `docs/runbooks/account-pnl-alert.md`에 "확정 손익" 절을 추가한다.
  - 열 의미와 상태값
  - 귀속 전제(전용 계좌, 롱 체결 포함)
  - 90일 한도
  - PENDING 사유 범주와 backoff
  - 잔여 위험(7일 초과 지정가 왕복)
  - owner 대조 절차: seed 건 SQL 조회 → 거래소 내역과 비교
  - env
- `.ai/PROJECT_STATUS.md`·`ARCHITECTURE_DESIGN.md`는 마무리 단계 tech-docs가 갱신한다.

## 사전 충돌 점검

| 대상 | 생산 → 소비 | 결과 |
|---|---|---|
| T1 → T2·T3·T4·T5 | 공유 계약 타입·포트 | 시그니처를 위 블록으로 고정 |
| T2 ↔ T5 | `findTarget`/`record` 의미(backoff·단조) | `record`의 nextAttemptAt 계산은 Job, 저장은 store |
| T3·T4 ↔ T5 | `FillHistoryWindowLimitException` → WINDOW_LIMIT | 일치 |
| T3·T4 ↔ 기존 adapter | 공유 클라이언트 추출 | R1: 기존 테스트 무수정 통과가 조건 |
| T5 ↔ OwnerOpsSignalJob | `JobId.entries` 감시 | 새 Job도 감시 대상. 의도와 일치(PENDING은 Success) |
| T6 ↔ T2~T5 | 전 구성 | 마지막 순서 |
