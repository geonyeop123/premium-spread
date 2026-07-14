package io.premiumspread.cache

import io.premiumspread.client.TickerData
import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.math.BigDecimal
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset

/**
 * `saveToSecondsWithScore` 통합 검증.
 *
 * - ZSet 멤버 포맷 `{epochMs}:{price}`로 동일 가격이 여러 score로 저장돼도 distinct entries 누적
 *   되는지 검증 (Phase 3 flat-price 회귀 방지).
 */
@DisplayName("TickerCacheService — saveToSecondsWithScore")
@Import(TickerCacheServiceScoreTest.FixedClockConfig::class)
class TickerCacheServiceScoreTest : BatchIntegrationTestBase() {

    @TestConfiguration
    class FixedClockConfig {
        @Bean
        @Primary
        fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-05-12T00:00:10Z"), ZoneOffset.UTC)
    }

    @Autowired
    lateinit var tickerCacheService: TickerCacheService

    private val fixedNow = Instant.parse("2026-05-12T00:00:10Z")

    @Test
    fun `saveToSecondsWithScore는 ticker timestamp가 아닌 명시 score를 ZSet에 저장한다`() {
        val ticker = TickerData(
            exchange = "BITHUMB",
            symbol = "BTC",
            currency = "KRW",
            price = BigDecimal("100000000"),
            volume = null,
            timestamp = Instant.parse("2026-05-12T00:00:00Z"),
        )
        val score = Instant.parse("2026-05-12T00:00:05Z")

        tickerCacheService.saveToSecondsWithScore(ticker, score)

        val results = tickerCacheService.getSecondsData(
            ticker.exchange, ticker.symbol,
            score.minusSeconds(1), score.plusSeconds(1),
        )
        assertThat(results).hasSize(1)
        assertThat(results.first().first).isEqualTo(score)
        assertThat(results.first().second).isEqualByComparingTo(BigDecimal("100000000"))
    }

    @Test
    fun `동일 가격을 다른 score로 5회 저장하면 ZSet에 5 distinct entries가 누적된다 (flat-price 회귀 방지)`() {
        val baseTimestamp = Instant.parse("2026-05-12T00:00:00Z")
        val price = BigDecimal("100000000")
        val ticker = TickerData(
            exchange = "BITHUMB",
            symbol = "BTC",
            currency = "KRW",
            price = price,
            volume = null,
            timestamp = baseTimestamp,
        )

        // 1초 간격으로 5회 flush — 같은 가격이라도 ZSet 멤버는 epochMs:price로 unique
        val scores = (0..4).map { baseTimestamp.plusSeconds(it.toLong()) }
        scores.forEach { tickerCacheService.saveToSecondsWithScore(ticker, it) }

        val results = tickerCacheService.getSecondsData(
            ticker.exchange, ticker.symbol,
            baseTimestamp.minusSeconds(1), baseTimestamp.plusSeconds(10),
        )
        assertThat(results).hasSize(5)
        assertThat(results.map { it.first }).containsExactlyElementsOf(scores)
        assertThat(results).allSatisfy { (_, p) ->
            assertThat(p).isEqualByComparingTo(price)
        }
    }

    @Test
    fun `retention 경계보다 오래된 데이터는 다음 저장 시 제거된다`() {
        val ticker = tickerAt(fixedNow.minusSeconds(301))
        tickerCacheService.saveToSecondsWithScore(ticker, ticker.timestamp)
        tickerCacheService.saveToSecondsWithScore(tickerAt(fixedNow), fixedNow)

        val results = tickerCacheService.getSecondsData(
            "BITHUMB",
            "BTC",
            fixedNow.minusSeconds(600),
            fixedNow.plusMillis(1),
        )

        assertThat(results.map { it.first }).containsExactly(fixedNow)
    }

    @Test
    fun `retention 경계 시각은 inclusive 정리 정책에 따라 제거된다`() {
        val boundary = fixedNow.minusSeconds(300)
        tickerCacheService.saveToSecondsWithScore(tickerAt(boundary), boundary)
        tickerCacheService.saveToSecondsWithScore(tickerAt(fixedNow), fixedNow)

        val results = tickerCacheService.getSecondsData("BITHUMB", "BTC", boundary, fixedNow.plusMillis(1))

        assertThat(results.map { it.first }).containsExactly(fixedNow)
    }

    private fun tickerAt(timestamp: Instant) = TickerData(
        exchange = "BITHUMB",
        symbol = "BTC",
        currency = "KRW",
        price = BigDecimal("100000000"),
        volume = null,
        timestamp = timestamp,
    )
}
