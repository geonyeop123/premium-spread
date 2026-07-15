package io.premiumspread.infrastructure.common.cache.premium

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.common.cache.AfterCommitCacheExecutor
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.support.TimeSeriesCacheSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@Tag("integration")
@Testcontainers
class PremiumCacheTransactionIntegrationTest {
    private val dataSource = DriverManagerDataSource(
        mysql.jdbcUrl + "?sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        mysql.username,
        mysql.password,
    )
    private val jdbcTemplate = JdbcTemplate(dataSource)
    private val transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))
    private val redisConnectionFactory = LettuceConnectionFactory(
        RedisStandaloneConfiguration(redis.host, redis.firstMappedPort),
    ).also { it.afterPropertiesSet() }
    private val redisTemplate = StringRedisTemplate(redisConnectionFactory).also { it.afterPropertiesSet() }
    private val writer = PremiumCacheWriter(
        redisTemplate,
        TimeSeriesCacheSupport(redisTemplate, Clock.fixed(OBSERVED_AT, ZoneOffset.UTC)),
        AfterCommitCacheExecutor(),
    )

    @BeforeEach
    fun setUp() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS cache_tx_probe (id BIGINT PRIMARY KEY)")
        jdbcTemplate.update("DELETE FROM cache_tx_probe")
        redisTemplate.connectionFactory?.connection?.serverCommands()?.flushAll()
    }

    @Test
    fun `DB transaction rollback이면 DB와 Redis 모두 변경되지 않는다`() {
        assertThatThrownBy {
            transactionTemplate.executeWithoutResult {
                jdbcTemplate.update("INSERT INTO cache_tx_probe(id) VALUES (1)")
                writer.saveAfterCommit(snapshot())
                error("rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(rowCount()).isZero()
        assertThat(redisTemplate.hasKey(cacheKey())).isFalse()
    }

    @Test
    fun `DB transaction commit 이후에만 Redis를 갱신한다`() {
        transactionTemplate.executeWithoutResult {
            jdbcTemplate.update("INSERT INTO cache_tx_probe(id) VALUES (1)")
            writer.saveAfterCommit(snapshot())
            assertThat(redisTemplate.hasKey(cacheKey())).isFalse()
        }

        assertThat(rowCount()).isEqualTo(1)
        assertThat(redisTemplate.hasKey(cacheKey())).isTrue()
    }

    private fun rowCount(): Int = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cache_tx_probe", Int::class.java)!!

    private fun cacheKey(): String = RedisKeyGenerator.premiumV2Key("BITHUMB", "BINANCE", "BTC")

    private fun snapshot() = PremiumSnapshot(
        pair = MarketPair.default(Symbol("BTC")),
        premiumRate = BigDecimal("1.50"),
        koreaPrice = BigDecimal("129555000"),
        foreignPrice = BigDecimal("89277.10"),
        foreignPriceInKrw = BigDecimal("127894943.46"),
        fxRate = BigDecimal("1432.60"),
        observedAt = OBSERVED_AT,
        fxObservedAt = OBSERVED_AT.minusSeconds(1),
    )

    companion object {
        private val OBSERVED_AT: Instant = Instant.parse("2026-07-14T00:00:00Z")

        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread")
            .withUsername("test")
            .withPassword("test")

        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)

    }
}
