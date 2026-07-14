package io.premiumspread.infrastructure.common.cache

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.infrastructure.common.cache.exchangerate.FxCacheReader
import io.premiumspread.infrastructure.common.cache.ticker.TickerCacheReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.RedisConnectionFailureException

class CachePayloadReaderTest {
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val hashOps = mockk<HashOperations<String, String, String>>()
    private val metrics = mockk<CacheReadMetrics>(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForHash<String, String>() } returns hashOps
    }

    @Test
    fun `ticker timestamp parse 실패는 현재 시각을 합성하지 않고 corrupt 처리한다`() {
        every { hashOps.entries("ticker:bithumb:btc") } returns mapOf(
            "schema_version" to "2",
            "exchange" to "BITHUMB",
            "symbol" to "BTC",
            "currency" to "KRW",
            "price" to "100",
            "volume" to "",
            "timestamp" to "not-an-epoch",
        )

        assertThat(TickerCacheReader(redisTemplate, metrics).get("bithumb", "btc")).isNull()
        verify { metrics.record("ticker", CacheReadOutcome.CORRUPT) }
    }

    @Test
    fun `FX payload identity와 source를 검증한다`() {
        every { hashOps.entries("fx:usd:krw") } returns mapOf(
            "schema_version" to "2",
            "base" to "USD",
            "quote" to "KRW",
            "rate" to "1432.6",
            "timestamp" to "1706500000000",
            "source" to "FX_PROVIDER",
        )

        val result = FxCacheReader(redisTemplate, metrics).get("usd", "krw")

        assertThat(result?.source).isEqualTo(Exchange.FX_PROVIDER)
        verify { metrics.record("fx", CacheReadOutcome.HIT) }
    }

    @Test
    fun `FX timestamp parse 실패는 데이터를 폐기하고 corrupt metric을 기록한다`() {
        every { hashOps.entries("fx:usd:krw") } returns mapOf(
            "schema_version" to "2",
            "base" to "USD",
            "quote" to "KRW",
            "rate" to "1432.6",
            "timestamp" to "not-an-epoch",
            "source" to "FX_PROVIDER",
        )

        assertThat(FxCacheReader(redisTemplate, metrics).get("usd", "krw")).isNull()
        verify { metrics.record("fx", CacheReadOutcome.CORRUPT) }
    }

    @Test
    fun `FX key와 payload currency가 다르면 corrupt 처리한다`() {
        every { hashOps.entries("fx:usd:krw") } returns mapOf(
            "base" to "EUR",
            "quote" to "KRW",
            "rate" to "1432.6",
            "timestamp" to "1706500000000",
        )

        assertThat(FxCacheReader(redisTemplate, metrics).get("usd", "krw")).isNull()
        verify { metrics.record("fx", CacheReadOutcome.CORRUPT) }
    }

    @Test
    fun `Redis 장애는 ticker cache error로 기록하고 fallback을 위해 null을 반환한다`() {
        every { hashOps.entries("ticker:bithumb:btc") } throws RedisConnectionFailureException("down")

        assertThat(TickerCacheReader(redisTemplate, metrics).get("bithumb", "btc")).isNull()
        verify { metrics.record("ticker", CacheReadOutcome.ERROR) }
    }

    @Test
    fun `Redis 장애는 FX cache error로 기록하고 DB fallback을 차단하지 않는다`() {
        every { hashOps.entries("fx:usd:krw") } throws RedisConnectionFailureException("down")

        assertThat(FxCacheReader(redisTemplate, metrics).get("usd", "krw")).isNull()
        verify { metrics.record("fx", CacheReadOutcome.ERROR) }
    }
}
