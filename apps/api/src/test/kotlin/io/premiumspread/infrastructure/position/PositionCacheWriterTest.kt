package io.premiumspread.infrastructure.position

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

@DisplayName("PositionCacheWriter")
class PositionCacheWriterTest {

    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var valueOps: ValueOperations<String, String>
    private lateinit var positionCacheWriter: PositionCacheWriter

    @BeforeEach
    fun setUp() {
        valueOps = mockk(relaxed = true)
        redisTemplate = mockk()
        every { redisTemplate.opsForValue() } returns valueOps
        positionCacheWriter = PositionCacheWriter(redisTemplate)
    }

    @Nested
    @DisplayName("updateOpenPositionStatus")
    inner class UpdateOpenPositionStatus {

        @Test
        fun `exists와 count를 각각 Redis에 저장한다`() {
            positionCacheWriter.updateOpenPositionStatus(exists = true, count = 3)

            verify { valueOps.set("position:open:exists", "true", any<Duration>()) }
            verify { valueOps.set("position:open:count", "3", any<Duration>()) }
        }

        @Test
        fun `포지션이 없으면 false와 0을 저장한다`() {
            positionCacheWriter.updateOpenPositionStatus(exists = false, count = 0)

            verify { valueOps.set("position:open:exists", "false", any<Duration>()) }
            verify { valueOps.set("position:open:count", "0", any<Duration>()) }
        }
    }

    @Nested
    @DisplayName("hasOpenPosition")
    inner class HasOpenPosition {

        @Test
        fun `캐시 값이 true이면 true를 반환한다`() {
            every { valueOps.get("position:open:exists") } returns "true"

            assertThat(positionCacheWriter.hasOpenPosition()).isTrue()
        }

        @Test
        fun `캐시 값이 false이면 false를 반환한다`() {
            every { valueOps.get("position:open:exists") } returns "false"

            assertThat(positionCacheWriter.hasOpenPosition()).isFalse()
        }

        @Test
        fun `캐시가 없으면 false를 반환한다`() {
            every { valueOps.get("position:open:exists") } returns null

            assertThat(positionCacheWriter.hasOpenPosition()).isFalse()
        }
    }

    @Nested
    @DisplayName("getOpenPositionCount")
    inner class GetOpenPositionCount {

        @Test
        fun `캐시 값이 숫자이면 해당 값을 반환한다`() {
            every { valueOps.get("position:open:count") } returns "5"

            assertThat(positionCacheWriter.getOpenPositionCount()).isEqualTo(5)
        }

        @Test
        fun `캐시가 없으면 0을 반환한다`() {
            every { valueOps.get("position:open:count") } returns null

            assertThat(positionCacheWriter.getOpenPositionCount()).isEqualTo(0)
        }

        @Test
        fun `캐시 값이 숫자가 아니면 0을 반환한다`() {
            every { valueOps.get("position:open:count") } returns "invalid"

            assertThat(positionCacheWriter.getOpenPositionCount()).isEqualTo(0)
        }
    }
}
