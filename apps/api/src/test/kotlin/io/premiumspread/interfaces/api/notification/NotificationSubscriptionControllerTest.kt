package io.premiumspread.interfaces.api.notification

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.justRun
import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.application.notification.NotificationSubscriptionCriteria
import io.premiumspread.application.notification.NotificationSubscriptionFacade
import io.premiumspread.application.notification.NotificationSubscriptionResult
import io.premiumspread.interfaces.api.config.WebMvcConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.security.Principal

@WebMvcTest(NotificationSubscriptionController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(WebMvcConfig::class)
class NotificationSubscriptionControllerTest {
    @Autowired lateinit var mockMvc: MockMvc
    @MockkBean lateinit var facade: NotificationSubscriptionFacade
    private val principal = Principal { "1" }

    @Test
    fun `create는 문자열 Criteria로 변환하고 201을 반환한다`() {
        every { facade.create(NotificationSubscriptionCriteria.Create(1L, "BTC", "ABOVE", BigDecimal("5"))) } returns detail()
        mockMvc.post("/api/v1/notifications/subscriptions") {
            principal = this@NotificationSubscriptionControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"BTC","direction":"ABOVE","threshold":5}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.direction") { value("ABOVE") }
            jsonPath("$.koreaExchange") { value("BITHUMB") }
            jsonPath("$.foreignExchange") { value("BINANCE") }
        }
    }

    @Test
    fun `create는 명시한 거래소 쌍을 Criteria와 응답에 보존한다`() {
        val criteria = NotificationSubscriptionCriteria.Create(
            1L,
            "BTC",
            "ABOVE",
            BigDecimal("5"),
            "UPBIT",
            "BINANCE",
        )
        every { facade.create(criteria) } returns detail("UPBIT", "BINANCE")

        mockMvc.post("/api/v1/notifications/subscriptions") {
            principal = this@NotificationSubscriptionControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"symbol":"BTC","direction":"ABOVE","threshold":5,"koreaExchange":"UPBIT","foreignExchange":"BINANCE"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.koreaExchange") { value("UPBIT") }
            jsonPath("$.foreignExchange") { value("BINANCE") }
        }
    }

    @Test
    fun `빈 symbol은 transport 400이다`() {
        mockMvc.post("/api/v1/notifications/subscriptions") {
            principal = this@NotificationSubscriptionControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"","direction":"ABOVE","threshold":5}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `잘못된 enum semantic 오류는 422다`() {
        every { facade.create(any()) } throws ApplicationException(ApplicationError.DOMAIN_ERROR)
        mockMvc.post("/api/v1/notifications/subscriptions") {
            principal = this@NotificationSubscriptionControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"BTC","direction":"WRONG","threshold":5}"""
        }.andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    fun `목록 Details는 기존 배열 Response로 변환한다`() {
        every { facade.findAll(NotificationSubscriptionCriteria.FindAll(1L)) } returns
            NotificationSubscriptionResult.Details(listOf(detail()))
        mockMvc.get("/api/v1/notifications/subscriptions") {
            principal = this@NotificationSubscriptionControllerTest.principal
        }.andExpect { jsonPath("$.length()") { value(1) } }
    }

    @Test
    fun `update는 거래소 쌍을 Criteria에 전달한다`() {
        val criteria = NotificationSubscriptionCriteria.Update(
            10L,
            1L,
            null,
            null,
            null,
            "UPBIT",
            "BINANCE",
        )
        every { facade.update(criteria) } returns detail("UPBIT", "BINANCE")

        mockMvc.patch("/api/v1/notifications/subscriptions/10") {
            principal = this@NotificationSubscriptionControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            content = """{"koreaExchange":"UPBIT","foreignExchange":"BINANCE"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.koreaExchange") { value("UPBIT") }
        }
    }

    @Test
    fun `delete는 Criteria를 전달하고 기존 204를 유지한다`() {
        justRun { facade.delete(NotificationSubscriptionCriteria.Delete(10L, 1L)) }
        mockMvc.delete("/api/v1/notifications/subscriptions/10") {
            principal = this@NotificationSubscriptionControllerTest.principal
        }.andExpect { status { isNoContent() } }
    }

    private fun detail(
        koreaExchange: String = "BITHUMB",
        foreignExchange: String = "BINANCE",
    ) = NotificationSubscriptionResult.Detail(
        10L, 1L, "BTC", "ABOVE", BigDecimal("5"), "ACTIVE", koreaExchange, foreignExchange,
    )
}
