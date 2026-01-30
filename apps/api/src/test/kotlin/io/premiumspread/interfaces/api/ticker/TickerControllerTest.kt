package io.premiumspread.interfaces.api.ticker

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.premiumspread.application.ticker.TickerIngestCriteria
import io.premiumspread.application.ticker.TickerIngestFacade
import io.premiumspread.application.ticker.TickerResult
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.ExchangeRegion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant

@WebMvcTest(TickerController::class)
class TickerControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockkBean
    private lateinit var tickerIngestFacade: TickerIngestFacade

    @Test
    fun `코인 티커를 등록한다`() {
        val request = TickerIngestRequest(
            exchange = "UPBIT",
            baseCode = "BTC",
            quoteCurrency = "KRW",
            price = BigDecimal("129555000"),
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        val result = TickerResult(
            id = 1L,
            exchange = Exchange.UPBIT,
            exchangeRegion = ExchangeRegion.KOREA,
            baseCode = "BTC",
            quoteCurrency = Currency.KRW,
            price = BigDecimal("129555000"),
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        every { tickerIngestFacade.ingest(any<TickerIngestCriteria>()) } returns result

        mockMvc.post("/api/v1/tickers") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(1) }
            jsonPath("$.exchange") { value("UPBIT") }
            jsonPath("$.exchangeRegion") { value("KOREA") }
            jsonPath("$.baseCode") { value("BTC") }
            jsonPath("$.quoteCurrency") { value("KRW") }
            jsonPath("$.price") { value(129555000) }
        }
    }

    @Test
    fun `환율 티커를 등록한다`() {
        val request = TickerIngestRequest(
            exchange = "FX_PROVIDER",
            baseCode = "USD",
            quoteCurrency = "KRW",
            price = BigDecimal("1432.6"),
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        val result = TickerResult(
            id = 2L,
            exchange = Exchange.FX_PROVIDER,
            exchangeRegion = ExchangeRegion.FOREIGN,
            baseCode = "USD",
            quoteCurrency = Currency.KRW,
            price = BigDecimal("1432.6"),
            observedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        every { tickerIngestFacade.ingest(any<TickerIngestCriteria>()) } returns result

        mockMvc.post("/api/v1/tickers") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(2) }
            jsonPath("$.exchange") { value("FX_PROVIDER") }
            jsonPath("$.baseCode") { value("USD") }
        }
    }

    @Test
    fun `잘못된 거래소로 요청하면 400을 반환한다`() {
        val request = mapOf(
            "exchange" to "INVALID_EXCHANGE",
            "baseCode" to "BTC",
            "quoteCurrency" to "KRW",
            "price" to 129555000,
            "observedAt" to "2024-01-01T00:00:00Z",
        )

        mockMvc.post("/api/v1/tickers") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_ARGUMENT") }
        }
    }
}
