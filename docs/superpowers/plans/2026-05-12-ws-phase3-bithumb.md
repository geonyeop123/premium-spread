# Phase 3: 빗썸 WebSocket 통합 (1Hz down-sample) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 빗썸 이벤트 기반 ticker push를 in-memory에 보관하고 `@Scheduled(fixedRate=1000)`로 ZSet에 1Hz down-sample 저장한다. Hash는 메시지 수신 시점에만 갱신해 5초 TTL freshness 의미를 보존.

**Issue:** #31 · **Epic:** #28 · **Spec:** `docs/superpowers/specs/2026-05-12-websocket-ingestion-design.md` (Phase 3 섹션) + Issue #31 body

**Worktree:** `.worktrees/feat-issue-31-bithumb-ws` (branch: `feat/issue-31-bithumb-ws`, base: `feature/premium`)

---

## 사전 결정 사항

1. **`TickerIngestionJob` 수정은 Phase 2 PR에서 모두 처리 (mode 분기 binance+bithumb 동시 추가)** — 본 Phase 3 PR은 `TickerIngestionJob`을 건드리지 않는다. 즉 Phase 2 PR이 먼저 머지되어야 `bithumb.mode=websocket` 토글이 의미 있음. 머지 순서 의존성 ↔ 코드 충돌은 없음 (서로 다른 파일).
   - **만약 Phase 3 PR이 Phase 2 PR보다 먼저 머지된다면**: `bithumb.mode=websocket`로 토글해도 REST 폴링이 계속 돌아 hash가 더 자주 갱신되지만, 충돌 없음. Phase 2 머지 후 정상화.
2. **`saveToSecondsWithScore(ticker, scoreInstant)` 신규 메서드** — 기존 `saveToSeconds(ticker)`는 `ticker.timestamp`를 score로 사용. 신규 메서드는 score를 명시적으로 받아 flush 시점(`Instant.now()`)을 사용. Hash는 건드리지 않음. 기존 `saveToSeconds`는 `saveToSecondsWithScore(ticker, ticker.timestamp)`의 alias로 유지.
3. **ZSet member 포맷 — `{epochMs}:{price}`** *(Codex review 1차 ACCEPT)*: Redis ZSet은 멤버 유일성을 강제하므로 같은 가격 문자열이 다른 score로 들어오면 기존 entry의 score만 update돼서 1개 entry로 collapse된다. Flat-price 시나리오에서 1Hz down-sample이 깨지는 결함을 막기 위해 `saveToSecondsWithScore`는 멤버를 `{epochMs}:{price}` 형식으로 인코딩한다. 기존 `saveToSeconds`도 동일 포맷을 사용하도록 통일 (alias 관계 유지). `getSecondsData` 리더는 `":"` 포함 여부로 신/구 포맷 둘 다 파싱 (기존 데이터 호환).
4. **ingestion 컴포넌트는 `infrastructure/ingestion/bithumb/` 패키지** *(Codex review 2차 MAINTAINED → ACCEPT)*: `BithumbTickerIngestion` / `BithumbFlushJob`은 WebSocket 메시지 처리 + 캐시 어댑터 역할이므로 인프라 레이어가 자연. `WebSocketMetrics`(infrastructure) 직접 주입을 application 패키지에서 하는 것은 `.ai/rules/architecture.md`의 의존 방향 위반 소지. `application.ingestion` 대신 `infrastructure.ingestion.bithumb`로 배치. `BithumbFlushScheduler`는 `scheduler/` 그대로 유지 (thin entrypoint = 배치 규칙 명시 위치).
5. **monotonic check는 `AtomicReference.updateAndGet`로 atomic** *(Codex review ISSUE-3 ACCEPT)*: 단순 get→비교→set 패턴은 동시 메시지 사이의 race로 오래된 timestamp가 latest를 덮어쓸 수 있음. CAS 루프로 atomic 보장 + hash save는 update 성공 후에만 수행.
6. **exchange timestamp 파싱 실패는 메시지 폐기 + parse-error 메트릭** *(Codex review ISSUE-4 ACCEPT)*: `parseTimestamp`가 `Instant.now()`로 fallback 하지 않고 null 반환. `parse()`도 null 반환 → 메시지 폐기. `MeterRegistry`로 `ws.parse.error{exchange=bithumb}` counter 증가. 연속 5회 parse 실패 시 critical alert.
7. **`Clock` 주입** — `Instant.now()`를 테스트에서 제어하기 위해 `Clock`을 생성자 주입. Spring config에서 `Clock.systemUTC()`를 빈으로 노출 (없으면 추가).
8. **stale 기준 10초** — `age > 10s`면 flush skip + `ws.stale.bithumb` 증가. (issue spec 명시.)
9. **5회 연속 flush 실패 시 critical alert** — 단발 실패는 swallow + 다음 주기 재시도. 5회 연속은 별도 카운터로 추적.
10. **`batch:last-run:bithumb-flush` Redis 키** — 성공 시마다 갱신, TTL = `RedisTtl.BATCH_HEALTH`. (헬스체크 + 운영 모니터링 용도.)
11. **lag 메트릭 (`ws.message.lag.ms`)** *(Codex review ISSUE 추가 보강)*: 메시지 수신 시 `Duration.between(ticker.timestamp, Instant.now(clock))`을 `metrics.recordLag(exchange, lagMs)`로 기록. 음수는 0으로 clamp.
12. **테스트**:
    - 단위: ingestion atomic CAS(최댓값만 latest), flush 정상/stale/null/exception 분기, parse 실패(missing date/time → null), 동일 가격 5회 flush → ZSet 5 distinct entries
    - 통합: MockWebServer + Redis TC, 5초간 1건 push → ZSet 5 distinct scores + hash 1회만 갱신, 메시지 zero → first.message.timeout alert, reverse-order → discard

## 파일 구조

