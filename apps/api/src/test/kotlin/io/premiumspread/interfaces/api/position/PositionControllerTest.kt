package io.premiumspread.interfaces.api.position

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.premiumspread.application.position.PositionCriteria
import io.premiumspread.application.position.PositionFacade
import io.premiumspread.application.position.PositionNotFoundException
import io.premiumspread.application.position.PositionResult
import io.premiumspread.application.position.PremiumNotFoundException
import io.premiumspread.domain.position.InvalidPositionException
import io.premiumspread.domain.position.PositionStatus
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.infrastructure.security.CustomUserDetails
import io.premiumspread.infrastructure.security.SecurityConfig
import io.premiumspread.interfaces.api.config.WebMvcConfig
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant

@WebMvcTest(PositionController::class)
@Import(SecurityConfig::class, WebMvcConfig::class)
class PositionControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockkBean
    private lateinit var positionFacade: PositionFacade

    private val testUserDetails = CustomUserDetails(
        memberId = 1L,
        email = "test@test.com",
        nickname = "test",
        encodedPassword = "pw",
    )

    @Test
    fun `인증 없이 포지션 조회 시 401 반환`() {
        mockMvc.get("/api/v1/positions")
            .andExpect { status { isUnauthorized() } }
    }

    @Nested
    inner class OpenPosition {

        @Test
        fun `포지션을 생성한다`() {
            val request = PositionRequest.Open(
                symbol = "BTC",
                exchange = "UPBIT",
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("129555000"),
                entryFxRate = BigDecimal("1432.6"),
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )

            val result = PositionResult.Detail(
                id = 1L,
                memberId = 1L,
                symbol = "BTC",
                exchange = Exchange.UPBIT,
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("129555000"),
                entryFxRate = BigDecimal("1432.6"),
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
                status = PositionStatus.OPEN,
            )

            every { positionFacade.openPosition(any<PositionCriteria.Open>()) } returns result

            mockMvc.post("/api/v1/positions") {
                with(user(testUserDetails))
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
                with(user(testUserDetails))
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_ARGUMENT") }
            }
        }

        @Test
        fun `도메인 유효성 오류면 400과 INVALID_POSITION 코드를 반환한다`() {
            val request = PositionRequest.Open(
                symbol = "BTC",
                exchange = "UPBIT",
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("129555000"),
                entryFxRate = BigDecimal("1432.6"),
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )

            every {
                positionFacade.openPosition(any<PositionCriteria.Open>())
            } throws InvalidPositionException("수량은 0보다 커야 합니다")

            mockMvc.post("/api/v1/positions") {
                with(user(testUserDetails))
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_POSITION") }
                jsonPath("$.message") { value("수량은 0보다 커야 합니다") }
            }
        }
    }

    @Nested
    inner class GetById {

        @Test
        fun `ID로 포지션을 조회한다`() {
            val result = PositionResult.Detail(
                id = 1L,
                memberId = 1L,
                symbol = "BTC",
                exchange = Exchange.UPBIT,
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("129555000"),
                entryFxRate = BigDecimal("1432.6"),
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
                status = PositionStatus.OPEN,
            )

            every { positionFacade.findById(1L, 1L) } returns result

            mockMvc.get("/api/v1/positions/1") {
                with(user(testUserDetails))
            }.andExpect {
                status { isOk() }
                jsonPath("$.id") { value(1) }
                jsonPath("$.symbol") { value("BTC") }
            }
        }

        @Test
        fun `포지션이 없으면 404를 반환한다`() {
            every { positionFacade.findById(999L, 1L) } returns null

            mockMvc.get("/api/v1/positions/999") {
                with(user(testUserDetails))
            }.andExpect {
                status { isNotFound() }
            }
        }
    }

    @Nested
    inner class GetAllOpen {

        @Test
        fun `열린 포지션 목록을 조회한다`() {
            val results = listOf(
                PositionResult.Detail(
                    id = 1L,
                    memberId = 1L,
                    symbol = "BTC",
                    exchange = Exchange.UPBIT,
                    quantity = BigDecimal("0.5"),
                    entryPrice = BigDecimal("129555000"),
                    entryFxRate = BigDecimal("1432.6"),
                    entryPremiumRate = BigDecimal("1.28"),
                    entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
                    status = PositionStatus.OPEN,
                ),
                PositionResult.Detail(
                    id = 2L,
                    memberId = 1L,
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

            every { positionFacade.findAllOpenByMemberId(1L) } returns results

            mockMvc.get("/api/v1/positions") {
                with(user(testUserDetails))
            }.andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
                jsonPath("$[0].symbol") { value("BTC") }
                jsonPath("$[1].symbol") { value("ETH") }
            }
        }

        @Test
        fun `열린 포지션이 없으면 빈 배열을 반환한다`() {
            every { positionFacade.findAllOpenByMemberId(1L) } returns emptyList()

            mockMvc.get("/api/v1/positions") {
                with(user(testUserDetails))
            }.andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }
        }
    }

    @Nested
    inner class GetPnl {

        @Test
        fun `포지션의 PnL을 조회한다 - 이익`() {
            val result = PositionResult.Pnl(
                positionId = 1L,
                premiumDiff = BigDecimal("-2.00"),
                entryPremiumRate = BigDecimal("3.00"),
                currentPremiumRate = BigDecimal("1.00"),
                isProfit = true,
                calculatedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )

            every { positionFacade.calculatePnl(1L, 1L) } returns result

            mockMvc.get("/api/v1/positions/1/pnl") {
                with(user(testUserDetails))
            }.andExpect {
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
                positionFacade.calculatePnl(999L, 1L)
            } throws PositionNotFoundException("Position not found: 999")

            mockMvc.get("/api/v1/positions/999/pnl") {
                with(user(testUserDetails))
            }.andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("POSITION_NOT_FOUND") }
            }
        }

        @Test
        fun `프리미엄이 없으면 404를 반환한다`() {
            every {
                positionFacade.calculatePnl(1L, 1L)
            } throws PremiumNotFoundException("Premium not found for symbol: BTC")

            mockMvc.get("/api/v1/positions/1/pnl") {
                with(user(testUserDetails))
            }.andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("PREMIUM_NOT_FOUND") }
            }
        }
    }

    @Nested
    inner class ClosePosition {

        @Test
        fun `포지션을 청산한다`() {
            val result = PositionResult.Detail(
                id = 1L,
                memberId = 1L,
                symbol = "BTC",
                exchange = Exchange.UPBIT,
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("129555000"),
                entryFxRate = BigDecimal("1432.6"),
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
                status = PositionStatus.CLOSED,
            )

            every { positionFacade.closePosition(1L, 1L) } returns result

            mockMvc.post("/api/v1/positions/1/close") {
                with(user(testUserDetails))
            }.andExpect {
                status { isOk() }
                jsonPath("$.id") { value(1) }
                jsonPath("$.status") { value("CLOSED") }
            }
        }

        @Test
        fun `포지션이 없으면 404를 반환한다`() {
            every {
                positionFacade.closePosition(999L, 1L)
            } throws PositionNotFoundException("Position not found: 999")

            mockMvc.post("/api/v1/positions/999/close") {
                with(user(testUserDetails))
            }.andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("POSITION_NOT_FOUND") }
            }
        }
    }
}
