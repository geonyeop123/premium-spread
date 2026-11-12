package io.premiumspread.infrastructure.batch.exchange.binance

import com.fasterxml.jackson.databind.JsonNode
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import java.time.Clock
import java.time.Duration

/**
 * 바이낸스 USDⓈ-M 서명 GET 과 서버 시각 오프셋. 모든 호출은 [BinanceFuturesReadClient.get] 을 거친다.
 * 오프셋은 첫 서명 요청 전에 한 번 재고, `-1021` 거부 시 한 번 다시 재서 재시도한다.
 */
internal class BinanceFuturesSignedReadClient(
    private val client: BinanceFuturesReadClient,
    private val apiKey: String,
    private val secretKey: String,
    private val clock: Clock,
    private val recvWindowMillis: Long,
) {
    @Volatile
    private var serverOffsetMillis: Long? = null

    /** 서버 시각 추정(오프셋을 아직 재지 않았으면 로컬 시각). */
    fun nowMillis(): Long = clock.millis() + (serverOffsetMillis ?: 0L)

    /** 서명 GET. `-1021` 이면 서버 시각을 다시 재서 한 번만 재시도하고, 또 거부되면 NETWORK 로 실패한다. */
    fun get(path: String, params: String): JsonNode {
        try {
            return send(path, params)
        } catch (_: BinanceTimestampRejectedException) {
            measureOffset()
        }
        try {
            return send(path, params)
        } catch (e: BinanceTimestampRejectedException) {
            throw AccountReadException(AccountReadFailureCategory.NETWORK, "Binance $path timestamp rejected after re-sync", e)
        }
    }

    /** 서명 GET 한 번. `-1021` 은 [BinanceTimestampRejectedException] 그대로 던진다(재시도 여부는 호출자가 정한다). */
    fun send(path: String, params: String, timeout: Duration? = null): JsonNode {
        val offset = serverOffsetMillis ?: measureOffset()
        val prefix = if (params.isEmpty()) "" else "$params&"
        val base = prefix + "recvWindow=$recvWindowMillis&timestamp=${clock.millis() + offset}"
        val query = "$base&signature=${BinanceRequestSigner.sign(secretKey, base)}"
        return if (timeout == null) client.get(path, query, apiKey) else client.get(path, query, apiKey, timeout)
    }

    private fun measureOffset(): Long {
        val server = client.get("/fapi/v1/time", "").get("serverTime")?.takeIf { it.isIntegralNumber }?.asLong()
            ?: binanceUnexpected("serverTime is missing")
        return (server - clock.millis()).also { serverOffsetMillis = it }
    }
}
