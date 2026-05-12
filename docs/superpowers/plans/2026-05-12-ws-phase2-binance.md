# Phase 2: 바이낸스 WebSocket 통합 (PoC) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Phase 1에서 만든 `WebSocketConnectionManager` + `WebSocketMetrics`를 사용해 바이낸스 `@miniTicker` 1초 push를 캐시에 직접 반영한다. `mode=websocket` 시 `TickerIngestionJob`의 바이낸스 REST 경로는 자동 스킵.

**Issue:** #30 · **Epic:** #28 · **Spec:** `docs/superpowers/specs/2026-05-12-websocket-ingestion-design.md` (Phase 2 섹션) + Issue #30 body

**Worktree:** `.worktrees/feat-issue-30-binance-ws` (branch: `feat/issue-30-binance-ws`, base: `feature/premium`)

---

## 사전 결정 사항

1. **currency 표기는 issue spec대로 `"USD"`** — 기존 `BinanceClient` REST 경로는 `"USDT"`를 반환하지만 `TickerAggregationScheduler.kt:34`에서 이미 `AggregationTarget("binance", "btc", "USD")`로 조회하므로 다운스트림은 `"USD"`를 가정. WebSocket 경로도 `"USD"`로 통일하여 issue 명세 준수. (REST의 USDT 불일치는 본 phase 범위 밖.)
2. **`BinanceWebSocketClient`(연결 + 파싱) vs `BinanceTickerIngestion`(monotonic + 캐시) 분리** — Phase 3 `BithumbTickerIngestion`과 대칭. ingestion은 pure component라 단위 테스트가 WebSocket 없이 가능.
3. **ingestion 컴포넌트는 `infrastructure/ingestion/binance/` 패키지** *(Codex review 2차 MAINTAINED → ACCEPT)*: `BinanceTickerIngestion`은 WebSocket 메시지 처리 + 캐시 어댑터 역할이므로 인프라 레이어가 자연. `application.ingestion`이 `infrastructure.websocket.WebSocketMetrics`를 직접 주입하는 것은 `.ai/rules/architecture.md`의 의존 방향 위반 소지. `infrastructure.ingestion.binance`로 배치.
4. **양쪽 mode 분기를 모두 Phase 2 PR에 포함** — `TickerIngestionJob.run()`에 `binanceMode == "rest"` / `bithumbMode == "rest"` 두 가지 분기를 같이 추가. Phase 3는 `bithumb.mode = websocket`만 토글하면 됨 (Phase 2/3 병렬 머지 시 충돌 회피).
5. **invalid mode 값은 startup에서 fail-fast** *(Codex review ISSUE-1 ACCEPT)*: mode가 `rest`/`websocket` 둘 다 아니면 REST/WS 둘 다 비활성되어 silent outage. `IngestionModeConfig`에 `@PostConstruct` validation 추가하여 `IllegalStateException` 발생.
6. **monotonic check는 `AtomicReference.updateAndGet`로 atomic** *(동시성 회귀 방지)*: 단순 get→비교→set은 race로 오래된 timestamp가 latest를 덮어쓸 수 있음. CAS로 atomic 보장 + 캐시 save는 update 성공 후에만.
7. **lag 메트릭 + 캐시 write 실패 alert** *(Codex review ISSUE-3, ISSUE-4 ACCEPT)*: 메시지 수신 시 `Duration.between(ticker.timestamp, Instant.now(clock))`을 `metrics.recordLag`로 기록. 캐시 write 실패는 swallow하지 않고 `metrics.recordFlushError` + 5회 연속 시 `AlertService.sendCriticalAlert`.
8. **parse 실패는 `ws.parse.error{exchange}` 메트릭 + 5회 연속 alert** *(Codex review ISSUE-2 부분 ACCEPT)*: Phase 1 `WebSocketConnectionManager`가 raw 메시지 수신 시점에 `ws.message.received` + `ws.last.message.age`를 갱신하지만, parse 실패는 별도 카운터로 추적해 silent outage 가시화. `MeterRegistry`로 client 내부에서 직접 카운터 등록 (Phase 1 `WebSocketMetrics`에 새 메서드 추가하지 않음 — Phase 2/3 병렬 머지 충돌 회피).
9. **`@PostConstruct` start / `@PreDestroy` stop** — Spring 라이프사이클로 WebSocket 연결 생명주기 관리. `@Scheduled` 사용 안 함 (1초 push 자체가 down-sample).
10. **JSON parsing은 ObjectMapper + data class** — 기존 `BinanceResponse.kt` 패턴 따라 `@JsonIgnoreProperties(ignoreUnknown=true)` 적용.
11. **`Clock` 주입** — `Instant.now()`를 테스트에서 제어하기 위해 `Clock`을 생성자 주입. Spring config에서 `Clock.systemUTC()`를 빈으로 노출 (없으면 추가).
12. **테스트는 단위 + 통합 모두 추가**:
    - 단위: parse 정상/실패, monotonic CAS 동시성, ingestion 정상/캐시실패 alert/lag, mode validator
    - 통합: MockWebServer + Redis TestContainer, 메시지 1건 → 캐시 반영, 5초 메시지 zero → AlertService 호출

