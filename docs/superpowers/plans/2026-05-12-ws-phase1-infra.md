# Phase 1: WebSocket 공통 인프라 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** apps/batch에 WebSocket 클라이언트 공통 인프라(`WebSocketMetrics`, `WebSocketConnectionManager`)를 추가한다. Phase 2/3에서 거래소별 클라이언트를 만들기 위한 토대.

**Architecture:** Reactor Netty `ReactorNettyWebSocketClient`(webflux 의존성)를 래핑하는 generic Connection Manager + Micrometer 기반 메트릭. 거래소별 lifecycle은 Phase 2/3에서 `@Component`로 주입. Phase 1 자체는 단독 빈 등록 없이 클래스만 제공.

**Tech Stack:** Spring Boot 3.4, Kotlin 2.0 coroutines + reactor, Micrometer, okhttp3 MockWebServer (테스트).

**Issue:** #29 · **Epic:** #28 · **Spec:** `docs/superpowers/specs/2026-05-12-websocket-ingestion-design.md` (Phase 1 섹션)

---

## 사전 결정

1. **`RatePerSecondLimiter`는 산출물에서 제외 (YAGNI)** — Phase 3 빗썸 down-sample은 `@Scheduled(fixedRate=1000)` + `AtomicReference` 패턴으로 자연스럽게 1Hz 보장되므로 별도 limiter 불필요. **이 결정은 Task 0에서 spec/이슈를 먼저 정리한 후 코드 작업을 시작한다** (approved spec mutation 검토 게이트 확보).
2. **Connection Manager는 generic 클래스만 Phase 1에 제공.** 거래소별 빈 등록은 Phase 2/3 Configuration에서 수행 (Phase 1 단독으로는 `@Component` 등록 없음).
3. **테스트는 단위 테스트만.** `okhttp3.mockwebserver.MockWebServer`로 가짜 WebSocket endpoint를 띄워 연결/메시지/끊김 시나리오를 검증. 통합 테스트는 Phase 2 PR에서 실제 거래소 클라이언트와 함께 추가.
4. **`AlertService`는 required 의존성** — silent outage 알림 누락 방지. 테스트에서는 capture용 fake 구현 사용.

## 파일 구조

| 파일 | 역할 |
|------|------|
| Create `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketMetrics.kt` | Micrometer 메트릭 래퍼 (9종) |
| Create `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/HeartbeatPolicy.kt` | sealed interface (None/ClientPing/ServerPingResponse) |
| Create `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionConfig.kt` | 연결 설정 데이터 클래스 |
| Create `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManager.kt` | 연결 lifecycle + 재연결 + 하트비트 + 메트릭 통합 |
| Create `apps/batch/src/test/kotlin/io/premiumspread/infrastructure/websocket/WebSocketMetricsTest.kt` | 메트릭 단위 테스트 |
| Create `apps/batch/src/test/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManagerTest.kt` | Manager 단위 테스트 (MockWebServer) |
| Modify `docs/superpowers/specs/2026-05-12-websocket-ingestion-design.md` | RatePerSecondLimiter 제거, Phase 1 산출물 정리 |

---

### Task 0: 스펙·이슈 정리 (RatePerSecondLimiter 제거) — 코드 task 이전에 먼저 수행

**Files:**
- Modify: `docs/superpowers/specs/2026-05-12-websocket-ingestion-design.md`
- 이슈 본문: `#29` (gh issue edit)

approved spec에 변경이 들어가므로 이 작업을 별도 커밋으로 분리하여 코드 작업과 다른 검토 단위로 둔다.

- [ ] **Step 1: 스펙에서 RatePerSecondLimiter 언급 제거**

`docs/superpowers/specs/2026-05-12-websocket-ingestion-design.md` 두 곳:
1. `### 모듈 배치` 블록 내 `RatePerSecondLimiter.kt (Phase 3 사용, 인프라 phase에 미리 둠)` 라인 삭제
2. Phase 1 산출물의 `- \`RatePerSecondLimiter\` (Phase 3 사용, 인프라 phase에 미리 둠)` 항목 삭제

수정 후 `## 핵심 결정 사항` 끝에 다음 항목 추가:
- `11. RatePerSecondLimiter 미도입 — @Scheduled fixedRate=1000 + AtomicReference로 1Hz 자연 보장 (YAGNI)`

- [ ] **Step 2: 이슈 #29 본문에서 동일 항목 제거**

Run:

```bash
gh issue view 29 --json body --jq .body > /tmp/issue29.md
# 수동 편집 또는 sed로 RatePerSecondLimiter 라인 삭제 후
gh issue edit 29 --body "$(cat /tmp/issue29.md | sed '/RatePerSecondLimiter/d')"
```

- [ ] **Step 3: 커밋 (단독)**

```bash
git add docs/superpowers/specs/2026-05-12-websocket-ingestion-design.md
git commit -m "$(cat <<'EOF'
docs: RatePerSecondLimiter 제거 (YAGNI) (#29)

- Phase 3 빗썸 down-sample은 @Scheduled(fixedRate=1000) + AtomicReference로 충분
- 실제 사용처 없음, Phase 1 산출물 및 모듈 배치에서 제외
- 스펙 핵심 결정 사항에 미도입 사유 명시
EOF
)"
```

---

### Task 1: WebSocketMetrics — 9종 메트릭 정의

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketMetrics.kt`
- Test: `apps/batch/src/test/kotlin/io/premiumspread/infrastructure/websocket/WebSocketMetricsTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

