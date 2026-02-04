package io.premiumspread.redis

import java.time.Duration

/**
 * 집계 데이터 시간 단위
 *
 * ZSet 저장/조회 시 사용되는 시간 단위별 키 생성 및 TTL 관리
 */
enum class AggregationTimeUnit(
    private val keyPrefix: String,
    val ttl: Duration,
) {
    SECONDS("premium:seconds", RedisTtl.SECONDS_DATA),
    MINUTES("premium:minutes", RedisTtl.MINUTES_DATA),
    HOURS("premium:hours", RedisTtl.HOURS_DATA),
    ;

    /**
     * symbol에 대한 Redis 키 생성
     */
    fun keyFor(symbol: String): String = "$keyPrefix:${symbol.lowercase()}"
}
