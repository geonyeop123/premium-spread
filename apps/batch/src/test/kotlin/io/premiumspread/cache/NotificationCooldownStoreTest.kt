package io.premiumspread.cache

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

class NotificationCooldownStoreTest {

    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
    private val redisTemplate = mockk<StringRedisTemplate>(relaxed = true) {
        every { opsForValue() } returns valueOps
    }
    private val sut = NotificationCooldownStore(redisTemplate)

    @Test
    fun `tryAcquireCooldown은 키가 없을 때 setIfAbsent로 60분 TTL을 설정하고 true를 반환한다`() {
        val key = slot<String>()
        val ttl = slot<Duration>()
        every { valueOps.setIfAbsent(capture(key), any(), capture(ttl)) } returns true

        val result = sut.tryAcquireCooldown(42L)

        assertThat(result).isTrue()
        assertThat(key.captured).isEqualTo("notification:cooldown:42")
        assertThat(ttl.captured).isEqualTo(Duration.ofMinutes(60))
        verify(exactly = 1) { valueOps.setIfAbsent(any(), any(), any<Duration>()) }
    }

    @Test
    fun `tryAcquireCooldown은 이미 키가 있으면 false를 반환한다`() {
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns false
        assertThat(sut.tryAcquireCooldown(42L)).isFalse()
    }

    @Test
    fun `tryAcquireCooldown은 setIfAbsent가 null을 반환해도 false로 처리한다`() {
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns null
        assertThat(sut.tryAcquireCooldown(42L)).isFalse()
    }

    @Test
    fun `release는 키를 삭제한다`() {
        val key = slot<String>()
        every { redisTemplate.delete(capture(key)) } returns true

        sut.release(42L)

        assertThat(key.captured).isEqualTo("notification:cooldown:42")
        verify(exactly = 1) { redisTemplate.delete("notification:cooldown:42") }
    }
}
