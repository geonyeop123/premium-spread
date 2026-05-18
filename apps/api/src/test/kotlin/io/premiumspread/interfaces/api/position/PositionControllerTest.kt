package io.premiumspread.interfaces.api.position

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.premiumspread.application.position.PositionCriteria
import io.premiumspread.application.position.PositionFacade
import io.premiumspread.application.position.PositionNotFoundException
import io.premiumspread.application.position.PositionResult
import io.premiumspread.application.position.PremiumNotFoundException
import io.premiumspread.application.position.PremiumSnapshotNotAvailableException
import io.premiumspread.application.position.StalePremiumSnapshotException
import io.premiumspread.domain.position.InvalidPositionException
import io.premiumspread.domain.position.PositionStatus
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.infrastructure.security.CustomUserDetails
import io.premiumspread.infrastructure.security.CustomUserDetailsService
import io.premiumspread.infrastructure.security.JwtTokenProvider
import io.premiumspread.infrastructure.security.JwtValidationResult
import io.premiumspread.infrastructure.security.SecurityConfig
import io.premiumspread.interfaces.api.config.WebMvcConfig
import org.junit.jupiter.api.BeforeEach
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

    @MockkBean(relaxed = true)
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @MockkBean(relaxed = true)
    private lateinit var userDetailsService: CustomUserDetailsService

    private val testUserDetails = CustomUserDetails(
        memberId = 1L,
        email = "test@test.com",
        nickname = "test",
        encodedPassword = "pw",
    )

    @BeforeEach
    fun setUp() {
        every { jwtTokenProvider.validateAndGetClaims(any()) } returns JwtValidationResult.Invalid
    }

    @Nested
    inner class OpenAutoPosition {

        @Test
        fun `AUTO 포지션을 생성한다`() {
            every { positionFacade.openAutoPosition(any<PositionCriteria.OpenAuto>()) } returns positionDetail()

            mockMvc.post("/api/v1/positions/auto") {
                with(user(testUserDetails))
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(openAutoRequest())
            }.andExpect {
                status { isCreated() }
                jsonPath("$.id") { value(1) }
                jsonPath("$.symbol") { value("BTC") }
                jsonPath("$.koreaExchange") { value("UPBIT") }
                jsonPath("$.koreaQuantity") { value(0.5) }
                jsonPath("$.foreignExchange") { value("BINANCE") }
                jsonPath("$.foreignLeverage") { value(1) }
                jsonPath("$.status") { value("OPEN") }
            }
        }

        @Test
        fun `AUTO 포지션 생성 시 스냅샷이 없으면 409를 반환한다`() {
            every {
                positionFacade.openAutoPosition(any<PositionCriteria.OpenAuto>())
            } throws PremiumSnapshotNotAvailableException("Premium snapshot not available for symbol: BTC")

            mockMvc.post("/api/v1/positions/auto") {
                with(user(testUserDetails))
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(openAutoRequest())
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("PREMIUM_SNAPSHOT_NOT_AVAILABLE") }
                jsonPath("$.message") { value("해당 종목의 최신 프리미엄 스냅샷이 없습니다.") }
            }
        }

        @Test
        fun `AUTO 포지션 생성 시 스냅샷이 오래되면 409를 반환한다`() {
            every {
                positionFacade.openAutoPosition(any<PositionCriteria.OpenAuto>())
            } throws StalePremiumSnapshotException("Premium snapshot is stale")

            mockMvc.post("/api/v1/positions/auto") {
                with(user(testUserDetails))
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(openAutoRequest())
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("STALE_PREMIUM_SNAPSHOT") }
                jsonPath("$.message") { value("프리미엄 스냅샷이 오래되어 사용할 수 없습니다. 잠시 후 다시 시도해주세요.") }
            }
        }

        @Test
        fun `AUTO 포지션 생성 시 도메인 유효성 오류면 400을 반환한다`() {
            every {
                positionFacade.openAutoPosition(any<PositionCriteria.OpenAuto>())
            } throws InvalidPositionException("한국 거래소가 아닙니다")

            mockMvc.post("/api/v1/positions/auto") {
                with(user(testUserDetails))
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(openAutoRequest(koreaExchange = "BINANCE"))
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_POSITION") }
            }
        }
    }

    @Nested
    inner class OpenManualPosition {

        @Test
        fun `MANUAL 포지션을 생성한다`() {
            every { positionFacade.openManualPosition(any<PositionCriteria.OpenManual>()) } returns positionDetail()

            mockMvc.post("/api/v1/positions/manual") {
                with(user(testUserDetails))
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(openManualRequest())
            }.andExpect {
                status { isCreated() }
                jsonPath("$.id") { value(1) }
                jsonPath("$.symbol") { value("BTC") }
                jsonPath("$.koreaExchange") { value("UPBIT") }
                jsonPath("$.foreignExchange") { value("BINANCE") }
                jsonPath("$.status") { value("OPEN") }
            }
        }

        @Test
        fun `MANUAL 포지션 생성 시 도메인 유효성 오류면 400을 반환한다`() {
            every {
                positionFacade.openManualPosition(any<PositionCriteria.OpenManual>())
            } throws InvalidPositionException("한국 거래소가 아닙니다")

            mockMvc.post("/api/v1/positions/manual") {
                with(user(testUserDetails))
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(openManualRequest(koreaExchange = "BINANCE"))
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_POSITION") }
            }
        }
    }

    @Test
    fun `루트 POST 포지션 생성 라우트는 제거되어 있다`() {
        mockMvc.post("/api/v1/positions") {
            with(user(testUserDetails))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(openManualRequest())
        }.andExpect {
            status { isMethodNotAllowed() }
        }
    }

    @Nested
    inner class GetById {

        @Test
        fun `ID로 포지션을 조회한다`() {
            every { positionFacade.findById(1L, 1L) } returns positionDetail()

            mockMvc.get("/api/v1/positions/1") {
                with(user(testUserDetails))
            }.andExpect {
                status { isOk() }
                jsonPath("$.id") { value(1) }
                jsonPath("$.symbol") { value("BTC") }
                jsonPath("$.koreaExchange") { value("UPBIT") }
                jsonPath("$.foreignExchange") { value("BINANCE") }
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
                positionDetail(id = 1L, symbol = "BTC"),
                positionDetail(id = 2L, symbol = "ETH"),
            )
            every { positionFacade.findAllOpenByMemberId(1L) } returns results

            mockMvc.get("/api/v1/positions") {
                with(user(testUserDetails))
            }.andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
                jsonPath("$[0].symbol") { value("BTC") }
                jsonPath("$[0].koreaExchange") { value("UPBIT") }
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
    inner class GetHistory {

        @Test
        fun `닫힌 포지션 이력을 조회한다`() {
            every { positionFacade.findAllClosedByMemberId(1L) } returns
                listOf(positionDetail(status = PositionStatus.CLOSED))

            mockMvc.get("/api/v1/positions/history") {
                with(user(testUserDetails))
            }.andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].id") { value(1) }
                jsonPath("$[0].symbol") { value("BTC") }
                jsonPath("$[0].status") { value("CLOSED") }
            }
        }

        @Test
        fun `닫힌 포지션이 없으면 빈 배열을 반환한다`() {
            every { positionFacade.findAllClosedByMemberId(1L) } returns emptyList()

            mockMvc.get("/api/v1/positions/history") {
                with(user(testUserDetails))
            }.andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }
        }
    }

    @Nested
    inner class GetSummary {

        @Test
        fun `포지션 요약을 조회한다`() {
            every { positionFacade.getSummary(1L) } returns PositionResult.Summary(
                totalPositions = 3,
                openPositions = 2,
                closedPositions = 1,
            )

            mockMvc.get("/api/v1/positions/summary") {
                with(user(testUserDetails))
            }.andExpect {
                status { isOk() }
                jsonPath("$.totalPositions") { value(3) }
                jsonPath("$.openPositions") { value(2) }
                jsonPath("$.closedPositions") { value(1) }
            }
        }

        @Test
        fun `포지션이 없으면 0으로 반환한다`() {
            every { positionFacade.getSummary(1L) } returns PositionResult.Summary(
                totalPositions = 0,
                openPositions = 0,
                closedPositions = 0,
            )

            mockMvc.get("/api/v1/positions/summary") {
                with(user(testUserDetails))
            }.andExpect {
                status { isOk() }
                jsonPath("$.totalPositions") { value(0) }
                jsonPath("$.openPositions") { value(0) }
                jsonPath("$.closedPositions") { value(0) }
            }
        }
    }

    @Nested
    inner class GetPnl {

        @Test
        fun `포지션의 PnL을 조회한다`() {
            every { positionFacade.calculatePnl(1L, 1L) } returns pnlResult()

            mockMvc.get("/api/v1/positions/1/pnl") {
                with(user(testUserDetails))
            }.andExpect {
                status { isOk() }
                jsonPath("$.positionId") { value(1) }
                jsonPath("$.premiumDiff") { value(9.74) }
                jsonPath("$.entryPremiumRate") { value(-10.13) }
                jsonPath("$.currentPremiumRate") { value(-0.39) }
                jsonPath("$.koreaPnl") { value(-6777343.344) }
                jsonPath("$.foreignPnlKrw") { value(8585481.2175) }
                jsonPath("$.totalPnlKrw") { value(1808137.8735) }
                jsonPath("$.koreaCurrentValue") { value(18577182) }
                jsonPath("$.totalPnlPercent") { value(9.73) }
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
            every { positionFacade.closePosition(1L, 1L) } returns positionDetail(status = PositionStatus.CLOSED)

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

    private fun openAutoRequest(koreaExchange: String = "UPBIT"): Map<String, Any> = mapOf(
        "symbol" to "BTC",
        "koreaExchange" to koreaExchange,
        "koreaQuantity" to 0.5,
        "foreignExchange" to "BINANCE",
        "foreignQuantity" to 0.5,
        "foreignLeverage" to 1,
    )

    private fun openManualRequest(koreaExchange: String = "UPBIT"): Map<String, Any> = mapOf(
        "symbol" to "BTC",
        "koreaExchange" to koreaExchange,
        "koreaQuantity" to 0.5,
        "koreaEntryPrice" to 129555000,
        "foreignExchange" to "BINANCE",
        "foreignQuantity" to 0.5,
        "foreignEntryPrice" to 89500,
        "foreignLeverage" to 1,
        "entryFxRate" to 1432.6,
        "entryObservedAt" to "2024-01-01T00:00:00Z",
    )

    private fun positionDetail(
        id: Long = 1L,
        symbol: String = "BTC",
        status: PositionStatus = PositionStatus.OPEN,
    ): PositionResult.Detail = PositionResult.Detail(
        id = id,
        memberId = 1L,
        symbol = symbol,
        koreaExchange = Exchange.UPBIT,
        koreaQuantity = BigDecimal("0.5"),
        koreaEntryPrice = BigDecimal("129555000"),
        foreignExchange = Exchange.BINANCE,
        foreignQuantity = BigDecimal("0.5"),
        foreignEntryPrice = BigDecimal("89500"),
        foreignLeverage = 1,
        entryFxRate = BigDecimal("1432.6"),
        entryPremiumRate = BigDecimal("1.04"),
        entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
        status = status,
    )

    private fun pnlResult(): PositionResult.Pnl = PositionResult.Pnl(
        positionId = 1L,
        premiumDiff = BigDecimal("9.74"),
        entryPremiumRate = BigDecimal("-10.13"),
        currentPremiumRate = BigDecimal("-0.39"),
        koreaPnl = BigDecimal("-6777343.344"),
        foreignPnlKrw = BigDecimal("8585481.2175"),
        totalPnlKrw = BigDecimal("1808137.8735"),
        koreaCurrentValue = BigDecimal("18577182"),
        totalPnlPercent = BigDecimal("9.73"),
        isProfit = true,
        calculatedAt = Instant.parse("2024-01-01T00:00:00Z"),
    )
}
