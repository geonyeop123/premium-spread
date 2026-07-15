package io.premiumspread.infrastructure.premium

import io.premiumspread.domain.premium.Premium
import io.premiumspread.domain.premium.PremiumRepository
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.Ticker
import io.premiumspread.domain.ticker.TickerRepository
import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.Instant

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, io.premiumspread.config.TestConfig::class)
class PremiumRepositoryTest @Autowired constructor(
    private val premiumRepository: PremiumRepository,
    private val tickerRepository: TickerRepository,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisTemplate: StringRedisTemplate,
) {

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        redisTemplate.delete(RedisKeyGenerator.premiumKey("btc"))
    }

    private fun createTickersAndPremium(
        symbol: String = "BTC",
        koreaPrice: BigDecimal = BigDecimal("129555000"),
        foreignPrice: BigDecimal = BigDecimal("89277"),
        fxRate: BigDecimal = BigDecimal("1432.6"),
        observedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
        koreaExchange: Exchange = Exchange.BITHUMB,
    ): Premium {
        val koreaTicker = tickerRepository.save(
            Ticker.create(
                exchange = koreaExchange,
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

        @Test
        fun `soft-deleted premium은 ID로 조회되지 않는다`() {
            val saved = premiumRepository.save(createTickersAndPremium())
            saved.delete(Instant.parse("2026-07-14T03:00:00Z"))
            premiumRepository.save(saved)

            assertThat(premiumRepository.findById(saved.id)).isNull()
        }
    }

    @Nested
    @DisplayName("findLatestBySymbol")
    inner class FindLatestBySymbol {
        @Test
        fun `Batch v2 hash metadata를 API가 동일 pair와 UTC Instant로 읽는다`() {
            val key = RedisKeyGenerator.premiumV2Key("UPBIT", "BINANCE", "btc")
            redisTemplate.opsForHash<String, String>().putAll(
                key,
                mapOf(
                    "schema_version" to "2",
                    "symbol" to "BTC",
                    "rate" to "1.2350",
                    "korea_price" to "101000",
                    "foreign_price" to "100",
                    "foreign_price_krw" to "100000",
                    "fx_rate" to "1000",
                    "observed_at" to "1704067200123",
                    "korea_exchange" to "UPBIT",
                    "foreign_exchange" to "BINANCE",
                    "fx_source" to "FX_PROVIDER",
                    "fx_observed_at" to "1704067140123",
                ),
            )
            val pair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)

            val snapshot = premiumRepository.findLatestSnapshotByPair(pair)!!

            assertThat(snapshot.pair).isEqualTo(pair)
            assertThat(snapshot.observedAt).isEqualTo(Instant.ofEpochMilli(1_704_067_200_123L))
            assertThat(snapshot.fxObservedAt).isEqualTo(Instant.ofEpochMilli(1_704_067_140_123L))
            assertThat(snapshot.premiumRate).isEqualByComparingTo("1.2350")
        }

        @Test
        fun `동일 BTC의 BITHUMB와 UPBIT premium은 latest snapshot history가 pair별로 분리된다`() {
            val bithumb = premiumRepository.save(
                createTickersAndPremium(
                    koreaExchange = Exchange.BITHUMB,
                    koreaPrice = BigDecimal("101000"),
                    observedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            )
            val upbit = premiumRepository.save(
                createTickersAndPremium(
                    koreaExchange = Exchange.UPBIT,
                    koreaPrice = BigDecimal("102000"),
                    observedAt = Instant.parse("2024-01-01T00:01:00Z"),
                ),
            )
            val bithumbPair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE)
            val upbitPair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
            val from = Instant.parse("2024-01-01T00:00:00Z")
            val to = Instant.parse("2024-01-01T00:02:00Z")

            assertThat(premiumRepository.findLatestByPair(bithumbPair)!!.id).isEqualTo(bithumb.id)
            assertThat(premiumRepository.findLatestByPair(upbitPair)!!.id).isEqualTo(upbit.id)
            assertThat(premiumRepository.findLatestSnapshotByPair(bithumbPair)!!.pair).isEqualTo(bithumbPair)
            assertThat(premiumRepository.findLatestSnapshotByPair(upbitPair)!!.pair).isEqualTo(upbitPair)
            assertThat(premiumRepository.findAllByPair(bithumbPair, from, to).map { it.id })
                .containsExactly(bithumb.id)
            assertThat(premiumRepository.findAllByPair(upbitPair, from, to).map { it.id })
                .containsExactly(upbit.id)
        }

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
            val found = premiumRepository.findLatestByPair(MarketPair.default(Symbol("BTC")))

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
            val found = premiumRepository.findLatestByPair(MarketPair.default(Symbol("ETH")))

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
            val found = premiumRepository.findLatestByPair(MarketPair.default(Symbol("ETH")))

            // then
            assertThat(found).isNotNull
            assertThat(found!!.id).isEqualTo(ethPremium.id)
            assertThat(found.symbol.code).isEqualTo("ETH")
        }

        @Test
        fun `latest와 snapshot 조회는 soft-deleted premium을 제외한다`() {
            val pair = MarketPair.default(Symbol("BTC"))
            redisTemplate.delete(RedisKeyGenerator.premiumV2Key("BITHUMB", "BINANCE", "BTC"))
            val active = premiumRepository.save(
                createTickersAndPremium(observedAt = Instant.parse("2024-01-01T00:00:00Z")),
            )
            val deleted = premiumRepository.save(
                createTickersAndPremium(observedAt = Instant.parse("2024-01-02T00:00:00Z")),
            )
            deleted.delete(Instant.parse("2026-07-14T03:00:00Z"))
            premiumRepository.save(deleted)

            val latest = premiumRepository.findLatestByPair(pair)
            val snapshot = premiumRepository.findLatestSnapshotByPair(pair)

            assertThat(latest?.id).isEqualTo(active.id)
            assertThat(snapshot?.observedAt).isEqualTo(active.observedAt)
        }
    }

    @Nested
    @DisplayName("findAllBySymbolAndPeriod")
    inner class FindAllBySymbolAndPeriod {
        @Test
        fun `기간 조회는 from inclusive to exclusive를 적용한다`() {
            // given
            val p1 = premiumRepository.save(
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
            premiumRepository.save(
                createTickersAndPremium(
                    symbol = "BTC",
                    observedAt = Instant.parse("2024-01-03T00:00:00Z"),
                ),
            )

            // when
            val found = premiumRepository.findAllByPair(
                pair = MarketPair.default(Symbol("BTC")),
                from = Instant.parse("2024-01-01T00:00:00Z"),
                to = Instant.parse("2024-01-02T00:00:00Z"),
            )

            // then
            assertThat(found).hasSize(1)
            assertThat(found.map { it.id }).containsExactly(p1.id)
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
            val found = premiumRepository.findAllByPair(
                pair = MarketPair.default(Symbol("BTC")),
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
            val found = premiumRepository.findAllByPair(
                pair = MarketPair.default(Symbol("BTC")),
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
            val found = premiumRepository.findAllByPair(
                pair = MarketPair.default(Symbol("BTC")),
                from = Instant.parse("2024-01-01T00:00:00Z"),
                to = Instant.parse("2024-01-31T00:00:00Z"),
            )

            // then
            assertThat(found).hasSize(3)
            assertThat(found[0].observedAt).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))
            assertThat(found[1].observedAt).isEqualTo(Instant.parse("2024-01-02T00:00:00Z"))
            assertThat(found[2].observedAt).isEqualTo(Instant.parse("2024-01-03T00:00:00Z"))
        }

        @Test
        fun `pair 기간 조회는 soft-deleted premium을 제외한다`() {
            val active = premiumRepository.save(
                createTickersAndPremium(observedAt = Instant.parse("2024-01-01T00:00:00Z")),
            )
            val deleted = premiumRepository.save(
                createTickersAndPremium(observedAt = Instant.parse("2024-01-02T00:00:00Z")),
            )
            deleted.delete(Instant.parse("2026-07-14T03:00:00Z"))
            premiumRepository.save(deleted)

            val found = premiumRepository.findAllByPair(
                pair = MarketPair.default(Symbol("BTC")),
                from = Instant.parse("2024-01-01T00:00:00Z"),
                to = Instant.parse("2024-01-03T00:00:00Z"),
            )

            assertThat(found.map(Premium::id)).containsExactly(active.id)
        }
    }
}
