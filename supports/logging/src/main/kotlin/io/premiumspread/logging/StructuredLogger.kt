package io.premiumspread.logging

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.math.BigDecimal

/**
 * 구조화된 로깅 유틸리티
 *
 * JSON 형식으로 이벤트 로깅
 */
object StructuredLogger {

    private val logger = LoggerFactory.getLogger(StructuredLogger::class.java)

    fun logTickerFetch(
        exchange: String,
        symbol: String,
        price: BigDecimal,
        latencyMs: Long,
    ) {
        withMdc(
            "event" to "ticker_fetch",
            "exchange" to exchange,
            "symbol" to symbol,
        ) {
            logger.info(
                """{"event":"ticker_fetch","exchange":"$exchange","symbol":"$symbol","price":"$price","latency_ms":$latencyMs}""",
            )
        }
    }

    fun logPremiumCalculation(
        symbol: String,
        rate: BigDecimal,
        koreaPrice: BigDecimal,
        foreignPrice: BigDecimal,
        fxRate: BigDecimal,
    ) {
        withMdc(
            "event" to "premium_calc",
            "symbol" to symbol,
        ) {
            logger.info(
                """{"event":"premium_calc","symbol":"$symbol","rate":"$rate","korea_price":"$koreaPrice","foreign_price":"$foreignPrice","fx_rate":"$fxRate"}""",
            )
        }
    }

    fun logExternalApiError(
        api: String,
        errorType: String,
        message: String,
    ) {
        withMdc(
            "event" to "external_api_error",
            "api" to api,
            "error_type" to errorType,
        ) {
            logger.error(
                """{"event":"external_api_error","api":"$api","error_type":"$errorType","message":"$message"}""",
            )
        }
    }

    fun logBatchJobStart(job: String) {
        withMdc("event" to "batch_job_start", "job" to job) {
            logger.info("""{"event":"batch_job_start","job":"$job"}""")
        }
    }

    fun logBatchJobComplete(job: String, durationMs: Long) {
        withMdc("event" to "batch_job_complete", "job" to job) {
            logger.info("""{"event":"batch_job_complete","job":"$job","duration_ms":$durationMs}""")
        }
    }

    private inline fun withMdc(vararg pairs: Pair<String, String>, block: () -> Unit) {
        pairs.forEach { (key, value) -> MDC.put(key, value) }
        try {
            block()
        } finally {
            pairs.forEach { (key, _) -> MDC.remove(key) }
        }
    }
}
