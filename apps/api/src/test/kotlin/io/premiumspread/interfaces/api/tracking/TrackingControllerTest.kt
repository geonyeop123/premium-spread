package io.premiumspread.interfaces.api.tracking

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.application.tracking.TrackingCriteria
import io.premiumspread.application.tracking.TrackingFacade
import io.premiumspread.application.tracking.TrackingResult
import io.premiumspread.interfaces.api.config.WebMvcConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.security.Principal
import java.time.Instant

@WebMvcTest(TrackingController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(WebMvcConfig::class)
class TrackingControllerTest {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockkBean lateinit var facade: TrackingFacade
    private val principal = Principal { "1" }

    @Test
    fun `AUTO 생성은 Request를 primitive Criteria로 변환하고 201을 반환한다`() {
        val request = TrackingRequest.RecordFromMarket("BTC", "UPBIT", BigDecimal.ONE, "BINANCE", BigDecimal.ONE, 1)
        every {
            facade.recordFromMarket(TrackingCriteria.RecordFromMarket(1L, "BTC", "UPBIT", BigDecimal.ONE, "BINANCE", BigDecimal.ONE, 1))
        } returns detail()

        mockMvc.post("/api/v1/trackings/from-market") {
            principal = this@TrackingControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.koreaExchange") { value("UPBIT") }
        }
    }

    @Test
    fun `DTO validation 오류는 transport 400이다`() {
        val request = TrackingRequest.RecordFromMarket("", "UPBIT", BigDecimal.ZERO, "BINANCE", BigDecimal.ONE, 1)
        mockMvc.post("/api/v1/trackings/from-market") {
            principal = this@TrackingControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `도메인 semantic 오류는 422다`() {
        every { facade.recordFromMarket(any()) } throws ApplicationException(ApplicationError.INVALID_TRACKING)
        val request = TrackingRequest.RecordFromMarket("BTC", "BINANCE", BigDecimal.ONE, "BINANCE", BigDecimal.ONE, 1)
        mockMvc.post("/api/v1/trackings/from-market") {
            principal = this@TrackingControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("INVALID_TRACKING") }
        }
    }

    @Test
    fun `단건 미발견은 404 envelope다`() {
        every { facade.findById(TrackingCriteria.FindById(99L, 1L)) } throws
            ApplicationException(ApplicationError.TRACKING_NOT_FOUND)
        mockMvc.get("/api/v1/trackings/99") { principal = this@TrackingControllerTest.principal }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("TRACKING_NOT_FOUND") }
        }
    }

    @Test
    fun `목록 Details Result를 기존 배열 Response로 변환한다`() {
        every { facade.findAllActiveByMemberId(TrackingCriteria.FindAllActive(1L)) } returns
            TrackingResult.Details(listOf(detail()))
        mockMvc.get("/api/v1/trackings") { principal = this@TrackingControllerTest.principal }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
        }
    }

    private fun detail(status: String = "ACTIVE") = TrackingResult.Detail(
        id = 1L,
        memberId = 1L,
        symbol = "BTC",
        koreaExchange = "UPBIT",
        koreaQuantity = BigDecimal.ONE,
        koreaEntryPrice = BigDecimal("100"),
        foreignExchange = "BINANCE",
        foreignQuantity = BigDecimal.ONE,
        foreignEntryPrice = BigDecimal("90"),
        foreignLeverage = 1,
        entryFxRate = BigDecimal("1400"),
        entryPremiumRate = BigDecimal.ONE,
        entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
        status = status,
        closedAt = null,
        closePriceSource = null,
        hasConfirmedClose = false,
    )
}
