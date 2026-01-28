package io.premiumspread.domain.position

import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PositionTest {

    @Test
    fun `포지션을 정상적으로 생성한다`() {
        val position = Position.create(
            symbol = Symbol("BTC"),
            exchange = Exchange.UPBIT,
            quantity = BigDecimal("0.5"),
            entryPrice = BigDecimal("129555000"),
            entryFxRate = BigDecimal("1432.6"),
            entryPremiumRate = BigDecimal("1.28"),
            entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        assertThat(position.symbol).isEqualTo(Symbol("BTC"))
        assertThat(position.exchange).isEqualTo(Exchange.UPBIT)
        assertThat(position.quantity).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(position.entryPrice).isEqualByComparingTo(BigDecimal("129555000"))
        assertThat(position.entryFxRate).isEqualByComparingTo(BigDecimal("1432.6"))
        assertThat(position.entryPremiumRate).isEqualByComparingTo(BigDecimal("1.28"))
        assertThat(position.entryObservedAt).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))
        assertThat(position.status).isEqualTo(PositionStatus.OPEN)
    }

    @Test
    fun `수량이 0 이하이면 예외를 던진다`() {
        assertThatThrownBy {
            Position.create(
                symbol = Symbol("BTC"),
                exchange = Exchange.UPBIT,
                quantity = BigDecimal.ZERO,
                entryPrice = BigDecimal("129555000"),
                entryFxRate = BigDecimal("1432.6"),
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
        }.isInstanceOf(InvalidPositionException::class.java)
            .hasMessageContaining("quantity")

        assertThatThrownBy {
            Position.create(
                symbol = Symbol("BTC"),
                exchange = Exchange.UPBIT,
                quantity = BigDecimal("-1"),
                entryPrice = BigDecimal("129555000"),
                entryFxRate = BigDecimal("1432.6"),
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
        }.isInstanceOf(InvalidPositionException::class.java)
            .hasMessageContaining("quantity")
    }

    @Test
    fun `진입 가격이 0 이하이면 예외를 던진다`() {
        assertThatThrownBy {
            Position.create(
                symbol = Symbol("BTC"),
                exchange = Exchange.UPBIT,
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal.ZERO,
                entryFxRate = BigDecimal("1432.6"),
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
        }.isInstanceOf(InvalidPositionException::class.java)
            .hasMessageContaining("entryPrice")

        assertThatThrownBy {
            Position.create(
                symbol = Symbol("BTC"),
                exchange = Exchange.UPBIT,
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("-100"),
                entryFxRate = BigDecimal("1432.6"),
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
        }.isInstanceOf(InvalidPositionException::class.java)
            .hasMessageContaining("entryPrice")
    }

    @Test
    fun `진입 환율이 0 이하이면 예외를 던진다`() {
        assertThatThrownBy {
            Position.create(
                symbol = Symbol("BTC"),
                exchange = Exchange.UPBIT,
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("129555000"),
                entryFxRate = BigDecimal.ZERO,
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
        }.isInstanceOf(InvalidPositionException::class.java)
            .hasMessageContaining("entryFxRate")

        assertThatThrownBy {
            Position.create(
                symbol = Symbol("BTC"),
                exchange = Exchange.UPBIT,
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("129555000"),
                entryFxRate = BigDecimal("-1"),
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
        }.isInstanceOf(InvalidPositionException::class.java)
            .hasMessageContaining("entryFxRate")
    }

    @Test
    fun `한국 거래소가 아니면 예외를 던진다`() {
        assertThatThrownBy {
            Position.create(
                symbol = Symbol("BTC"),
                exchange = Exchange.BINANCE,
                quantity = BigDecimal("0.5"),
                entryPrice = BigDecimal("89277"),
                entryFxRate = BigDecimal("1432.6"),
                entryPremiumRate = BigDecimal("1.28"),
                entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
        }.isInstanceOf(InvalidPositionException::class.java)
            .hasMessageContaining("KOREA")
    }

    @Test
    fun `프리미엄 차이를 계산한다 - 프리미엄 하락시 이익`() {
        val position = Position.create(
            symbol = Symbol("BTC"),
            exchange = Exchange.UPBIT,
            quantity = BigDecimal("0.5"),
            entryPrice = BigDecimal("129555000"),
            entryFxRate = BigDecimal("1432.6"),
            entryPremiumRate = BigDecimal("3.00"),
            entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        val pnl = position.calculatePremiumDiff(currentPremiumRate = BigDecimal("1.00"))

        assertThat(pnl.premiumDiff).isEqualByComparingTo(BigDecimal("-2.00"))
        assertThat(pnl.isProfit()).isTrue()
    }

    @Test
    fun `프리미엄 차이를 계산한다 - 프리미엄 상승시 손실`() {
        val position = Position.create(
            symbol = Symbol("BTC"),
            exchange = Exchange.UPBIT,
            quantity = BigDecimal("0.5"),
            entryPrice = BigDecimal("129555000"),
            entryFxRate = BigDecimal("1432.6"),
            entryPremiumRate = BigDecimal("1.00"),
            entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        val pnl = position.calculatePremiumDiff(currentPremiumRate = BigDecimal("3.00"))

        assertThat(pnl.premiumDiff).isEqualByComparingTo(BigDecimal("2.00"))
        assertThat(pnl.isProfit()).isFalse()
    }

    @Test
    fun `포지션을 청산한다`() {
        val position = Position.create(
            symbol = Symbol("BTC"),
            exchange = Exchange.UPBIT,
            quantity = BigDecimal("0.5"),
            entryPrice = BigDecimal("129555000"),
            entryFxRate = BigDecimal("1432.6"),
            entryPremiumRate = BigDecimal("1.28"),
            entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        position.close()

        assertThat(position.status).isEqualTo(PositionStatus.CLOSED)
    }

    @Test
    fun `이미 청산된 포지션은 다시 청산할 수 없다`() {
        val position = Position.create(
            symbol = Symbol("BTC"),
            exchange = Exchange.UPBIT,
            quantity = BigDecimal("0.5"),
            entryPrice = BigDecimal("129555000"),
            entryFxRate = BigDecimal("1432.6"),
            entryPremiumRate = BigDecimal("1.28"),
            entryObservedAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        position.close()

        assertThatThrownBy {
            position.close()
        }.isInstanceOf(InvalidPositionException::class.java)
            .hasMessageContaining("already closed")
    }
}