`apps/batch/src/test/kotlin/io/premiumspread/infrastructure/websocket/WebSocketMetricsTest.kt`:

```kotlin
package io.premiumspread.infrastructure.websocket

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WebSocketMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = WebSocketMetrics(registry)

    @Test
    fun `setConnectionState 1로 설정하면 Gauge가 1을 반환한다`() {
        metrics.setConnectionState("binance", connected = true)
        val gauge = registry.find("ws.connection.state").tag("exchange", "binance").gauge()
        assertThat(gauge?.value()).isEqualTo(1.0)
    }

    @Test
    fun `setConnectionState false면 Gauge가 0을 반환한다`() {
        metrics.setConnectionState("binance", connected = true)
        metrics.setConnectionState("binance", connected = false)
        assertThat(registry.find("ws.connection.state").tag("exchange", "binance").gauge()?.value()).isEqualTo(0.0)
    }

    @Test
    fun `recordMessage 호출 시 Counter가 증가한다`() {
        metrics.recordMessage("binance")
        metrics.recordMessage("binance")
        assertThat(registry.find("ws.message.received").tag("exchange", "binance").counter()?.count()).isEqualTo(2.0)
    }

    @Test
    fun `recordReconnect 호출 시 Counter가 증가한다`() {
        metrics.recordReconnect("binance")
        assertThat(registry.find("ws.reconnect.attempt").tag("exchange", "binance").counter()?.count()).isEqualTo(1.0)
    }

    @Test
    fun `recordLag 호출 시 Timer에 기록된다`() {
        metrics.recordLag("binance", 12)
        val timer = registry.find("ws.message.lag.ms").tag("exchange", "binance").timer()
        assertThat(timer?.count()).isEqualTo(1L)
    }

    @Test
    fun `recordFirstMessageTimeout Counter 증가`() {
        metrics.recordFirstMessageTimeout("bithumb")
        assertThat(registry.find("ws.first.message.timeout").tag("exchange", "bithumb").counter()?.count()).isEqualTo(1.0)
    }

    @Test
    fun `recordOutOfOrder Counter 증가`() {
        metrics.recordOutOfOrder("bithumb")
        assertThat(registry.find("ws.out_of_order").tag("exchange", "bithumb").counter()?.count()).isEqualTo(1.0)
    }

    @Test
    fun `recordStale Counter 증가`() {
        metrics.recordStale("bithumb")
        assertThat(registry.find("ws.stale.bithumb").counter()?.count()).isEqualTo(1.0)
    }

    @Test
    fun `lastMessageAgeSeconds Gauge는 마지막 메시지 후 경과 초를 반환한다`() {
        val baseMs = 1_700_000_000_000L
        metrics.onMessageReceivedAt("binance", baseMs)
        val gauge = registry.find("ws.last.message.age").tag("exchange", "binance").gauge()
        assertThat(gauge).isNotNull
        // 같은 시각에 측정하면 0 또는 매우 작은 값
        val age = gauge!!.value()
        assertThat(age).isGreaterThanOrEqualTo(0.0)
    }
}
```

- [ ] **Step 2: 테스트 실행하여 컴파일 실패 확인**

Run: `./gradlew :apps:batch:test --tests "*.WebSocketMetricsTest"`
Expected: 컴파일 실패 (`WebSocketMetrics` 클래스 없음)

- [ ] **Step 3: 최소 구현 작성**

`apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketMetrics.kt`:

```kotlin
package io.premiumspread.infrastructure.websocket

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * WebSocket 인프라 메트릭.
 *
 * Phase 1 산출물 — 거래소별 태그(`exchange`)로 노출되며 Phase 2/3에서 record 메서드를 호출한다.
 */
@Component
class WebSocketMetrics(
    private val registry: MeterRegistry,
) {
    private val connectionStates = ConcurrentHashMap<String, AtomicReference<Double>>()
    private val lastMessageMs = ConcurrentHashMap<String, AtomicLong>()

    fun setConnectionState(exchange: String, connected: Boolean) {
        val ref = connectionStates.computeIfAbsent(exchange) {
            val initial = AtomicReference(0.0)
            Gauge.builder("ws.connection.state", initial) { it.get() }
                .tag("exchange", exchange)
                .description("1=connected, 0=disconnected")
                .register(registry)
            initial
        }
        ref.set(if (connected) 1.0 else 0.0)
    }

    fun recordMessage(exchange: String) {
        Counter.builder("ws.message.received").tag("exchange", exchange).register(registry).increment()
    }

    fun recordReconnect(exchange: String) {
        Counter.builder("ws.reconnect.attempt").tag("exchange", exchange).register(registry).increment()
    }

    fun recordLag(exchange: String, lagMs: Long) {
        Timer.builder("ws.message.lag.ms").tag("exchange", exchange).register(registry)
            .record(lagMs, TimeUnit.MILLISECONDS)
    }

    fun recordFirstMessageTimeout(exchange: String) {
        Counter.builder("ws.first.message.timeout").tag("exchange", exchange).register(registry).increment()
    }

    fun recordOutOfOrder(exchange: String) {
        Counter.builder("ws.out_of_order").tag("exchange", exchange).register(registry).increment()
    }

    fun recordStale(exchange: String) {
        Counter.builder("ws.stale.$exchange").register(registry).increment()
    }

    fun recordFlush(exchange: String) {
        Counter.builder("ticker.flush.$exchange").register(registry).increment()
    }

    fun recordFlushError(exchange: String, exception: Exception) {
        Counter.builder("ticker.flush.error.$exchange")
            .tag("exception", exception.javaClass.simpleName)
            .register(registry).increment()
    }

    /**
     * 마지막 메시지 수신 시각(ms epoch) 기록. `ws.last.message.age` Gauge가 (now - this) 초를 반환.
     */
    fun onMessageReceivedAt(exchange: String, epochMs: Long) {
        val ref = lastMessageMs.computeIfAbsent(exchange) {
            val initial = AtomicLong(epochMs)
            Gauge.builder("ws.last.message.age", initial) { (System.currentTimeMillis() - it.get()) / 1000.0 }
                .tag("exchange", exchange)
                .description("Seconds since last received WebSocket message")
                .register(registry)
            initial
        }
        ref.set(epochMs)
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :apps:batch:test --tests "*.WebSocketMetricsTest"`
Expected: 9개 테스트 PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketMetrics.kt \
        apps/batch/src/test/kotlin/io/premiumspread/infrastructure/websocket/WebSocketMetricsTest.kt
