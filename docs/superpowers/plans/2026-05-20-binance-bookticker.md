# Binance bookTicker 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Binance USD-M Futures 시세 수집 채널을 `@miniTicker`(2초 스냅샷)에서 `@bookTicker`(best bid/ask 실시간)로 전환하고, 관련 리포 문서의 miniTicker 주기 오기재(#51)를 정정한다.

**Architecture:** 빗썸이 이미 보유한 "변동 push + in-memory 보관 + 1초 down-sample flush" 구조(`BithumbTickerIngestion` / `BithumbFlushJob` / `BithumbFlushScheduler`)를 Binance에 이식한다. WebSocket 클라이언트는 bookTicker payload를 mid 가격으로 파싱하고, ingestion은 hash만 즉시 쓰며 최신값을 보관, 신규 flush job이 1초 주기로 ZSet에 down-sample 저장한다.

**Tech Stack:** Kotlin 2.0, Spring Boot 3.4, Jackson, Micrometer, Redis(Lettuce), JUnit5 + AssertJ + MockK.

**참조 스펙:** `docs/superpowers/specs/2026-05-20-binance-bookticker-design.md`

---

## File Structure

| 파일 | 책임 | 작업 |
|------|------|------|
| `apps/batch/.../client/binance/BinanceWebSocketResponse.kt` | bookTicker payload 역직렬화 모델 | 수정 (클래스 교체) |
| `apps/batch/.../client/binance/BinanceWebSocketClient.kt` | WS 연결 + payload→TickerData 파싱 (mid) | 수정 |
| `apps/batch/.../infrastructure/ingestion/binance/BinanceTickerIngestion.kt` | 메시지 monotonic 검증 + hash write + 최신값 보관 | 수정 |
| `apps/batch/.../infrastructure/ingestion/binance/BinanceFlushJob.kt` | 1초 주기 ZSet down-sample flush | 신규 |
| `apps/batch/.../scheduler/BinanceFlushScheduler.kt` | flush job thin entrypoint | 신규 |
| `apps/batch/.../client/binance/BinanceWebSocketClientTest.kt` | parse 단위 테스트 | 수정 |
| `apps/batch/.../infrastructure/ingestion/binance/BinanceTickerIngestionTest.kt` | ingestion 단위 테스트 | 수정 |
| `apps/batch/.../infrastructure/ingestion/binance/BinanceFlushJobTest.kt` | flush job 단위 테스트 | 신규 |
| `apps/batch/.../client/binance/BinanceWebSocketIntegrationTest.kt` | WS 통합 테스트 | 수정 |
| `docs/superpowers/specs/2026-05-12-websocket-ingestion-design.md` 외 3종 | #51 문서 정정 | 수정 |

패키지 루트: `apps/batch/src/main/kotlin/io/premiumspread/`, 테스트 루트: `apps/batch/src/test/kotlin/io/premiumspread/`.

---

## Task 1: bookTicker payload 파싱 (BinanceBookTickerMessage + BinanceWebSocketClient)

**Files:**
- Modify: `apps/batch/src/main/kotlin/io/premiumspread/client/binance/BinanceWebSocketResponse.kt`
- Modify: `apps/batch/src/main/kotlin/io/premiumspread/client/binance/BinanceWebSocketClient.kt`
- Test: `apps/batch/src/test/kotlin/io/premiumspread/client/binance/BinanceWebSocketClientTest.kt`

- [ ] **Step 1: 테스트를 bookTicker 기준으로 교체**

`BinanceWebSocketClientTest.kt` 전체를 아래로 교체한다.

