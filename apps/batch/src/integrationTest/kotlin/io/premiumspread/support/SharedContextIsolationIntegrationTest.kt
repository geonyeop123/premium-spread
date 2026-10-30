package io.premiumspread.support

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 공용 context 의 MockWebServer 가 테스트 사이에 초기화되는지 검증한다.
 * 공용 서버의 requestCount 는 누적값이라 단언하지 않는다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SharedContextIsolationIntegrationTest : BatchIntegrationTestBase() {

    @Autowired
    lateinit var exchangeRateMockServer: MockWebServer

    @Test
    @Order(1)
    fun `앞 테스트가 공용 서버에 응답과 요청 기록을 남긴다`() {
        exchangeRateMockServer.enqueue(MockResponse().setBody("leftover-1"))
        exchangeRateMockServer.enqueue(MockResponse().setBody("leftover-2"))

        assertThat(get(exchangeRateMockServer.url("/first").toString())).isEqualTo("leftover-1")
    }

    @Test
    @Order(2)
    fun `다음 테스트는 앞 테스트의 응답과 요청 기록을 보지 않는다`() {
        exchangeRateMockServer.enqueue(MockResponse().setBody("fresh"))

        assertThat(get(exchangeRateMockServer.url("/second").toString())).isEqualTo("fresh")
        assertThat(exchangeRateMockServer.takeRequest(0, TimeUnit.MILLISECONDS)!!.path).isEqualTo("/second")
        assertThat(exchangeRateMockServer.takeRequest(0, TimeUnit.MILLISECONDS)).isNull()
    }

    private fun get(url: String): String {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("Connection", "close")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
