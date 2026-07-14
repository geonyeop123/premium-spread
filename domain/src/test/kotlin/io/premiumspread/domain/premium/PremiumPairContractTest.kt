package io.premiumspread.domain.premium

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PremiumPairContractTest {
    private val pair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
    private val observedAt = Instant.parse("2024-01-01T00:00:00Z")

    @Test
    fun `PremiumSnapshot의 symbol은 pair에서 파생한다`() {
        val snapshot = snapshot(pair)

        assertThat(snapshot.symbol).isEqualTo("BTC")
        assertThat(snapshot.pair).isEqualTo(pair)
    }

    @Test
    fun `PremiumAggregationSnapshot도 pair를 유일한 identity로 사용한다`() {
        val snapshot = PremiumAggregationSnapshot(
            pair = pair,
            high = BigDecimal("2.0"),
            low = BigDecimal("1.0"),
            open = BigDecimal("1.1"),
            close = BigDecimal("1.9"),
            avg = BigDecimal("1.5"),
            count = 60,
            observedAt = observedAt,
        )

        assertThat(snapshot.symbol).isEqualTo(pair.symbol.code)
        assertThat(snapshot.pair).isEqualTo(pair)
    }

    @Test
    fun `PremiumRepository의 pair 조회 메서드는 구현체가 반드시 구현한다`() {
        val pairMethods = listOf(
            "findLatestByPair",
            "findLatestSnapshotByPair",
            "findAllByPair",
            "findAggregationByPair",
        )

        pairMethods.forEach { name ->
            val method = PremiumRepository::class.java.methods.single { it.name == name }
            assertThat(Modifier.isAbstract(method.modifiers)).describedAs(name).isTrue()
            assertThat(method.isDefault).describedAs(name).isFalse()
        }
        assertThat(PremiumRepository::class.java.methods.map { it.name })
            .doesNotContain("findLatestBySymbol", "findLatestSnapshotBySymbol", "findAllBySymbolAndPeriod")
    }

    private fun snapshot(pair: MarketPair) = PremiumSnapshot(
        pair = pair,
        premiumRate = BigDecimal("1.00"),
        koreaPrice = BigDecimal("101000"),
        foreignPrice = BigDecimal("100"),
        foreignPriceInKrw = BigDecimal("100000"),
        fxRate = BigDecimal("1000"),
        observedAt = observedAt,
    )
}
