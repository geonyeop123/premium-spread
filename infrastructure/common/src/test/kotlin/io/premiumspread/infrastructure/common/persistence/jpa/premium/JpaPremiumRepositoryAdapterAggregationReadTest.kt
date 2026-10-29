package io.premiumspread.infrastructure.common.persistence.jpa.premium

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.aggregation.AggregationUnit
import io.premiumspread.domain.aggregation.AggregationWindow
import io.premiumspread.domain.aggregation.AggregationZone
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.infrastructure.common.cache.premium.PremiumAggregationCacheReader
import io.premiumspread.infrastructure.common.persistence.jdbc.premium.PremiumAggregationQueryRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class JpaPremiumRepositoryAdapterAggregationReadTest {
    private val pair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE)
    private val otherPair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
    private val from = Instant.parse("2026-10-28T00:00:00Z")
    private val to = Instant.parse("2026-10-29T00:00:00Z")
    private val window = AggregationWindow(from, to, AggregationZone.DEFAULT)

    private val cacheReader = mockk<PremiumAggregationCacheReader>()
    private val queryRepository = mockk<PremiumAggregationQueryRepository>()

    private fun adapter(cache: PremiumAggregationCacheReader? = cacheReader) = JpaPremiumRepositoryAdapter(
        premiumRepository = mockk<SpringDataPremiumRepository>(),
        premiumCacheReader = null,
        premiumAggregationCacheReader = cache,
        premiumAggregationQueryRepository = queryRepository,
    )

    private fun snapshot(target: MarketPair, close: String, observedAt: Instant = from) = PremiumAggregationSnapshot(
        pair = target,
        high = BigDecimal("3.00"),
        low = BigDecimal("1.00"),
        open = BigDecimal("2.00"),
        close = BigDecimal(close),
        avg = BigDecimal("2.00"),
        count = 60,
        observedAt = observedAt,
    )

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `단위를 interval 문자열로 바꾸고 window 의 from 과 to 를 그대로 넘긴다`() {
        val expected = mapOf(
            AggregationUnit.MINUTE to "1m",
            AggregationUnit.HOUR to "1h",
            AggregationUnit.DAY to "1d",
        )
        every { cacheReader.findByInterval(any(), any(), any(), any()) } returns emptyList()
        every { queryRepository.findByInterval(any(), any(), any(), any()) } returns emptyList()

        expected.forEach { (unit, interval) ->
            adapter().read(pair, window, unit)

            verify(exactly = 1) { cacheReader.findByInterval(pair, interval, from, to) }
            verify(exactly = 1) { queryRepository.findByInterval(pair, interval, from, to) }
        }
    }

    @Test
    fun `cache 와 DB 가 같은 observedAt 이면 DB 값을 낸다`() {
        every { cacheReader.findByInterval(pair, "1m", from, to) } returns listOf(snapshot(pair, "9.99"))
        every { queryRepository.findByInterval(pair, "1m", from, to) } returns listOf(snapshot(pair, "2.50"))

        val result = adapter().read(pair, window, AggregationUnit.MINUTE)

        assertThat(result).hasSize(1)
        assertThat(result.single().close).isEqualByComparingTo("2.50")
    }

    @Test
    fun `방어 - 반환 snapshot 의 pair 가 요청과 다르면 예외를 던진다`() {
        every { cacheReader.findByInterval(any(), any(), any(), any()) } returns emptyList()
        every { queryRepository.findByInterval(pair, "1h", from, to) } returns
            listOf(snapshot(pair, "2.50"), snapshot(otherPair, "2.60", from.plusSeconds(3600)))

        assertThatThrownBy { adapter().read(pair, window, AggregationUnit.HOUR) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("premium aggregation pair mismatch")
    }

    @Test
    fun `cache reader 가 null 이면 DB 결과만 낸다`() {
        every { queryRepository.findByInterval(pair, "1d", from, to) } returns listOf(snapshot(pair, "2.50"))

        val result = adapter(cache = null).read(pair, window, AggregationUnit.DAY)

        assertThat(result).containsExactly(snapshot(pair, "2.50"))
        verify(exactly = 0) { cacheReader.findByInterval(any(), any(), any(), any()) }
    }
}
