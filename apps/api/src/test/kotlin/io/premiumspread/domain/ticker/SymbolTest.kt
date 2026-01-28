package io.premiumspread.domain.ticker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SymbolTest {
    @Test
    fun `심볼이 공백이면 예외를 던진다`() {
        assertThrows(IllegalArgumentException::class.java) {
            Symbol("   ")
        }
    }

    @Test
    fun `심볼 코드는 공백을 제거한 값을 사용한다`() {
        val symbol = Symbol(" BTC ")

        assertEquals("BTC", symbol.code)
    }
}
