package io.premiumspread.interfaces.api.premium

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.application.premium.PremiumCriteria
import io.premiumspread.application.premium.PremiumFacade
import io.premiumspread.application.premium.PremiumResult
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant

@WebMvcTest(PremiumController::class)
@AutoConfigureMockMvc(addFilters = false)
class PremiumControllerTest {
    @Autowired lateinit var mockMvc: MockMvc
    @MockkBean lateinit var facade: PremiumFacade
    private val from = Instant.parse("2024-01-01T00:00:00Z")
    private val to = Instant.parse("2024-01-01T01:00:00Z")

    @Test
    fun `calculate는 기존 200 계약을 유지한다`() {
        every { facade.calculateAndSave(PremiumCriteria.Create("BTC")) } returns detail()
        mockMvc.post("/api/v1/premiums/calculate/BTC").andExpect {
            status { isOk() }
            jsonPath("$.symbol") { value("BTC") }
        }
    }

    @Test
    fun `current 미발견은 안정된 404 envelope를 반환한다`() {
        every { facade.findCurrent(PremiumCriteria.FindCurrent("BTC")) } throws
            ApplicationException(ApplicationError.PREMIUM_NOT_FOUND)
        mockMvc.get("/api/v1/premiums/current/BTC").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("PREMIUM_NOT_FOUND") }
        }
    }

    @Test
    fun `aggregation은 Facade의 data와 hasMore Result만 Response로 옮긴다`() {
        every { facade.findAggregation(PremiumCriteria.FindAggregation("BTC", "1m", from, to)) } returns
            PremiumResult.AggregationPage(emptyList(), true)
        mockMvc.get("/api/v1/premiums/aggregation/BTC") {
            param("interval", "1m"); param("from", from.toString()); param("to", to.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.data") { isArray() }
            jsonPath("$.hasMore") { value(true) }
        }
    }

    @Test
    fun `semantic 범위 오류는 422다`() {
        every { facade.findByPeriod(any()) } throws ApplicationException(ApplicationError.INVALID_PREMIUM_INPUT)
        mockMvc.get("/api/v1/premiums/history/BTC") {
            param("from", to.toString()); param("to", from.toString())
        }.andExpect { status { isUnprocessableEntity() } }
    }

    private fun detail() = PremiumResult.Detail(
        1L, "BTC", 1L, 2L, 3L, BigDecimal("1.3"), from,
    )
}
