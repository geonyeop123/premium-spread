package io.premiumspread.redis

import java.time.Duration

/**
 * Ticker 집계 데이터 시간 단위
 *
 * ZSet 저장/조회 시 사용되는 시간 단위별 키 생성 및 TTL 관리
 */
enum class TickerAggregationTimeUnit(
    private val keyPrefix: String,
    val ttl: Duration,
) {
    SECONDS("ticker:seconds", RedisTtl.SECONDS_DATA),
    MINUTES("ticker:minutes", RedisTtl.MINUTES_DATA),
    HOURS("ticker:hours", RedisTtl.HOURS_DATA),
    ;

    /**
     * exchange와 symbol에 대한 Redis 키 생성
     */
    fun keyFor(exchange: String, symbol: String): String =
        "$keyPrefix:${exchange.lowercase()}:${symbol.lowercase()}"
}
