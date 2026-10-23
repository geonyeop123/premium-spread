package io.premiumspread.infrastructure.batch.notification

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.domain.owneralert.DiscordMessage
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertSendException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.io.IOException
import java.net.URI
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

@ExtendWith(OutputCaptureExtension::class)
class DiscordWebhookSenderAdapterTest {

    private val mapper = ObjectMapper()
    private val server = MockWebServer()
    private val sleeps = mutableListOf<Duration>()
    private val message = DiscordMessage("제목 \"q\"", "줄1\n줄2 \\ 끝", 0x2ECC71)

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        Thread.interrupted()
        runCatching { server.shutdown() }
    }

    private fun uri(token: String): URI = server.url("/api/webhooks/1/$token").toUri()

    private val pnlUri get() = uri("TOKENpnlQ7")
    private val marketUri get() = uri("TOKENmktQ7")
    private val opsUri get() = uri("TOKENopsQ7")

    private fun adapter(
        webhooks: DiscordWebhooks = DiscordWebhooks(pnlUri, marketUri, opsUri),
        timeout: Duration = Duration.ofSeconds(3),
    ) = DiscordWebhookSenderAdapter(webhooks, timeout, Duration.ofSeconds(2)) { sleeps += it }

    private fun route(target: DiscordTarget = DiscordTarget.PNL, push: DiscordPush = DiscordPush.SOUND) =
        DiscordRoute(target, push)

    private fun failure(
        target: DiscordTarget = DiscordTarget.PNL,
        adapter: DiscordWebhookSenderAdapter = adapter(),
    ): OwnerAlertSendException {
        try {
            adapter.send(route(target), message)
        } catch (e: OwnerAlertSendException) {
            return e
        }
        throw AssertionError("expected OwnerAlertSendException")
    }

    private fun embed(color: Int = 0x2ECC71) =
        """{"title":"제목 \"q\"","description":"줄1\n줄2 \\ 끝","color":$color}"""

    private fun json(text: String): JsonNode = mapper.readTree(text)

    @Test
    fun `무음 소리 here 별 payload`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }

        adapter().send(route(push = DiscordPush.SILENT), message)
        adapter().send(route(push = DiscordPush.SOUND), message)
        adapter().send(route(push = DiscordPush.HERE), message)

        val requests = List(3) { server.takeRequest() }
        requests.forEach {
            assertThat(it.method).isEqualTo("POST")
            assertThat(it.getHeader("Content-Type")).startsWith("application/json")
        }
        assertThat(json(requests[0].body.readUtf8())).isEqualTo(
            json("""{"embeds":[${embed()}],"allowed_mentions":{"parse":[]},"flags":4096}"""),
        )
        assertThat(json(requests[1].body.readUtf8())).isEqualTo(
            json("""{"embeds":[${embed()}],"allowed_mentions":{"parse":[]}}"""),
        )
        assertThat(json(requests[2].body.readUtf8())).isEqualTo(
            json("""{"content":"@here","embeds":[${embed()}],"allowed_mentions":{"parse":["everyone"]}}"""),
        )
    }

    private fun pathsFor(webhooks: DiscordWebhooks): Map<DiscordTarget, String> =
        DiscordTarget.entries.associateWith {
            server.enqueue(MockResponse().setResponseCode(204))
            adapter(webhooks).send(route(it), message)
            server.takeRequest().path!!
        }

    @Test
    fun `대상별 webhook 으로 보낸다`() {
        assertThat(pathsFor(DiscordWebhooks(pnlUri, marketUri, opsUri))).isEqualTo(
            mapOf(
                DiscordTarget.PNL to "/api/webhooks/1/TOKENpnlQ7",
                DiscordTarget.MARKET to "/api/webhooks/1/TOKENmktQ7",
                DiscordTarget.OPS to "/api/webhooks/1/TOKENopsQ7",
            ),
        )
    }

    @Test
    fun `하나만 설정하면 모든 대상이 그리로 간다`() {
        assertThat(pathsFor(DiscordWebhooks(null, null, opsUri)).values).containsOnly("/api/webhooks/1/TOKENopsQ7")
    }

    @Test
    fun `PNL OPS 만 설정하면 MARKET 은 PNL 로`() {
        assertThat(pathsFor(DiscordWebhooks(pnlUri, null, opsUri))[DiscordTarget.MARKET])
            .isEqualTo("/api/webhooks/1/TOKENpnlQ7")
    }

    @Test
    fun `MARKET OPS 만 설정하면 PNL 은 MARKET 으로`() {
        assertThat(pathsFor(DiscordWebhooks(null, marketUri, opsUri))[DiscordTarget.PNL])
            .isEqualTo("/api/webhooks/1/TOKENmktQ7")
    }

    @Test
    fun `204 와 200 은 성공`() {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        adapter().send(route(), message)
        adapter().send(route(), message)

        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `429 Retry-After 0_5 면 500ms 기다려 한 번 재시도한다`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0.5"))
        server.enqueue(MockResponse().setResponseCode(204))

        adapter().send(route(), message)

        assertThat(sleeps).containsExactly(Duration.ofMillis(500))
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `429 본문 retry_after 1_2 면 1200ms`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"message":"x","retry_after":1.2,"global":false}"""))
        server.enqueue(MockResponse().setResponseCode(204))

        adapter().send(route(), message)

        assertThat(sleeps).containsExactly(Duration.ofMillis(1200))
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `429 Retry-After 30 은 기다리지 않고 재시도 가능 실패`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))

        val e = failure()

        assertThat(e.permanent).isFalse()
        assertThat(e.message).isEqualTo("Discord webhook PNL rate limited")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(sleeps).isEmpty()
    }

    @Test
    fun `429 대기 시간이 없거나 파싱 불가면 재시도 가능 실패`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "abc"))

        assertThat(failure().message).isEqualTo("Discord webhook PNL rate limited")
        assertThat(failure().message).isEqualTo("Discord webhook PNL rate limited")
        assertThat(sleeps).isEmpty()
    }

    @Test
    fun `429 두 번이면 재시도 가능 실패`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))

        val e = failure()

        assertThat(e.permanent).isFalse()
        assertThat(e.message).isEqualTo("Discord webhook PNL rate limited")
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(sleeps).hasSize(1)
    }

    @Test
    fun `500 503 은 재시도 가능`() {
        listOf(500, 503).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status))
            val e = failure(DiscordTarget.OPS)
            assertThat(e.permanent).isFalse()
            assertThat(e.message).isEqualTo("Discord webhook OPS responded $status")
        }
    }

    @Test
    fun `400 401 403 404 는 재시도 불가`() {
        listOf(400, 401, 403, 404).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"message":"nope"}"""))
            val e = failure(DiscordTarget.MARKET)
            assertThat(e.permanent).isTrue()
            assertThat(e.message).isEqualTo("Discord webhook MARKET responded $status")
        }
    }

    @Test
    fun `연결 거부는 재시도 가능`() {
        val adapter = adapter()
        server.shutdown()

        val e = failure(adapter = adapter)

        assertThat(e.permanent).isFalse()
        assertThat(e.message).startsWith("Discord webhook PNL request failed: ")
    }

    @Test
    fun `timeout 은 재시도 가능`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok").setBodyDelay(1, TimeUnit.SECONDS))

        val e = failure(adapter = adapter(timeout = Duration.ofMillis(200)))

        assertThat(e.permanent).isFalse()
        assertThat(e.message).startsWith("Discord webhook PNL request failed: ")
    }

    @Test
    fun `대기 중 interrupt 면 플래그를 복구하고 재시도 가능 실패`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        val adapter = DiscordWebhookSenderAdapter(DiscordWebhooks(pnlUri, null, null), sleeper = { throw InterruptedException() })

        val e = failure(adapter = adapter)
        val flag = Thread.interrupted()

        assertThat(e.permanent).isFalse()
        assertThat(e.message).isEqualTo("Discord webhook PNL interrupted")
        assertThat(e.cause).isNull()
        assertThat(flag).isTrue()
    }

    @Test
    fun `요청 중 interrupt 면 플래그를 복구하고 원본 예외를 노출하지 않는다`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok").setBodyDelay(1, TimeUnit.SECONDS))
        val adapter = adapter()
        try {
            Thread.currentThread().interrupt()

            val e = failure(adapter = adapter)
            val flag = Thread.currentThread().isInterrupted

            assertThat(e.permanent).isFalse()
            assertThat(e.message).isEqualTo("Discord webhook PNL interrupted")
            assertThat(e.cause).isNull()
            assertThat(flag).isTrue()
            assertNoSecret(e)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `취소 판정은 원인 체인을 본다`() {
        assertThat(DiscordWebhookSenderAdapter.isCancellation(RuntimeException(InterruptedException()))).isTrue()
        assertThat(
            DiscordWebhookSenderAdapter.isCancellation(IllegalStateException(RuntimeException(CancellationException()))),
        ).isTrue()
        assertThat(DiscordWebhookSenderAdapter.isCancellation(IOException())).isFalse()
    }

    @Test
    fun `취소 판정은 순환 원인 체인에서도 끝난다`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        assertThat(DiscordWebhookSenderAdapter.isCancellation(a)).isFalse()
    }

    private fun assertNoSecret(e: Throwable) {
        generateSequence(e) { it.cause }.take(16).forEach { assertThat(it.message.orEmpty()).doesNotContain(*SECRETS()) }
        assertThat(e.stackTraceToString()).doesNotContain(*SECRETS())
        assertThat(e.cause).isNull()
    }

    private fun SECRETS() = arrayOf("TOKEN", "/api/webhooks/", server.url("/").toString())

    @Test
    fun `어떤 실패에도 URL 과 token 이 없다`(output: CapturedOutput) {
        val failures = mutableListOf<OwnerAlertSendException>()
        listOf(400, 404, 500, 503).forEach {
            server.enqueue(MockResponse().setResponseCode(it).setBody("""{"url":"${pnlUri}"}"""))
            failures += failure()
        }
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        failures += failure()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        failures += failure()
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok").setBodyDelay(1, TimeUnit.SECONDS))
        failures += failure(adapter = adapter(timeout = Duration.ofMillis(200)))
        val refused = adapter()
        server.shutdown()
        failures += failure(adapter = refused)

        failures.forEach(::assertNoSecret)
        assertThat(output.all).doesNotContain(*SECRETS())
    }

    @Test
    fun `DiscordWebhooks toString 에 URL 이 없다`() {
        assertThat(DiscordWebhooks(pnlUri, null, null).toString())
            .isEqualTo("DiscordWebhooks(pnl=set, market=fallback(PNL), ops=fallback(PNL))")
        assertThatThrownBy { DiscordWebhooks(null, null, null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("at least one Discord webhook is required.")
    }
}