## 파일 구조

| 파일 | 역할 |
|------|------|
| Create `apps/batch/src/main/kotlin/io/premiumspread/client/binance/BinanceWebSocketResponse.kt` | `@miniTicker` 메시지 DTO (`BinanceMiniTickerMessage`) |
| Create `apps/batch/src/main/kotlin/io/premiumspread/client/binance/BinanceWebSocketClient.kt` | WebSocket 연결 + 파싱 + parse-error 메트릭/alert, ingestion에 위임 (start/stop 라이프사이클) |
| Create `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/ingestion/binance/BinanceTickerIngestion.kt` | monotonic CAS + lag 메트릭 + 캐시 저장 + 실패 5회 alert |
| Create `apps/batch/src/main/kotlin/io/premiumspread/config/IngestionModeConfig.kt` | startup validation: mode가 rest/websocket 아닌 경우 fail-fast |
| Create or extend `apps/batch/src/main/kotlin/io/premiumspread/config/ClockConfig.kt` | `Clock.systemUTC()` 빈 (없으면 신규) |
| Modify `apps/batch/src/main/kotlin/io/premiumspread/application/job/ticker/TickerIngestionJob.kt` | mode별 분기 추가 (binance/bithumb 각각) |
| Modify `apps/batch/src/main/resources/application.yml` | `premium.ingestion.{binance,bithumb}.mode=rest` 기본값 추가 |
| Modify `apps/batch/src/main/resources/application-local.yml` | local에서 `binance.mode=websocket` 활성 (검증용) |
| Create `apps/batch/src/test/kotlin/io/premiumspread/client/binance/BinanceWebSocketClientTest.kt` | parse 단위 테스트 (정상/malformed/missing field) |
| Create `apps/batch/src/test/kotlin/io/premiumspread/infrastructure/ingestion/binance/BinanceTickerIngestionTest.kt` | monotonic CAS + 캐시 실패 alert + lag 단위 테스트 |
| Create `apps/batch/src/test/kotlin/io/premiumspread/config/IngestionModeConfigTest.kt` | invalid mode → fail-fast 단위 테스트 |
| Create `apps/batch/src/test/kotlin/io/premiumspread/application/job/ticker/TickerIngestionJobModeTest.kt` | mode 분기 단위 테스트 |
| Create `apps/batch/src/test/kotlin/io/premiumspread/client/binance/BinanceWebSocketIntegrationTest.kt` | `@Tag("integration")` MockWebServer + Redis TC 통합 테스트 |

---

### Task 1: BinanceMiniTickerMessage DTO

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/client/binance/BinanceWebSocketResponse.kt`

Binance `@miniTicker` payload 예시:
```json
{
  "e": "24hrMiniTicker",
  "E": 1706500000000,
  "s": "BTCUSDT",
  "c": "89277.10",
  "o": "88042.60",
  "h": "90000.00",
  "l": "87500.00",
  "v": "123456.789",
  "q": "10987654321.12"
}
```

- [ ] **Step 1: 파일 작성**

```kotlin
package io.premiumspread.client.binance

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Binance Futures @miniTicker stream payload.
 *
 * URL: wss://fstream.binance.com/ws/{symbol}@miniTicker
 *
 * Push 주기: 1초 고정 (마지막 1초 동안의 close/open/high/low/volume).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BinanceMiniTickerMessage(
    @JsonProperty("e") val eventType: String,
    @JsonProperty("E") val eventTime: Long,
    @JsonProperty("s") val symbol: String,
    @JsonProperty("c") val close: String,
    @JsonProperty("o") val open: String? = null,
    @JsonProperty("h") val high: String? = null,
    @JsonProperty("l") val low: String? = null,
    @JsonProperty("v") val volume: String? = null,
    @JsonProperty("q") val quoteVolume: String? = null,
)
```

---

### Task 2: BinanceTickerIngestion (pure component — infrastructure 레이어)

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/ingestion/binance/BinanceTickerIngestion.kt`
- Create: `apps/batch/src/test/kotlin/io/premiumspread/infrastructure/ingestion/binance/BinanceTickerIngestionTest.kt`

