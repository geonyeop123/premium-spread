package io.premiumspread.cache

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class NotificationCooldownStore(
    private val redisTemplate: StringRedisTemplate,
) {

    fun tryAcquireCooldown(subscriptionId: Long): Boolean {
        val acquired = redisTemplate.opsForValue()
            .setIfAbsent(key(subscriptionId), "1", COOLDOWN)
        return acquired == true
    }

    fun release(subscriptionId: Long) {
        redisTemplate.delete(key(subscriptionId))
    }

    companion object {
        private val COOLDOWN: Duration = Duration.ofMinutes(60)
        private fun key(id: Long) = "notification:cooldown:$id"
    }
}
