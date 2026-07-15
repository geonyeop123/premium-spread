package io.premiumspread.infrastructure.batch.exchange

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

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
    fun `validated pair response is converted to a snapshot`() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"result":"success","base_code":"USD","target_code":"KRW","conversion_rate":1432.60,"time_last_update_unix":1706486401}""",
            ),
        )

        val snapshot = client(maxRetries = 0).fetchConfiguredRate()

        assertThat(snapshot.baseCurrency).isEqualTo("USD")
        assertThat(snapshot.quoteCurrency).isEqualTo("KRW")
        assertThat(snapshot.rate).isEqualByComparingTo("1432.60")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `retryable 5xx is retried only up to the configured bound`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }

        assertThatThrownBy { client(maxRetries = 2).fetchConfiguredRate() }
            .isInstanceOf(RetryableExchangeRateException::class.java)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `non retryable 4xx fails without retry`() {
        server.enqueue(MockResponse().setResponseCode(400))

        assertThatThrownBy { client(maxRetries = 3).fetchConfiguredRate() }
            .isInstanceOf(ExchangeRateHttpException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `read timeout is retried only to the configured bound`() {
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{}")
                    .setBodyDelay(2, TimeUnit.SECONDS),
            )
        }

        assertThatThrownBy { client(maxRetries = 1, readTimeout = Duration.ofMillis(200)).fetchConfiguredRate() }
            .isInstanceOf(Exception::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    private fun client(
        maxRetries: Int,
        readTimeout: Duration = Duration.ofSeconds(3),
    ): ExchangeRateClient {
        val properties = ExchangeRateClientProperties(
            endpoint = server.url("/").toUri(),
            apiKey = "test-key",
            connectTimeout = Duration.ofMillis(100),
            readTimeout = readTimeout,
            maxRetries = maxRetries,
            initialBackoff = Duration.ofMillis(10),
            maxBackoff = Duration.ofMillis(20),
        )
        return ExchangeRateClient(
            ExchangeWebClientConfig().exchangeRateWebClient(properties),
            SimpleMeterRegistry(),
            properties,
        )
    }
}