- [ ] **Step 1: 본체 작성 (CAS + lag + 캐시 실패 alert)**

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
 * 바이낸스 WebSocket으로 도착한 TickerData를 monotonic 검증 후 캐시에 저장한다.
 *
 * - `@miniTicker`는 1초 고정 push이므로 별도 down-sample 불필요. 메시지 수신 즉시 hash + 초ZSet 동시 저장.
 * - monotonic은 `updateAndGet` CAS로 atomic — 동시 메시지 race 차단.
 * - 캐시 write 실패 5회 연속 → critical alert (silent data loss 방지).
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
    private val lastTimestamp = AtomicReference<Instant?>(null)
    private val consecutiveSaveFailures = AtomicInteger(0)

    fun onMessage(ticker: TickerData) {
        // Atomic monotonic CAS
        val updated = lastTimestamp.updateAndGet { prev ->
            if (prev != null && !ticker.timestamp.isAfter(prev)) prev else ticker.timestamp
        }
        if (updated != ticker.timestamp) {
            metrics.recordOutOfOrder(EXCHANGE)
            log.debug("Discard out-of-order binance ticker: prev={}, current={}", updated, ticker.timestamp)
            return
        }

        val now = Instant.now(clock)
        val lagMs = Duration.between(ticker.timestamp, now).toMillis().coerceAtLeast(0)
        metrics.recordLag(EXCHANGE, lagMs)

        try {
            tickerCacheService.save(ticker)
            tickerCacheService.saveToSeconds(ticker)
            consecutiveSaveFailures.set(0)
        } catch (e: Exception) {
            metrics.recordFlushError(EXCHANGE, e)
            val failures = consecutiveSaveFailures.incrementAndGet()
            log.warn("Binance ingestion cache write failed (consecutive={}): {}", failures, e.message)
            if (failures >= FAILURE_ALERT_THRESHOLD) {
                alertService.sendCriticalAlert("[binance] WebSocket ingestion 캐시 저장 5회 연속 실패: ${e.message}")
                consecutiveSaveFailures.set(0)
            }
        }
    }

    companion object {
        private const val EXCHANGE = "binance"
        private const val FAILURE_ALERT_THRESHOLD = 5
    }
}
```

- [ ] **Step 2: 단위 테스트 작성**

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
    private val fixedNow = Instant.parse("2026-05-12T00:00:10Z")
    private lateinit var tickerCacheService: TickerCacheService
    private lateinit var metrics: WebSocketMetrics
    private lateinit var alertService: AlertService
    private lateinit var ingestion: BinanceTickerIngestion

    @BeforeEach
    fun setUp() {
        tickerCacheService = mockk(relaxed = true)
        metrics = mockk(relaxed = true)
        alertService = mockk(relaxed = true)
        ingestion = BinanceTickerIngestion(
            tickerCacheService, metrics, alertService,
            Clock.fixed(fixedNow, ZoneOffset.UTC),
        )
    }

    @Test
    fun `정상 메시지는 hash와 초ZSet 둘 다 저장하고 lag을 기록한다`() {
        val ticker = tickerAt(Instant.parse("2026-05-12T00:00:08Z"))

        ingestion.onMessage(ticker)

        verify(exactly = 1) { tickerCacheService.save(ticker) }
        verify(exactly = 1) { tickerCacheService.saveToSeconds(ticker) }
        verify(exactly = 1) { metrics.recordLag("binance", 2000L) }
    }

    @Test
    fun `직전 메시지보다 timestamp가 빠르면 폐기하고 out_of_order를 기록한다`() {
        val first = tickerAt(Instant.parse("2026-05-12T00:00:01Z"))
        val stale = tickerAt(Instant.parse("2026-05-12T00:00:00Z"))

        ingestion.onMessage(first)
        ingestion.onMessage(stale)

        verify(exactly = 1) { tickerCacheService.save(first) }
        verify(exactly = 0) { tickerCacheService.save(stale) }
        verify(exactly = 1) { metrics.recordOutOfOrder("binance") }
    }

    @Test
    fun `timestamp가 같으면 (=) 새 메시지는 폐기되어 한 번만 저장된다 (CAS strict ordering)`() {
        val ts = Instant.parse("2026-05-12T00:00:00Z")

        ingestion.onMessage(tickerAt(ts))
        ingestion.onMessage(tickerAt(ts))

        verify(exactly = 1) { tickerCacheService.save(any()) }
        verify(exactly = 1) { metrics.recordOutOfOrder("binance") }
    }

    @Test
    fun `cache save 5회 연속 실패면 sendCriticalAlert가 호출되고 counter는 리셋된다`() {
        every { tickerCacheService.save(any()) } throws RuntimeException("redis down")
        repeat(5) { ingestion.onMessage(tickerAt(Instant.parse("2026-05-12T00:00:00Z").plusMillis(it.toLong()))) }

        verify(exactly = 5) { metrics.recordFlushError(eq("binance"), any()) }
        verify(exactly = 1) { alertService.sendCriticalAlert(match { it.contains("5회 연속") }) }
    }

    @Test
    fun `동시에 다수 스레드에서 호출해도 최종 latest는 max timestamp가 된다 (CAS atomic)`() {
        val threadCount = 16; val perThread = 100
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
                } finally { latch.countDown() }
            }
        }
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue
        executor.shutdownNow()

        // lastTimestamp는 private이므로 마지막 onMessage가 stale 처리되는지로 확인
        ingestion.onMessage(tickerAt(base.plusMillis(maxOffsetMs - 1))) // max-1은 stale이어야 함
        verify(atLeast = 1) { metrics.recordOutOfOrder("binance") }
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

---

### Task 3: BinanceWebSocketClient (연결 + 파싱)

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/client/binance/BinanceWebSocketClient.kt`
- Create: `apps/batch/src/test/kotlin/io/premiumspread/client/binance/BinanceWebSocketClientTest.kt`

