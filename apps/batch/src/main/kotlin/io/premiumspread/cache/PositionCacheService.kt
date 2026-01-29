package io.premiumspread.cache

import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class PositionCacheService(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 열린 포지션 존재 여부 확인
     */
    fun hasOpenPosition(): Boolean {
        val key = RedisKeyGenerator.positionOpenExistsKey()
        return redisTemplate.opsForValue().get(key) == "true"
    }

    /**
     * 열린 포지션 수 조회
     */
    fun getOpenPositionCount(): Int {
        val key = RedisKeyGenerator.positionOpenCountKey()
        return redisTemplate.opsForValue().get(key)?.toIntOrNull() ?: 0
    }

    /**
     * 열린 포지션 정보 갱신 (API 서버에서 호출)
     */
    fun updateOpenPositionStatus(exists: Boolean, count: Int) {
        redisTemplate.opsForValue().set(
            RedisKeyGenerator.positionOpenExistsKey(),
            exists.toString(),
            RedisTtl.POSITION,
        )
        redisTemplate.opsForValue().set(
            RedisKeyGenerator.positionOpenCountKey(),
            count.toString(),
            RedisTtl.POSITION,
        )
        log.debug("Updated position cache: exists={}, count={}", exists, count)
    }
}
