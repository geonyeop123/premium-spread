package io.premiumspread.application.ticker

import io.mockk.every
import io.mockk.mockk
import io.premiumspread.TickerFixtures
import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.InvalidTickerException
import io.premiumspread.domain.ticker.TickerService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class TickerFacadeTest {
    private val service = mockk<TickerService>()
    private val facade = TickerFacade(service)
    private val criteria = TickerCriteria.Ingest(
        exchange = "UPBIT",
        baseCode = "BTC",
        quoteCurrency = "KRW",
        price = BigDecimal("100"),
        observedAt = Instant.parse("2024-01-01T00:00:00Z"),
    )

    @Test
    fun `티커 Entity를 primitive Result로 변환한다`() {
        every { service.create(any()) } returns TickerFixtures.koreaTicker(exchange = io.premiumspread.domain.ticker.Exchange.UPBIT)

        val result = facade.ingest(criteria)

        assertThat(result.exchange).isEqualTo("UPBIT")
        assertThat(result.quoteCurrency).isEqualTo("KRW")
    }

    @Test
    fun `enum 파싱과 도메인 유효성 오류를 안정된 INVALID_TICKER로 변환한다`() {
        assertError(criteria.copy(exchange = "WRONG"))
        every { service.create(any()) } throws InvalidTickerException("internal")
        assertError(criteria)
    }

    private fun assertError(input: TickerCriteria.Ingest) {
        assertThatThrownBy { facade.ingest(input) }
            .isInstanceOf(ApplicationException::class.java)
            .hasFieldOrPropertyWithValue("error", ApplicationError.INVALID_TICKER)
    }
}