- [ ] **Step 1: 본체 작성**

```kotlin
package io.premiumspread.client.binance

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.premiumspread.client.TickerData
import io.premiumspread.infrastructure.ingestion.binance.BinanceTickerIngestion
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
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * 바이낸스 BTCUSDT 무기한 선물 `@miniTicker` (1초 push) WebSocket 구독.
 *
 * - 연결 직후 별도 subscribe 메시지 불필요 (URL에 채널이 포함됨).
 * - 메시지마다 [BinanceTickerIngestion]로 위임.
 * - parse 실패는 `ws.parse.error{exchange=binance}` counter 증가 + 5회 연속 시 critical alert.
 */
@Component
@ConditionalOnProperty("premium.ingestion.binance.mode", havingValue = "websocket")
class BinanceWebSocketClient(
    private val ingestion: BinanceTickerIngestion,
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
            heartbeat = HeartbeatPolicy.ServerPingResponse,
            onMessage = ::handlePayload,
        ),
        metrics = metrics,
        alertService = alertService,
    )

    @PostConstruct
    fun start() {
        log.info("Starting Binance WebSocket client: {}", URL)
        manager.start()
    }

    @PreDestroy
    fun stop() {
        log.info("Stopping Binance WebSocket client")
        manager.stop()
    }

    internal fun handlePayload(payload: String) {
        val ticker = parse(payload)
        if (ticker == null) return
        consecutiveParseErrors.set(0)
        ingestion.onMessage(ticker)
    }

    internal fun parse(payload: String): TickerData? {
        return try {
            val msg = objectMapper.readValue(payload, BinanceMiniTickerMessage::class.java)
            val price = msg.close.toBigDecimalOrNull()
                ?: return recordParseError("invalid price: ${msg.close}")
            TickerData(
                exchange = EXCHANGE_UPPER,
                symbol = extractBaseSymbol(msg.symbol),
                currency = CURRENCY,
                price = price,
                volume = msg.volume?.toBigDecimalOrNull(),
                timestamp = Instant.ofEpochMilli(msg.eventTime),
            )
        } catch (e: Exception) {
            recordParseError("exception: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun recordParseError(reason: String): TickerData? {
        parseErrorCounter.increment()
        val failures = consecutiveParseErrors.incrementAndGet()
        log.warn("Binance parse error ({}): {}", failures, reason)
        if (failures >= PARSE_FAILURE_ALERT_THRESHOLD) {
            alertService.sendCriticalAlert("[binance] WebSocket 메시지 parse 5회 연속 실패: $reason")
            consecutiveParseErrors.set(0)
        }
        return null
    }

    private fun extractBaseSymbol(symbol: String): String = when {
        symbol.endsWith("USDT") -> symbol.dropLast(4)
        symbol.endsWith("USD") -> symbol.dropLast(3)
        else -> symbol
    }

    companion object {
        const val URL = "wss://fstream.binance.com/ws/btcusdt@miniTicker"
        private const val EXCHANGE = "binance"
        private const val EXCHANGE_UPPER = "BINANCE"
        // issue spec 준수: 다운스트림 TickerAggregationScheduler가 "USD"로 조회하므로 통일.
        private const val CURRENCY = "USD"
        private const val PARSE_FAILURE_ALERT_THRESHOLD = 5
    }
}
```

