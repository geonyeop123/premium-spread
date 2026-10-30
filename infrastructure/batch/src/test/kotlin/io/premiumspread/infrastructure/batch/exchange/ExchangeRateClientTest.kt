package io.premiumspread.infrastructure.batch.exchange

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.premiumspread.domain.ticker.Exchange
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
import org.springframework.web.reactive.function.client.WebClientRequestException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@ExtendWith(OutputCaptureExtension::class)
class ExchangeRateClientTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `요청은 exchange_rate 경로와 symbol 쿼리, 헤더 인증을 쓴다`() {
        server.enqueue(success())

        client(maxRetries = 0).fetchConfiguredRate()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.requestUrl!!.encodedPath).isEqualTo("/exchange_rate")
        assertThat(request.requestUrl!!.queryParameter("symbol")).isEqualTo("USD/KRW")
        assertThat(request.getHeader("Authorization")).isEqualTo("apikey test-key")
        assertThat(request.requestUrl.toString()).doesNotContain("test-key")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `정상 응답은 snapshot 으로 변환된다`() {
        server.enqueue(success())

        val snapshot = client(maxRetries = 0).fetchConfiguredRate()

        assertThat(snapshot.baseCurrency).isEqualTo("USD")
        assertThat(snapshot.quoteCurrency).isEqualTo("KRW")
        assertThat(snapshot.rate).isEqualByComparingTo("1234.5678")
        assertThat(snapshot.observedAt).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"))
        assertThat(snapshot.source).isEqualTo(Exchange.FX_PROVIDER)
    }

    @Test
    fun `HTTP 200 이어도 status error 면 재시도 없이 거절한다`() {
        assertRejected(
            """{"code":429,"message":"x","status":"error"}""",
            "Exchange rate API error: code=429",
        )
    }

    @Test
    fun `다른 symbol 은 거절한다`() {
        assertRejected(
            """{"symbol":"EUR/USD","rate":1234.5678,"timestamp":1767225600}""",
            "Unexpected exchange rate symbol (expected USD/KRW)",
        )
    }

    @Test
    fun `symbol 누락은 거절한다`() {
        assertRejected(
            """{"rate":1234.5678,"timestamp":1767225600}""",
            "Unexpected exchange rate symbol (expected USD/KRW)",
        )
    }

    @Test
    fun `rate 누락은 거절한다`() {
        assertRejected("""{"symbol":"USD/KRW","timestamp":1767225600}""", "No rate in response")
    }

    @Test
    fun `rate 0 은 거절한다`() {
        assertRejected(
            """{"symbol":"USD/KRW","rate":0,"timestamp":1767225600}""",
            "Non-positive rate in response",
        )
    }

    @Test
    fun `rate 음수는 거절한다`() {
        assertRejected(
            """{"symbol":"USD/KRW","rate":-1,"timestamp":1767225600}""",
            "Non-positive rate in response",
        )
    }

    @Test
    fun `timestamp 누락은 거절한다`() {
        assertRejected(
            """{"symbol":"USD/KRW","rate":1234.5678}""",
            "No observation timestamp in response",
        )
    }

    @Test
    fun `timestamp 0 은 거절한다`() {
        assertRejected(
            """{"symbol":"USD/KRW","rate":1234.5678,"timestamp":0}""",
            "No observation timestamp in response",
        )
    }

    @Test
    fun `기본 deadline 은 lock fx execution timeout 30초보다 짧다`() {
        val defaults = ExchangeRateClientProperties(apiKey = "test-key")

        assertThat(defaults.maxRetries).isEqualTo(1)
        assertThat(defaults.retryableStatuses).isEqualTo((500..599).toSet())
        assertThat(defaults.overallDeadline()).isEqualTo(Duration.ofSeconds(17))
        assertThat(defaults.endpoint.host).isEqualTo("api.twelvedata.com")
    }

    @Test
    fun `maxRetries=1 이면 503 은 2회 요청 후 RetryableExchangeRateException 이다`() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(503)) }

        assertThatThrownBy { client().fetchConfiguredRate() }
            .isInstanceOf(RetryableExchangeRateException::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `429 는 재시도하지 않는다`() {
        server.enqueue(MockResponse().setResponseCode(429))

        assertThatThrownBy { client(maxRetries = 3).fetchConfiguredRate() }
            .isInstanceOf(ExchangeRateHttpException::class.java)
            .isNotInstanceOf(RetryableExchangeRateException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `401 은 재시도하지 않는다`() {
        server.enqueue(MockResponse().setResponseCode(401))

        assertThatThrownBy { client(maxRetries = 3).fetchConfiguredRate() }
            .isInstanceOf(ExchangeRateHttpException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `read timeout 은 설정한 횟수까지만 재시도한다`() {
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{}")
                    .setBodyDelay(1, TimeUnit.SECONDS),
            )
        }

        assertThatThrownBy { client(maxRetries = 1, readTimeout = Duration.ofMillis(200)).fetchConfiguredRate() }
            // Reactor timeout() 과 Netty ReadTimeout 이 경합한다. 운영 isTimeoutType 과 같이 둘 다 timeout 으로 본다.
            .isInstanceOfAny(TimeoutException::class.java, io.netty.handler.timeout.TimeoutException::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `응답 헤더 지연으로 Netty responseTimeout 이 이기면 WebClientRequestException 으로 재시도한다`() {
        repeat(2) {
            server.enqueue(success().setHeadersDelay(1, TimeUnit.SECONDS))
        }

        assertThatThrownBy { client(maxRetries = 1, readTimeout = Duration.ofMillis(200)).fetchConfiguredRate() }
            .isInstanceOf(WebClientRequestException::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `응답 DTO toString 은 제공자 message 를 노출하지 않는다`() {
        val response = ExchangeRateResponse(status = "error", code = 401, message = "bad key $SENTINEL")

        assertThat(response.toString()).doesNotContain(SENTINEL).contains("code=401")
    }

    @Test
    fun `401 오류 본문의 key 는 예외 메시지와 URL 에 나타나지 않는다`(output: CapturedOutput) {
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json").setBody(
                """{"code":401,"message":"bad key $SENTINEL","status":"error"}""",
            ),
        )
        val properties = properties(maxRetries = 3)

        assertThatThrownBy { client(properties).fetchConfiguredRate() }
            .isInstanceOf(ExchangeRateHttpException::class.java)
            .hasMessage("Exchange rate HTTP 401")
        val request = server.takeRequest()
        assertThat(request.getHeader("Authorization")).isEqualTo("apikey $SENTINEL")
        assertThat(request.requestUrl.toString()).doesNotContain(SENTINEL)
        assertThat(properties.toString()).doesNotContain(SENTINEL).contains("apiKey=****")
        assertThat(output.all).contains("Failed to fetch exchange rate").doesNotContain(SENTINEL)
    }

    @Test
    fun `재시도 경로의 로그와 예외에 key 와 응답 본문이 나타나지 않는다`(output: CapturedOutput) {
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(503).setBody("upstream echo $SENTINEL"))
        }
        val properties = properties(maxRetries = 1)

        assertThatThrownBy { client(properties).fetchConfiguredRate() }
            .isInstanceOf(RetryableExchangeRateException::class.java)
            .hasMessage("Exchange rate HTTP 503")

        assertThat(server.requestCount).isEqualTo(2)
        repeat(2) {
            val request = server.takeRequest()
            assertThat(request.getHeader("Authorization")).isEqualTo("apikey $SENTINEL")
            assertThat(request.requestUrl.toString()).doesNotContain(SENTINEL)
        }
        assertThat(properties.toString()).doesNotContain(SENTINEL).contains("apiKey=****")
        assertThat(output.all)
            .contains("Exchange rate request retry")
            .contains("Failed to fetch exchange rate")
            .doesNotContain(SENTINEL)
    }

    private fun assertRejected(body: String, message: String) {
        server.enqueue(success(body))

        assertThatThrownBy { client(maxRetries = 3).fetchConfiguredRate() }
            .isExactlyInstanceOf(ExchangeRateApiException::class.java)
            .hasMessage(message)
        assertThat(server.requestCount).isEqualTo(1)
    }

    private fun success(
        body: String = """{"symbol":"USD/KRW","rate":1234.5678,"timestamp":1767225600}""",
    ): MockResponse = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun properties(
        maxRetries: Int = 1,
        readTimeout: Duration = Duration.ofSeconds(3),
        apiKey: String = SENTINEL,
    ) = ExchangeRateClientProperties(
        endpoint = server.url("/").toUri(),
        apiKey = apiKey,
        connectTimeout = Duration.ofMillis(100),
        readTimeout = readTimeout,
        maxRetries = maxRetries,
        initialBackoff = Duration.ofMillis(10),
        maxBackoff = Duration.ofMillis(20),
    )

    private fun client(
        maxRetries: Int = 1,
        readTimeout: Duration = Duration.ofSeconds(3),
    ): ExchangeRateClient = client(properties(maxRetries, readTimeout, apiKey = "test-key"))

    private fun client(properties: ExchangeRateClientProperties): ExchangeRateClient = ExchangeRateClient(
        ExchangeWebClientConfig().exchangeRateWebClient(properties),
        SimpleMeterRegistry(),
        properties,
    )

    private companion object {
        const val SENTINEL = "fake-key-SENTINEL-93"
    }
}