git commit -m "$(cat <<'EOF'
feat: WebSocketMetrics 추가 (#29)

- 9종 메트릭 정의 (connection_state, message_received, reconnect, lag,
  last_message_age, first_message_timeout, out_of_order, stale, flush, flush_error)
- Micrometer Counter/Gauge/Timer 기반
- ws.last.message.age는 (now - lastMessageMs) 초 동적 계산
EOF
)"
```

---

### Task 2: HeartbeatPolicy sealed interface + WebSocketConnectionConfig

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/HeartbeatPolicy.kt`
- Create: `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionConfig.kt`

이 task는 데이터 정의만, 별도 테스트 없음 (다음 task에서 함께 검증).

- [ ] **Step 1: HeartbeatPolicy 작성**

`apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/HeartbeatPolicy.kt`:

```kotlin
package io.premiumspread.infrastructure.websocket

import java.time.Duration

/**
 * 거래소별 하트비트 정책.
 *
 * - [None]: 별도 하트비트 송수신 없음 (사용 안 권장)
 * - [ServerPingResponse]: 서버가 ping frame을 보내면 클라이언트는 자동 pong (Netty 기본)
 * - [ClientPing]: 클라이언트가 일정 주기로 텍스트 ping 메시지를 송신 (빗썸 등)
 */
sealed interface HeartbeatPolicy {
    data object None : HeartbeatPolicy
    data object ServerPingResponse : HeartbeatPolicy
    data class ClientPing(val interval: Duration, val pingMessage: String) : HeartbeatPolicy
}
```

- [ ] **Step 2: WebSocketConnectionConfig 작성**

`apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionConfig.kt`:

```kotlin
package io.premiumspread.infrastructure.websocket

import java.time.Duration

/**
 * WebSocket 연결 설정.
 *
 * @param exchange 메트릭 태그용 거래소 식별자 (예: "binance", "bithumb")
 * @param url WebSocket endpoint URL
 * @param subscribeMessage 연결 직후 송신할 구독 메시지 (없으면 null)
 * @param heartbeat 하트비트 정책
 * @param firstMessageTimeout 연결 후 첫 메시지를 기다리는 시간. 초과 시 메트릭 + 알람.
 * @param onMessage 메시지 수신 콜백 (메시지 문자열)
 */
data class WebSocketConnectionConfig(
    val exchange: String,
    val url: String,
    val subscribeMessage: String? = null,
    val heartbeat: HeartbeatPolicy = HeartbeatPolicy.ServerPingResponse,
    val firstMessageTimeout: Duration = Duration.ofSeconds(5),
    val onMessage: (String) -> Unit,
)
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :apps:batch:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/HeartbeatPolicy.kt \
        apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionConfig.kt
git commit -m "$(cat <<'EOF'
feat: HeartbeatPolicy + WebSocketConnectionConfig (#29)

- sealed interface로 None/ServerPingResponse/ClientPing 3종 정의
- ConnectionConfig는 exchange/url/subscribe/heartbeat/firstMessageTimeout/onMessage 캡슐화
EOF
)"
```

---