```kotlin
package io.premiumspread.client.binance

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.monitoring.AlertService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class BinanceWebSocketClientTest {

    private val client = BinanceWebSocketClient(
        ingestion = mockk(relaxed = true),
        metrics = mockk(relaxed = true),
        alertService = mockk(relaxed = true),
        objectMapper = ObjectMapper(),
        meterRegistry = SimpleMeterRegistry(),
    )

    @Test
    fun `정상 bookTicker payload는 mid 가격으로 파싱된다`() {
        val payload = """
            {"e":"bookTicker","u":400900217,"E":1715470800000,"T":1715470800000,"s":"BTCUSDT","b":"89277.00","B":"1.5","a":"89277.20","A":"2.0"}
        """.trimIndent()

        val ticker = client.parse(payload)

        assertThat(ticker).isNotNull
        assertThat(ticker!!.exchange).isEqualTo("BINANCE")
        assertThat(ticker.symbol).isEqualTo("BTC")
        assertThat(ticker.currency).isEqualTo("USD")
        // mid = (89277.00 + 89277.20) / 2 = 89277.10
        assertThat(ticker.price).isEqualByComparingTo(BigDecimal("89277.10"))
        assertThat(ticker.volume).isNull()
        assertThat(ticker.timestamp).isEqualTo(Instant.ofEpochMilli(1715470800000))
    }

    @Test
    fun `malformed JSON은 null을 반환한다`() {
        assertThat(client.parse("not-json")).isNull()
    }

    @Test
    fun `숫자가 아닌 bestBid는 null을 반환한다`() {
        val payload = """{"e":"bookTicker","u":1,"E":1,"T":1,"s":"BTCUSDT","b":"NaN","a":"89277.20"}"""
        assertThat(client.parse(payload)).isNull()
    }

    @Test
    fun `숫자가 아닌 bestAsk는 null을 반환한다`() {
        val payload = """{"e":"bookTicker","u":1,"E":1,"T":1,"s":"BTCUSDT","b":"89277.00","a":"NaN"}"""
        assertThat(client.parse(payload)).isNull()
    }

    @Test
    fun `parse 5회 연속 실패면 ws_parse_error 카운터 누적 + critical alert가 호출되고 counter는 리셋된다`() {
        val alertService = mockk<AlertService>(relaxed = true)
        val meterRegistry = SimpleMeterRegistry()
        val client = BinanceWebSocketClient(
            ingestion = mockk(relaxed = true),
            metrics = mockk(relaxed = true),
            alertService = alertService,
            objectMapper = ObjectMapper(),
            meterRegistry = meterRegistry,
        )

        repeat(5) { client.parse("not-json") }

        val counter = meterRegistry.find("ws.parse.error").tag("exchange", "binance").counter()
        assertThat(counter).isNotNull
        assertThat(counter!!.count()).isEqualTo(5.0)
        verify(exactly = 1) {
            alertService.sendCriticalAlert(match { it.contains("5회 연속") && it.contains("binance") })
        }
    }
}
```

- [ ] **Step 2: 테스트 실행하여 실패 확인**

Run: `./gradlew :apps:batch:test --tests "io.premiumspread.client.binance.BinanceWebSocketClientTest"`
Expected: FAIL — 기존 `parse()`가 `BinanceMiniTickerMessage`(필수 필드 `c`)로 역직렬화하므로 bookTicker payload가 파싱되지 않아 `정상 bookTicker payload...` 테스트가 실패.

- [ ] **Step 3: payload 클래스를 bookTicker로 교체**

`BinanceWebSocketResponse.kt` 전체를 아래로 교체한다.

```kotlin
package io.premiumspread.client.binance

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Binance USD-M Futures @bookTicker stream payload.
 *
 * URL: wss://fstream.binance.com/market/ws/{symbol}@bookTicker
 *
 * Push 주기: best bid/ask 변동 시마다 실시간 push (스냅샷이 아닌 변경 이벤트).
 * 가격은 best bid/ask의 mid `(b + a) / 2`로 산정한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BinanceBookTickerMessage(
    @JsonProperty("E") val eventTime: Long,
    @JsonProperty("s") val symbol: String,
    @JsonProperty("b") val bestBid: String,
    @JsonProperty("a") val bestAsk: String,
    @JsonProperty("e") val eventType: String? = null,
    @JsonProperty("u") val updateId: Long? = null,
    @JsonProperty("T") val transactTime: Long? = null,
    @JsonProperty("B") val bidQty: String? = null,
    @JsonProperty("A") val askQty: String? = null,
)
```

가격(`b`,`a`)·timestamp(`E`)·symbol(`s`)은 필수, 미사용 필드(`e`,`u`,`T`,`B`,`A`)는 nullable 기본값 — payload 일부 누락에도 회귀하지 않도록.

- [ ] **Step 4: BinanceWebSocketClient를 bookTicker + mid 파싱으로 수정**

`BinanceWebSocketClient.kt`에서 다음 4곳을 수정한다.

(4-1) import 블록에 BigDecimal/RoundingMode 추가 — `import org.springframework.stereotype.Component` 다음 줄에:

```kotlin
import java.math.BigDecimal
import java.math.RoundingMode
```

(4-2) 클래스 KDoc 주석(`/** 바이낸스 BTCUSDT ... */`)을 아래로 교체:

