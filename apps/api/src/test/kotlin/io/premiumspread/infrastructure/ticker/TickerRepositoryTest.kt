package io.premiumspread.infrastructure.ticker

import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.Ticker
import io.premiumspread.domain.ticker.TickerRepository
import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.Instant

@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, io.premiumspread.config.TestConfig::class)
class TickerRepositoryTest @Autowired constructor(
    private val tickerRepository: TickerRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
    }

    @Nested
    @DisplayName("save")
    inner class Save {
        @Test
        fun `should save ticker and return with id`() {
            // given
            val ticker = Ticker.create(
                exchange = Exchange.UPBIT,
                quote = Quote.coin(Symbol("BTC"), Currency.KRW),
                price = BigDecimal("129555000"),
                observedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )

            // when
            val saved = tickerRepository.save(ticker)

            // then
            assertThat(saved.id).isGreaterThan(0)
            assertThat(saved.exchange).isEqualTo(Exchange.UPBIT)
            assertThat(saved.quote.baseCode).isEqualTo("BTC")
            assertThat(saved.quote.currency).isEqualTo(Currency.KRW)
            assertThat(saved.price).isEqualByComparingTo(BigDecimal("129555000"))
        }
    }

    @Nested
    @DisplayName("findById")
    inner class FindById {
        @Test
        fun `should return ticker when exists`() {
            // given
            val saved = tickerRepository.save(
                Ticker.create(
                    exchange = Exchange.UPBIT,
                    quote = Quote.coin(Symbol("BTC"), Currency.KRW),
                    price = BigDecimal("129555000"),
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            )

            // when
            val found = tickerRepository.findById(saved.id)

            // then
            assertThat(found).isNotNull
            assertThat(found!!.id).isEqualTo(saved.id)
            assertThat(found.exchange).isEqualTo(Exchange.UPBIT)
        }

        @Test
        fun `should return null when not exists`() {
            // when
            val found = tickerRepository.findById(999L)

            // then
            assertThat(found).isNull()
        }
    }

    @Nested
    @DisplayName("findLatest")
    inner class FindLatest {
        @Test
        fun `should return latest ticker by observedAt`() {
            // given
            val oldTicker = tickerRepository.save(
                Ticker.create(
                    exchange = Exchange.UPBIT,
                    quote = Quote.coin(Symbol("BTC"), Currency.KRW),
                    price = BigDecimal("120000000"),
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            )
            val newTicker = tickerRepository.save(
                Ticker.create(
                    exchange = Exchange.UPBIT,
                    quote = Quote.coin(Symbol("BTC"), Currency.KRW),
                    price = BigDecimal("130000000"),
                    observedAt = Instant.parse("2024-01-02T00:00:00Z"),
                ),
            )

            // when
            val found = tickerRepository.findLatest(
                exchange = Exchange.UPBIT,
                quote = Quote.coin(Symbol("BTC"), Currency.KRW),
            )

            // then
            assertThat(found).isNotNull
            assertThat(found!!.id).isEqualTo(newTicker.id)
            assertThat(found.price).isEqualByComparingTo(BigDecimal("130000000"))
        }

        @Test
        fun `should return null when no matching ticker`() {
            // given
            tickerRepository.save(
                Ticker.create(
                    exchange = Exchange.UPBIT,
                    quote = Quote.coin(Symbol("BTC"), Currency.KRW),
                    price = BigDecimal("129555000"),
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            )

            // when
            val found = tickerRepository.findLatest(
                exchange = Exchange.BINANCE,
                quote = Quote.coin(Symbol("BTC"), Currency.USD),
            )

            // then
            assertThat(found).isNull()
        }

        @Test
        fun `should filter by exchange and quote`() {
            // given
            tickerRepository.save(
                Ticker.create(
                    exchange = Exchange.UPBIT,
                    quote = Quote.coin(Symbol("BTC"), Currency.KRW),
                    price = BigDecimal("129555000"),
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            )
            tickerRepository.save(
                Ticker.create(
                    exchange = Exchange.UPBIT,
                    quote = Quote.coin(Symbol("ETH"), Currency.KRW),
                    price = BigDecimal("4500000"),
                    observedAt = Instant.parse("2024-01-02T00:00:00Z"),
                ),
            )

            // when
            val found = tickerRepository.findLatest(
                exchange = Exchange.UPBIT,
                quote = Quote.coin(Symbol("BTC"), Currency.KRW),
            )

            // then
            assertThat(found).isNotNull
            assertThat(found!!.quote.baseCode).isEqualTo("BTC")
        }
    }

    @Nested
    @DisplayName("findAllByExchangeAndSymbol")
    inner class FindAllByExchangeAndSymbol {
        @Test
        fun `should return all tickers for exchange and symbol`() {
            // given
            tickerRepository.save(
                Ticker.create(
                    exchange = Exchange.UPBIT,
                    quote = Quote.coin(Symbol("BTC"), Currency.KRW),
                    price = BigDecimal("120000000"),
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            )
            tickerRepository.save(
                Ticker.create(
                    exchange = Exchange.UPBIT,
                    quote = Quote.coin(Symbol("BTC"), Currency.KRW),
                    price = BigDecimal("130000000"),
                    observedAt = Instant.parse("2024-01-02T00:00:00Z"),
                ),
            )
            tickerRepository.save(
                Ticker.create(
                    exchange = Exchange.BINANCE,
                    quote = Quote.coin(Symbol("BTC"), Currency.USD),
                    price = BigDecimal("89277"),
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            )

            // when
            val found = tickerRepository.findAllByExchangeAndSymbol(
                exchange = Exchange.UPBIT,
                symbol = Symbol("BTC"),
            )

            // then
            assertThat(found).hasSize(2)
            assertThat(found).allMatch { it.exchange == Exchange.UPBIT }
            assertThat(found).allMatch { it.quote.baseCode == "BTC" }
        }

        @Test
        fun `should return empty list when no matching tickers`() {
            // given
            tickerRepository.save(
                Ticker.create(
                    exchange = Exchange.UPBIT,
                    quote = Quote.coin(Symbol("BTC"), Currency.KRW),
                    price = BigDecimal("129555000"),
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            )

            // when
            val found = tickerRepository.findAllByExchangeAndSymbol(
                exchange = Exchange.UPBIT,
                symbol = Symbol("ETH"),
            )

            // then
            assertThat(found).isEmpty()
        }

        @Test
        fun `should return tickers ordered by observedAt desc`() {
            // given
            tickerRepository.save(
                Ticker.create(
                    exchange = Exchange.UPBIT,
                    quote = Quote.coin(Symbol("BTC"), Currency.KRW),
                    price = BigDecimal("120000000"),
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            )
            tickerRepository.save(
                Ticker.create(
                    exchange = Exchange.UPBIT,
                    quote = Quote.coin(Symbol("BTC"), Currency.KRW),
                    price = BigDecimal("130000000"),
                    observedAt = Instant.parse("2024-01-03T00:00:00Z"),
                ),
            )
            tickerRepository.save(
                Ticker.create(
                    exchange = Exchange.UPBIT,
                    quote = Quote.coin(Symbol("BTC"), Currency.KRW),
                    price = BigDecimal("125000000"),
                    observedAt = Instant.parse("2024-01-02T00:00:00Z"),
                ),
            )

            // when
            val found = tickerRepository.findAllByExchangeAndSymbol(
                exchange = Exchange.UPBIT,
                symbol = Symbol("BTC"),
            )

            // then
            assertThat(found).hasSize(3)
            assertThat(found[0].observedAt).isEqualTo(Instant.parse("2024-01-03T00:00:00Z"))
            assertThat(found[1].observedAt).isEqualTo(Instant.parse("2024-01-02T00:00:00Z"))
            assertThat(found[2].observedAt).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))
        }
    }
}
