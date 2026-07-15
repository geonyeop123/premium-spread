package io.premiumspread

import io.premiumspread.domain.BaseEntity
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.position.Position
import io.premiumspread.domain.position.PositionOpenSpec
import io.premiumspread.domain.premium.Premium
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.Ticker
import java.math.BigDecimal
import java.time.Instant

fun <T : BaseEntity> T.withId(id: Long): T {
    BaseEntity::class.java.getDeclaredField("id").apply {
        isAccessible = true
        set(this@withId, id)
    }
    return this
}

object TickerFixtures {
    fun koreaTicker(
        symbol: String = "BTC",
        price: BigDecimal = BigDecimal("129555000"),
        observedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
        id: Long = 1L,
    ): Ticker = Ticker.create(
        exchange = Exchange.UPBIT,
        quote = Quote.coin(Symbol(symbol), Currency.KRW),
        price = price,
        observedAt = observedAt,
    ).withId(id)

    fun foreignTicker(
        symbol: String = "BTC",
        price: BigDecimal = BigDecimal("89277"),
        observedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
        id: Long = 2L,
    ): Ticker = Ticker.create(
        exchange = Exchange.BINANCE,
        quote = Quote.coin(Symbol(symbol), Currency.USD),
        price = price,
        observedAt = observedAt,
    ).withId(id)

    fun fxTicker(
        price: BigDecimal = BigDecimal("1432.6"),
        observedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
        id: Long = 3L,
    ): Ticker = Ticker.create(
        exchange = Exchange.FX_PROVIDER,
        quote = Quote.fx(Currency.USD, Currency.KRW),
        price = price,
        observedAt = observedAt,
    ).withId(id)
}

object PositionFixtures {
    fun openPosition(
        memberId: Long = 1L,
        symbol: String = "BTC",
        koreaExchange: Exchange = Exchange.UPBIT,
        koreaQuantity: BigDecimal = BigDecimal("0.5"),
        koreaEntryPrice: BigDecimal = BigDecimal("129555000"),
        foreignExchange: Exchange = Exchange.BINANCE,
        foreignQuantity: BigDecimal = BigDecimal("0.5"),
        foreignEntryPrice: BigDecimal = BigDecimal("89500"),
        foreignLeverage: Int = 1,
        entryFxRate: BigDecimal = BigDecimal("1432.6"),
        entryObservedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
        id: Long? = 1L,
    ): Position {
        val position = Position.create(
            PositionOpenSpec(
                memberId = memberId,
                pair = MarketPair(Symbol(symbol), koreaExchange, foreignExchange),
                koreaQuantity = koreaQuantity,
                koreaEntryPrice = koreaEntryPrice,
                foreignQuantity = foreignQuantity,
                foreignEntryPrice = foreignEntryPrice,
                foreignLeverage = foreignLeverage,
                entryFxRate = entryFxRate,
                entryObservedAt = entryObservedAt,
            ),
        )
        return id?.let(position::withId) ?: position
    }
}

object PremiumFixtures {
    fun premium(
        symbol: String = "BTC",
        koreaTickerId: Long = 1L,
        foreignTickerId: Long = 2L,
        fxTickerId: Long = 3L,
        id: Long = 1L,
    ): Premium = Premium.create(
        TickerFixtures.koreaTicker(symbol = symbol, id = koreaTickerId),
        TickerFixtures.foreignTicker(symbol = symbol, id = foreignTickerId),
        TickerFixtures.fxTicker(id = fxTickerId),
    ).withId(id)

    fun premiumWithRate(
        symbol: String = "BTC",
        premiumRate: BigDecimal,
        observedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
        id: Long = 1L,
    ): Premium {
        val koreaPrice = BigDecimal("100000")
        val foreignPrice = BigDecimal("100")
            .divide(BigDecimal.ONE.add(premiumRate.divide(BigDecimal("100"))), 10, java.math.RoundingMode.HALF_UP)
        return Premium.create(
            TickerFixtures.koreaTicker(symbol, koreaPrice, observedAt, 1L),
            TickerFixtures.foreignTicker(symbol, foreignPrice, observedAt, 2L),
            TickerFixtures.fxTicker(BigDecimal("1000"), observedAt, 3L),
        ).withId(id)
    }
}
