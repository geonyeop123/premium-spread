package io.premiumspread

import io.premiumspread.domain.BaseEntity
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.member.Member
import io.premiumspread.domain.tracking.Tracking
import io.premiumspread.domain.tracking.TrackingRecordSpec
import io.premiumspread.domain.premium.Premium
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.Ticker
import java.math.BigDecimal
import java.time.Instant

@Suppress("UNCHECKED_CAST")
fun <T : BaseEntity> T.withId(id: Long): T {
    val idField = BaseEntity::class.java.getDeclaredField("id")
    idField.isAccessible = true
    idField.set(this, id)

    val now = Instant.parse("2024-01-01T00:00:00Z")
    val createdAtField = BaseEntity::class.java.getDeclaredField("createdAt")
    createdAtField.isAccessible = true
    createdAtField.set(this, now)

    val updatedAtField = BaseEntity::class.java.getDeclaredField("updatedAt")
    updatedAtField.isAccessible = true
    updatedAtField.set(this, now)

    return this
}

object TickerFixtures {
    fun koreaTicker(
        symbol: String = "BTC",
        exchange: Exchange = Exchange.UPBIT,
        price: BigDecimal = BigDecimal("129555000"),
        observedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
        id: Long = 1L,
    ): Ticker = Ticker.create(
            exchange = exchange,
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

object TrackingFixtures {
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
    ): Tracking {
        val tracking = Tracking.create(
            TrackingRecordSpec(
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
        return id?.let { tracking.withId(it) } ?: tracking
    }
}

object MemberFixtures {
    fun activeMember(
        email: String = "test@example.com",
        encodedPassword: String = "encoded_password",
        id: Long = 1L,
    ): Member = Member.create(
            email = email,
            encodedPassword = encodedPassword,
        ).withId(id)
}

object PremiumFixtures {
    fun premium(
        symbol: String = "BTC",
        koreaTickerId: Long = 1L,
        foreignTickerId: Long = 2L,
        fxTickerId: Long = 3L,
        id: Long = 1L,
    ): Premium {
        val koreaTicker = TickerFixtures.koreaTicker(
            symbol = symbol,
            exchange = Exchange.BITHUMB,
            id = koreaTickerId,
        )
        val foreignTicker = TickerFixtures.foreignTicker(symbol = symbol, id = foreignTickerId)
        val fxTicker = TickerFixtures.fxTicker(id = fxTickerId)
        return Premium.create(koreaTicker, foreignTicker, fxTicker).withId(id)
    }

    fun premiumWithRate(
        symbol: String = "BTC",
        premiumRate: BigDecimal,
        observedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
        id: Long = 1L,
    ): Premium {
        val koreaPrice = BigDecimal("100000")
        val foreignPrice = BigDecimal("100")
            .divide(BigDecimal.ONE.add(premiumRate.divide(BigDecimal("100"))), 10, java.math.RoundingMode.HALF_UP)
        val fxRate = BigDecimal("1000")

        val koreaTicker = Ticker.create(
            exchange = Exchange.UPBIT,
            quote = Quote.coin(Symbol(symbol), Currency.KRW),
            price = koreaPrice,
            observedAt = observedAt,
        ).withId(1L)

        val foreignTicker = Ticker.create(
            exchange = Exchange.BINANCE,
            quote = Quote.coin(Symbol(symbol), Currency.USD),
            price = foreignPrice,
            observedAt = observedAt,
        ).withId(2L)

        val fxTicker = Ticker.create(
            exchange = Exchange.FX_PROVIDER,
            quote = Quote.fx(Currency.USD, Currency.KRW),
            price = fxRate,
            observedAt = observedAt,
        ).withId(3L)

        return Premium.create(koreaTicker, foreignTicker, fxTicker).withId(id)
    }
}