| 파일 | 역할 |
|------|------|
| Create `apps/batch/src/main/kotlin/io/premiumspread/client/bithumb/BithumbWebSocketResponse.kt` | 빗썸 ticker stream payload DTO |
| Create `apps/batch/src/main/kotlin/io/premiumspread/client/bithumb/BithumbWebSocketClient.kt` | WebSocket 연결 + subscribe + 파싱 → ingestion (parse 실패 시 메시지 폐기 + parse-error 메트릭) |
| Create `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/ingestion/bithumb/BithumbTickerIngestion.kt` | monotonic CAS + AtomicReference + hash save + lag 메트릭 + 캐시 실패 alert |
| Create `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/ingestion/bithumb/BithumbFlushJob.kt` | 1초 flush — ZSet only (saveToSecondsWithScore), stale/exception/alert |
| Create `apps/batch/src/main/kotlin/io/premiumspread/scheduler/BithumbFlushScheduler.kt` | thin `@Scheduled(fixedRate=1000)` entrypoint |
| Modify `apps/batch/src/main/kotlin/io/premiumspread/cache/TickerCacheService.kt` | `saveToSecondsWithScore(ticker, scoreInstant)` 신규 메서드 + ZSet 멤버 `{epochMs}:{price}` 포맷, 기존 `saveToSeconds`는 alias, `getSecondsData` 리더가 양쪽 포맷 지원 |
| Create or extend `apps/batch/src/main/kotlin/io/premiumspread/config/ClockConfig.kt` | `Clock.systemUTC()` 빈 (없으면 신규) |
| Modify `apps/batch/src/main/resources/application.yml` | `premium.ingestion.bithumb.mode=rest` 기본값 (Phase 2 미머지 대비 안전망) |
| Create `apps/batch/src/test/kotlin/io/premiumspread/cache/TickerCacheServiceScoreTest.kt` | `saveToSecondsWithScore` 단위 테스트 (동일 가격 5회 → ZSet 5 entries 검증 포함) |
| Create `apps/batch/src/test/kotlin/io/premiumspread/infrastructure/ingestion/bithumb/BithumbTickerIngestionTest.kt` | monotonic CAS + thread-safe + 캐시 실패 alert + lag |
| Create `apps/batch/src/test/kotlin/io/premiumspread/infrastructure/ingestion/bithumb/BithumbFlushJobTest.kt` | flush 분기 + alert + last-run + ZSet only 검증 |
| Create `apps/batch/src/test/kotlin/io/premiumspread/client/bithumb/BithumbWebSocketIntegrationTest.kt` | `@Tag("integration")` 통합 시나리오 (5초 flat-price → 5 distinct ZSet entries 검증 포함) |

---

### Task 1: TickerCacheService — `saveToSecondsWithScore` 추가

**Files:**
- Modify: `apps/batch/src/main/kotlin/io/premiumspread/cache/TickerCacheService.kt`
- Modify or Create: `apps/batch/src/test/kotlin/io/premiumspread/cache/TickerCacheServiceTest.kt`

- [x] **Step 1: 메서드 추가 — ZSet member 포맷 `{epochMs}:{price}`**

`TimeSeriesCacheSupport.add`는 같은 score 중복만 제거하고, 같은 value(price)/다른 score 케이스는 멤버 유일성으로 인해 entry 1개로 collapse된다. Phase 3는 동일 가격 1Hz flush 시 5 distinct entries가 필요하므로 멤버에 timestamp를 포함시킨다.

→ `TimeSeriesCacheSupport`를 우회하고 `redisTemplate`로 직접 ZADD. retention 정리도 같은 위치에서 수행.

```kotlin
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

// (생성자에 redisTemplate, timeSeriesCache 기존 그대로)

/**
 * 초당 데이터 ZSet에 저장 (score 명시 + 멤버에 timestamp 포함하여 유일성 보장).
 *
 * - 멤버 포맷: `{epochMs}:{price}` — 같은 가격이 연속 flush돼도 distinct entries 누적.
 * - Hash는 건드리지 않음 (Phase 3 freshness 5s TTL 의미 보존).
 */
fun saveToSecondsWithScore(ticker: TickerData, scoreInstant: Instant) {
    val key = TickerAggregationTimeUnit.SECONDS.keyFor(ticker.exchange, ticker.symbol)
    val score = scoreInstant.toEpochMilli().toDouble()
    val member = "${scoreInstant.toEpochMilli()}:${ticker.price.toPlainString()}"

    redisTemplate.opsForZSet().add(key, member, score)
    redisTemplate.expire(key, RedisTtl.SECONDS_DATA)

    // retention: TTL 이전 데이터 정리
    val cutoff = Instant.now().minus(RedisTtl.SECONDS_DATA).toEpochMilli().toDouble()
    redisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff)

    log.debug("Saved ticker to seconds ZSet (member={}, score={}): {}", member, score, key)
}

/**
 * 초당 데이터 ZSet에 저장 (ticker.timestamp를 score로 사용 — REST 경로 기본).
 */
fun saveToSeconds(ticker: TickerData) {
    saveToSecondsWithScore(ticker, ticker.timestamp)
}
```

- [x] **Step 2: `getSecondsData` 리더가 신/구 멤버 포맷 둘 다 파싱하도록 갱신**

`":"` 포함 여부로 분기:

```kotlin
fun getSecondsData(exchange: String, symbol: String, from: Instant, to: Instant): List<Pair<Instant, BigDecimal>> {
    val key = TickerAggregationTimeUnit.SECONDS.keyFor(exchange, symbol)
    val entries = timeSeriesCache.rangeByTime(key, from, to)

    return entries.mapNotNull { entry ->
        val member = entry.value ?: return@mapNotNull null
        val priceStr = if (":" in member) member.substringAfter(":") else member
        val price = priceStr.toBigDecimalOrNull() ?: return@mapNotNull null
        val timestamp = timeSeriesCache.extractTimestamp(entry) ?: return@mapNotNull null
        timestamp to price
    }
}
```

- [x] **Step 3: 단위 테스트 추가 — 동일 가격 5회 flush → ZSet 5 distinct entries 검증**