- [ ] **Step 2: 단위 테스트 작성**

```kotlin
package io.premiumspread.client.binance

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.premiumspread.infrastructure.ingestion.binance.BinanceTickerIngestion
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
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
    fun `정상 miniTicker payload는 TickerData로 파싱된다`() {
        val payload = """
            {"e":"24hrMiniTicker","E":1715470800000,"s":"BTCUSDT","c":"89277.10","o":"88042.60","h":"90000.00","l":"87500.00","v":"123.45","q":"1000000"}
        """.trimIndent()

        val ticker = client.parse(payload)

        assertThat(ticker).isNotNull
        assertThat(ticker!!.exchange).isEqualTo("BINANCE")
        assertThat(ticker.symbol).isEqualTo("BTC")
        assertThat(ticker.currency).isEqualTo("USD")
        assertThat(ticker.price).isEqualByComparingTo(BigDecimal("89277.10"))
        assertThat(ticker.volume).isEqualByComparingTo(BigDecimal("123.45"))
        assertThat(ticker.timestamp).isEqualTo(Instant.ofEpochMilli(1715470800000))
    }

    @Test
    fun `malformed JSON은 null을 반환한다`() {
        val ticker = client.parse("not-json")
        assertThat(ticker).isNull()
    }

    @Test
    fun `숫자가 아닌 price는 null을 반환한다`() {
        val payload = """{"e":"24hrMiniTicker","E":1,"s":"BTCUSDT","c":"NaN"}"""
        val ticker = client.parse(payload)
        assertThat(ticker).isNull()
    }
}
```

---

### Task 4: TickerIngestionJob mode 분기

**Files:**
- Modify: `apps/batch/src/main/kotlin/io/premiumspread/application/job/ticker/TickerIngestionJob.kt`
- Create: `apps/batch/src/test/kotlin/io/premiumspread/application/job/ticker/TickerIngestionJobModeTest.kt`

`@Value` 주입으로 mode를 읽고, `rest`일 때만 해당 거래소 fetch 실행.

- [ ] **Step 1: TickerIngestionJob.kt 수정**

```kotlin
package io.premiumspread.application.job.ticker

import io.premiumspread.application.common.JobResult
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.client.binance.BinanceClient
import io.premiumspread.client.bithumb.BithumbClient
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class TickerIngestionJob(
    private val bithumbClient: BithumbClient,
    private val binanceClient: BinanceClient,
    private val tickerCacheService: TickerCacheService,
    @Value("\${premium.ingestion.binance.mode:rest}") private val binanceMode: String,
    @Value("\${premium.ingestion.bithumb.mode:rest}") private val bithumbMode: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): JobResult {
        return try {
            runBlocking {
                val bithumbDeferred = if (bithumbMode == REST_MODE) async { bithumbClient.getBtcTicker() } else null
                val binanceDeferred = if (binanceMode == REST_MODE) async { binanceClient.getBtcFuturesTicker() } else null

                bithumbDeferred?.await()?.let { ticker ->
                    tickerCacheService.save(ticker)
                    tickerCacheService.saveToSeconds(ticker)
                    log.debug("REST fetched Bithumb ticker: {} KRW", ticker.price)
                }
                binanceDeferred?.await()?.let { ticker ->
                    tickerCacheService.save(ticker)
                    tickerCacheService.saveToSeconds(ticker)
                    log.debug("REST fetched Binance ticker: {} USDT", ticker.price)
                }
            }
            JobResult.Success
        } catch (e: Exception) {
            log.error("Failed to fetch tickers", e)
            JobResult.Failure(e)
        }
    }

    companion object {
        private const val REST_MODE = "rest"
    }
}
```

- [ ] **Step 2: 단위 테스트 작성**

