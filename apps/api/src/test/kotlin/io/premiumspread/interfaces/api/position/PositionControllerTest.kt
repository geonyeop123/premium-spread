package io.premiumspread.interfaces.api.position

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.premiumspread.application.position.PositionFacade
import io.premiumspread.application.position.PositionNotFoundException
import io.premiumspread.application.position.PositionOpenCriteria
import io.premiumspread.application.position.PositionPnlResult
import io.premiumspread.application.position.PositionResult
import io.premiumspread.application.position.PremiumNotFoundException
import io.premiumspread.domain.position.PositionStatus
import io.premiumspread.domain.ticker.Exchange
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant

@WebMvcTest(PositionController::class)
class PositionControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockkBean
    private lateinit var positionFacade: PositionFacade

    @Nested
    inner class OpenPosition {

        @Test
        fun `포지션을 생성한다`() {
            val request = PositionOpenRequest(
                symbol = "BTC",
                exchange = "UPBIT",
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("129555000"),
                entryFxRate = BigDecimal("1432.6"),
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )

            val result = PositionResult(
                id = 1L,
                symbol = "BTC",
                exchange = Exchange.UPBIT,
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("129555000"),
                entryFxRate = BigDecimal("1432.6"),
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
                status = PositionStatus.OPEN,
            )

            every { positionFacade.openPosition(any<PositionOpenCriteria>()) } returns result

            mockMvc.post("/api/v1/positions") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }.andExpect {
                status { isCreated() }
                jsonPath("$.id") { value(1) }
                jsonPath("$.symbol") { value("BTC") }
                jsonPath("$.exchange") { value("UPBIT") }
                jsonPath("$.quantity") { value(0.5) }
                jsonPath("$.status") { value("OPEN") }
            }
        }

        @Test
        fun `잘못된 거래소로 요청하면 400을 반환한다`() {
            val request = mapOf(
                "symbol" to "BTC",
                "exchange" to "INVALID_EXCHANGE",
                "quantity" to 0.5,
                "entryPrice" to 129555000,
                "entryFxRate" to 1432.6,
                "entryPremiumRate" to 1.28,
                "entryObservedAt" to "2024-01-01T00:00:00Z",
            )

            mockMvc.post("/api/v1/positions") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_ARGUMENT") }
            }
        }
    }

    @Nested
    inner class GetById {

        @Test
        fun `ID로 포지션을 조회한다`() {
            val result = PositionResult(
                id = 1L,
                symbol = "BTC",
                exchange = Exchange.UPBIT,
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("129555000"),
                entryFxRate = BigDecimal("1432.6"),
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
                status = PositionStatus.OPEN,
            )

            every { positionFacade.findById(1L) } returns result

            mockMvc.get("/api/v1/positions/1")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(1) }
                    jsonPath("$.symbol") { value("BTC") }
                }
        }

        @Test
        fun `포지션이 없으면 404를 반환한다`() {
            every { positionFacade.findById(999L) } returns null

            mockMvc.get("/api/v1/positions/999")
                .andExpect {
                    status { isNotFound() }
                }
        }
    }

    @Nested
    inner class GetAllOpen {

        @Test
        fun `열린 포지션 목록을 조회한다`() {
            val results = listOf(
                PositionResult(
                    id = 1L,
                    symbol = "BTC",
                    exchange = Exchange.UPBIT,
                    quantity = BigDecimal("0.5"),
                    entryPrice = BigDecimal("129555000"),
                    entryFxRate = BigDecimal("1432.6"),
                    entryPremiumRate = BigDecimal("1.28"),
                    entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
                    status = PositionStatus.OPEN,
                ),
                PositionResult(
                    id = 2L,
                    symbol = "ETH",
                    exchange = Exchange.UPBIT,
                    quantity = BigDecimal("5"),
                    entryPrice = BigDecimal("5000000"),
                    entryFxRate = BigDecimal("1432.6"),
                    entryPremiumRate = BigDecimal("2.00"),
                    entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
                    status = PositionStatus.OPEN,
                ),
            )

            every { positionFacade.findAllOpen() } returns results

            mockMvc.get("/api/v1/positions")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.length()") { value(2) }
                    jsonPath("$[0].symbol") { value("BTC") }
                    jsonPath("$[1].symbol") { value("ETH") }
                }
        }

        @Test
        fun `열린 포지션이 없으면 빈 배열을 반환한다`() {
            every { positionFacade.findAllOpen() } returns emptyList()

            mockMvc.get("/api/v1/positions")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.length()") { value(0) }
                }
        }
    }

    @Nested
    inner class GetPnl {

        @Test
        fun `포지션의 PnL을 조회한다 - 이익`() {
            val result = PositionPnlResult(
                positionId = 1L,
                premiumDiff = BigDecimal("-2.00"),
                entryPremiumRate = BigDecimal("3.00"),
                currentPremiumRate = BigDecimal("1.00"),
                isProfit = true,
                calculatedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )

            every { positionFacade.calculatePnl(1L) } returns result

            mockMvc.get("/api/v1/positions/1/pnl")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.positionId") { value(1) }
                    jsonPath("$.premiumDiff") { value(-2.00) }
                    jsonPath("$.entryPremiumRate") { value(3.00) }
                    jsonPath("$.currentPremiumRate") { value(1.00) }
                    jsonPath("$.isProfit") { value(true) }
                }
        }

        @Test
        fun `포지션이 없으면 404를 반환한다`() {
            every {
                positionFacade.calculatePnl(999L)
            } throws PositionNotFoundException("Position not found: 999")

            mockMvc.get("/api/v1/positions/999/pnl")
                .andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("POSITION_NOT_FOUND") }
                }
        }

        @Test
        fun `프리미엄이 없으면 404를 반환한다`() {
            every {
                positionFacade.calculatePnl(1L)
            } throws PremiumNotFoundException("Premium not found for symbol: BTC")

            mockMvc.get("/api/v1/positions/1/pnl")
                .andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("PREMIUM_NOT_FOUND") }
                }
        }
    }

    @Nested
    inner class ClosePosition {

        @Test
        fun `포지션을 청산한다`() {
            val result = PositionResult(
                id = 1L,
                symbol = "BTC",
                exchange = Exchange.UPBIT,
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("129555000"),
                entryFxRate = BigDecimal("1432.6"),
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
                status = PositionStatus.CLOSED,
            )

            every { positionFacade.closePosition(1L) } returns result

            mockMvc.post("/api/v1/positions/1/close")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(1) }
                    jsonPath("$.status") { value("CLOSED") }
                }
        }

        @Test
        fun `포지션이 없으면 404를 반환한다`() {
            every {
                positionFacade.closePosition(999L)
            } throws PositionNotFoundException("Position not found: 999")

            mockMvc.post("/api/v1/positions/999/close")
                .andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("POSITION_NOT_FOUND") }
                }
        }
    }
}