```kotlin
/**
 * 바이낸스 BTCUSDT 무기한 선물 `@bookTicker` (best bid/ask 실시간 push) WebSocket 구독.
 *
 * - 연결 직후 별도 subscribe 메시지 불필요 (URL에 채널이 포함됨).
 * - 가격은 best bid/ask의 mid `(b + a) / 2`로 산정한다.
 * - 메시지마다 [BinanceTickerIngestion]로 위임.
 * - parse 실패는 `ws.parse.error{exchange=binance}` counter 증가 + 5회 연속 시 critical alert.
 */
```

(4-3) `parse()` 함수 본문을 아래로 교체:

```kotlin
    internal fun parse(payload: String): TickerData? {
        return try {
            val msg = objectMapper.readValue(payload, BinanceBookTickerMessage::class.java)
            val bid = msg.bestBid.toBigDecimalOrNull()
                ?: return recordParseError("invalid bestBid: ${msg.bestBid}")
            val ask = msg.bestAsk.toBigDecimalOrNull()
                ?: return recordParseError("invalid bestAsk: ${msg.bestAsk}")
            // 가격 = mid = (bestBid + bestAsk) / 2. scale·RoundingMode 명시 (defensive).
            val price = bid.add(ask).divide(BigDecimal(2), MID_PRICE_SCALE, RoundingMode.HALF_UP)
            TickerData(
                exchange = EXCHANGE_UPPER,
                symbol = extractBaseSymbol(msg.symbol),
                currency = CURRENCY,
                price = price,
                volume = null,
                timestamp = Instant.ofEpochMilli(msg.eventTime),
            )
        } catch (e: Exception) {
            recordParseError("exception: ${e.javaClass.simpleName} ${e.message}")
        }
    }
```

(4-4) `companion object`를 아래로 교체 (URL 변경 + `MID_PRICE_SCALE` 추가):

```kotlin
    companion object {
        const val URL = "wss://fstream.binance.com/market/ws/btcusdt@bookTicker"
        private const val EXCHANGE = "binance"
        private const val EXCHANGE_UPPER = "BINANCE"
        // issue spec 준수: 다운스트림 TickerAggregationScheduler가 "USD"로 조회하므로 통일.
        private const val CURRENCY = "USD"
        // mid 계산 시 나눗셈 결과 정밀도 — BTC 선물 가격은 소수 1~2자리이므로 8자리면 충분.
        private const val MID_PRICE_SCALE = 8
        private const val PARSE_FAILURE_ALERT_THRESHOLD = 5
    }
```

- [ ] **Step 5: 테스트 실행하여 통과 확인**

Run: `./gradlew :apps:batch:test --tests "io.premiumspread.client.binance.BinanceWebSocketClientTest"`
Expected: PASS (5개 테스트)

- [ ] **Step 6: 커밋**

```bash
git add apps/batch/src/main/kotlin/io/premiumspread/client/binance/BinanceWebSocketResponse.kt \
        apps/batch/src/main/kotlin/io/premiumspread/client/binance/BinanceWebSocketClient.kt \
        apps/batch/src/test/kotlin/io/premiumspread/client/binance/BinanceWebSocketClientTest.kt
git commit -m "feat: Binance WebSocket을 bookTicker 채널 + mid 가격 파싱으로 전환 (#52)

- BinanceMiniTickerMessage → BinanceBookTickerMessage 교체
- URL을 @miniTicker → @bookTicker로 변경
- 가격을 best bid/ask의 mid (b+a)/2로 산정"
```

---

## Task 2: BinanceTickerIngestion을 빗썸 패턴(latest 보관 + accept-equal)으로 변경

**Files:**
- Modify: `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/ingestion/binance/BinanceTickerIngestion.kt`
- Test: `apps/batch/src/test/kotlin/io/premiumspread/infrastructure/ingestion/binance/BinanceTickerIngestionTest.kt`

- [ ] **Step 1: 테스트를 latest 보관 + accept-equal 기준으로 교체**

`BinanceTickerIngestionTest.kt` 전체를 아래로 교체한다.

