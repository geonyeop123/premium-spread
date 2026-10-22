package io.premiumspread.infrastructure.batch.exchange.binance

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.PriceLevel
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException

/**
 * 바이낸스 USDⓈ-M REST GET 과 실패 정규화. 모든 실패는 [AccountReadException] 이다([BinanceTimestampRejectedException] 제외).
 * 서명은 하지 않는다 — 서명 query 는 호출자가 만들어 넘긴다.
 */
internal class BinanceFuturesReadClient(
    private val webClient: WebClient,
    private val timeout: Duration = Duration.ofSeconds(5),
) {
    private val mapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    /**
     * [query] 를 재인코딩 없이 그대로 붙여 GET 한다(서명 문자열 = 전송 문자열). [apiKey] 가 null 이 아니면 `X-MBX-APIKEY` 헤더를 붙인다.
     * HTTP 오류 코드 `-1021` 은 [apiKey] 가 있을 때만 [BinanceTimestampRejectedException] 으로, 없으면 일반 매핑으로 간다.
     */
    // adapter 경계: 모든 실패를 AccountReadException 으로 정규화한다.
    @Suppress("TooGenericExceptionCaught")
    fun get(path: String, query: String, apiKey: String? = null): JsonNode {
        val body = try {
            webClient.get()
                .uri { builder ->
                    builder.path(path).also { if (query.isNotEmpty()) it.replaceQuery(query) }.build()
                }
                .headers { if (apiKey != null) it.set("X-MBX-APIKEY", apiKey) }
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(timeout)
                .block()
        } catch (e: WebClientResponseException) {
            throw mapHttpError(path, e, signed = apiKey != null)
        } catch (e: WebClientRequestException) {
            throw AccountReadException(AccountReadFailureCategory.NETWORK, "Binance $path request failed", e)
        } catch (e: AccountReadException) {
            throw e
        } catch (e: RuntimeException) {
            val timedOut = generateSequence<Throwable>(e) { it.cause }.any { it is TimeoutException }
            val category = if (timedOut) AccountReadFailureCategory.NETWORK else AccountReadFailureCategory.UNEXPECTED
            throw AccountReadException(category, "Binance $path call failed", e)
        } ?: binanceUnexpected("Binance $path returned an empty body")
        return try {
            mapper.readTree(body) ?: binanceUnexpected("Binance $path returned an empty body")
        } catch (e: JsonProcessingException) {
            throw AccountReadException(AccountReadFailureCategory.UNEXPECTED, "Binance $path returned malformed JSON", e)
        }
    }

    /** 공개 depth 조회 — ask 레벨 가격 오름차순, `T` → observedAt. */
    fun askDepth(symbol: String): BinanceAskDepth {
        val root = get("/fapi/v1/depth", "symbol=$symbol&limit=20")
        val asks = root.get("asks")?.takeIf { it.isArray } ?: binanceUnexpected("depth asks are missing")
        val levels = asks.map {
            if (!it.isArray || it.size() < 2) binanceUnexpected("depth level is malformed")
            val price = it.get(0).toDecimal("depth price")
            val qty = it.get(1).toDecimal("depth quantity")
            if (price.signum() <= 0 || qty.signum() <= 0) binanceUnexpected("depth level is not positive")
            PriceLevel(price, qty)
        }.sortedBy { it.price }
        if (levels.isEmpty()) binanceUnexpected("depth has no ask levels")
        val time = root.get("T")?.takeIf { it.isIntegralNumber }?.asLong() ?: binanceUnexpected("depth time is missing")
        return BinanceAskDepth(levels, Instant.ofEpochMilli(time))
    }

    /** 공개 최근 체결가 조회의 `price` (양수). */
    fun price(symbol: String): BigDecimal {
        val price = get("/fapi/v2/ticker/price", "symbol=$symbol").requireDecimal("price")
        if (price.signum() <= 0) binanceUnexpected("price is not positive")
        return price
    }

    private fun mapHttpError(path: String, e: WebClientResponseException, signed: Boolean): RuntimeException {
        val status = e.statusCode.value()
        val code = try {
            mapper.readTree(e.responseBodyAsString)?.get("code")?.takeIf { it.isIntegralNumber }?.asInt()
        } catch (_: JsonProcessingException) {
            null
        }
        if (signed && code == CODE_INVALID_TIMESTAMP) return BinanceTimestampRejectedException(e)
        val category = when {
            status == 401 || code in AUTH_CODES -> AccountReadFailureCategory.BINANCE_AUTH
            status == 429 || status == 418 || status >= 500 -> AccountReadFailureCategory.NETWORK
            else -> AccountReadFailureCategory.UNEXPECTED
        }
        return AccountReadException(category, "Binance $path responded $status", e)
    }

    private companion object {
        const val CODE_INVALID_TIMESTAMP = -1021
        val AUTH_CODES = setOf(-2014, -2015, -1022)
    }
}

internal data class BinanceAskDepth(val levels: List<PriceLevel>, val observedAt: Instant)

/** 서명 요청이 `-1021`(timestamp 범위 밖)로 거부됐다. 호출자가 서버 시각을 다시 재서 재시도한다. */
internal class BinanceTimestampRejectedException(cause: Throwable) : RuntimeException(cause)

internal fun JsonNode.requireDecimal(field: String): BigDecimal =
    (get(field) ?: binanceUnexpected("field $field is missing")).toDecimal(field)

internal fun JsonNode.toDecimal(name: String): BigDecimal = try {
    when {
        isNumber -> decimalValue()
        isTextual -> BigDecimal(asText())
        else -> binanceUnexpected("field $name is not a number")
    }
} catch (e: NumberFormatException) {
    throw AccountReadException(AccountReadFailureCategory.UNEXPECTED, "field $name is not a number", e)
}

internal fun binanceUnexpected(message: String): Nothing =
    throw AccountReadException(AccountReadFailureCategory.UNEXPECTED, message)
