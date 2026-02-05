package io.premiumspread.redis

/**
 * Redis 키 생성 유틸리티
 *
 * 키 패턴: {domain}:{sub-domain}:{identifier}:{optional-qualifier}
 */
object RedisKeyGenerator {

    // Ticker 키
    fun tickerKey(exchange: String, symbol: String): String =
        "ticker:$exchange:$symbol"

    // FX 키
    fun fxKey(base: String, quote: String): String =
        "fx:$base:$quote"

    // Premium 키
    fun premiumKey(symbol: String): String =
        "premium:$symbol"

    fun premiumHistoryKey(symbol: String): String =
        "premium:$symbol:history"

    // 초당 데이터 키 (ZSet)
    fun tickerSecondsKey(exchange: String, symbol: String): String =
        "ticker:seconds:$exchange:$symbol"

    fun premiumSecondsKey(symbol: String): String =
        "premium:seconds:$symbol"

    // 집계 데이터 키 (ZSet)
    fun premiumMinutesKey(symbol: String): String =
        "premium:minutes:$symbol"

    fun premiumHoursKey(symbol: String): String =
        "premium:hours:$symbol"

    // 서머리 캐시 키 (Hash)
    fun summaryKey(interval: String, symbol: String): String =
        "summary:$interval:$symbol"

    // Position 키
    fun positionOpenExistsKey(): String =
        "position:open:exists"

    fun positionOpenCountKey(): String =
        "position:open:count"

    // Lock 키
    fun lockTickerKey(): String =
        "lock:ticker:all"

    fun lockFxKey(): String =
        "lock:fx"

    fun lockPremiumKey(): String =
        "lock:premium"

    // Batch 헬스 키
    fun batchLastRunKey(job: String): String =
        "batch:last_run:$job"

    fun batchHealthKey(server: String): String =
        "batch:health:$server"
}
