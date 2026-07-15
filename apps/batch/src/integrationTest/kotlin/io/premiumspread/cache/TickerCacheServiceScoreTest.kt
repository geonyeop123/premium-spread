package io.premiumspread.cache

import io.premiumspread.infrastructure.batch.cache.TickerCacheService
import io.premiumspread.infrastructure.batch.exchange.TickerData
import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@Import(TickerCacheServiceScoreTest.FixedClockConfig::class)
class TickerCacheServiceScoreTest : BatchIntegrationTestBase() {
    @TestConfiguration
    class FixedClockConfig {
        @Bean
        @Primary
        fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-05-12T00:00:10Z"), ZoneOffset.UTC)
    }

    @Autowired
    lateinit var service: TickerCacheService

    @Test
    fun `same exchange tick flushed at different sample times remains distinct`() {
        val tick = ticker(Instant.parse("2026-05-12T00:00:00Z"))
        val scores = (0..4).map { tick.timestamp.plusSeconds(it.toLong()) }

        scores.forEach { service.saveToSecondsWithScore(tick, it) }

        val results = service.getSecondsData("BITHUMB", "BTC", tick.timestamp.minusSeconds(1), tick.timestamp.plusSeconds(10))
        assertThat(results).hasSize(5)
        assertThat(results.map { it.first }).containsExactlyElementsOf(scores)
    }

    @Test
    fun `retention boundary and older samples are removed on the next flush`() {
        val now = Instant.parse("2026-05-12T00:00:10Z")
        service.saveToSecondsWithScore(ticker(now.minusSeconds(301)), now.minusSeconds(301))
        service.saveToSecondsWithScore(ticker(now.minusSeconds(300)), now.minusSeconds(300))
        service.saveToSecondsWithScore(ticker(now), now)

        val results = service.getSecondsData("BITHUMB", "BTC", now.minusSeconds(600), now.plusMillis(1))
        assertThat(results.map { it.first }).containsExactly(now)
    }

    private fun ticker(timestamp: Instant) = TickerData(
        exchange = "BITHUMB",
        symbol = "BTC",
        currency = "KRW",
        price = BigDecimal("100000000"),
        volume = null,
        timestamp = timestamp,
    )
}
