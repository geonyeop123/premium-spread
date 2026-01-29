package io.premiumspread.infrastructure.premium

import io.premiumspread.domain.premium.Premium
import io.premiumspread.domain.premium.PremiumRepository
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
class PremiumRepositoryTest @Autowired constructor(
    private val premiumRepository: PremiumRepository,
    private val tickerRepository: TickerRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
    }

    private fun createTickersAndPremium(
        symbol: String = "BTC",
        koreaPrice: BigDecimal = BigDecimal("129555000"),
        foreignPrice: BigDecimal = BigDecimal("89277"),
        fxRate: BigDecimal = BigDecimal("1432.6"),
        observedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
    ): Premium {
        val koreaTicker = tickerRepository.save(
            Ticker.create(
                exchange = Exchange.UPBIT,
                quote = Quote.coin(Symbol(symbol), Currency.KRW),
                price = koreaPrice,
                observedAt = observedAt,
            ),
        )
        val foreignTicker = tickerRepository.save(
            Ticker.create(
                exchange = Exchange.BINANCE,
                quote = Quote.coin(Symbol(symbol), Currency.USD),
                price = foreignPrice,
                observedAt = observedAt,
            ),
        )
        val fxTicker = tickerRepository.save(
            Ticker.create(
                exchange = Exchange.FX_PROVIDER,
                quote = Quote.fx(Currency.USD, Currency.KRW),
                price = fxRate,
                observedAt = observedAt,
            ),
        )
        return Premium.create(koreaTicker, foreignTicker, fxTicker)
    }

    @Nested
    @DisplayName("save")
    inner class Save {
        @Test
        fun `should save premium and return with id`() {
            // given
            val premium = createTickersAndPremium()

            // when
            val saved = premiumRepository.save(premium)

            // then
            assertThat(saved.id).isGreaterThan(0)
            assertThat(saved.symbol.code).isEqualTo("BTC")
            assertThat(saved.premiumRate).isNotNull
        }
    }

    @Nested
    @DisplayName("findById")
    inner class FindById {
        @Test
        fun `should return premium when exists`() {
            // given
            val saved = premiumRepository.save(createTickersAndPremium())

            // when
            val found = premiumRepository.findById(saved.id)

            // then
            assertThat(found).isNotNull
            assertThat(found!!.id).isEqualTo(saved.id)
            assertThat(found.symbol.code).isEqualTo("BTC")
        }

        @Test
        fun `should return null when not exists`() {
            // when
            val found = premiumRepository.findById(999L)

            // then
            assertThat(found).isNull()
        }
    }

    @Nested
    @DisplayName("findLatestBySymbol")
    inner class FindLatestBySymbol {
        @Test
        fun `should return latest premium by observedAt`() {
            // given
            premiumRepository.save(
                createTickersAndPremium(
                    symbol = "BTC",
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            )
            val latestPremium = premiumRepository.save(
                createTickersAndPremium(
                    symbol = "BTC",
                    observedAt = Instant.parse("2024-01-02T00:00:00Z"),
                ),
            )

            // when
            val found = premiumRepository.findLatestBySymbol(Symbol("BTC"))

            // then
            assertThat(found).isNotNull
            assertThat(found!!.id).isEqualTo(latestPremium.id)
            assertThat(found.observedAt).isEqualTo(Instant.parse("2024-01-02T00:00:00Z"))
        }

        @Test
        fun `should return null when no matching premium`() {
            // given
            premiumRepository.save(createTickersAndPremium(symbol = "BTC"))

            // when
            val found = premiumRepository.findLatestBySymbol(Symbol("ETH"))

            // then
            assertThat(found).isNull()
        }

        @Test
        fun `should filter by symbol`() {
            // given
            premiumRepository.save(
                createTickersAndPremium(
                    symbol = "BTC",
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            )
            val ethPremium = premiumRepository.save(
                createTickersAndPremium(
                    symbol = "ETH",
                    observedAt = Instant.parse("2024-01-02T00:00:00Z"),
                ),
            )

            // when
            val found = premiumRepository.findLatestBySymbol(Symbol("ETH"))

            // then
            assertThat(found).isNotNull
            assertThat(found!!.id).isEqualTo(ethPremium.id)
            assertThat(found.symbol.code).isEqualTo("ETH")
        }
    }

    @Nested
    @DisplayName("findAllBySymbolAndPeriod")
    inner class FindAllBySymbolAndPeriod {
        @Test
        fun `should return premiums within period`() {
            // given
            val p1 = premiumRepository.save(
                createTickersAndPremium(
                    symbol = "BTC",
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            )
            val p2 = premiumRepository.save(
                createTickersAndPremium(
                    symbol = "BTC",
                    observedAt = Instant.parse("2024-01-02T00:00:00Z"),
                ),
            )
            premiumRepository.save(
                createTickersAndPremium(
                    symbol = "BTC",
                    observedAt = Instant.parse("2024-01-03T00:00:00Z"),
                ),
            )

            // when
            val found = premiumRepository.findAllBySymbolAndPeriod(
                symbol = Symbol("BTC"),
                from = Instant.parse("2024-01-01T00:00:00Z"),
                to = Instant.parse("2024-01-02T00:00:00Z"),
            )

            // then
            assertThat(found).hasSize(2)
            assertThat(found.map { it.id }).containsExactly(p1.id, p2.id)
        }

        @Test
        fun `should return empty list when no premiums in period`() {
            // given
            premiumRepository.save(
                createTickersAndPremium(
                    symbol = "BTC",
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            )

            // when
            val found = premiumRepository.findAllBySymbolAndPeriod(
                symbol = Symbol("BTC"),
                from = Instant.parse("2024-02-01T00:00:00Z"),
                to = Instant.parse("2024-02-28T00:00:00Z"),
            )

            // then
            assertThat(found).isEmpty()
        }

        @Test
        fun `should filter by symbol`() {
            // given
            premiumRepository.save(
                createTickersAndPremium(
                    symbol = "BTC",
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            )
            premiumRepository.save(
                createTickersAndPremium(
                    symbol = "ETH",
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            )

            // when
            val found = premiumRepository.findAllBySymbolAndPeriod(
                symbol = Symbol("BTC"),
                from = Instant.parse("2024-01-01T00:00:00Z"),
                to = Instant.parse("2024-01-31T00:00:00Z"),
            )

            // then
            assertThat(found).hasSize(1)
            assertThat(found[0].symbol.code).isEqualTo("BTC")
        }

        @Test
        fun `should return premiums ordered by observedAt asc`() {
            // given
            premiumRepository.save(
                createTickersAndPremium(
                    symbol = "BTC",
                    observedAt = Instant.parse("2024-01-03T00:00:00Z"),
                ),
            )
            premiumRepository.save(
                createTickersAndPremium(
                    symbol = "BTC",
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            )
            premiumRepository.save(
                createTickersAndPremium(
                    symbol = "BTC",
                    observedAt = Instant.parse("2024-01-02T00:00:00Z"),
                ),
            )

            // when
            val found = premiumRepository.findAllBySymbolAndPeriod(
                symbol = Symbol("BTC"),
                from = Instant.parse("2024-01-01T00:00:00Z"),
                to = Instant.parse("2024-01-31T00:00:00Z"),
            )

            // then
            assertThat(found).hasSize(3)
            assertThat(found[0].observedAt).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))
            assertThat(found[1].observedAt).isEqualTo(Instant.parse("2024-01-02T00:00:00Z"))
            assertThat(found[2].observedAt).isEqualTo(Instant.parse("2024-01-03T00:00:00Z"))
        }
    }
}