```kotlin
package io.premiumspread.application.job.ticker

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.application.common.JobResult
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.client.TickerData
import io.premiumspread.client.binance.BinanceClient
import io.premiumspread.client.bithumb.BithumbClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class TickerIngestionJobModeTest {

    private val bithumbClient = mockk<BithumbClient>()
    private val binanceClient = mockk<BinanceClient>()
    private val cache = mockk<TickerCacheService>(relaxed = true)

    @Test
    fun `두 거래소 모두 rest면 둘 다 fetch한다`() {
        coEvery { bithumbClient.getBtcTicker() } returns ticker("BITHUMB")
        coEvery { binanceClient.getBtcFuturesTicker() } returns ticker("BINANCE")
        val job = TickerIngestionJob(bithumbClient, binanceClient, cache, binanceMode = "rest", bithumbMode = "rest")

        val result = job.run()

        assertThat(result).isEqualTo(JobResult.Success)
        coVerify(exactly = 1) { bithumbClient.getBtcTicker() }
        coVerify(exactly = 1) { binanceClient.getBtcFuturesTicker() }
        verify(exactly = 2) { cache.save(any()) }
    }

    @Test
    fun `binance만 websocket이면 binance REST는 호출되지 않는다`() {
        coEvery { bithumbClient.getBtcTicker() } returns ticker("BITHUMB")
        val job = TickerIngestionJob(bithumbClient, binanceClient, cache, binanceMode = "websocket", bithumbMode = "rest")

        val result = job.run()

        assertThat(result).isEqualTo(JobResult.Success)
        coVerify(exactly = 0) { binanceClient.getBtcFuturesTicker() }
        coVerify(exactly = 1) { bithumbClient.getBtcTicker() }
    }

    @Test
    fun `bithumb만 websocket이면 bithumb REST는 호출되지 않는다`() {
        coEvery { binanceClient.getBtcFuturesTicker() } returns ticker("BINANCE")
        val job = TickerIngestionJob(bithumbClient, binanceClient, cache, binanceMode = "rest", bithumbMode = "websocket")

        val result = job.run()

        assertThat(result).isEqualTo(JobResult.Success)
        coVerify(exactly = 0) { bithumbClient.getBtcTicker() }
        coVerify(exactly = 1) { binanceClient.getBtcFuturesTicker() }
    }

    @Test
    fun `둘 다 websocket이면 아무 REST도 호출하지 않고 Success를 반환한다`() {
        val job = TickerIngestionJob(bithumbClient, binanceClient, cache, binanceMode = "websocket", bithumbMode = "websocket")

        val result = job.run()

        assertThat(result).isEqualTo(JobResult.Success)
        coVerify(exactly = 0) { bithumbClient.getBtcTicker() }
        coVerify(exactly = 0) { binanceClient.getBtcFuturesTicker() }
        verify(exactly = 0) { cache.save(any()) }
    }

    private fun ticker(exchange: String) = TickerData(
        exchange = exchange,
        symbol = "BTC",
        currency = if (exchange == "BITHUMB") "KRW" else "USDT",
        price = BigDecimal("100"),
        volume = null,
        timestamp = Instant.now(),
    )
}
```

---

### Task 4b: IngestionModeConfig (startup validation + Clock bean)

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/config/IngestionModeConfig.kt`
- Create: `apps/batch/src/test/kotlin/io/premiumspread/config/IngestionModeConfigTest.kt`
- Optional: `apps/batch/src/main/kotlin/io/premiumspread/config/ClockConfig.kt` (없으면 추가, 있으면 skip)

`premium.ingestion.{binance,bithumb}.mode` 값을 startup에 validation. invalid 값(e.g. typo/blank/대소문자 다름)일 경우 둘 다 비활성되어 silent outage가 발생하므로 fail-fast.

- [ ] **Step 1: Clock 빈 확인 후 없으면 신규**

```bash
grep -rn "Clock.systemUTC\|@Bean.*Clock\|: Clock" apps/batch/src/main/kotlin/
```

없으면:
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

- [ ] **Step 2: IngestionModeConfig 작성**

```kotlin
package io.premiumspread.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration

