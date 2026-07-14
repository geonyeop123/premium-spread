package io.premiumspread.infrastructure.premium

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.zset.DefaultTuple
import org.springframework.data.redis.core.DefaultTypedTuple
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import java.time.Instant

@DisplayName("PremiumAggregationCacheReader")
class PremiumAggregationCacheReaderTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val zSetOps = mockk<ZSetOperations<String, String>>()
    private val cacheReader = PremiumAggregationCacheReader(redisTemplate)

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForZSet() } returns zSetOps
    }

    @Nested
    @DisplayName("findByInterval")
    inner class FindByInterval {

        @Test
        fun `캐시에 데이터가 있으면 PremiumAggregationSnapshot 리스트 반환`() {
            // given
            val from = Instant.parse("2026-03-03T10:00:00Z")
            val to = Instant.parse("2026-03-03T10:05:00Z")
            val score = Instant.parse("2026-03-03T10:01:00Z").toEpochMilli().toDouble()

            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns setOf(
                DefaultTypedTuple("1.50:1.00:1.20:1.30:1.25:10", score),
            )

            // when
            val result = cacheReader.findByInterval("btc", "1m", from, to)

            // then
            assertThat(result).hasSize(1)
            assertThat(result!![0].high).isEqualByComparingTo("1.50")
            assertThat(result[0].low).isEqualByComparingTo("1.00")
            assertThat(result[0].open).isEqualByComparingTo("1.20")
            assertThat(result[0].close).isEqualByComparingTo("1.30")
            assertThat(result[0].avg).isEqualByComparingTo("1.25")
            assertThat(result[0].count).isEqualTo(10)
            assertThat(result[0].symbol).isEqualTo("BTC")
        }

        @Test
        fun `FX rate가 없는 writer payload의 trailing blank를 null로 읽는다`() {
            val from = Instant.parse("2026-03-03T10:00:00Z")
            val to = Instant.parse("2026-03-03T10:05:00Z")
            val score = Instant.parse("2026-03-03T10:01:00Z").toEpochMilli().toDouble()
            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns setOf(
                DefaultTypedTuple("1.50:1.00:1.20:1.30:1.25:10:", score),
            )

            val result = cacheReader.findByInterval("btc", "1m", from, to)

            assertThat(result).hasSize(1)
            assertThat(result!![0].fxRate).isNull()
        }

        @Test
        fun `조회 범위는 to 경계를 제외하는 반개구간이다`() {
            val from = Instant.parse("2026-03-03T10:00:00Z")
            val to = Instant.parse("2026-03-03T10:05:00Z")
            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns emptySet()

            cacheReader.findByInterval("btc", "1m", from, to)

            verify {
                zSetOps.rangeByScoreWithScores(
                    "premium:minutes:btc",
                    from.toEpochMilli().toDouble(),
                    Math.nextDown(to.toEpochMilli().toDouble()),
                )
            }
        }

        @Test
        fun `from과 to가 같거나 역전되면 조회를 거부한다`() {
            val boundary = Instant.parse("2026-03-03T10:00:00Z")

            assertThatThrownBy { cacheReader.findByInterval("btc", "1m", boundary, boundary) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy {
                cacheReader.findByInterval("btc", "1m", boundary.plusSeconds(1), boundary)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `캐시가 비어있으면 null 반환`() {
            // given
            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns emptySet()
            val from = Instant.parse("2026-03-03T10:00:00Z")
            val to = Instant.parse("2026-03-03T10:01:00Z")

            // when
            val result = cacheReader.findByInterval("btc", "1m", from, to)

            // then
            assertThat(result).isNull()
        }

        @Test
        fun `캐시가 null이면 null 반환`() {
            // given
            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns null
            val from = Instant.parse("2026-03-03T10:00:00Z")
            val to = Instant.parse("2026-03-03T10:01:00Z")

            // when
            val result = cacheReader.findByInterval("btc", "1m", from, to)

            // then
            assertThat(result).isNull()
        }

        @Test
        fun `일부 entry가 손상되면 부분 응답 대신 cache miss로 처리한다`() {
            // given
            val score = Instant.now().toEpochMilli().toDouble()
            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns setOf(
                DefaultTypedTuple("invalid:data", score),
                DefaultTypedTuple("1.5:1.0:1.2:1.3:1.25:10", score),
            )

            // when
            val result = cacheReader.findByInterval("btc", "1h", Instant.now().minusSeconds(3600), Instant.now())

            // then
            assertThat(result).isNull()
        }

        @Test
        fun `지원하지 않는 interval이면 null 반환`() {
            val from = Instant.parse("2026-03-03T10:00:00Z")
            val to = Instant.parse("2026-03-03T10:01:00Z")

            // when
            val result = cacheReader.findByInterval("btc", "5m", from, to)

            // then
            assertThat(result).isNull()
        }

        @Test
        fun `1m interval은 MINUTES 키를 사용한다`() {
            // given
            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns emptySet()

            // when
            cacheReader.findByInterval("btc", "1m", Instant.now().minusSeconds(60), Instant.now())

            // then
            verify { zSetOps.rangeByScoreWithScores("premium:minutes:btc", any(), any()) }
        }

        @Test
        fun `1h interval은 HOURS 키를 사용한다`() {
            // given
            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns emptySet()

            // when
            cacheReader.findByInterval("btc", "1h", Instant.now().minusSeconds(3600), Instant.now())

            // then
            verify { zSetOps.rangeByScoreWithScores("premium:hours:btc", any(), any()) }
        }

        @Test
        fun `1d interval은 DAYS 키를 사용한다`() {
            // given
            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns emptySet()

            // when
            cacheReader.findByInterval("btc", "1d", Instant.now().minusSeconds(86400), Instant.now())

            // then
            verify { zSetOps.rangeByScoreWithScores("premium:days:btc", any(), any()) }
        }
    }
}