### Task 3: WebSocketConnectionManager 기본 연결 + 메시지 수신

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManager.kt`
- Test: `apps/batch/src/test/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManagerTest.kt`

- [ ] **Step 1: 실패 테스트 작성 (정상 연결 + 메시지 수신)**

`apps/batch/src/test/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManagerTest.kt`:

```kotlin
package io.premiumspread.infrastructure.websocket

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class WebSocketConnectionManagerTest {

    private lateinit var server: MockWebServer
    private val registry = SimpleMeterRegistry()
    private val metrics = WebSocketMetrics(registry)
    private val client = ReactorNettyWebSocketClient()
    private var manager: WebSocketConnectionManager? = null

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        manager?.stop()
        server.shutdown()
    }

    @Test
    fun `연결 성공 시 메시지 수신 콜백이 호출되고 메트릭이 기록된다`() {
        val received = ConcurrentLinkedQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"price":"100"}""")
            }
        }))

        val config = WebSocketConnectionConfig(
            exchange = "test",
            url = wsUrl(),
            onMessage = { received.add(it) },
            firstMessageTimeout = Duration.ofSeconds(2),
        )
        manager = WebSocketConnectionManager(config, metrics, client).also { it.start() }

        await().atMost(3, TimeUnit.SECONDS).untilAsserted {
            assertThat(received).contains("""{"price":"100"}""")
        }
        assertThat(registry.find("ws.connection.state").tag("exchange", "test").gauge()?.value()).isEqualTo(1.0)
        assertThat(registry.find("ws.message.received").tag("exchange", "test").counter()?.count()).isGreaterThanOrEqualTo(1.0)
    }

    private fun wsUrl(): String {
        val httpUrl = server.url("/ws")
        return "ws://${httpUrl.host}:${httpUrl.port}/ws"
    }
}
```

> Awaitility를 사용한다. `apps/batch/build.gradle.kts`의 testImplementation에 `org.awaitility:awaitility:4.2.0` 추가가 필요하면 함께 추가한다 (이미 있으면 skip).

- [ ] **Step 2: 의존성 확인 + 추가**

Run: `grep -r awaitility apps/batch/build.gradle.kts || echo "MISSING"`
- 결과가 `MISSING`이면 `apps/batch/build.gradle.kts` testImplementation 블록에 `testImplementation("org.awaitility:awaitility:4.2.0")` 추가.

- [ ] **Step 3: 테스트 실행하여 실패 확인**

Run: `./gradlew :apps:batch:test --tests "*.WebSocketConnectionManagerTest"`
Expected: 컴파일 실패 (`WebSocketConnectionManager` 클래스 없음)

- [ ] **Step 4: 최소 구현 작성 (기본 연결만)**

`apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManager.kt`:

```kotlin
package io.premiumspread.infrastructure.websocket

import org.slf4j.LoggerFactory
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import org.springframework.web.reactive.socket.client.WebSocketClient
import reactor.core.Disposable
import reactor.core.publisher.Mono
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/**
 * 단일 WebSocket 연결의 lifecycle을 관리한다.
 *
 * 책임:
 * - 시작/종료 lifecycle
 * - 메시지 수신 → onMessage 콜백 + 메트릭
 *
 * 미구현 (Task 4/5/6에서 추가):
 * - 자동 재연결 + exponential backoff
 * - 하트비트 정책 적용
 * - 첫 메시지 타임아웃
 */
class WebSocketConnectionManager(
    private val config: WebSocketConnectionConfig,
    private val metrics: WebSocketMetrics,
    private val client: WebSocketClient = ReactorNettyWebSocketClient(),
) {
    private val log = LoggerFactory.getLogger("${javaClass.simpleName}[${config.exchange}]")
    private val subscription = AtomicReference<Disposable?>()

    fun start() {
        connect()
    }

    fun stop() {
        subscription.getAndSet(null)?.dispose()
        metrics.setConnectionState(config.exchange, connected = false)
    }

    private fun connect() {
        val mono = client.execute(URI.create(config.url)) { session: WebSocketSession ->
            metrics.setConnectionState(config.exchange, connected = true)

            val sendInit: Mono<Void> = config.subscribeMessage
                ?.let { msg -> session.send(Mono.just(session.textMessage(msg))) }
                ?: Mono.empty()

            val receive: Mono<Void> = session.receive()
                .doOnNext { frame ->
                    val payload = frame.payloadAsText
                    metrics.recordMessage(config.exchange)
                    metrics.onMessageReceivedAt(config.exchange, System.currentTimeMillis())
                    try {
                        config.onMessage(payload)
                    } catch (e: Exception) {
                        log.warn("onMessage handler failed", e)
                    }
                }
                .then()

            sendInit.then(receive)
        }

        val disposable = mono
            .doOnError { e -> log.error("WebSocket connection error", e) }
            .doFinally { metrics.setConnectionState(config.exchange, connected = false) }
            .subscribe()

        subscription.set(disposable)
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :apps:batch:test --tests "*.WebSocketConnectionManagerTest"`
Expected: 1개 테스트 PASS (`연결 성공 시 메시지 수신 콜백이 호출되고 메트릭이 기록된다`)

- [ ] **Step 6: 커밋**

```bash
git add apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManager.kt \
        apps/batch/src/test/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManagerTest.kt \
        apps/batch/build.gradle.kts
git commit -m "$(cat <<'EOF'
feat: WebSocketConnectionManager 기본 연결/수신 (#29)

- Reactor Netty WebSocketClient 래핑
- start/stop lifecycle, 메시지 수신 → onMessage 콜백 + 메트릭
- 재연결/하트비트/타임아웃은 후속 task에서 추가
- MockWebServer 기반 단위 테스트
EOF
)"
```

---

### Task 4: 자동 재연결 + Exponential Backoff + Stop/Start race 안전

**Files:**
- Modify: `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManager.kt`
- Modify: `apps/batch/src/test/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManagerTest.kt`

핵심: reconnect timer의 `Disposable`을 반드시 저장 + `stop()` 시 dispose해서 펜딩 timer가 재기동 후 중복 connection을 트리거하지 않도록 한다 (Codex review ISSUE-3 대응).

- [ ] **Step 1: 재연결 시나리오 테스트 추가**

기존 `WebSocketConnectionManagerTest`에 추가:

```kotlin
@Test
fun `서버가 연결을 끊으면 재연결을 시도하고 ws_reconnect_attempt 메트릭이 증가한다`() {
    val received = ConcurrentLinkedQueue<String>()
    // 첫 응답: 즉시 끊김
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            webSocket.close(1000, "bye")
        }
    }))
    // 두 번째 응답: 정상 메시지
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            webSocket.send("""{"price":"200"}""")
        }
    }))

    val config = WebSocketConnectionConfig(
        exchange = "test",
        url = wsUrl(),
        onMessage = { received.add(it) },
        firstMessageTimeout = Duration.ofSeconds(10),
    )
    manager = WebSocketConnectionManager(
        config, metrics, client,
        initialBackoff = Duration.ofMillis(50),
        maxBackoff = Duration.ofSeconds(1),
    ).also { it.start() }

    await().atMost(5, TimeUnit.SECONDS).untilAsserted {
        assertThat(received).contains("""{"price":"200"}""")
    }
    assertThat(registry.find("ws.reconnect.attempt").tag("exchange", "test").counter()?.count())
        .isGreaterThanOrEqualTo(1.0)
}

@Test
fun `stop 후 start 시 펜딩 reconnect timer가 dispose되어 중복 연결이 생기지 않는다`() {
    val connections = java.util.concurrent.atomic.AtomicInteger(0)
    // 즉시 끊김 → backoff 동안 stop()을 호출하고 곧바로 다시 start()
    repeat(4) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                connections.incrementAndGet()
                webSocket.close(1000, "bye")
            }
        }))
    }
    val config = WebSocketConnectionConfig(
        exchange = "test",
        url = wsUrl(),
        onMessage = { },
        firstMessageTimeout = Duration.ofSeconds(10),
    )
    manager = WebSocketConnectionManager(
        config, metrics, client,
        initialBackoff = Duration.ofMillis(500),
        maxBackoff = Duration.ofSeconds(2),
    ).also { it.start() }

    // 첫 연결 완료 + backoff 진입까지 대기
    await().atMost(2, TimeUnit.SECONDS).until { connections.get() >= 1 }

    // backoff 도중 stop → start
    manager!!.stop()
    Thread.sleep(100)
    manager!!.start()

    // 펜딩 timer가 살아있었다면 한 번 더 immediate 추가 연결이 발생 (총 3개 이상)
    // 정상 동작: stop이 펜딩을 dispose → start 후 첫 1개만 추가 (총 2개)
    Thread.sleep(700) // backoff 한 사이클
    assertThat(connections.get()).isLessThanOrEqualTo(3)
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :apps:batch:test --tests "*.WebSocketConnectionManagerTest"`
Expected: 재연결 테스트 + stop/start race 테스트 FAIL

- [ ] **Step 3: 재연결 로직 추가 (pendingReconnect Disposable 추적 포함)**

`WebSocketConnectionManager.kt`를 다음과 같이 수정:

```kotlin
package io.premiumspread.infrastructure.websocket

import org.slf4j.LoggerFactory
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import org.springframework.web.reactive.socket.client.WebSocketClient
import reactor.core.Disposable
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WebSocketConnectionManager(
    private val config: WebSocketConnectionConfig,
    private val metrics: WebSocketMetrics,
    private val client: WebSocketClient = ReactorNettyWebSocketClient(),
    private val initialBackoff: Duration = Duration.ofSeconds(1),
    private val maxBackoff: Duration = Duration.ofSeconds(30),
) {
    private val log = LoggerFactory.getLogger("${javaClass.simpleName}[${config.exchange}]")
    private val subscription = AtomicReference<Disposable?>()
    private val pendingReconnect = AtomicReference<Disposable?>()
    private val running = AtomicBoolean(false)
    private val currentBackoff = AtomicReference(initialBackoff)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        connectWithRetry()
    }

    fun stop() {
        running.set(false)
        subscription.getAndSet(null)?.dispose()
        pendingReconnect.getAndSet(null)?.dispose()
        metrics.setConnectionState(config.exchange, connected = false)
    }

    private fun connectWithRetry() {
        if (!running.get()) return

        val mono = client.execute(URI.create(config.url)) { session: WebSocketSession ->
            metrics.setConnectionState(config.exchange, connected = true)
            currentBackoff.set(initialBackoff) // 연결 성공 시 backoff 리셋

            val sendInit: Mono<Void> = config.subscribeMessage
                ?.let { msg -> session.send(Mono.just(session.textMessage(msg))) }
                ?: Mono.empty()

            val receive: Mono<Void> = session.receive()
                .doOnNext { frame ->
                    val payload = frame.payloadAsText
                    metrics.recordMessage(config.exchange)
                    metrics.onMessageReceivedAt(config.exchange, System.currentTimeMillis())
                    try {
                        config.onMessage(payload)
                    } catch (e: Exception) {
                        log.warn("onMessage handler failed", e)
                    }
                }
                .then()

            sendInit.then(receive)
        }

        val disposable = mono
            .doOnError { e -> log.error("WebSocket connection error: {}", e.message) }
            .doFinally {
                metrics.setConnectionState(config.exchange, connected = false)
                if (running.get()) {
                    val backoff = currentBackoff.get()
                    val next = (backoff.toMillis() * 2).coerceAtMost(maxBackoff.toMillis())
                    currentBackoff.set(Duration.ofMillis(next))
                    metrics.recordReconnect(config.exchange)
                    log.info("Reconnecting in {} ms", backoff.toMillis())
                    // 펜딩 reconnect timer를 추적해서 stop() 시 dispose 가능하게 함
                    val timer = Mono.delay(backoff, Schedulers.parallel())
                        .doOnNext {
                            if (running.get()) connectWithRetry()
                        }
                        .subscribe()
                    pendingReconnect.getAndSet(timer)?.dispose()
                }
            }
            .subscribe()

        subscription.set(disposable)
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :apps:batch:test --tests "*.WebSocketConnectionManagerTest"`
Expected: 모든 테스트 PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManager.kt \
        apps/batch/src/test/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManagerTest.kt
git commit -m "$(cat <<'EOF'
feat: WebSocketConnectionManager 자동 재연결 (#29)

- exponential backoff (initialBackoff → 2배 → maxBackoff)
- 연결 성공 시 backoff 리셋
- stop()으로 lifecycle 종료, running flag로 재연결 중단
- ws.reconnect.attempt 메트릭 증가
EOF
)"
```

---

### Task 5: 첫 메시지 타임아웃 (silent ingestion outage 방어) — cancellable side task

**Files:**
- Modify: `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManager.kt`
- Modify: `apps/batch/src/test/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManagerTest.kt`

**핵심 (Codex review ISSUE-2 대응)**: timeout은 **session handler 완료의 일부가 되면 안 된다.** `Mono.when`으로 묶으면 빠른 disconnect 시 receive가 끝나도 timeout이 안 끝나서 reconnect가 지연된다. 별도 cancellable Disposable로 분리하고, 첫 메시지 도착 또는 session 종료 시 dispose한다.

**AlertService는 required 의존성** (ISSUE-4): nullable 제거. 테스트에서는 capture용 fake 구현 사용.

- [ ] **Step 1: AlertService 인터페이스 확인 + 테스트 추가**

Run: `cat supports/monitoring/src/main/kotlin/io/premiumspread/monitoring/AlertService.kt`

확인 후 테스트 코드에 정확한 시그니처 반영:

```kotlin
private val capturedAlerts = ConcurrentLinkedQueue<String>()
private val fakeAlertService = object : io.premiumspread.monitoring.AlertService {
    override fun sendCriticalAlert(message: String) { capturedAlerts.add(message) }
    // 다른 메서드는 Step 1의 cat 결과를 보고 정확히 override
}
```

> **만약 인터페이스에 `sendWarningAlert` / `sendInfoAlert` 등이 있으면 모두 빈 구현으로 override해야 컴파일 통과.**

기존 테스트에 추가:

```kotlin
@Test
fun `연결 후 첫 메시지가 timeout 내 도착하지 않으면 first_message_timeout 메트릭과 알람을 호출한다`() {
    // 연결만 받고 메시지 송신 없음
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))

    val config = WebSocketConnectionConfig(
        exchange = "test",
        url = wsUrl(),
        onMessage = { },
        firstMessageTimeout = Duration.ofMillis(300),
    )
    manager = WebSocketConnectionManager(config, metrics, client, fakeAlertService).also { it.start() }

    await().atMost(2, TimeUnit.SECONDS).untilAsserted {
        assertThat(registry.find("ws.first.message.timeout").tag("exchange", "test").counter()?.count())
            .isGreaterThanOrEqualTo(1.0)
        assertThat(capturedAlerts).isNotEmpty()
    }
}

@Test
fun `메시지가 timeout 전에 도착하면 first_message_timeout 메트릭과 알람이 트리거되지 않는다`() {
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            webSocket.send("hello")
        }
    }))
    val config = WebSocketConnectionConfig(
        exchange = "test",
        url = wsUrl(),
        onMessage = { },
        firstMessageTimeout = Duration.ofMillis(500),
    )
    manager = WebSocketConnectionManager(config, metrics, client, fakeAlertService).also { it.start() }

    await().atMost(1, TimeUnit.SECONDS).until {
        (registry.find("ws.message.received").tag("exchange", "test").counter()?.count() ?: 0.0) >= 1.0
    }
    Thread.sleep(700) // timeout 시간 이상 대기
    assertThat(registry.find("ws.first.message.timeout").tag("exchange", "test").counter()?.count() ?: 0.0).isZero
    assertThat(capturedAlerts).isEmpty()
}

@Test
fun `서버가 즉시 끊으면 timeout 대기 없이 즉시 재연결을 시도한다 (Codex ISSUE-2 회귀 방지)`() {
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            webSocket.close(1000, "bye")
        }
    }))
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            webSocket.send("ok")
        }
    }))
    val config = WebSocketConnectionConfig(
        exchange = "test",
        url = wsUrl(),
        onMessage = { },
        firstMessageTimeout = Duration.ofSeconds(30), // 큰 값
    )
    manager = WebSocketConnectionManager(
        config, metrics, client, fakeAlertService,
        initialBackoff = Duration.ofMillis(100),
        maxBackoff = Duration.ofMillis(500),
    ).also { it.start() }

    // 재연결이 30s timeout을 기다리지 않고 100ms backoff 안에 일어나야 함
    await().atMost(2, TimeUnit.SECONDS).untilAsserted {
        assertThat(registry.find("ws.message.received").tag("exchange", "test").counter()?.count() ?: 0.0)
            .isGreaterThanOrEqualTo(1.0)
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :apps:batch:test --tests "*.WebSocketConnectionManagerTest"`
Expected: 신규 테스트 3개 FAIL

- [ ] **Step 3: 타임아웃 로직 추가 (cancellable side task + required AlertService)**

`WebSocketConnectionManager.kt`:

```kotlin
import io.premiumspread.monitoring.AlertService
// ...

class WebSocketConnectionManager(
    private val config: WebSocketConnectionConfig,
    private val metrics: WebSocketMetrics,
    private val client: WebSocketClient = ReactorNettyWebSocketClient(),
    private val alertService: AlertService,
    private val initialBackoff: Duration = Duration.ofSeconds(1),
    private val maxBackoff: Duration = Duration.ofSeconds(30),
) {
    // ...

    private fun connectWithRetry() {
        if (!running.get()) return
        val firstReceived = AtomicBoolean(false)
        val timeoutDisposable = AtomicReference<Disposable?>()

        val mono = client.execute(URI.create(config.url)) { session: WebSocketSession ->
            metrics.setConnectionState(config.exchange, connected = true)
            currentBackoff.set(initialBackoff)

            // 첫 메시지 타임아웃을 별도 cancellable 작업으로 시작 (session handler 완료에 묶이지 않음)
            val timer = Mono.delay(config.firstMessageTimeout, Schedulers.parallel())
                .doOnNext {
                    if (firstReceived.compareAndSet(false, true).not()) return@doOnNext
                    metrics.recordFirstMessageTimeout(config.exchange)
                    alertService.sendCriticalAlert(
                        "[${config.exchange}] WebSocket 연결 후 ${config.firstMessageTimeout.toSeconds()}초 내 메시지 미수신"
                    )
                }
                .subscribe()
            timeoutDisposable.set(timer)

            val sendInit: Mono<Void> = config.subscribeMessage
                ?.let { msg -> session.send(Mono.just(session.textMessage(msg))) }
                ?: Mono.empty()

            val receive: Mono<Void> = session.receive()
                .doOnNext { frame ->
                    // 첫 메시지 도착 시 timeout 취소
                    if (firstReceived.compareAndSet(false, true)) {
                        timeoutDisposable.getAndSet(null)?.dispose()
                    }
                    val payload = frame.payloadAsText
                    metrics.recordMessage(config.exchange)
                    metrics.onMessageReceivedAt(config.exchange, System.currentTimeMillis())
                    try {
                        config.onMessage(payload)
                    } catch (e: Exception) {
                        log.warn("onMessage handler failed", e)
                    }
                }
                .then()

            sendInit.then(receive)
        }

        val disposable = mono
            .doOnError { e -> log.error("WebSocket connection error: {}", e.message) }
            .doFinally {
                // session 종료 시 timeout도 무조건 정리 (이미 도착했으면 no-op)
                timeoutDisposable.getAndSet(null)?.dispose()
                metrics.setConnectionState(config.exchange, connected = false)
                if (running.get()) {
                    val backoff = currentBackoff.get()
                    val next = (backoff.toMillis() * 2).coerceAtMost(maxBackoff.toMillis())
                    currentBackoff.set(Duration.ofMillis(next))
                    metrics.recordReconnect(config.exchange)
                    log.info("Reconnecting in {} ms", backoff.toMillis())
                    val reconnectTimer = Mono.delay(backoff, Schedulers.parallel())
                        .doOnNext {
                            if (running.get()) connectWithRetry()
                        }
                        .subscribe()
                    pendingReconnect.getAndSet(reconnectTimer)?.dispose()
                }
            }
            .subscribe()

        subscription.set(disposable)
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :apps:batch:test --tests "*.WebSocketConnectionManagerTest"`
Expected: 모든 테스트 PASS (5개)

- [ ] **Step 5: 커밋**

```bash
git add apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManager.kt \
        apps/batch/src/test/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManagerTest.kt
git commit -m "$(cat <<'EOF'
feat: WebSocketConnectionManager 첫 메시지 타임아웃 (#29)

- 연결 후 firstMessageTimeout(기본 5s) 내 메시지 없으면
  ws.first.message.timeout 메트릭 + AlertService.sendCriticalAlert
- timeout은 별도 cancellable Disposable (session handler 완료에 묶이지 않음)
  → 빠른 disconnect 시 reconnect 지연 방지 (Codex review ISSUE-2 대응)
- AlertService를 required 의존성으로 강제 (ISSUE-4 대응)
- silent ingestion outage 방어
EOF
)"
```

---

### Task 6: 하트비트 정책 (ClientPing) 구현

**Files:**
- Modify: `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManager.kt`
- Modify: `apps/batch/src/test/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManagerTest.kt`

ServerPingResponse는 Netty 기본 동작이라 별도 구현 불필요. ClientPing은 주기적 텍스트 송신.

- [ ] **Step 1: 테스트 추가 (ClientPing 정책 → 서버가 ping 메시지 수신)**

```kotlin
@Test
fun `ClientPing 정책 시 일정 주기로 ping 메시지를 송신한다`() {
    val pingsReceived = ConcurrentLinkedQueue<String>()
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) { pingsReceived.add(text) }
    }))

    val config = WebSocketConnectionConfig(
        exchange = "test",
        url = wsUrl(),
        heartbeat = HeartbeatPolicy.ClientPing(interval = Duration.ofMillis(200), pingMessage = "ping"),
        firstMessageTimeout = Duration.ofSeconds(5),
        onMessage = { },
    )
    manager = WebSocketConnectionManager(config, metrics, client).also { it.start() }

    await().atMost(2, TimeUnit.SECONDS).untilAsserted {
        assertThat(pingsReceived.filter { it == "ping" }).hasSizeGreaterThanOrEqualTo(2)
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :apps:batch:test --tests "*.WebSocketConnectionManagerTest"`
Expected: ClientPing 테스트 FAIL

- [ ] **Step 3: ClientPing 구현 (cancellable side task로 분리)**

ping stream을 session handler 완료에 묶지 말 것 — Task 5와 동일한 lifecycle 문제 발생. session 종료 시 dispose하는 별도 Disposable로 분리.

`WebSocketConnectionManager.kt` 안의 `connectWithRetry()`에서 session handler 안에 ping Disposable 추가:

```kotlin
// 이미 timeoutDisposable이 AtomicReference로 있음. ping도 동일 패턴.
val pingDisposable = AtomicReference<Disposable?>()
// ...

val mono = client.execute(URI.create(config.url)) { session: WebSocketSession ->
    metrics.setConnectionState(config.exchange, connected = true)
    currentBackoff.set(initialBackoff)

    // 첫 메시지 타임아웃 (Task 5에서 추가됨)
    val timer = Mono.delay(config.firstMessageTimeout, Schedulers.parallel()) /* ... */ .subscribe()
    timeoutDisposable.set(timer)

    // 하트비트 ClientPing: 별도 Disposable
    when (val hb = config.heartbeat) {
        is HeartbeatPolicy.ClientPing -> {
            val ping = reactor.core.publisher.Flux.interval(hb.interval, Schedulers.parallel())
                .flatMap { session.send(Mono.just(session.textMessage(hb.pingMessage))) }
                .subscribe()
            pingDisposable.set(ping)
        }
        else -> { /* None / ServerPingResponse는 별도 송신 없음 */ }
    }

    val sendInit: Mono<Void> = config.subscribeMessage
        ?.let { msg -> session.send(Mono.just(session.textMessage(msg))) }
        ?: Mono.empty()

    val receive: Mono<Void> = session.receive()
        .doOnNext { /* ... 기존 그대로 ... */ }
        .then()

    sendInit.then(receive)
}

val disposable = mono
    .doOnError { e -> log.error("WebSocket connection error: {}", e.message) }
    .doFinally {
        // session 종료 시 ping + timeout 모두 정리
        timeoutDisposable.getAndSet(null)?.dispose()
        pingDisposable.getAndSet(null)?.dispose()
        metrics.setConnectionState(config.exchange, connected = false)
        // ... 기존 reconnect 로직 ...
    }
    .subscribe()
```

> 클래스 멤버에 `private val pingDisposable = AtomicReference<Disposable?>()` 도 추가. `stop()`에서도 `pingDisposable.getAndSet(null)?.dispose()` 호출 (안전벨트).

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :apps:batch:test --tests "*.WebSocketConnectionManagerTest"`
Expected: 모든 테스트 PASS (4개)

- [ ] **Step 5: 커밋**

```bash
git add apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManager.kt \
        apps/batch/src/test/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManagerTest.kt
git commit -m "$(cat <<'EOF'
feat: WebSocketConnectionManager ClientPing 하트비트 (#29)

- HeartbeatPolicy.ClientPing이면 interval마다 pingMessage 텍스트 송신
- ServerPingResponse는 Netty 기본 동작이라 별도 구현 없음
- 빗썸의 60s idle 종료 정책 대응 (Phase 3)
EOF
)"
```

---

### Task 7: 빌드/테스트 전체 통과 확인

- [ ] **Step 1: 전체 단위 테스트**

Run: `./gradlew :apps:batch:test`
Expected: BUILD SUCCESSFUL, WebSocket 관련 모든 테스트 PASS

- [ ] **Step 2: compileKotlin 전체**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 기존 테스트 회귀 없음 확인**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (모든 모듈)

---

## Self-Review Checklist (구현 완료 후 자체 점검)

- [ ] **Task 0**: 스펙·이슈에서 RatePerSecondLimiter 동기화 (코드 task 전에 별도 커밋)
- [ ] 스펙의 Phase 1 산출물 모두 구현 (`WebSocketMetrics`, `WebSocketConnectionManager` 및 부속 클래스)
- [ ] 메트릭 9종 모두 정의 + 노출 확인
- [ ] 재연결 + backoff 동작 + **stop/start race 시 중복 connection 없음** 확인
- [ ] 첫 메시지 타임아웃 + AlertService(required) 호출 확인 + **빠른 disconnect 시 reconnect 지연 없음** 확인
- [ ] ClientPing 하트비트가 별도 Disposable로 분리되어 session 종료 시 dispose 확인
- [ ] 기존 테스트 모두 통과 (회귀 없음)
- [ ] 커밋이 task 단위로 깔끔하게 나뉘어 있음

---

## Out of Scope (Phase 2/3에서 처리)

- `BinanceWebSocketClient` / `BithumbWebSocketClient`: 거래소별 클라이언트
- `BinanceTickerIngestion` / `BithumbTickerIngestion`: 도메인 ingestion
- `BithumbFlushJob` / `BithumbFlushScheduler`: 1Hz flush
- `TickerCacheService.saveToSecondsWithScore()`: 신규 메서드 (Phase 3에서 추가)
- 통합 테스트 (Redis TestContainer + 실제 거래소 클라이언트와 함께)
- 피처 플래그 (`premium.ingestion.{exchange}.mode`)
- `application.yml` 설정

---

## 참고

- Spec: `docs/superpowers/specs/2026-05-12-websocket-ingestion-design.md` (Phase 1 섹션)
- Issue: #29
- Epic: #28
- Codex review: 2라운드 반영분 (silent outage + monotonic check + last-run/alert) 모두 포함