```kotlin
package io.premiumspread.infrastructure.ingestion.binance

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

class BinanceTickerIngestionTest {
    private val fixedNow: Instant = Instant.parse("2026-05-12T00:00:10Z")
    private lateinit var cache: TickerCacheService
    private lateinit var metrics: WebSocketMetrics
    private lateinit var alertService: AlertService
    private lateinit var ingestion: BinanceTickerIngestion

    @BeforeEach
    fun setUp() {
        cache = mockk(relaxed = true)
        metrics = mockk(relaxed = true)
        alertService = mockk(relaxed = true)
        ingestion = BinanceTickerIngestion(
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
        verify(exactly = 1) { metrics.recordLag("binance", 2000L) }
        assertThat(ingestion.latest()?.ticker).isEqualTo(ticker)
        assertThat(ingestion.latest()?.receivedAt).isEqualTo(fixedNow)
    }

    @Test
    fun `ZSet 직접 저장은 하지 않는다 (flush job이 담당)`() {
        ingestion.onMessage(tickerAt(Instant.parse("2026-05-12T00:00:08Z")))

        verify(exactly = 0) { cache.saveToSeconds(any()) }
        verify(exactly = 0) { cache.saveToSecondsWithScore(any(), any()) }
    }

    @Test
    fun `직전보다 strict하게 오래된 timestamp는 폐기되고 out_of_order가 증가한다`() {
        val first = tickerAt(Instant.parse("2026-05-12T00:00:05Z"))
        val stale = tickerAt(Instant.parse("2026-05-12T00:00:01Z"))
        ingestion.onMessage(first)

        ingestion.onMessage(stale)

        verify(exactly = 0) { cache.save(stale) }
        verify(exactly = 1) { metrics.recordOutOfOrder("binance") }
        assertThat(ingestion.latest()?.ticker).isEqualTo(first)
    }

    @Test
    fun `같은 ms timestamp 메시지는 수용된다 (bookTicker는 동일 eventTime에 복수 push 정상)`() {
        val ts = Instant.parse("2026-05-12T00:00:05Z")
        val first = tickerAt(ts)
        val sameMs = tickerAt(ts).copy(price = BigDecimal("200.00"))

        ingestion.onMessage(first)
        ingestion.onMessage(sameMs)

        verify(exactly = 1) { cache.save(first) }
        verify(exactly = 1) { cache.save(sameMs) }
        verify(exactly = 0) { metrics.recordOutOfOrder("binance") }
        assertThat(ingestion.latest()?.ticker).isEqualTo(sameMs)
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
        assertThat(final!!.ticker.timestamp).isEqualTo(base.plusMillis(maxOffsetMs))
    }

    @Test
    fun `cache save 5회 연속 실패면 sendCriticalAlert가 호출되고 counter는 리셋된다`() {
        every { cache.save(any()) } throws RuntimeException("redis down")

        repeat(5) {
            ingestion.onMessage(tickerAt(Instant.parse("2026-05-12T00:00:00Z").plusMillis(it.toLong())))
        }

        verify(exactly = 5) { metrics.recordFlushError(eq("binance"), any()) }
        verify(exactly = 1) { alertService.sendCriticalAlert(match { it.contains("5회 연속") }) }
    }

    private fun tickerAt(timestamp: Instant) = TickerData(
        exchange = "BINANCE",
        symbol = "BTC",
        currency = "USD",
        price = BigDecimal("100.00"),
        volume = null,
        timestamp = timestamp,
    )
}
```

- [ ] **Step 2: 테스트 실행하여 실패 확인**

Run: `./gradlew :apps:batch:test --tests "io.premiumspread.infrastructure.ingestion.binance.BinanceTickerIngestionTest"`
Expected: FAIL — `ingestion.latest()` 메서드와 `LatestTicker`가 아직 없어 컴파일 실패.

- [ ] **Step 3: BinanceTickerIngestion을 빗썸 패턴으로 교체**

`BinanceTickerIngestion.kt` 전체를 아래로 교체한다.