기존 `TickerCacheServiceTest`(있다면)에 케이스 추가 또는 `TickerCacheServiceScoreTest.kt` 신규 생성. Redis 통신은 mock 또는 TestContainer 기반(`@Tag("integration")`)으로 결정.

```kotlin
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestContainersConfig::class)
class TickerCacheServiceScoreTest {
    @Autowired private lateinit var tickerCacheService: TickerCacheService

    @Test
    fun `saveToSecondsWithScore는 ticker timestamp가 아닌 명시 score를 ZSet에 저장한다`() {
        val ticker = TickerData(
            exchange = "BITHUMB", symbol = "BTC", currency = "KRW",
            price = BigDecimal("100000000"), volume = null,
            timestamp = Instant.parse("2026-05-12T00:00:00Z"),
        )
        val score = Instant.parse("2026-05-12T00:00:05Z")

        tickerCacheService.saveToSecondsWithScore(ticker, score)

        val results = tickerCacheService.getSecondsData(
            ticker.exchange, ticker.symbol, score.minusSeconds(1), score.plusSeconds(1),
        )
        assertThat(results).hasSize(1)
        assertThat(results.first().first).isEqualTo(score)
        assertThat(results.first().second).isEqualByComparingTo(BigDecimal("100000000"))
    }

    @Test
    fun `동일 가격을 다른 score로 5회 저장하면 ZSet에 5 distinct entries가 누적된다 (flat-price 회귀 방지)`() {
        val baseTimestamp = Instant.parse("2026-05-12T00:00:00Z")
        val price = BigDecimal("100000000")
        val ticker = TickerData(
            exchange = "BITHUMB", symbol = "BTC", currency = "KRW",
            price = price, volume = null, timestamp = baseTimestamp,
        )

        // 1초 간격으로 5회 flush — 같은 가격이라도 ZSet 멤버는 epochMs:price로 unique
        val scores = (0..4).map { baseTimestamp.plusSeconds(it.toLong()) }
        scores.forEach { tickerCacheService.saveToSecondsWithScore(ticker, it) }

        val results = tickerCacheService.getSecondsData(
            ticker.exchange, ticker.symbol,
            baseTimestamp.minusSeconds(1), baseTimestamp.plusSeconds(10),
        )
        assertThat(results).hasSize(5)
        assertThat(results.map { it.first }).containsExactlyElementsOf(scores)
        assertThat(results).allSatisfy { (_, p) -> assertThat(p).isEqualByComparingTo(price) }
    }
}
```

> 기존 `TickerCacheServiceTest`가 단위/통합 어느 쪽인지 확인하고 일관성 유지. 통합 테스트로 두는 편이 Redis ZSet 멤버 동작을 검증하기 위해 더 정확.

---

### Task 2: BithumbWebSocketResponse DTO

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/client/bithumb/BithumbWebSocketResponse.kt`

빗썸 WebSocket subscribe 응답 + ticker 메시지 두 종류:

```json
// subscribe 응답
{"status":"0000","resmsg":"Connected Successfully"}

