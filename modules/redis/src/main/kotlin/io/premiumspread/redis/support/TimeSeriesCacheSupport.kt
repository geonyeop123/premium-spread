package io.premiumspread.redis.support

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations.TypedTuple
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * ZSet 기반 시계열 데이터 저장/조회 공통 유틸리티
 *
 * score = epochMilli (timestamp), value = 직렬화된 문자열
 * 동일 score 중복 방지를 위해 add 전 동일 score 삭제 후 삽입
 */
@Component
class TimeSeriesCacheSupport(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * ZSet에 시계열 데이터 저장 (동일 timestamp 중복 제거)
     *
     * @param key Redis ZSet 키
     * @param value 직렬화된 값
     * @param timestamp 데이터 시점
     * @param ttl ZSet 키 TTL
     * @param retentionPeriod TTL 이전 오래된 데이터 삭제 기간 (기본값 = ttl)
     */
    fun add(
        key: String,
        value: String,
        timestamp: Instant,
        ttl: Duration,
        retentionPeriod: Duration = ttl,
    ) {
        val score = timestamp.toEpochMilli().toDouble()

        // 동일 score 중복 제거 후 삽입
        redisTemplate.opsForZSet().removeRangeByScore(key, score, score)
        redisTemplate.opsForZSet().add(key, value, score)
        redisTemplate.expire(key, ttl)

        // retention 기간 이전 데이터 삭제
        val cutoff = Instant.now().minus(retentionPeriod).toEpochMilli().toDouble()
        redisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff)

        log.debug("Added to ZSet: {} score={}", key, score)
    }

    /**
     * ZSet에서 시간 범위로 데이터 조회
     *
     * @param key Redis ZSet 키
     * @param from 시작 시점 (inclusive)
     * @param to 종료 시점 (inclusive)
     * @return (score, value) 튜플 리스트
     */
    fun rangeByTime(
        key: String,
        from: Instant,
        to: Instant,
    ): List<TypedTuple<String>> {
        return redisTemplate.opsForZSet().rangeByScoreWithScores(
            key,
            from.toEpochMilli().toDouble(),
            to.toEpochMilli().toDouble(),
        )?.toList() ?: emptyList()
    }

    /**
     * TypedTuple에서 timestamp 추출
     */
    fun extractTimestamp(entry: TypedTuple<String>): Instant? {
        return entry.score?.toLong()?.let { Instant.ofEpochMilli(it) }
    }
}
