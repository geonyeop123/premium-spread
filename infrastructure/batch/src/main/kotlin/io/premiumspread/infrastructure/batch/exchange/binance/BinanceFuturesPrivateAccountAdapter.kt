package io.premiumspread.infrastructure.batch.exchange.binance

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import io.premiumspread.domain.accountpnl.PriceLevel
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException

/** 바이낸스 USDⓈ-M 선물 read-only 계정 조회. 모든 호출은 GET 이며 실패는 [AccountReadException] 으로만 던진다. */
class BinanceFuturesPrivateAccountAdapter(
    private val webClient: WebClient,
    private val apiKey: String,
    private val secretKey: String,
    private val clock: Clock,
    private val recvWindowMillis: Long = 10_000,
    private val timeout: Duration = Duration.ofSeconds(5),
) : ForeignPerpAccountReadPort {
    private val mapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    @Volatile
    private var serverOffsetMillis: Long? = null

    private var cache: EntryCache? = null

    /**
     * 깊이(호가)는 마지막에 조회한다 — 체결·펀딩 이력 탐색이 길어져도 판정 호가 관측 시각이 빗썸 호가와 벌어지지
     * 않게 한다(시세 신선도 skew 5초).
     */
    @Synchronized
    override fun read(): ForeignPerpAccountSnapshot {
        val position = readPosition()
        val history = if (position.amount.signum() != 0) resolveHistory(position) else null
        val fee = readTakerFeeRate()
        val entryCommission = history?.let { commissionUsdt(it) }
        val lastPrice = readPrice(SYMBOL)
        val depth = readDepth()
        return ForeignPerpAccountSnapshot(
            signedQuantity = position.amount,
            entryPrice = position.entryPrice,
            entryCommissionUsdt = entryCommission,
            fundingUsdt = history?.funding?.sum,
            takerFeeRate = fee,
            askLevels = depth.first,
            lastPrice = lastPrice,
            askObservedAt = depth.second,
        )
    }

    private data class Position(val amount: BigDecimal, val entryPrice: BigDecimal, val positionSide: String)

    private data class Trade(
        val id: Long,
        val time: Long,
        val signedQty: BigDecimal,
        val commission: BigDecimal,
        val asset: String,
        val positionSide: String,
    )

    private data class FundingState(val sum: BigDecimal, val lastTime: Long, val boundaryIds: Set<Long>)

    private data class History(val usdt: BigDecimal, val bnb: BigDecimal, val funding: FundingState)

    /**
     * 진입 이력 탐색 결과(미발견 포함)를 같은 포지션 동안 보관한다. 새 체결 감지:
     * [lastTradeId] 가 있으면 그 다음 id 부터, 없으면(체결 없음·1000건 창) [walkedAtMillis] 이후 시각부터 1건 조회.
     */
    private data class EntryCache(
        val amount: BigDecimal,
        val entryPrice: BigDecimal,
        val lastTradeId: Long?,
        val walkedAtMillis: Long,
        val history: History?,
    )

    private data class WalkResult(val lastTradeId: Long?, val found: Walked?)

    private data class Walked(val entryTime: Long, val usdt: BigDecimal, val bnb: BigDecimal)

    private fun readPosition(): Position {
        val rows = signedGet("/fapi/v3/positionRisk", "symbol=$SYMBOL")
        if (!rows.isArray) unexpected("positionRisk response is not an array")
        val row = rows.filter { it.path("symbol").asText() == SYMBOL }
            .let { list -> list.firstOrNull { it.path("positionSide").asText() == "SHORT" } ?: list.firstOrNull { it.path("positionSide").asText() == "BOTH" } }
            ?: return Position(BigDecimal.ZERO, BigDecimal.ZERO, "BOTH")
        val amount = row.dec("positionAmt")
        val entry = row.dec("entryPrice")
        if (amount.signum() == 0) return Position(BigDecimal.ZERO, BigDecimal.ZERO, row.path("positionSide").asText())
        if (entry.signum() <= 0) unexpected("entry price is not positive for an open position")
        return Position(amount, entry, row.path("positionSide").asText())
    }

    private fun resolveHistory(position: Position): History? {
        val cached = cache?.takeIf { it.amount.compareTo(position.amount) == 0 && it.entryPrice.compareTo(position.entryPrice) == 0 }
        if (cached != null && !hasNewTrades(cached)) {
            val refreshed = cached.history?.let { it.copy(funding = readFunding(it.funding)) }
            cache = cached.copy(history = refreshed)
            return refreshed
        }
        cache = null
        val walkedAt = nowMillis()
        val walk = walkTrades(position, walkedAt)
        val history = walk.found?.let {
            History(it.usdt, it.bnb, readFunding(FundingState(BigDecimal.ZERO, it.entryTime, emptySet())))
        }
        cache = EntryCache(position.amount, position.entryPrice, walk.lastTradeId, walkedAt, history)
        return history
    }

    private fun commissionUsdt(history: History): BigDecimal {
        if (history.bnb.signum() == 0) return history.usdt
        return history.usdt.add(history.bnb.multiply(readPrice("BNBUSDT")))
    }

    private fun hasNewTrades(cached: EntryCache): Boolean {
        val params = if (cached.lastTradeId != null) {
            "symbol=$SYMBOL&fromId=${cached.lastTradeId + 1}&limit=1"
        } else {
            val now = nowMillis()
            if (now <= cached.walkedAtMillis) return false
            // userTrades 의 startTime~endTime 은 7일 이하다. 그보다 오래된 미발견 캐시는 다시 걷는다
            if (now - cached.walkedAtMillis > WINDOW_MILLIS) return true
            "symbol=$SYMBOL&startTime=${cached.walkedAtMillis + 1}&endTime=$now&limit=1"
        }
        val rows = signedGet("/fapi/v1/userTrades", params)
        if (!rows.isArray) unexpected("userTrades response is not an array")
        return rows.size() > 0
    }

    /**
     * [walkedAt] 부터 7일 창을 거꾸로 걷는다. 반환 lastTradeId 는 끝까지 받은(1000건 미만) 창에서 본 최신 체결 id 다 —
     * 1000건으로 잘린 창의 id 는 최신이 아닐 수 있어 쓰지 않는다(그 경우 시각 기준으로 새 체결을 감지한다).
     */
    private fun walkTrades(position: Position, walkedAt: Long): WalkResult {
        var end = walkedAt
        var cumulative = BigDecimal.ZERO
        var usdt = BigDecimal.ZERO
        var bnb = BigDecimal.ZERO
        var newestId: Long? = null
        val seen = HashSet<Long>()
        repeat(MAX_WINDOWS) {
            val start = end - WINDOW_MILLIS
            val raw = readTrades(start, end)
            if (raw.size >= TRADE_LIMIT) return WalkResult(newestId, null)
            raw.maxOfOrNull { it.id }?.let { id -> newestId = maxOf(newestId ?: id, id) }
            val trades = raw.filter { position.positionSide == "BOTH" || it.positionSide == position.positionSide }
                .sortedWith(compareByDescending<Trade> { it.time }.thenByDescending { it.id })
            for (trade in trades) {
                if (!seen.add(trade.id)) continue
                cumulative = cumulative.add(trade.signedQty)
                when (trade.asset) {
                    "USDT" -> usdt = usdt.add(trade.commission)
                    "BNB" -> bnb = bnb.add(trade.commission)
                    else -> unexpected("unsupported commission asset")
                }
                if (cumulative.compareTo(position.amount) == 0) {
                    return WalkResult(newestId, Walked(trade.time, usdt, bnb))
                }
            }
            end = start - 1
        }
        return WalkResult(newestId, null)
    }

    private fun readTrades(start: Long, end: Long): List<Trade> {
        val rows = signedGet("/fapi/v1/userTrades", "symbol=$SYMBOL&startTime=$start&endTime=$end&limit=$TRADE_LIMIT")
        if (!rows.isArray) unexpected("userTrades response is not an array")
        return rows.map {
            val qty = it.dec("qty")
            val price = it.dec("price")
            val commission = it.dec("commission")
            if (qty.signum() <= 0 || price.signum() <= 0 || commission.signum() < 0) unexpected("trade value is out of range")
            val sign = when (it.path("side").asText()) {
                "BUY" -> 1
                "SELL" -> -1
                else -> unexpected("trade side is unknown")
            }
            val id = it.get("id")?.takeIf { n -> n.isIntegralNumber }?.asLong() ?: unexpected("trade id is missing")
            val time = it.get("time")?.takeIf { n -> n.isIntegralNumber }?.asLong() ?: unexpected("trade time is missing")
            Trade(id, time, if (sign > 0) qty else qty.negate(), commission, it.path("commissionAsset").asText(), it.path("positionSide").asText())
        }
    }

    private fun readFunding(initial: FundingState): FundingState {
        var sum = initial.sum
        var last = initial.lastTime
        val seen = initial.boundaryIds.toMutableSet()
        var start = initial.lastTime
        while (true) {
            val rows = signedGet("/fapi/v1/income", "incomeType=FUNDING_FEE&symbol=$SYMBOL&limit=$INCOME_LIMIT&startTime=$start")
            if (!rows.isArray) unexpected("income response is not an array")
            var added = 0
            for (row in rows.sortedBy { it.path("time").asLong() }) {
                val tranId = row.get("tranId")?.takeIf { it.isIntegralNumber }?.asLong() ?: unexpected("tranId is missing")
                val time = row.get("time")?.takeIf { it.isIntegralNumber }?.asLong() ?: unexpected("income time is missing")
                if (row.path("asset").asText() != "USDT") unexpected("unsupported funding asset")
                if (time == last && tranId in seen) continue
                sum = sum.add(row.dec("income"))
                added++
                if (time > last) {
                    last = time
                    seen.clear()
                }
                seen.add(tranId)
            }
            if (rows.size() < INCOME_LIMIT || added == 0) break
            start = last
        }
        return FundingState(sum, last, seen)
    }

    private fun readTakerFeeRate(): BigDecimal {
        val rate = signedGet("/fapi/v1/commissionRate", "symbol=$SYMBOL").dec("takerCommissionRate")
        if (rate.signum() < 0) unexpected("taker commission rate is negative")
        return rate
    }

    private fun readDepth(): Pair<List<PriceLevel>, Instant> {
        val root = send("/fapi/v1/depth", "symbol=$SYMBOL&limit=20", signed = false)
        val asks = root.get("asks")?.takeIf { it.isArray } ?: unexpected("depth asks are missing")
        val levels = asks.map {
            if (!it.isArray || it.size() < 2) unexpected("depth level is malformed")
            val price = it.get(0).decimal("depth price")
            val qty = it.get(1).decimal("depth quantity")
            if (price.signum() <= 0 || qty.signum() <= 0) unexpected("depth level is not positive")
            PriceLevel(price, qty)
        }.sortedBy { it.price }
        if (levels.isEmpty()) unexpected("depth has no ask levels")
        val time = root.get("T")?.takeIf { it.isIntegralNumber }?.asLong() ?: unexpected("depth time is missing")
        return levels to Instant.ofEpochMilli(time)
    }

    private fun readPrice(symbol: String): BigDecimal {
        val price = send("/fapi/v2/ticker/price", "symbol=$symbol", signed = false).dec("price")
        if (price.signum() <= 0) unexpected("price is not positive")
        return price
    }

    private fun nowMillis() = clock.millis() + (serverOffsetMillis ?: 0L)

    private fun measureOffset(): Long {
        val server = send("/fapi/v1/time", "", signed = false).get("serverTime")?.takeIf { it.isIntegralNumber }?.asLong()
            ?: unexpected("serverTime is missing")
        return (server - clock.millis()).also { serverOffsetMillis = it }
    }

    private class TimestampRejected : RuntimeException()

    private fun signedGet(path: String, params: String): JsonNode {
        try {
            return send(path, params, signed = true)
        } catch (_: TimestampRejected) {
            measureOffset()
        }
        try {
            return send(path, params, signed = true)
        } catch (e: TimestampRejected) {
            throw AccountReadException(AccountReadFailureCategory.NETWORK, "Binance $path timestamp rejected after re-sync", e)
        }
    }

    private fun send(path: String, params: String, signed: Boolean): JsonNode {
        val query = if (signed) {
            val offset = serverOffsetMillis ?: measureOffset()
            val base = (if (params.isEmpty()) "" else "$params&") + "recvWindow=$recvWindowMillis&timestamp=${clock.millis() + offset}"
            "$base&signature=${BinanceRequestSigner.sign(secretKey, base)}"
        } else {
            params
        }
        val body = try {
            webClient.get()
                .uri { builder ->
                    builder.path(path).also { if (query.isNotEmpty()) it.replaceQuery(query) }.build()
                }
                .headers { if (signed) it.set("X-MBX-APIKEY", apiKey) }
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(timeout)
                .block()
        } catch (e: WebClientResponseException) {
            throw mapHttpError(path, e)
        } catch (e: WebClientRequestException) {
            throw AccountReadException(AccountReadFailureCategory.NETWORK, "Binance $path request failed", e)
        } catch (e: AccountReadException) {
            throw e
        } catch (e: RuntimeException) {
            val timedOut = generateSequence<Throwable>(e) { it.cause }.any { it is TimeoutException }
            val category = if (timedOut) AccountReadFailureCategory.NETWORK else AccountReadFailureCategory.UNEXPECTED
            throw AccountReadException(category, "Binance $path call failed", e)
        } ?: unexpected("Binance $path returned an empty body")
        return try {
            mapper.readTree(body) ?: unexpected("Binance $path returned an empty body")
        } catch (e: JsonProcessingException) {
            throw AccountReadException(AccountReadFailureCategory.UNEXPECTED, "Binance $path returned malformed JSON", e)
        }
    }

    private fun mapHttpError(path: String, e: WebClientResponseException): RuntimeException {
        val status = e.statusCode.value()
        val code = try {
            mapper.readTree(e.responseBodyAsString)?.get("code")?.takeIf { it.isIntegralNumber }?.asInt()
        } catch (_: JsonProcessingException) {
            null
        }
        if (code == CODE_INVALID_TIMESTAMP) return TimestampRejected()
        val category = when {
            status == 401 || code in AUTH_CODES -> AccountReadFailureCategory.BINANCE_AUTH
            status == 429 || status == 418 || status >= 500 -> AccountReadFailureCategory.NETWORK
            else -> AccountReadFailureCategory.UNEXPECTED
        }
        return AccountReadException(category, "Binance $path responded $status", e)
    }

    private fun JsonNode.dec(field: String): BigDecimal = (get(field) ?: unexpected("field $field is missing")).decimal(field)

    private fun JsonNode.decimal(name: String): BigDecimal = try {
        when {
            isNumber -> decimalValue()
            isTextual -> BigDecimal(asText())
            else -> unexpected("field $name is not a number")
        }
    } catch (e: NumberFormatException) {
        throw AccountReadException(AccountReadFailureCategory.UNEXPECTED, "field $name is not a number", e)
    }

    private fun unexpected(message: String): Nothing =
        throw AccountReadException(AccountReadFailureCategory.UNEXPECTED, message)

    private companion object {
        const val SYMBOL = "BTCUSDT"
        const val TRADE_LIMIT = 1000
        const val INCOME_LIMIT = 1000
        const val MAX_WINDOWS = 13
        const val WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val CODE_INVALID_TIMESTAMP = -1021
        val AUTH_CODES = setOf(-2014, -2015, -1022)
    }
}