```kotlin
package io.premiumspread.infrastructure.ingestion.binance

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
 * 바이낸스 WebSocket(@bookTicker)으로 도착한 TickerData를 in-memory에 보관하고 hash를 갱신한다.
 *
 * - hash 갱신은 메시지 수신 시점 (exchange timestamp 그대로). TTL freshness 의미 보존.
 * - ZSet 저장은 [BinanceFlushJob]이 1초 주기로 처리 (down-sample) — bookTicker는 초당 수십~수백 건 push.
 * - monotonic check는 `updateAndGet` CAS로 atomic — strict하게 오래된 메시지만 폐기.
 *   같은 ms 타임스탬프는 수용 (bookTicker는 동일 eventTime ms에 복수 push가 정상).
 * - 캐시 저장 실패 5회 연속 → critical alert.
 */
@Component
@ConditionalOnProperty("premium.ingestion.binance.mode", havingValue = "websocket")
class BinanceTickerIngestion(
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

        // Atomic monotonic CAS — strict하게 오래된 메시지만 폐기. 같은 ms 타임스탬프는 수용
        // (bookTicker는 동일 eventTime ms에 복수 메시지가 정상).
        val updated = lastTicker.updateAndGet { prev ->
            if (prev != null && ticker.timestamp.isBefore(prev.ticker.timestamp)) prev else candidate
        }
        if (updated !== candidate) {
            metrics.recordOutOfOrder(EXCHANGE)
            log.debug(
                "Discard out-of-order binance ticker: prev={}, current={}",
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
            log.warn("Binance ingestion hash save failed (consecutive={}): {}", failures, e.message)
            if (failures >= FAILURE_ALERT_THRESHOLD) {
                alertService.sendCriticalAlert("[binance] WebSocket ingestion hash 저장 5회 연속 실패: ${e.message}")
                consecutiveSaveFailures.set(0)
            }
        }
    }

    fun latest(): LatestTicker? = lastTicker.get()

    companion object {
        private const val EXCHANGE = "binance"
        private const val FAILURE_ALERT_THRESHOLD = 5
    }
}
```

- [ ] **Step 4: 테스트 실행하여 통과 확인**

Run: `./gradlew :apps:batch:test --tests "io.premiumspread.infrastructure.ingestion.binance.BinanceTickerIngestionTest"`
Expected: PASS (6개 테스트)

- [ ] **Step 5: 커밋**

```bash
git add apps/batch/src/main/kotlin/io/premiumspread/infrastructure/ingestion/binance/BinanceTickerIngestion.kt \
        apps/batch/src/test/kotlin/io/premiumspread/infrastructure/ingestion/binance/BinanceTickerIngestionTest.kt
git commit -m "feat: BinanceTickerIngestion을 latest 보관 + accept-equal 패턴으로 변경 (#52)

- ZSet 즉시 저장 제거 → hash write + latest() 보관 (빗썸 패턴)
- monotonic을 strict에서 accept-equal로 완화 (같은 ms 복수 push 수용)"
```

---

## Task 3: BinanceFlushJob 신규 (1초 down-sample flush)

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/ingestion/binance/BinanceFlushJob.kt`
- Test: `apps/batch/src/test/kotlin/io/premiumspread/infrastructure/ingestion/binance/BinanceFlushJobTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

`BinanceFlushJobTest.kt`를 신규 생성한다.

```kotlin
package io.premiumspread.infrastructure.ingestion.binance

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.client.TickerData
import io.premiumspread.infrastructure.ingestion.binance.BinanceTickerIngestion.LatestTicker
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class BinanceFlushJobTest {
    private val now = Instant.parse("2026-05-12T00:00:10Z")
    private lateinit var ingestion: BinanceTickerIngestion
    private lateinit var cache: TickerCacheService
    private lateinit var metrics: WebSocketMetrics
    private lateinit var alertService: AlertService
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var valueOps: ValueOperations<String, String>
    private lateinit var job: BinanceFlushJob

    @BeforeEach
    fun setUp() {
        ingestion = mockk()
        cache = mockk(relaxed = true)
        metrics = mockk(relaxed = true)
        alertService = mockk(relaxed = true)
        redisTemplate = mockk()
        valueOps = mockk(relaxed = true)
        every { redisTemplate.opsForValue() } returns valueOps
        job = BinanceFlushJob(
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
        verify(exactly = 1) { valueOps.set(BinanceFlushJob.LAST_RUN_KEY, now.toEpochMilli().toString(), any<Duration>()) }
        verify(exactly = 1) { metrics.recordFlush("binance") }
        verify(exactly = 0) { metrics.recordStale(any()) }
    }

    @Test
    fun `age가 10초를 초과하면 stale로 분류되고 flush를 호출하지 않는다`() {
        val ticker = tickerData()
        every { ingestion.latest() } returns LatestTicker(ticker, now.minusSeconds(11))

        job.run()

        verify(exactly = 0) { cache.saveToSecondsWithScore(any(), any()) }
        verify(exactly = 1) { metrics.recordStale("binance") }
    }

    @Test
    fun `save 예외 5회 연속이면 sendCriticalAlert를 호출하고 counter는 리셋된다`() {
        val ticker = tickerData()
        every { ingestion.latest() } returns LatestTicker(ticker, now.minusSeconds(2))
        every { cache.saveToSecondsWithScore(any(), any()) } throws RuntimeException("redis down")

        repeat(5) { job.run() }

        verify(exactly = 1) { alertService.sendCriticalAlert(match { it.contains("5회 연속") }) }

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

        every { cache.saveToSecondsWithScore(any(), any()) } throws RuntimeException("temp")
        repeat(5) { job.run() }
        verify(exactly = 1) { alertService.sendCriticalAlert(any()) }
    }

    private fun tickerData() = TickerData(
        exchange = "BINANCE",
        symbol = "BTC",
        currency = "USD",
        price = BigDecimal("89277.10"),
        volume = null,
        timestamp = now.minusSeconds(2),
    )
}
```

