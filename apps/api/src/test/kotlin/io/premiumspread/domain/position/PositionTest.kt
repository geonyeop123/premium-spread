package io.premiumspread.domain.position

import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PositionTest {

    @Test
    fun `포지션을 페어 모델로 정상 생성한다`() {
        val observedAt = Instant.parse("2024-01-01T00:00:00Z")

        val position = createPosition(entryObservedAt = observedAt)

        assertThat(position.memberId).isEqualTo(1L)
        assertThat(position.symbol).isEqualTo(Symbol("BTC"))
        assertThat(position.koreaExchange).isEqualTo(Exchange.UPBIT)
        assertThat(position.koreaQuantity).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(position.koreaEntryPrice).isEqualByComparingTo(BigDecimal("129555000"))
        assertThat(position.foreignExchange).isEqualTo(Exchange.BINANCE)
        assertThat(position.foreignQuantity).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(position.foreignEntryPrice).isEqualByComparingTo(BigDecimal("89500"))
        assertThat(position.foreignLeverage).isEqualTo(1)
        assertThat(position.entryFxRate).isEqualByComparingTo(BigDecimal("1432.6"))
        assertThat(position.entryPremiumRate).isEqualByComparingTo(BigDecimal("1.04"))
        assertThat(position.entryObservedAt).isEqualTo(observedAt)
        assertThat(position.status).isEqualTo(PositionStatus.OPEN)
    }

    @Test
    fun `진입 프리미엄을 결정론적으로 계산한다 - 양수`() {
        val position = createPosition(
            koreaEntryPrice = BigDecimal("110000"),
            foreignEntryPrice = BigDecimal("100"),
            entryFxRate = BigDecimal("1000"),
        )

        assertThat(position.entryPremiumRate).isEqualByComparingTo(BigDecimal("10.00"))
    }

    @Test
    fun `진입 프리미엄을 결정론적으로 계산한다 - 음수`() {
        val position = createPosition(
            koreaEntryPrice = BigDecimal("90000"),
            foreignEntryPrice = BigDecimal("100"),
            entryFxRate = BigDecimal("1000"),
        )

        assertThat(position.entryPremiumRate).isEqualByComparingTo(BigDecimal("-10.00"))
    }

    @Test
    fun `한국 거래소가 KOREA region이 아니면 예외를 던진다`() {
        assertThatThrownBy {
            createPosition(koreaExchange = Exchange.BINANCE)
        }.isInstanceOf(InvalidPositionException::class.java)
            .hasMessageContaining("KOREA")
    }

    @Test
    fun `해외 거래소가 FOREIGN region이 아니면 예외를 던진다`() {
        assertThatThrownBy {
            createPosition(foreignExchange = Exchange.UPBIT)
        }.isInstanceOf(InvalidPositionException::class.java)
            .hasMessageContaining("FOREIGN")
    }

    @Test
    fun `페어 수량 가격 환율이 0 이하이면 예외를 던진다`() {
        val invalidCases = listOf(
            "koreaQuantity" to { createPosition(koreaQuantity = BigDecimal.ZERO) },
            "koreaQuantity" to { createPosition(koreaQuantity = BigDecimal("-0.1")) },
            "koreaEntryPrice" to { createPosition(koreaEntryPrice = BigDecimal.ZERO) },
            "koreaEntryPrice" to { createPosition(koreaEntryPrice = BigDecimal("-1")) },
            "foreignQuantity" to { createPosition(foreignQuantity = BigDecimal.ZERO) },
            "foreignQuantity" to { createPosition(foreignQuantity = BigDecimal("-0.1")) },
            "foreignEntryPrice" to { createPosition(foreignEntryPrice = BigDecimal.ZERO) },
            "foreignEntryPrice" to { createPosition(foreignEntryPrice = BigDecimal("-1")) },
            "entryFxRate" to { createPosition(entryFxRate = BigDecimal.ZERO) },
            "entryFxRate" to { createPosition(entryFxRate = BigDecimal("-1")) },
        )

        invalidCases.forEach { (fieldName, factory) ->
            assertThatThrownBy { factory() }
                .isInstanceOf(InvalidPositionException::class.java)
                .hasMessageContaining(fieldName)
        }
    }

    @Test
    fun `해외 레버리지는 1 이상 125 이하만 허용한다`() {
        assertThatThrownBy {
            createPosition(foreignLeverage = 0)
        }.isInstanceOf(InvalidPositionException::class.java)
            .hasMessageContaining("leverage")

        assertThatThrownBy {
            createPosition(foreignLeverage = 126)
        }.isInstanceOf(InvalidPositionException::class.java)
            .hasMessageContaining("leverage")

        assertThatCode { createPosition(foreignLeverage = 1) }.doesNotThrowAnyException()
        assertThatCode { createPosition(foreignLeverage = 125) }.doesNotThrowAnyException()
    }

    @Test
    fun `포지션을 청산한다`() {
        val position = createPosition()

        position.close()

        assertThat(position.status).isEqualTo(PositionStatus.CLOSED)
    }

    @Test
    fun `이미 청산된 포지션은 다시 청산할 수 없다`() {
        val position = createPosition()
        position.close()

        assertThatThrownBy {
            position.close()
        }.isInstanceOf(InvalidPositionException::class.java)
            .hasMessageContaining("already closed")
    }

    @Test
    fun `프리미엄 차이를 계산한다 - 기존 동작 유지`() {
        val position = createPosition(
            koreaEntryPrice = BigDecimal("103000"),
            foreignEntryPrice = BigDecimal("100"),
            entryFxRate = BigDecimal("1000"),
        )

        val pnl = position.calculatePremiumDiff(currentPremiumRate = BigDecimal("1.00"))

        assertThat(position.entryPremiumRate).isEqualByComparingTo(BigDecimal("3.00"))
        assertThat(pnl.premiumDiff).isEqualByComparingTo(BigDecimal("-2.00"))
        assertThat(pnl.entryPremiumRate).isEqualByComparingTo(BigDecimal("3.00"))
        assertThat(pnl.currentPremiumRate).isEqualByComparingTo(BigDecimal("1.00"))
        assertThat(pnl.isProfit()).isTrue()
    }

    private fun createPosition(
        memberId: Long = 1L,
        symbol: Symbol = Symbol("BTC"),
        koreaExchange: Exchange = Exchange.UPBIT,
        koreaQuantity: BigDecimal = BigDecimal("0.5"),
        koreaEntryPrice: BigDecimal = BigDecimal("129555000"),
        foreignExchange: Exchange = Exchange.BINANCE,
        foreignQuantity: BigDecimal = BigDecimal("0.5"),
        foreignEntryPrice: BigDecimal = BigDecimal("89500"),
        foreignLeverage: Int = 1,
        entryFxRate: BigDecimal = BigDecimal("1432.6"),
        entryObservedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
    ): Position = Position.create(
        memberId = memberId,
        symbol = symbol,
        koreaExchange = koreaExchange,
        koreaQuantity = koreaQuantity,
        koreaEntryPrice = koreaEntryPrice,
        foreignExchange = foreignExchange,
        foreignQuantity = foreignQuantity,
        foreignEntryPrice = foreignEntryPrice,
        foreignLeverage = foreignLeverage,
        entryFxRate = entryFxRate,
        entryObservedAt = entryObservedAt,
    )
}