// ticker 메시지
{
  "type":"ticker",
  "content":{
    "symbol":"BTC_KRW",
    "tickType":"24H",
    "date":"20260512",
    "time":"093000",
    "openPrice":"129000000",
    "closePrice":"129555000",
    "lowPrice":"128000000",
    "highPrice":"130000000",
    "value":"123456789",
    "volume":"1234.5678",
    "sellVolume":"600",
    "buyVolume":"634.5678",
    "prevClosePrice":"128500000",
    "chgRate":"0.82",
    "chgAmt":"1055000",
    "volumePower":"105.76"
  }
}
```

- [x] **Step 1: 파일 작성**

```kotlin
package io.premiumspread.client.bithumb

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * 빗썸 WebSocket Public — ticker 채널 메시지.
 *
 * `type` 이 "ticker"가 아닌 메시지(예: 구독 응답)는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BithumbWebSocketTickerMessage(
    val type: String? = null,
    val content: BithumbWebSocketTickerContent? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BithumbWebSocketTickerContent(
    val symbol: String,                   // 예: "BTC_KRW"
    val tickType: String? = null,         // "24H" 등
    val date: String? = null,             // "yyyyMMdd"
    val time: String? = null,             // "HHmmss"
    val closePrice: String,               // 종가 (현재가)
    val openPrice: String? = null,
    val lowPrice: String? = null,
    val highPrice: String? = null,
    val volume: String? = null,
    val chgRate: String? = null,
    val chgAmt: String? = null,
)
```

---

### Task 3: BithumbTickerIngestion (pure component — infrastructure 레이어)

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/ingestion/bithumb/BithumbTickerIngestion.kt`
- Create: `apps/batch/src/test/kotlin/io/premiumspread/infrastructure/ingestion/bithumb/BithumbTickerIngestionTest.kt`

- [x] **Step 1: 본체 작성 (atomic CAS + lag + 캐시 실패 alert)**

```kotlin
package io.premiumspread.infrastructure.ingestion.bithumb

import io.premiumspread.cache.TickerCacheService
import io.premiumspread.client.TickerData
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 빗썸 WebSocket으로 도착한 TickerData를 in-memory에 보관하고 hash를 갱신한다.
 *
 * - hash 갱신은 메시지 수신 시점 (exchange timestamp 그대로). TTL 5s freshness 의미 보존.
 * - ZSet 저장은 [BithumbFlushJob]이 1초 주기로 처리 (down-sample).
 * - monotonic check는 `updateAndGet` CAS로 atomic 처리 — 동시 메시지의 race 차단.
 * - 캐시 저장 실패 5회 연속 → critical alert.
 */
@Component
@ConditionalOnProperty("premium.ingestion.bithumb.mode", havingValue = "websocket")
class BithumbTickerIngestion(
    private val tickerCacheService: TickerCacheService,
    private val metrics: WebSocketMetrics,
    private val alertService: AlertService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastTicker = AtomicReference<LatestTicker?>(null)
    private val consecutiveSaveFailures = AtomicInteger(0)

    data class LatestTicker(val ticker: TickerData, val receivedAt: Instant)

    fun onMessage(ticker: TickerData) {
        val now = Instant.now(clock)
        val candidate = LatestTicker(ticker, now)

        // Atomic monotonic CAS — 오래된 메시지가 통과하면 prev 그대로 유지
        val updated = lastTicker.updateAndGet { prev ->
            if (prev != null && !ticker.timestamp.isAfter(prev.ticker.timestamp)) prev else candidate
        }
        if (updated !== candidate) {
            metrics.recordOutOfOrder(EXCHANGE)
            log.debug(
                "Discard out-of-order bithumb ticker: prev={}, current={}",
                updated?.ticker?.timestamp, ticker.timestamp,
            )
            return
        }

        // lag 메트릭 (exchange timestamp → now)
        val lagMs = Duration.between(ticker.timestamp, now).toMillis().coerceAtLeast(0)
        metrics.recordLag(EXCHANGE, lagMs)

        // Hash 저장 — 실패 시 메트릭 + threshold alert (lastTicker는 이미 갱신됐으므로 ZSet은 다음 flush에서 가능)
        try {
            tickerCacheService.save(ticker)
            consecutiveSaveFailures.set(0)
        } catch (e: Exception) {
            metrics.recordFlushError(EXCHANGE, e)
            val failures = consecutiveSaveFailures.incrementAndGet()
            log.warn("Bithumb hash save failed (consecutive={}): {}", failures, e.message)
            if (failures >= FAILURE_ALERT_THRESHOLD) {
                alertService.sendCriticalAlert("[bithumb] WebSocket ingestion hash 저장 5회 연속 실패: ${e.message}")
                consecutiveSaveFailures.set(0)
            }
        }
    }

    fun latest(): LatestTicker? = lastTicker.get()

    companion object {
        const val EXCHANGE = "bithumb"
        private const val FAILURE_ALERT_THRESHOLD = 5
    }
}
```

- [x] **Step 2: 단위 테스트 작성**

```kotlin
package io.premiumspread.infrastructure.ingestion.bithumb

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.client.TickerData
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BithumbTickerIngestionTest {
    private lateinit var cache: TickerCacheService
    private lateinit var metrics: WebSocketMetrics
    private lateinit var alertService: AlertService
    private lateinit var ingestion: BithumbTickerIngestion
    private val fixedNow: Instant = Instant.parse("2026-05-12T00:00:10Z")

    @BeforeEach
    fun setUp() {
        cache = mockk(relaxed = true)
        metrics = mockk(relaxed = true)
        alertService = mockk(relaxed = true)
        ingestion = BithumbTickerIngestion(
            tickerCacheService = cache,
            metrics = metrics,
            alertService = alertService,
            clock = Clock.fixed(fixedNow, ZoneOffset.UTC),
        )
    }

    @Test
    fun `정상 메시지는 hash를 저장하고 latest를 갱신하며 lag를 기록한다`() {
        val ts = Instant.parse("2026-05-12T00:00:08Z") // now - 2s
        val ticker = tickerAt(ts)

        ingestion.onMessage(ticker)

        verify(exactly = 1) { cache.save(ticker) }
        verify(exactly = 1) { metrics.recordLag("bithumb", 2000L) }
        assertThat(ingestion.latest()?.ticker).isEqualTo(ticker)
        assertThat(ingestion.latest()?.receivedAt).isEqualTo(fixedNow)
    }

    @Test
    fun `직전보다 오래된 exchange timestamp는 폐기되고 out_of_order가 증가한다`() {
        val first = tickerAt(Instant.parse("2026-05-12T00:00:05Z"))
        val stale = tickerAt(Instant.parse("2026-05-12T00:00:01Z"))
        ingestion.onMessage(first)

        ingestion.onMessage(stale)

        verify(exactly = 0) { cache.save(stale) }
        verify(exactly = 1) { metrics.recordOutOfOrder("bithumb") }
        assertThat(ingestion.latest()?.ticker).isEqualTo(first)
    }

    @Test
    fun `동시에 다수 스레드에서 호출해도 최종 latest는 최대 timestamp가 된다 (CAS atomic 보장)`() {
        val threadCount = 16
        val perThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val base = Instant.parse("2026-05-12T00:00:00Z")
        val maxOffsetMs = (threadCount * perThread - 1).toLong()

        repeat(threadCount) { t ->
            executor.submit {
                try {
                    repeat(perThread) { i ->
                        ingestion.onMessage(tickerAt(base.plusMillis((t * perThread + i).toLong())))
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue
        executor.shutdownNow()

        val final = ingestion.latest()
        assertThat(final).isNotNull
        // CAS이므로 최종 latest는 정확히 max timestamp여야 함 (race로 인한 regression 없음)
        assertThat(final!!.ticker.timestamp).isEqualTo(base.plusMillis(maxOffsetMs))
    }

    @Test
    fun `cache save 5회 연속 실패면 sendCriticalAlert가 호출되고 counter는 리셋된다`() {
        every { cache.save(any()) } throws RuntimeException("redis down")

        repeat(5) {
            ingestion.onMessage(tickerAt(Instant.parse("2026-05-12T00:00:00Z").plusMillis(it.toLong())))
        }

        verify(exactly = 5) { metrics.recordFlushError(eq("bithumb"), any()) }
        verify(exactly = 1) { alertService.sendCriticalAlert(match { it.contains("5회 연속") }) }
    }

    private fun tickerAt(timestamp: Instant) = TickerData(
        exchange = "BITHUMB",
        symbol = "BTC",
        currency = "KRW",
        price = BigDecimal("100000000"),
        volume = null,
        timestamp = timestamp,
    )
}
```

---

### Task 4: BithumbFlushJob (1Hz flush — infrastructure 레이어)

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/ingestion/bithumb/BithumbFlushJob.kt`
- Create: `apps/batch/src/test/kotlin/io/premiumspread/infrastructure/ingestion/bithumb/BithumbFlushJobTest.kt`

- [ ] **Step 1: 본체 작성**

```kotlin
package io.premiumspread.infrastructure.ingestion.bithumb

import io.premiumspread.cache.TickerCacheService
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import io.premiumspread.redis.RedisTtl
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * 빗썸 WebSocket으로 받은 최신 ticker를 1초 주기로 ZSet에 flush 한다.
 *
 * - Hash는 [BithumbTickerIngestion]이 메시지 수신 시점에 이미 갱신 → 본 job은 ZSet만 갱신.
 * - score는 flush 시점(`Instant.now(clock)`)을 사용 — exchange timestamp가 변하지 않아도 distinct score 보장.
 * - 마지막 메시지가 10초 이상 지난 경우 stale 처리하고 skip.
 * - flush 실패 5회 연속 발생 시 critical alert.
 */
@Component
@ConditionalOnProperty("premium.ingestion.bithumb.mode", havingValue = "websocket")
class BithumbFlushJob(
    private val ingestion: BithumbTickerIngestion,
    private val tickerCacheService: TickerCacheService,
    private val metrics: WebSocketMetrics,
    private val alertService: AlertService,
    private val redisTemplate: StringRedisTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val consecutiveFailures = AtomicInteger(0)

    fun run() {
        val latest = ingestion.latest() ?: return
        val now = Instant.now(clock)
        val age = Duration.between(latest.receivedAt, now)
        if (age > STALE_THRESHOLD) {
            metrics.recordStale(EXCHANGE)
            return
        }
        try {
            tickerCacheService.saveToSecondsWithScore(latest.ticker, now)
            redisTemplate.opsForValue().set(LAST_RUN_KEY, now.toEpochMilli().toString(), RedisTtl.BATCH_HEALTH)
            metrics.recordFlush(EXCHANGE)
            consecutiveFailures.set(0)
        } catch (e: Exception) {
            metrics.recordFlushError(EXCHANGE, e)
            val failures = consecutiveFailures.incrementAndGet()
            log.warn("Bithumb flush failed (consecutive={}): {}", failures, e.message)
            if (failures >= FAILURE_ALERT_THRESHOLD) {
                alertService.sendCriticalAlert("[bithumb] flush 5회 연속 실패: ${e.message}")
                consecutiveFailures.set(0)
            }
        }
    }

    companion object {
        const val EXCHANGE = "bithumb"
        val STALE_THRESHOLD: Duration = Duration.ofSeconds(10)
        const val FAILURE_ALERT_THRESHOLD = 5
        const val LAST_RUN_KEY = "batch:last-run:bithumb-flush"
    }
}
```

- [ ] **Step 2: 단위 테스트 작성**

```kotlin
package io.premiumspread.infrastructure.ingestion.bithumb

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.infrastructure.ingestion.bithumb.BithumbTickerIngestion.LatestTicker
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.client.TickerData
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BithumbFlushJobTest {
    private val now = Instant.parse("2026-05-12T00:00:10Z")
    private lateinit var ingestion: BithumbTickerIngestion
    private lateinit var cache: TickerCacheService
    private lateinit var metrics: WebSocketMetrics
    private lateinit var alertService: AlertService
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var valueOps: ValueOperations<String, String>
    private lateinit var job: BithumbFlushJob

    @BeforeEach
    fun setUp() {
        ingestion = mockk()
        cache = mockk(relaxed = true)
        metrics = mockk(relaxed = true)
        alertService = mockk(relaxed = true)
        redisTemplate = mockk()
        valueOps = mockk(relaxed = true)
        every { redisTemplate.opsForValue() } returns valueOps
        job = BithumbFlushJob(
            ingestion, cache, metrics, alertService, redisTemplate,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    @Test
    fun `latest가 null이면 아무것도 하지 않는다`() {
        every { ingestion.latest() } returns null

        job.run()

        verify(exactly = 0) { cache.saveToSecondsWithScore(any(), any()) }
        verify(exactly = 0) { metrics.recordStale(any()) }
    }

    @Test
    fun `정상이면 ZSet score를 now로 저장하고 last-run을 갱신한다`() {
        val ticker = tickerData()
        every { ingestion.latest() } returns LatestTicker(ticker, now.minusSeconds(2))

        job.run()

        verify(exactly = 1) { cache.saveToSecondsWithScore(ticker, now) }
        verify(exactly = 1) { valueOps.set(BithumbFlushJob.LAST_RUN_KEY, now.toEpochMilli().toString(), any()) }
        verify(exactly = 1) { metrics.recordFlush("bithumb") }
        verify(exactly = 0) { metrics.recordStale(any()) }
    }

    @Test
    fun `age가 10초를 초과하면 stale로 분류되고 flush를 호출하지 않는다`() {
        val ticker = tickerData()
        every { ingestion.latest() } returns LatestTicker(ticker, now.minusSeconds(11))

        job.run()

        verify(exactly = 0) { cache.saveToSecondsWithScore(any(), any()) }
        verify(exactly = 1) { metrics.recordStale("bithumb") }
    }

    @Test
    fun `save 예외 5회 연속이면 sendCriticalAlert를 호출하고 counter는 리셋된다`() {
        val ticker = tickerData()
        every { ingestion.latest() } returns LatestTicker(ticker, now.minusSeconds(2))
        every { cache.saveToSecondsWithScore(any(), any()) } throws RuntimeException("redis down")

        repeat(5) { job.run() }

        verify(exactly = 1) { alertService.sendCriticalAlert(match { it.contains("5회 연속") }) }

        // 6번째에는 이미 리셋됐으니 다시 1회로 시작 — 추가 alert는 아직 없음
        job.run()
        verify(exactly = 1) { alertService.sendCriticalAlert(any()) }
    }

    @Test
    fun `save 예외 후 다음에 성공하면 consecutive failure counter는 리셋된다`() {
        val ticker = tickerData()
        every { ingestion.latest() } returns LatestTicker(ticker, now.minusSeconds(2))
        every { cache.saveToSecondsWithScore(any(), any()) } throws RuntimeException("temp") andThen Unit andThen Unit

        repeat(2) { job.run() } // 1 fail, 1 success

        verify(exactly = 0) { alertService.sendCriticalAlert(any()) }

        // 다시 5회 실패해야 alert (counter 리셋 확인)
        every { cache.saveToSecondsWithScore(any(), any()) } throws RuntimeException("temp")
        repeat(5) { job.run() }
        verify(exactly = 1) { alertService.sendCriticalAlert(any()) }
    }

    private fun tickerData() = TickerData(
        exchange = "BITHUMB",
        symbol = "BTC",
        currency = "KRW",
        price = BigDecimal("100000000"),
        volume = null,
        timestamp = now.minusSeconds(2),
    )
}
```

---

### Task 5: BithumbFlushScheduler (thin entrypoint)

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/scheduler/BithumbFlushScheduler.kt`

- [ ] **Step 1: 본체 작성**

```kotlin
package io.premiumspread.scheduler

import io.premiumspread.infrastructure.ingestion.bithumb.BithumbFlushJob
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 빗썸 1초 flush 스케줄러 — thin entrypoint.
 *
 * - 단일 인스턴스 전제. 분산 락 불필요 → `JobExecutor` 미사용.
 * - 비즈니스/예외/메트릭/last-run/알람은 [BithumbFlushJob] 내부.
 */
@Component
@ConditionalOnProperty("premium.ingestion.bithumb.mode", havingValue = "websocket")
class BithumbFlushScheduler(
    private val flushJob: BithumbFlushJob,
) {
    @Scheduled(fixedRate = 1000)
    fun flush() {
        flushJob.run()
    }
}
```

---

### Task 6: BithumbWebSocketClient

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/client/bithumb/BithumbWebSocketClient.kt`

- [ ] **Step 1: 본체 작성**

```kotlin
package io.premiumspread.client.bithumb

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.premiumspread.client.TickerData
import io.premiumspread.infrastructure.ingestion.bithumb.BithumbTickerIngestion
import io.premiumspread.infrastructure.websocket.HeartbeatPolicy
import io.premiumspread.infrastructure.websocket.WebSocketConnectionConfig
import io.premiumspread.infrastructure.websocket.WebSocketConnectionManager
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * 빗썸 BTC_KRW 현물 ticker WebSocket 구독.
 *
 * - URL: wss://pubwss.bithumb.com/pub/ws
 * - 연결 직후 subscribe 메시지: {"type":"ticker","symbols":["BTC_KRW"],"tickTypes":["24H"]}
 * - idle 60초 종료 정책 대응 → ClientPing 30s 주기.
 */
@Component
@ConditionalOnProperty("premium.ingestion.bithumb.mode", havingValue = "websocket")
class BithumbWebSocketClient(
    private val ingestion: BithumbTickerIngestion,
    private val metrics: WebSocketMetrics,
    private val alertService: AlertService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val consecutiveParseErrors = AtomicInteger(0)
    private val parseErrorCounter: Counter = Counter.builder("ws.parse.error")
        .tag("exchange", EXCHANGE)
        .register(meterRegistry)

    private val manager = WebSocketConnectionManager(
        config = WebSocketConnectionConfig(
            exchange = EXCHANGE,
            url = URL,
            subscribeMessage = SUBSCRIBE_MESSAGE,
            heartbeat = HeartbeatPolicy.ClientPing(interval = PING_INTERVAL, pingMessage = PING_MESSAGE),
            onMessage = ::handlePayload,
        ),
        metrics = metrics,
        alertService = alertService,
    )

    @PostConstruct
    fun start() {
        log.info("Starting Bithumb WebSocket client: {}", URL)
        manager.start()
    }

    @PreDestroy
    fun stop() {
        log.info("Stopping Bithumb WebSocket client")
        manager.stop()
    }

    internal fun handlePayload(payload: String) {
        val ticker = parse(payload)
        if (ticker == null) {
            // 구독 응답(`type != "ticker"`) 등 정상 흐름은 parse가 null 반환하지만 parse 실패와 구분 불가하므로
            // 여기서는 모두 "수용 불가 메시지"로 취급. ticker 수신 빈도가 압도적이라 노이즈 영향 미미.
            // 정확히 구분하려면 parse가 sealed result 반환하도록 확장 필요.
            return
        }
        consecutiveParseErrors.set(0)
        ingestion.onMessage(ticker)
    }

    internal fun parse(payload: String): TickerData? {
        return try {
            val msg = objectMapper.readValue(payload, BithumbWebSocketTickerMessage::class.java)
            if (msg.type != "ticker") return null
            val content = msg.content ?: return null
            val price = content.closePrice.toBigDecimalOrNull() ?: return recordParseError("invalid price: ${content.closePrice}")
            val symbol = content.symbol.substringBefore("_")
            val currency = content.symbol.substringAfter("_", "KRW")
            // exchange timestamp 파싱 실패 시 메시지 폐기 (synthetic Instant.now() 사용 금지 — stale/replay 검출 정확성 보존)
            val timestamp = parseTimestamp(content.date, content.time)
                ?: return recordParseError("missing or malformed date/time: ${content.date} ${content.time}")

            TickerData(
                exchange = EXCHANGE_UPPER,
                symbol = symbol,
                currency = currency,
                price = price,
                volume = content.volume?.toBigDecimalOrNull(),
                timestamp = timestamp,
            )
        } catch (e: Exception) {
            recordParseError("exception: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun recordParseError(reason: String): TickerData? {
        parseErrorCounter.increment()
        val failures = consecutiveParseErrors.incrementAndGet()
        log.warn("Bithumb parse error ({}): {}", failures, reason)
        if (failures >= PARSE_FAILURE_ALERT_THRESHOLD) {
            alertService.sendCriticalAlert("[bithumb] WebSocket 메시지 parse 5회 연속 실패: $reason")
            consecutiveParseErrors.set(0)
        }
        return null
    }

    private fun parseTimestamp(date: String?, time: String?): Instant? {
        if (date.isNullOrBlank() || time.isNullOrBlank()) return null
        return try {
            val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            val ldt = LocalDateTime.parse(date + time, formatter)
            ldt.atZone(ZoneId.of("Asia/Seoul")).toInstant()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val URL = "wss://pubwss.bithumb.com/pub/ws"
        const val SUBSCRIBE_MESSAGE = """{"type":"ticker","symbols":["BTC_KRW"],"tickTypes":["24H"]}"""
        const val PING_MESSAGE = """{"type":"ping"}"""
        private const val EXCHANGE = "bithumb"
        private const val EXCHANGE_UPPER = "BITHUMB"
        private const val PARSE_FAILURE_ALERT_THRESHOLD = 5
        val PING_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}
```

> **NOTE on PING_MESSAGE:** 빗썸 ping 프로토콜은 공식 문서에 명시되지 않음. 일반적으로 무의미한 메시지(빈 텍스트 또는 JSON)를 보내면 서버가 idle reset로 받아들임. 운영 검증 중 ping이 종료를 유발하면 메시지 형식 조정 필요. (이슈 본문 "Idle 방지: 클라이언트 ping" 정도만 명시되어 있음.)

---

### Task 7: Clock Bean (있는지 확인 후 없으면 추가)

**Files:**
- Search: 기존에 `Clock` 빈이 등록돼 있는지 확인
- Create or Modify: `apps/batch/src/main/kotlin/io/premiumspread/config/ClockConfig.kt`

- [ ] **Step 1: 검색**

```bash
grep -rn "Clock.systemUTC\|@Bean.*Clock\|: Clock" .worktrees/feat-issue-31-bithumb-ws/apps/batch/src/main/kotlin/
```

- [ ] **Step 2: 없으면 생성**

```kotlin
package io.premiumspread.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
```

(이미 있으면 Skip — 그대로 사용.)

---

### Task 8: application 설정

**Files:**
- Modify: `apps/batch/src/main/resources/application.yml`

- [ ] **Step 1: 기본값 추가 (Phase 2 PR 미머지 대비)**

이미 `premium.ingestion.bithumb.mode`가 application.yml에 정의돼 있으면 Skip. 없으면 아래를 추가:

```yaml
premium:
  ingestion:
    bithumb:
      mode: rest
```

> Phase 2 PR이 먼저 머지되면 이 설정은 이미 존재. 충돌 회피를 위해 본 PR에서는 **`bithumb.mode`만** 명시. `binance.mode`는 건드리지 않음.

---

### Task 9: 통합 테스트

**Files:**
- Create: `apps/batch/src/test/kotlin/io/premiumspread/client/bithumb/BithumbWebSocketIntegrationTest.kt`

- [ ] **Step 1: 통합 시나리오 작성**

```kotlin
package io.premiumspread.client.bithumb

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.infrastructure.ingestion.bithumb.BithumbFlushJob
import io.premiumspread.infrastructure.ingestion.bithumb.BithumbTickerIngestion
import io.premiumspread.infrastructure.websocket.WebSocketConnectionConfig
import io.premiumspread.infrastructure.websocket.WebSocketConnectionManager
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import io.premiumspread.redis.RedisTestContainersConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestContainersConfig::class)
class BithumbWebSocketIntegrationTest {

    @Autowired private lateinit var tickerCacheService: TickerCacheService
    @Autowired private lateinit var redisTemplate: StringRedisTemplate

    private lateinit var server: MockWebServer
    private val metrics = WebSocketMetrics(SimpleMeterRegistry())
    private val alerts = ConcurrentLinkedQueue<String>()
    private val fakeAlertService = object : AlertService {
        override fun sendAlert(message: String, severity: AlertService.Severity) {
            alerts.add("[$severity] $message")
        }
    }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        try {
            Thread.sleep(300)
            server.shutdown()
        } catch (e: Exception) {
            // ignore
        }
    }

    @Test
    fun `메시지 1건 push되면 hash가 갱신되고 flush가 ZSet에 score 1개를 저장한다`() {
        val payload = """
            {"type":"ticker","content":{"symbol":"BTC_KRW","tickType":"24H","date":"20260512","time":"093000","closePrice":"100000000","volume":"1.5"}}
        """.trimIndent()
        server.enqueue(MockResponse().withWebSocketUpgrade(EchoOnce(payload)))

        val ingestion = BithumbTickerIngestion(tickerCacheService, metrics, fakeAlertService, Clock.systemUTC())
        val client = BithumbWebSocketClient(ingestion, metrics, fakeAlertService, ObjectMapper(), SimpleMeterRegistry())
        val flushJob = BithumbFlushJob(ingestion, tickerCacheService, metrics, fakeAlertService, redisTemplate, Clock.systemUTC())
        val manager = WebSocketConnectionManager(
            config = WebSocketConnectionConfig(
                exchange = "bithumb",
                url = server.url("/pub/ws").toString().replace("http", "ws"),
                onMessage = client::handlePayload,
            ),
            metrics = metrics,
            alertService = fakeAlertService,
        )
        manager.start()

        await.atMost(Duration.ofSeconds(5)).untilCallTo {
            tickerCacheService.get("BITHUMB", "BTC")
        } matches { it != null && it.price.toPlainString() == "100000000" }

        // 동일 가격으로 5회 flush — 멤버 `{epochMs}:{price}` 포맷이라 distinct entries로 누적 (flat-price 회귀 방지)
        repeat(5) {
            flushJob.run()
            Thread.sleep(50)
        }

        val results = tickerCacheService.getSecondsData(
            "BITHUMB", "BTC",
            from = Instant.now().minusSeconds(60),
            to = Instant.now().plusSeconds(60),
        )
        assertThat(results.map { it.first }).hasSize(5)
        assertThat(results).allSatisfy { (_, p) ->
            assertThat(p).isEqualByComparingTo(java.math.BigDecimal("100000000"))
        }
        assertThat(redisTemplate.opsForValue().get(BithumbFlushJob.LAST_RUN_KEY)).isNotBlank

        manager.stop()
    }

    @Test
    fun `연결됐지만 5초 동안 메시지 zero면 critical alert가 호출된다 (silent outage 회귀 방지)`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(IdleListener()))

        val ingestion = BithumbTickerIngestion(tickerCacheService, metrics, fakeAlertService, Clock.systemUTC())
        val client = BithumbWebSocketClient(ingestion, metrics, fakeAlertService, ObjectMapper(), SimpleMeterRegistry())
        val manager = WebSocketConnectionManager(
            config = WebSocketConnectionConfig(
                exchange = "bithumb",
                url = server.url("/pub/ws").toString().replace("http", "ws"),
                firstMessageTimeout = Duration.ofSeconds(2),
                onMessage = client::handlePayload,
            ),
            metrics = metrics,
            alertService = fakeAlertService,
        )
        manager.start()

        await.atMost(Duration.ofSeconds(6)).untilCallTo { alerts.toList() } matches {
            it.any { msg -> msg.contains("[CRITICAL]") && msg.contains("bithumb") }
        }

        manager.stop()
    }

    @Test
    fun `reverse-order 메시지 시퀀스에서 오래된 메시지는 폐기된다 (reorder 회귀 방지)`() {
        val newer = """{"type":"ticker","content":{"symbol":"BTC_KRW","tickType":"24H","date":"20260512","time":"093005","closePrice":"100000000"}}"""
        val older = """{"type":"ticker","content":{"symbol":"BTC_KRW","tickType":"24H","date":"20260512","time":"093000","closePrice":"99000000"}}"""
        server.enqueue(MockResponse().withWebSocketUpgrade(SendBoth(newer, older)))

        val ingestion = BithumbTickerIngestion(tickerCacheService, metrics, fakeAlertService, Clock.systemUTC())
        val client = BithumbWebSocketClient(ingestion, metrics, fakeAlertService, ObjectMapper(), SimpleMeterRegistry())
        val manager = WebSocketConnectionManager(
            config = WebSocketConnectionConfig(
                exchange = "bithumb",
                url = server.url("/pub/ws").toString().replace("http", "ws"),
                onMessage = client::handlePayload,
            ),
            metrics = metrics,
            alertService = fakeAlertService,
        )
        manager.start()

        // 둘 다 도착할 시간을 충분히 줌
        await.atMost(Duration.ofSeconds(5)).untilCallTo {
            ingestion.latest()?.ticker?.price?.toPlainString()
        } matches { it == "100000000" }

        // older가 도착해도 latest는 100M (newer) 그대로
        Thread.sleep(500)
        assertThat(ingestion.latest()?.ticker?.price?.toPlainString()).isEqualTo("100000000")

        manager.stop()
    }
}

private class EchoOnce(private val payload: String) : okhttp3.WebSocketListener() {
    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
        webSocket.send(payload)
    }
}

private class IdleListener : okhttp3.WebSocketListener()

private class SendBoth(private val first: String, private val second: String) : okhttp3.WebSocketListener() {
    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
        webSocket.send(first)
        Thread.sleep(100)
        webSocket.send(second)
    }
}
```

> **NOTE:** `TickerCacheService` Bean을 test profile에서 활성화하기 위한 별도 config가 필요하면 작업 중 발견 시 보완. (`redis.enabled: false`가 test profile 기본인데 RedisTestContainersConfig가 override 하는지 확인.)

---

### Task 10: 빌드/테스트 검증

- [ ] **Step 1: compileKotlin 통과**

```bash
./gradlew :apps:batch:compileKotlin
```

- [ ] **Step 2: 단위 테스트 통과**

```bash
./gradlew :apps:batch:test
```

- [ ] **Step 3: 통합 테스트 통과**

```bash
docker compose -f docker/infra-compose.yml up -d
./gradlew :apps:batch:integrationTest
```

- [ ] **Step 4: 로컬 수동 검증 (선택, Phase 2 PR 머지 후)**

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :apps:batch:bootRun
# 1초마다 ws.message.received{exchange=bithumb} + ticker.flush.bithumb 카운터 증가 확인
curl -s localhost:8081/actuator/metrics/ticker.flush.bithumb | jq
# batch:last-run:bithumb-flush 가 1초마다 갱신되는지
redis-cli GET batch:last-run:bithumb-flush
```

---

## DoD 체크리스트

- [ ] 메시지 수신 시 hash 갱신 (exchange timestamp 그대로) — 통합 테스트
- [ ] 1초 주기 flush 시 ZSet score는 `Instant.now(clock)`, 멤버는 `{epochMs}:{price}` — 단위 + 통합
- [ ] **flat-price 5회 flush → ZSet 5 distinct entries 누적** (Codex 회귀 방지) — 통합
- [ ] `latest() == null` → no-op
- [ ] `age > 10s` → flush skip + `ws.stale.bithumb` 증가
- [ ] 5회 연속 flush 실패 → `AlertService.sendCriticalAlert` 호출
- [ ] **5회 연속 hash save 실패 (ingestion) → `AlertService.sendCriticalAlert` 호출** — 단위
- [ ] **5회 연속 parse 실패 → `AlertService.sendCriticalAlert` 호출** — 단위
- [ ] **monotonic check가 CAS atomic** (동시 16스레드 × 100메시지 → 최종 latest는 정확히 max timestamp) — 단위
- [ ] **missing/malformed date/time → 메시지 폐기 + `ws.parse.error{bithumb}` 증가** — 단위
- [ ] **lag 메트릭 (`ws.message.lag.ms{bithumb}`)** 기록 — 단위
- [ ] 성공 시 `batch:last-run:bithumb-flush` 갱신
- [ ] 연결 후 5초 메시지 zero → `ws.first.message.timeout` + alert (통합)
- [ ] reverse-order 메시지 → 오래된 것 폐기 + `ws.out_of_order` 증가 (단위 + 통합)
- [ ] `compileKotlin` + `:apps:batch:test` 통과
- [ ] `:apps:batch:integrationTest` 통과
