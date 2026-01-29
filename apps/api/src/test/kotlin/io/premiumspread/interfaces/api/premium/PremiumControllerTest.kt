package io.premiumspread.interfaces.api.premium

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.premiumspread.application.premium.PremiumCreateCriteria
import io.premiumspread.application.premium.PremiumFacade
import io.premiumspread.application.premium.PremiumResult
import io.premiumspread.application.premium.TickerNotFoundException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant

@WebMvcTest(PremiumController::class)
class PremiumControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var premiumFacade: PremiumFacade

    @Nested
    inner class Calculate {

        @Test
        fun `프리미엄을 계산하고 저장한다`() {
            val result = PremiumResult(
                id = 1L,
                symbol = "BTC",
                koreaTickerId = 1L,
                foreignTickerId = 2L,
                fxTickerId = 3L,
                premiumRate = BigDecimal("1.30"),
                observedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )

            every { premiumFacade.calculateAndSave(PremiumCreateCriteria("BTC")) } returns result

            mockMvc.post("/api/v1/premiums/calculate/BTC")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(1) }
                    jsonPath("$.symbol") { value("BTC") }
                    jsonPath("$.koreaTickerId") { value(1) }
                    jsonPath("$.foreignTickerId") { value(2) }
                    jsonPath("$.fxTickerId") { value(3) }
                    jsonPath("$.premiumRate") { value(1.30) }
                }
        }

        @Test
        fun `티커가 없으면 404를 반환한다`() {
            every {
                premiumFacade.calculateAndSave(PremiumCreateCriteria("BTC"))
            } throws TickerNotFoundException("Korea ticker not found for symbol: BTC")

            mockMvc.post("/api/v1/premiums/calculate/BTC")
                .andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("TICKER_NOT_FOUND") }
                    jsonPath("$.message") { value("Korea ticker not found for symbol: BTC") }
                }
        }
    }

    @Nested
    inner class GetCurrent {

        @Test
        fun `최신 프리미엄을 조회한다`() {
            val result = PremiumResult(
                id = 1L,
                symbol = "BTC",
                koreaTickerId = 1L,
                foreignTickerId = 2L,
                fxTickerId = 3L,
                premiumRate = BigDecimal("1.30"),
                observedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )

            every { premiumFacade.findLatest("BTC") } returns result

            mockMvc.get("/api/v1/premiums/current/BTC")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(1) }
                    jsonPath("$.symbol") { value("BTC") }
                    jsonPath("$.premiumRate") { value(1.30) }
                }
        }

        @Test
        fun `프리미엄이 없으면 404를 반환한다`() {
            every { premiumFacade.findLatest("BTC") } returns null

            mockMvc.get("/api/v1/premiums/current/BTC")
                .andExpect {
                    status { isNotFound() }
                }
        }
    }

    @Nested
    inner class GetHistory {

        @Test
        fun `기간별 프리미엄 목록을 조회한다`() {
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-02T00:00:00Z")

            val results = listOf(
                PremiumResult(
                    id = 1L,
                    symbol = "BTC",
                    koreaTickerId = 1L,
                    foreignTickerId = 2L,
                    fxTickerId = 3L,
                    premiumRate = BigDecimal("1.30"),
                    observedAt = Instant.parse("2024-01-01T01:00:00Z"),
                ),
                PremiumResult(
                    id = 2L,
                    symbol = "BTC",
                    koreaTickerId = 4L,
                    foreignTickerId = 5L,
                    fxTickerId = 6L,
                    premiumRate = BigDecimal("1.50"),
                    observedAt = Instant.parse("2024-01-01T02:00:00Z"),
                ),
            )

            every { premiumFacade.findByPeriod("BTC", from, to) } returns results

            mockMvc.get("/api/v1/premiums/history/BTC") {
                param("from", "2024-01-01T00:00:00Z")
                param("to", "2024-01-02T00:00:00Z")
            }.andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
                jsonPath("$[0].id") { value(1) }
                jsonPath("$[0].premiumRate") { value(1.30) }
                jsonPath("$[1].id") { value(2) }
                jsonPath("$[1].premiumRate") { value(1.50) }
            }
        }

        @Test
        fun `프리미엄이 없으면 빈 배열을 반환한다`() {
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-02T00:00:00Z")

            every { premiumFacade.findByPeriod("BTC", from, to) } returns emptyList()

            mockMvc.get("/api/v1/premiums/history/BTC") {
                param("from", "2024-01-01T00:00:00Z")
                param("to", "2024-01-02T00:00:00Z")
            }.andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }
        }
    }
}