- [ ] **Step 2: 테스트 실행하여 실패 확인**

Run: `./gradlew :apps:batch:test --tests "io.premiumspread.infrastructure.ingestion.binance.BinanceFlushJobTest"`
Expected: FAIL — `BinanceFlushJob` 클래스가 없어 컴파일 실패.

- [ ] **Step 3: BinanceFlushJob 생성**

`BinanceFlushJob.kt`를 신규 생성한다.

```kotlin
package io.premiumspread.infrastructure.ingestion.binance

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
 * 바이낸스 WebSocket(@bookTicker)으로 받은 최신 ticker를 1초 주기로 ZSet에 flush 한다.
 *
 * - Hash는 [BinanceTickerIngestion]이 메시지 수신 시점에 이미 갱신 → 본 job은 ZSet만 갱신.
 * - score는 flush 시점(`Instant.now(clock)`)을 사용 — exchange timestamp가 변하지 않아도 distinct score 보장.
 * - 마지막 메시지가 10초 이상 지난 경우 stale 처리하고 skip.
 * - flush 실패 5회 연속 발생 시 critical alert.
 */
@Component
@ConditionalOnProperty("premium.ingestion.binance.mode", havingValue = "websocket")
class BinanceFlushJob(
    private val ingestion: BinanceTickerIngestion,
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
            log.warn("Binance flush failed (consecutive={}): {}", failures, e.message)
            if (failures >= FAILURE_ALERT_THRESHOLD) {
                alertService.sendCriticalAlert("[binance] flush 5회 연속 실패: ${e.message}")
                consecutiveFailures.set(0)
            }
        }
    }

    companion object {
        const val EXCHANGE = "binance"
        val STALE_THRESHOLD: Duration = Duration.ofSeconds(10)
        const val FAILURE_ALERT_THRESHOLD = 5
        const val LAST_RUN_KEY = "batch:last-run:binance-flush"
    }
}
```

- [ ] **Step 4: 테스트 실행하여 통과 확인**

Run: `./gradlew :apps:batch:test --tests "io.premiumspread.infrastructure.ingestion.binance.BinanceFlushJobTest"`
Expected: PASS (5개 테스트)

- [ ] **Step 5: 커밋**

```bash
git add apps/batch/src/main/kotlin/io/premiumspread/infrastructure/ingestion/binance/BinanceFlushJob.kt \
        apps/batch/src/test/kotlin/io/premiumspread/infrastructure/ingestion/binance/BinanceFlushJobTest.kt
git commit -m "feat: BinanceFlushJob 추가 — 1초 주기 ZSet down-sample flush (#52)

- 빗썸 BithumbFlushJob 패턴 이식
- stale 임계값 10초, last-run key batch:last-run:binance-flush"
```

---

## Task 4: BinanceFlushScheduler 신규 (thin entrypoint)

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/scheduler/BinanceFlushScheduler.kt`

- [ ] **Step 1: BinanceFlushScheduler 생성**

`BinanceFlushScheduler.kt`를 신규 생성한다.

```kotlin
package io.premiumspread.scheduler

import io.premiumspread.infrastructure.ingestion.binance.BinanceFlushJob
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 바이낸스 1초 flush 스케줄러 — thin entrypoint.
 *
 * - 단일 인스턴스 전제. 분산 락 불필요 → `JobExecutor` 미사용.
 * - 비즈니스/예외/메트릭/last-run/알람은 [BinanceFlushJob] 내부.
 */
