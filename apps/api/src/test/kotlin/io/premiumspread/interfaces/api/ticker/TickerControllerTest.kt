package io.premiumspread.interfaces.api.ticker

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.application.ticker.TickerCriteria
import io.premiumspread.application.ticker.TickerFacade
import io.premiumspread.application.ticker.TickerResult
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant

@WebMvcTest(TickerController::class)
@AutoConfigureMockMvc(addFilters = false)
class TickerControllerTest {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockkBean lateinit var facade: TickerFacade

    private val request = TickerRequest.Ingest(
        "UPBIT",
        "BTC",
        "KRW",
        BigDecimal("100"),
        Instant.parse("2024-01-01T00:00:00Z"),
    )

    @Test
    fun `ingest는 Criteria로 변환하고 201 Detail을 반환한다`() {
        every { facade.ingest(TickerCriteria.Ingest("UPBIT", "BTC", "KRW", BigDecimal("100"), request.observedAt)) } returns
            TickerResult.Detail(1L, "UPBIT", "KOREA", "BTC", "KRW", BigDecimal("100"), request.observedAt)

        mockMvc.post("/api/v1/tickers") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.exchange") { value("UPBIT") }
        }
    }

    @Test
    fun `Bean Validation 오류는 transport 400이다`() {
        mockMvc.post("/api/v1/tickers") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request.copy(price = BigDecimal.ZERO))
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `Facade semantic 오류는 422다`() {
        every { facade.ingest(any()) } throws ApplicationException(ApplicationError.INVALID_TICKER)
        mockMvc.post("/api/v1/tickers") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("INVALID_TICKER") }
        }
    }
}
