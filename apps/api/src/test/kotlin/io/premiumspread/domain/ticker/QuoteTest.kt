package io.premiumspread.domain.ticker

import io.premiumspread.domain.InvalidQuoteException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QuoteTest {
    @Test
    fun `환율의 기준 통화와 견적 통화가 같으면 예외를 던진다`() {
        assertThrows(InvalidQuoteException::class.java) {
            Quote.fx(Currency.USD, Currency.USD)
        }
    }

    @Test
    fun `환율은 기준 통화와 견적 통화가 다르면 생성된다`() {
        val quote = Quote.fx(Currency.USD, Currency.KRW)

        assertEquals(Currency.USD, quote.base)
        assertEquals(Currency.KRW, quote.currency)
    }
}
