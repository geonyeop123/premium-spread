package io.premiumspread.infrastructure.cache

import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * 포지션 캐시 Writer (API 서버용)
 *
 * API 서버에서 포지션 상태를 캐시에 기록하여
 * 배치 서버가 조건부 캐싱 정책을 적용할 수 있도록 함
 */
@Component
class PositionCacheWriter(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 열린 포지션 상태 갱신
     *
     * @param exists 열린 포지션 존재 여부
     * @param count 열린 포지션 수
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

    /**
     * 열린 포지션 존재 여부 조회
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
}
