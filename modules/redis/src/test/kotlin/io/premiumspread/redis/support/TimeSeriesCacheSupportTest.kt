package io.premiumspread.redis.support

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import java.time.Duration
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset

class TimeSeriesCacheSupportTest {

    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var zSetOps: ZSetOperations<String, String>
    private lateinit var sut: TimeSeriesCacheSupport

    @BeforeEach
    fun setUp() {
        redisTemplate = mockk(relaxed = true)
        zSetOps = mockk(relaxed = true)
        every { redisTemplate.opsForZSet() } returns zSetOps
        sut = TimeSeriesCacheSupport(redisTemplate, Clock.fixed(Instant.parse("2026-05-12T00:00:00Z"), ZoneOffset.UTC))
    }

    @Nested
    @DisplayName("add")
    inner class Add {

        @Test
        fun `동일 score 제거 후 새 값을 추가하고 TTL을 설정한다`() {
            // given
            val key = "test:key"
            val value = "test-value"
            val timestamp = Instant.ofEpochMilli(1000000L)
            val ttl = Duration.ofMinutes(5)

            // when
            sut.add(key, value, timestamp, ttl)

            // then
            val score = timestamp.toEpochMilli().toDouble()
            verify { zSetOps.removeRangeByScore(key, score, score) }
            verify { zSetOps.add(key, value, score) }
            verify { redisTemplate.expire(key, ttl) }
        }

        @Test
        fun `retention 기간 이전 데이터를 삭제한다`() {
            // given
            val key = "test:key"
            val value = "test-value"
            val timestamp = Instant.now()
            val ttl = Duration.ofMinutes(5)

            // when
            sut.add(key, value, timestamp, ttl)

            // then
            verify { zSetOps.removeRangeByScore(key, Double.NEGATIVE_INFINITY, 1_778_543_700_000.0) }
        }

        @Test
        fun `retentionPeriod를 별도로 지정할 수 있다`() {
            // given
            val key = "test:key"
            val value = "test-value"
            val timestamp = Instant.now()
            val ttl = Duration.ofMinutes(10)
            val retentionPeriod = Duration.ofMinutes(5)

            // when
            sut.add(key, value, timestamp, ttl, retentionPeriod)

            // then
            verify { redisTemplate.expire(key, ttl) }
            // retention cutoff 삭제 호출 확인
            verify { zSetOps.removeRangeByScore(key, Double.NEGATIVE_INFINITY, 1_778_543_700_000.0) }
        }
    }

    @Nested
    @DisplayName("rangeByTime")
    inner class RangeByTime {

        @Test
        fun `시간 범위를 from inclusive to exclusive로 조회한다`() {
            // given
            val key = "test:key"
            val from = Instant.ofEpochMilli(1000L)
            val to = Instant.ofEpochMilli(2000L)
            val tuple = mockk<ZSetOperations.TypedTuple<String>>()
            every { tuple.value } returns "test-value"
            every { tuple.score } returns 1500.0
            every {
                zSetOps.rangeByScoreWithScores(key, 1000.0, Math.nextDown(2000.0))
            } returns setOf(tuple)

            // when
            val result = sut.rangeByTime(key, from, to)

            // then
            assertThat(result).hasSize(1)
            assertThat(result[0].value).isEqualTo("test-value")
        }

        @Test
        fun `조회 결과가 null이면 빈 리스트를 반환한다`() {
            // given
            val key = "test:key"
            val from = Instant.ofEpochMilli(1000L)
            val to = Instant.ofEpochMilli(2000L)
            every {
                zSetOps.rangeByScoreWithScores(key, 1000.0, Math.nextDown(2000.0))
            } returns null

            // when
            val result = sut.rangeByTime(key, from, to)

            // then
            assertThat(result).isEmpty()
        }

        @Test
        fun `빈 범위나 역전 범위를 거부한다`() {
            val at = Instant.ofEpochMilli(1000L)

            assertThatThrownBy { sut.rangeByTime("test:key", at, at) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("from < to")
            assertThatThrownBy { sut.rangeByTime("test:key", at.plusMillis(1), at) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("from < to")
        }
    }

    @Nested
    @DisplayName("extractTimestamp")
    inner class ExtractTimestamp {

        @Test
        fun `score에서 Instant를 추출한다`() {
            // given
            val tuple = mockk<ZSetOperations.TypedTuple<String>>()
            every { tuple.score } returns 1700000000000.0

            // when
            val result = sut.extractTimestamp(tuple)

            // then
            assertThat(result).isEqualTo(Instant.ofEpochMilli(1700000000000L))
        }

        @Test
        fun `score가 null이면 null을 반환한다`() {
            // given
            val tuple = mockk<ZSetOperations.TypedTuple<String>>()
            every { tuple.score } returns null

            // when
            val result = sut.extractTimestamp(tuple)

            // then
            assertThat(result).isNull()
        }
    }
}