@Component
@ConditionalOnProperty("premium.ingestion.binance.mode", havingValue = "websocket")
class BinanceFlushScheduler(
    private val flushJob: BinanceFlushJob,
) {
    @Scheduled(fixedRate = 1000)
    fun flush() {
        flushJob.run()
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :apps:batch:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add apps/batch/src/main/kotlin/io/premiumspread/scheduler/BinanceFlushScheduler.kt
git commit -m "feat: BinanceFlushScheduler 추가 — 1초 flush thin entrypoint (#52)"
```

---

## Task 5: BinanceWebSocketIntegrationTest를 bookTicker payload로 갱신

**Files:**
- Modify: `apps/batch/src/test/kotlin/io/premiumspread/client/binance/BinanceWebSocketIntegrationTest.kt`

`BinanceTickerIngestion`이 더 이상 ZSet을 직접 쓰지 않지만 hash(`save`)는 메시지 수신 즉시 갱신하므로, "메시지 1건 → 캐시 hash 갱신" 통합 테스트는 flush job 없이도 유효하다. payload만 bookTicker로 교체하고, mid 가격(8 scale)이라 가격 단언을 `compareTo` 기반으로 바꾼다.

- [ ] **Step 1: import에 BigDecimal 추가**

`import java.time.Duration` 다음 줄에 추가:

```kotlin
import java.math.BigDecimal
```

- [ ] **Step 2: 첫 번째 테스트의 payload·URL·단언 교체**

`메시지 1건 push되면 TickerCacheService 캐시가 갱신된다` 테스트에서:

(2-1) payload 줄 교체:

```kotlin
        val payload = """{"e":"bookTicker","u":1,"E":1715470800000,"T":1715470800000,"s":"BTCUSDT","b":"89277.00","B":"1.5","a":"89277.20","A":"2.0"}"""
```

(2-2) `url = server.url("/ws/btcusdt@miniTicker")...` 을 교체:

```kotlin
                url = server.url("/ws/btcusdt@bookTicker").toString().replace("http", "ws"),
```

(2-3) `await` 블록의 단언 교체 (mid = 89277.10, scale 8 → `toPlainString()`이 "89277.10000000"이므로 값 비교 사용):

```kotlin
        await.atMost(Duration.ofSeconds(5)).untilCallTo {
            tickerCacheService.get("BINANCE", "BTC")
        } matches { it != null && it.price.compareTo(BigDecimal("89277.10")) == 0 }
```

- [ ] **Step 3: 두 번째 테스트의 URL 교체**

`연결됐지만 첫 메시지 timeout이 지나면 ...` 테스트에서 `url = server.url("/ws/btcusdt@miniTicker")...` 을 교체:

```kotlin
                url = server.url("/ws/btcusdt@bookTicker").toString().replace("http", "ws"),
```

- [ ] **Step 4: EchoOnce 헬퍼 정리 확인**

파일 하단 `EchoOnce` 클래스는 그대로 사용 (payload 문자열만 주입받으므로 변경 불필요). `IdleListener`도 그대로.

- [ ] **Step 5: 컴파일 확인**

Run: `./gradlew :apps:batch:compileTestKotlin`
Expected: BUILD SUCCESSFUL — 통합 테스트 실행은 Docker(Testcontainers) 필요. 컴파일 통과가 본 태스크의 검증 기준.

- [ ] **Step 6: 커밋**

```bash
git add apps/batch/src/test/kotlin/io/premiumspread/client/binance/BinanceWebSocketIntegrationTest.kt
git commit -m "test: Binance WebSocket 통합 테스트를 bookTicker payload로 갱신 (#52)"
```

---

## Task 6: 전체 빌드·테스트 검증

**Files:** 없음 (검증 전용)

- [ ] **Step 1: batch 모듈 단위 테스트 전체 실행**

Run: `./gradlew :apps:batch:test`
Expected: BUILD SUCCESSFUL — 모든 단위 테스트 통과. (`@Tag("integration")` 테스트는 unit `test`에서 제외됨)

- [ ] **Step 2: 전체 컴파일 확인**

Run: `./gradlew :apps:batch:compileKotlin :apps:batch:compileTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 실패 시 수정**

테스트 실패 시 해당 테스트/구현을 수정하고 Step 1~2를 재실행한다. 모두 통과할 때까지 반복.

---

## Task 7: #51 — 리포 문서 miniTicker 주기 정정

**Files:**
- Modify: `docs/superpowers/specs/2026-05-12-websocket-ingestion-design.md`
- Modify: `.ai/architecture/ARCHITECTURE_DESIGN.md`
- Modify: `.ai/PROJECT_STATUS.md`
- Modify: `CLAUDE.md`

GitHub 이슈 본문(#28/#30)은 **수정하지 않는다** (의사결정 — 리포 문서만 정정).

- [ ] **Step 1: 설계 문서(2026-05-12-websocket-ingestion-design.md) 정정**

파일을 Read로 열어 다음을 정정한다.
- 채널 비교 표의 `바이낸스 | btcusdt@miniTicker | 1초 고정 주기` 행 → `바이낸스 | btcusdt@bookTicker | 변동 시 실시간 push` 로 변경.
- 본문 중 바이낸스 `@miniTicker` "1초 고정 push" 언급(핵심 결정 사항 9번, Phase 2 섹션, 데이터 흐름 다이어그램의 `[바이낸스 — 1초 고정 push]` 등)을 정정:
  - miniTicker의 실제 update speed는 **2초**였음을 명시 (Phase 2 PoC는 miniTicker 2초 채널로 검증됨).
  - 이후 이슈 #52에서 `@bookTicker`(실시간) + 1초 down-sample flush로 전환됨을 **연혁 노트**로 추가.
- Phase 2 섹션의 "바이낸스는 1초 고정 push라 down-sample 불필요, 별도 flush job 없음" 문장 → "(#52 이후) bookTicker 전환으로 `BinanceFlushJob` 1초 down-sample 도입" 으로 갱신.

- [ ] **Step 2: ARCHITECTURE_DESIGN.md 정정**

파일을 Read로 열어 다음을 정정한다.
- `**바이낸스 — 1초 고정 push (`@miniTicker` 채널)**` → `**바이낸스 — 실시간 push (`@bookTicker` 채널, best bid/ask mid)**`
- 바이낸스 데이터 흐름 다이어그램: `BinanceTickerIngestion (AtomicReference CAS, strict monotonic)` → `BinanceTickerIngestion (AtomicReference CAS, accept-equal) → BinanceFlushJob (1초 down-sample)` 형태로 갱신.
- 디렉터리 트리 주석 `binance/BinanceTickerIngestion # CAS strict monotonic + lag 측정` → `binance/BinanceTickerIngestion # CAS accept-equal monotonic + latest 보관` 로 갱신하고, `BinanceFlushJob` 항목을 ingestion/binance 아래에 추가.

- [ ] **Step 3: PROJECT_STATUS.md 정정**

파일을 Read로 열어 다음을 정정한다.
- Phase 2 (#30) 설명 줄의 `BinanceTickerIngestion (CAS strict monotonic + lag 측정)` → `(CAS accept-equal monotonic + latest 보관)`.
- Epic #28 표 또는 인근에 `#52 Binance bookTicker 전환` 항목을 완료로 추가 (형식은 기존 표에 맞춤).

- [ ] **Step 4: CLAUDE.md 확인**

`batch/` 설명 줄(`REST 1초/30분 수집 또는 WebSocket 실시간 수집 + ...`)은 이미 "WebSocket 실시간 수집"으로 정확하므로 **변경 불필요**. Read로 확인만 하고 넘어간다.

- [ ] **Step 5: 커밋**

```bash
git add docs/superpowers/specs/2026-05-12-websocket-ingestion-design.md \
        .ai/architecture/ARCHITECTURE_DESIGN.md .ai/PROJECT_STATUS.md
git commit -m "docs: Binance miniTicker 주기 정정 + bookTicker 전환 반영 (#51, #52)

- miniTicker 실제 update speed 1초 → 2초 정정
- bookTicker 전환 및 BinanceFlushJob 1초 down-sample 반영"
```

---

## 완료 기준 (DoD)

- [ ] Task 1~7 전체 완료, 각 커밋 생성됨
- [ ] `./gradlew :apps:batch:test` 통과
- [ ] `./gradlew :apps:batch:compileKotlin :apps:batch:compileTestKotlin` 통과
- [ ] `BinanceWebSocketClient.URL`이 `@bookTicker`
- [ ] `BinanceFlushJob` / `BinanceFlushScheduler` 신규 추가, `@ConditionalOnProperty(binance.mode=websocket)` 가드
- [ ] `TickerData` 계약 불변 — 다운스트림(집계·김프) 영향 없음
- [ ] #51 리포 문서 3종 정정 완료 (CLAUDE.md는 확인만)