@Configuration
class IngestionModeConfig(
    @Value("\${premium.ingestion.binance.mode:rest}") private val binanceMode: String,
    @Value("\${premium.ingestion.bithumb.mode:rest}") private val bithumbMode: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun validate() {
        require(binanceMode in VALID_MODES) {
            "Invalid premium.ingestion.binance.mode: '$binanceMode' (must be one of $VALID_MODES)"
        }
        require(bithumbMode in VALID_MODES) {
            "Invalid premium.ingestion.bithumb.mode: '$bithumbMode' (must be one of $VALID_MODES)"
        }
        log.info("Ingestion modes — binance: {}, bithumb: {}", binanceMode, bithumbMode)
    }

    companion object {
        val VALID_MODES = setOf("rest", "websocket")
    }
}
```

- [ ] **Step 3: 단위 테스트 작성**

```kotlin
package io.premiumspread.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class IngestionModeConfigTest {

    @Test
    fun `정상 mode 값(rest, websocket)은 검증 통과`() {
        listOf(
            "rest" to "rest",
            "rest" to "websocket",
            "websocket" to "rest",
            "websocket" to "websocket",
        ).forEach { (b, t) ->
            IngestionModeConfig(b, t).validate() // throws == fail
        }
    }

    @Test
    fun `invalid binance mode면 IllegalArgumentException 발생`() {
        assertThatThrownBy { IngestionModeConfig("foobar", "rest").validate() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("binance.mode")
    }

    @Test
    fun `invalid bithumb mode면 IllegalArgumentException 발생`() {
        assertThatThrownBy { IngestionModeConfig("rest", "WEBSOCKET").validate() } // 대소문자 strict
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("bithumb.mode")
    }

    @Test
    fun `빈 문자열도 invalid로 분류`() {
        assertThatThrownBy { IngestionModeConfig("", "rest").validate() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

---

### Task 5: application 설정 추가

**Files:**
- Modify: `apps/batch/src/main/resources/application.yml`
- Modify: `apps/batch/src/main/resources/application-local.yml`

- [ ] **Step 1: application.yml — 기본 모드 명시**

상단 `exchange-rate:` 블록 다음에 다음을 추가:

```yaml
# 시세 수집 모드 (rest | websocket)
premium:
  ingestion:
    binance:
      mode: rest
    bithumb:
      mode: rest
```

- [ ] **Step 2: application-local.yml — local에서 binance만 websocket 활성**

```yaml
exchange-rate:
  api-key: local-dummy-exchange-rate-api-key

premium:
  ingestion:
    binance:
      mode: websocket
    bithumb:
      mode: rest
```

---

### Task 6: 통합 테스트 (MockWebServer + Redis TC)

**Files:**
- Create: `apps/batch/src/test/kotlin/io/premiumspread/client/binance/BinanceWebSocketIntegrationTest.kt`

`okhttp3.mockwebserver.MockWebServer`로 가짜 WebSocket endpoint, Redis는 testcontainers, AlertService는 capture fake.

- [ ] **Step 1: 통합 테스트 작성**

```kotlin
package io.premiumspread.client.binance

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.infrastructure.ingestion.binance.BinanceTickerIngestion
import io.premiumspread.infrastructure.websocket.WebSocketConnectionConfig
import io.premiumspread.infrastructure.websocket.WebSocketConnectionManager
import io.premiumspread.infrastructure.websocket.WebSocketMetrics
import io.premiumspread.monitoring.AlertService
import io.premiumspread.redis.RedisTestContainersConfig
import io.premiumspread.redis.RedisKeyGenerator
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
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
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestContainersConfig::class)
class BinanceWebSocketIntegrationTest {

    @Autowired private lateinit var tickerCacheService: TickerCacheService

    private lateinit var server: MockWebServer
    private val metrics = WebSocketMetrics(SimpleMeterRegistry())
    private val captured = ConcurrentLinkedQueue<String>()
    private val fakeAlertService = object : AlertService {
        override fun sendAlert(message: String, severity: AlertService.Severity) {
            captured.add("[$severity] $message")
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
    fun `메시지 1건 push되면 TickerCacheService 캐시가 갱신된다`() {
        val payload = """{"e":"24hrMiniTicker","E":1715470800000,"s":"BTCUSDT","c":"89277.10"}"""
        server.enqueue(MockResponse().withWebSocketUpgrade(EchoOnce(payload)))

        val ingestion = BinanceTickerIngestion(tickerCacheService, metrics, fakeAlertService, java.time.Clock.systemUTC())
        val client = BinanceWebSocketClient(ingestion, metrics, fakeAlertService, ObjectMapper(), io.micrometer.core.instrument.simple.SimpleMeterRegistry())
        // direct manager so we can override URL to MockWebServer
        val manager = WebSocketConnectionManager(
            config = WebSocketConnectionConfig(
                exchange = "binance",
                url = server.url("/ws/btcusdt@miniTicker").toString().replace("http", "ws"),
                onMessage = client::handlePayload,
            ),
            metrics = metrics,
            alertService = fakeAlertService,
        )
        manager.start()

        await.atMost(Duration.ofSeconds(5)).untilCallTo {
            tickerCacheService.get("BINANCE", "BTC")
        } matches { it != null && it.price.toPlainString() == "89277.10" }

        manager.stop()
    }

    @Test
    fun `연결됐지만 5초 동안 메시지 zero면 AlertService에 critical alert가 호출된다`() {
        // upgrade만 하고 메시지 안 보내는 listener
        server.enqueue(MockResponse().withWebSocketUpgrade(IdleListener()))

        val ingestion = BinanceTickerIngestion(tickerCacheService, metrics, fakeAlertService, java.time.Clock.systemUTC())
        val client = BinanceWebSocketClient(ingestion, metrics, fakeAlertService, ObjectMapper(), io.micrometer.core.instrument.simple.SimpleMeterRegistry())
        val manager = WebSocketConnectionManager(
            config = WebSocketConnectionConfig(
                exchange = "binance",
                url = server.url("/ws/btcusdt@miniTicker").toString().replace("http", "ws"),
                firstMessageTimeout = Duration.ofSeconds(2),
                onMessage = client::handlePayload,
            ),
            metrics = metrics,
            alertService = fakeAlertService,
        )
        manager.start()

        await.atMost(Duration.ofSeconds(6)).untilCallTo { captured.toList() } matches {
            it.any { msg -> msg.contains("[CRITICAL]") && msg.contains("binance") }
        }

        manager.stop()
    }
}

// 메시지 1건 송신 후 종료
private class EchoOnce(private val payload: String) : okhttp3.WebSocketListener() {
    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
        webSocket.send(payload)
        webSocket.close(1000, null)
    }
}

private class IdleListener : okhttp3.WebSocketListener()
```

> **NOTE:** `RedisTestContainersConfig`는 `:modules:redis`의 testFixtures에서 제공. `TickerCacheService` Bean이 test profile에서 활성화되도록 별도 config가 필요하면 Task 6 진행 중 발견 시 보완.

---

### Task 7: 빌드/테스트 검증

- [ ] **Step 1: compileKotlin 통과**

```bash
./gradlew :apps:batch:compileKotlin
```

- [ ] **Step 2: 단위 테스트 통과**

```bash
./gradlew :apps:batch:test
```

- [ ] **Step 3: 통합 테스트 통과 (Docker 필요)**

```bash
docker compose -f docker/infra-compose.yml up -d
./gradlew :apps:batch:integrationTest
```

- [ ] **Step 4: 로컬 수동 검증 (선택)**

`application-local.yml`에서 `binance.mode=websocket`로 설정 후:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :apps:batch:bootRun
# 1초 단위로 ws.message.received{exchange=binance} 카운터 증가 확인
curl -s localhost:8081/actuator/metrics/ws.message.received | jq
```

---

## DoD 체크리스트

- [ ] `mode=websocket` 시 `TickerIngestionJob`의 바이낸스 REST 분기가 호출되지 않음 (단위 테스트)
- [ ] WebSocket 메시지 1건 → hash + 초ZSet 모두 갱신 (통합 테스트)
- [ ] monotonic check (CAS atomic) — 오래된 timestamp 폐기 + `ws.out_of_order` 카운터 증가 (단위 테스트)
- [ ] 연결 후 5초 메시지 zero → `ws.first.message.timeout` + AlertService 호출 (통합 테스트)
- [ ] **invalid mode 값 → startup fail-fast (`IllegalArgumentException`)** — 단위
- [ ] **lag 메트릭 (`ws.message.lag.ms{binance}`) 기록** — 단위
- [ ] **5회 연속 cache write 실패 → `AlertService.sendCriticalAlert` 호출** — 단위
- [ ] **5회 연속 parse 실패 → `AlertService.sendCriticalAlert` 호출 + `ws.parse.error{binance}` 누적** — 단위
- [ ] `compileKotlin` + `:apps:batch:test` 통과
- [ ] `:apps:batch:integrationTest` 통과
- [ ] `mode=websocket`으로 local 1시간 무중단 검증 (수동, DoD 확인용)
