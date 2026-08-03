package io.premiumspread.domain.tracking

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumPolicy
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class TrackingTest {

    @Test
    fun `추적 기록을 페어 모델로 정상 생성한다`() {
        val observedAt = Instant.parse("2024-01-01T00:00:00Z")

        val tracking = createPosition(entryObservedAt = observedAt)

        assertThat(tracking.memberId).isEqualTo(1L)
        assertThat(tracking.symbol).isEqualTo(Symbol("BTC"))
        assertThat(tracking.koreaExchange).isEqualTo(Exchange.UPBIT)
        assertThat(tracking.koreaQuantity).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(tracking.koreaEntryPrice).isEqualByComparingTo(BigDecimal("129555000"))
        assertThat(tracking.foreignExchange).isEqualTo(Exchange.BINANCE)
        assertThat(tracking.foreignQuantity).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(tracking.foreignEntryPrice).isEqualByComparingTo(BigDecimal("89500"))
        assertThat(tracking.foreignLeverage).isEqualTo(1)
        assertThat(tracking.entryFxRate).isEqualByComparingTo(BigDecimal("1432.6"))
        assertThat(tracking.entryPremiumRate).isEqualByComparingTo(BigDecimal("1.04"))
        assertThat(tracking.entryObservedAt).isEqualTo(observedAt)
        assertThat(tracking.status).isEqualTo(TrackingStatus.OPEN)
    }

    @Test
    fun `진입 프리미엄을 결정론적으로 계산한다 - 양수`() {
        val tracking = createPosition(
            koreaEntryPrice = BigDecimal("110000"),
            foreignEntryPrice = BigDecimal("100"),
            entryFxRate = BigDecimal("1000"),
        )

        val expected = PremiumPolicy.calculate(BigDecimal("110000"), BigDecimal("100"), BigDecimal("1000"))
            .entityPremiumRate
        assertThat(tracking.entryPremiumRate).isEqualByComparingTo(expected)
    }

    @Test
    fun `진입 프리미엄을 결정론적으로 계산한다 - 음수`() {
        val tracking = createPosition(
            koreaEntryPrice = BigDecimal("90000"),
            foreignEntryPrice = BigDecimal("100"),
            entryFxRate = BigDecimal("1000"),
        )

        val expected = PremiumPolicy.calculate(BigDecimal("90000"), BigDecimal("100"), BigDecimal("1000"))
            .entityPremiumRate
        assertThat(tracking.entryPremiumRate).isEqualByComparingTo(expected)
    }

    @Test
    fun `한국 거래소가 KOREA region이 아니면 페어 생성에서 예외를 던진다`() {
        assertThatThrownBy {
            createPosition(koreaExchange = Exchange.BINANCE)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("KOREA")
    }

    @Test
    fun `해외 거래소가 FOREIGN region이 아니면 페어 생성에서 예외를 던진다`() {
        assertThatThrownBy {
            createPosition(foreignExchange = Exchange.UPBIT)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("FOREIGN")
    }

    @Test
    fun `페어는 해외 거래소로 FX_PROVIDER를 허용하지 않는다`() {
        assertThatThrownBy {
            createPosition(foreignExchange = Exchange.FX_PROVIDER)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tradable FOREIGN")
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
                .isInstanceOf(InvalidTrackingException::class.java)
                .hasMessageContaining(fieldName)
        }
    }

    @Test
    fun `해외 레버리지는 1 이상 125 이하만 허용한다`() {
        assertThatThrownBy {
            createPosition(foreignLeverage = 0)
        }.isInstanceOf(InvalidTrackingException::class.java)
            .hasMessageContaining("leverage")

        assertThatThrownBy {
            createPosition(foreignLeverage = 126)
        }.isInstanceOf(InvalidTrackingException::class.java)
            .hasMessageContaining("leverage")

        assertThatCode { createPosition(foreignLeverage = 1) }.doesNotThrowAnyException()
        assertThatCode { createPosition(foreignLeverage = 125) }.doesNotThrowAnyException()
    }

    @Test
    fun `추적 기록을 청산한다`() {
        val tracking = createPosition()

        tracking.close()

        assertThat(tracking.status).isEqualTo(TrackingStatus.CLOSED)
    }

    @Test
    fun `이미 청산된 추적 기록은 다시 청산할 수 없다`() {
        val tracking = createPosition()
        tracking.close()

        assertThatThrownBy {
            tracking.close()
        }.isInstanceOf(InvalidTrackingException::class.java)
            .hasMessageContaining("already closed")
    }

    @Test
    fun `PnL을 페어 기반 KRW 손익으로 계산한다 - 사용자 예시 회귀`() {
        val tracking = createPosition(
            koreaEntryPrice = BigDecimal("161493792"),
            koreaQuantity = BigDecimal("0.157"),
            foreignEntryPrice = BigDecimal("118100"),
            foreignQuantity = BigDecimal("0.15"),
            entryFxRate = BigDecimal("1521.6"),
        )

        val pnl = tracking.calculatePnl(
            currentKoreaPrice = BigDecimal("118326000"),
            currentForeignPrice = BigDecimal("79699.1"),
            currentFxRate = BigDecimal("1490.5"),
            currentPremiumRate = BigDecimal("-0.39"),
            calculatedAt = Instant.parse("2024-01-01T00:01:00Z"),
        )

        assertThat(pnl.koreaPnl).isEqualByComparingTo(BigDecimal("-6777343.344"))
        assertThat(pnl.foreignPnlKrw).isEqualByComparingTo(BigDecimal("8585481.2175"))
        assertThat(pnl.totalPnlKrw).isEqualByComparingTo(BigDecimal("1808137.8735"))
        assertThat(pnl.koreaCurrentValue).isEqualByComparingTo(BigDecimal("18577182"))
        assertThat(pnl.totalPnlPercent).isEqualByComparingTo(BigDecimal("9.73"))
        assertThat(pnl.isProfit()).isTrue()
    }

    @Test
    fun `양쪽 손실일 때 totalPnlKrw 음수`() {
        val tracking = createPosition(
            koreaEntryPrice = BigDecimal("100000"),
            koreaQuantity = BigDecimal("1.0"),
            foreignEntryPrice = BigDecimal("100"),
            foreignQuantity = BigDecimal("1.0"),
            entryFxRate = BigDecimal("1000"),
        )

        val pnl = tracking.calculatePnl(
            currentKoreaPrice = BigDecimal("90000"),
            currentForeignPrice = BigDecimal("110"),
            currentFxRate = BigDecimal("1000"),
            currentPremiumRate = BigDecimal("-18.18"),
            calculatedAt = Instant.parse("2024-01-01T00:01:00Z"),
        )

        assertThat(pnl.koreaPnl).isNegative()
        assertThat(pnl.foreignPnlKrw).isNegative()
        assertThat(pnl.totalPnlKrw).isNegative()
        assertThat(pnl.isProfit()).isFalse()
    }

    @Test
    fun `isProfit은 totalPnlKrw 기준이며 premiumDiff와 부호 불일치 가능`() {
        val tracking = createPosition(
            koreaEntryPrice = BigDecimal("100000"),
            koreaQuantity = BigDecimal("1.0"),
            foreignEntryPrice = BigDecimal("100"),
            foreignQuantity = BigDecimal("1.0"),
            entryFxRate = BigDecimal("1000"),
        )

        val pnl = tracking.calculatePnl(
            currentKoreaPrice = BigDecimal("120000"),
            currentForeignPrice = BigDecimal("105"),
            currentFxRate = BigDecimal("1000"),
            currentPremiumRate = BigDecimal("15.00"),
            calculatedAt = Instant.parse("2024-01-01T00:01:00Z"),
        )

        assertThat(tracking.entryPremiumRate).isEqualByComparingTo(BigDecimal("0.00"))
        assertThat(pnl.premiumDiff).isPositive()
        assertThat(pnl.totalPnlKrw).isPositive()
        assertThat(pnl.isProfit()).isEqualTo(pnl.totalPnlKrw > BigDecimal.ZERO)
    }

    @Test
    fun `시세가 0 이하면 IllegalArgumentException`() {
        val tracking = createPosition()
        val invalidCases = listOf(
            { tracking.calculatePnl(BigDecimal.ZERO, BigDecimal("89500"), BigDecimal("1432.6"), BigDecimal("1.00"), Instant.EPOCH) },
            { tracking.calculatePnl(BigDecimal("-1"), BigDecimal("89500"), BigDecimal("1432.6"), BigDecimal("1.00"), Instant.EPOCH) },
            { tracking.calculatePnl(BigDecimal("129555000"), BigDecimal.ZERO, BigDecimal("1432.6"), BigDecimal("1.00"), Instant.EPOCH) },
            { tracking.calculatePnl(BigDecimal("129555000"), BigDecimal("-1"), BigDecimal("1432.6"), BigDecimal("1.00"), Instant.EPOCH) },
            { tracking.calculatePnl(BigDecimal("129555000"), BigDecimal("89500"), BigDecimal.ZERO, BigDecimal("1.00"), Instant.EPOCH) },
            { tracking.calculatePnl(BigDecimal("129555000"), BigDecimal("89500"), BigDecimal("-1"), BigDecimal("1.00"), Instant.EPOCH) },
        )

        invalidCases.forEach { calculate ->
            assertThatThrownBy { calculate() }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
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
    ): Tracking = Tracking.create(
        TrackingRecordSpec(
            memberId = memberId,
            pair = MarketPair(symbol, koreaExchange, foreignExchange),
            koreaQuantity = koreaQuantity,
            koreaEntryPrice = koreaEntryPrice,
            foreignQuantity = foreignQuantity,
            foreignEntryPrice = foreignEntryPrice,
            foreignLeverage = foreignLeverage,
            entryFxRate = entryFxRate,
            entryObservedAt = entryObservedAt,
        ),
    )
}
