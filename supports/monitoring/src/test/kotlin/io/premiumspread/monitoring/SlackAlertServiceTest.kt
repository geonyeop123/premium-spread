package io.premiumspread.monitoring

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("SlackAlertService")
class SlackAlertServiceTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var sut: SlackAlertService
    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        sut = SlackAlertService(mockServer.url("/webhook").toString(), objectMapper)
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `sendAlert 호출 시 Slack Webhook으로 JSON 페이로드를 전송한다`() {
        // given
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        // when
        sut.sendAlert("서버 경고", AlertService.Severity.WARNING)

        // then
        val request = mockServer.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.getHeader("Content-Type")).contains("application/json")

        val body = objectMapper.readTree(request.body.readUtf8())
        assertThat(body.get("text").asText()).contains("WARNING")
        assertThat(body.get("text").asText()).contains("서버 경고")
    }

    @Test
    fun `CRITICAL severity는 rotating_light 이모지를 포함한다`() {
        // given
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        // when
        sut.sendCriticalAlert("DB 장애 발생")

        // then
        val request = mockServer.takeRequest()
        val body = objectMapper.readTree(request.body.readUtf8())
        assertThat(body.get("text").asText()).contains(":rotating_light:")
        assertThat(body.get("text").asText()).contains("CRITICAL")
    }

    @Test
    fun `Webhook 호출 실패는 상위 bounded adapter가 계측할 수 있게 전달한다`() {
        // given
        mockServer.enqueue(MockResponse().setResponseCode(500).setBody("error"))

        // when & then
        assertThatThrownBy {
            sut.sendAlert("실패 테스트", AlertService.Severity.WARNING)
        }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `연결 실패도 상위 bounded adapter가 계측할 수 있게 전달한다`() {
        // given
        val badService = SlackAlertService("http://localhost:1/invalid", objectMapper)

        // when & then
        assertThatThrownBy {
            badService.sendAlert("실패 테스트", AlertService.Severity.CRITICAL)
        }.isInstanceOf(Exception::class.java)
    }
}
