package io.premiumspread.infrastructure.batch.exchange.binance

import com.fasterxml.jackson.databind.JsonNode
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration

/** 바이낸스 USDⓈ-M 선물 read-only 계정 조회. 모든 호출은 GET 이며 실패는 [AccountReadException] 으로만 던진다. */
class BinanceFuturesPrivateAccountAdapter(
    webClient: WebClient,
    private val apiKey: String,
    private val secretKey: String,
    private val clock: Clock,
    private val recvWindowMillis: Long = 10_000,
    timeout: Duration = Duration.ofSeconds(5),
) : ForeignPerpAccountReadPort {
    private val client = BinanceFuturesReadClient(webClient, timeout)

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
        val lastPrice = client.price(SYMBOL)
        val depth = client.askDepth(SYMBOL)
        return ForeignPerpAccountSnapshot(
            signedQuantity = position.amount,
            entryPrice = position.entryPrice,
            entryCommissionUsdt = entryCommission,
            fundingUsdt = history?.funding?.sum,
            takerFeeRate = fee,
            askLevels = depth.levels,
            lastPrice = lastPrice,
            askObservedAt = depth.observedAt,
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
        if (!rows.isArray) binanceUnexpected("positionRisk response is not an array")
        val row = rows.filter { it.path("symbol").asText() == SYMBOL }
            .let { list ->
                list.firstOrNull { it.path("positionSide").asText() == "SHORT" }
                    ?: list.firstOrNull { it.path("positionSide").asText() == "BOTH" }
            }
            ?: return Position(BigDecimal.ZERO, BigDecimal.ZERO, "BOTH")
        val amount = row.requireDecimal("positionAmt")
        val entry = row.requireDecimal("entryPrice")
        if (amount.signum() == 0) return Position(BigDecimal.ZERO, BigDecimal.ZERO, row.path("positionSide").asText())
        if (entry.signum() <= 0) binanceUnexpected("entry price is not positive for an open position")
        return Position(amount, entry, row.path("positionSide").asText())
    }

    private fun resolveHistory(position: Position): History? {
        val cached = cache?.takeIf {
            it.amount.compareTo(position.amount) == 0 && it.entryPrice.compareTo(position.entryPrice) == 0
        }
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
        return history.usdt.add(history.bnb.multiply(client.price("BNBUSDT")))
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
        if (!rows.isArray) binanceUnexpected("userTrades response is not an array")
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
                    else -> binanceUnexpected("unsupported commission asset")
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
        if (!rows.isArray) binanceUnexpected("userTrades response is not an array")
        return rows.map {
            val qty = it.requireDecimal("qty")
            val price = it.requireDecimal("price")
            val commission = it.requireDecimal("commission")
            if (qty.signum() <= 0 || price.signum() <= 0 || commission.signum() < 0) {
                binanceUnexpected("trade value is out of range")
            }
            val sign = when (it.path("side").asText()) {
                "BUY" -> 1
                "SELL" -> -1
                else -> binanceUnexpected("trade side is unknown")
            }
            val id = it.get("id")?.takeIf { n -> n.isIntegralNumber }?.asLong() ?: binanceUnexpected("trade id is missing")
            val time = it.get("time")?.takeIf { n -> n.isIntegralNumber }?.asLong() ?: binanceUnexpected("trade time is missing")
            Trade(
                id,
                time,
                if (sign > 0) qty else qty.negate(),
                commission,
                it.path("commissionAsset").asText(),
                it.path("positionSide").asText(),
            )
        }
    }

    private fun readFunding(initial: FundingState): FundingState {
        var sum = initial.sum
        var last = initial.lastTime
        val seen = initial.boundaryIds.toMutableSet()
        var start = initial.lastTime
        while (true) {
            val rows = signedGet("/fapi/v1/income", "incomeType=FUNDING_FEE&symbol=$SYMBOL&limit=$INCOME_LIMIT&startTime=$start")
            if (!rows.isArray) binanceUnexpected("income response is not an array")
            var added = 0
            for (row in rows.sortedBy { it.path("time").asLong() }) {
                val tranId = row.get("tranId")?.takeIf { it.isIntegralNumber }?.asLong() ?: binanceUnexpected("tranId is missing")
                val time = row.get("time")?.takeIf { it.isIntegralNumber }?.asLong()
                    ?: binanceUnexpected("income time is missing")
                if (row.path("asset").asText() != "USDT") binanceUnexpected("unsupported funding asset")
                if (time == last && tranId in seen) continue
                sum = sum.add(row.requireDecimal("income"))
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
        val rate = signedGet("/fapi/v1/commissionRate", "symbol=$SYMBOL").requireDecimal("takerCommissionRate")
        if (rate.signum() < 0) binanceUnexpected("taker commission rate is negative")
        return rate
    }

    private fun nowMillis() = clock.millis() + (serverOffsetMillis ?: 0L)

    private fun measureOffset(): Long {
        val server = client.get("/fapi/v1/time", "").get("serverTime")?.takeIf { it.isIntegralNumber }?.asLong()
            ?: binanceUnexpected("serverTime is missing")
        return (server - clock.millis()).also { serverOffsetMillis = it }
    }

    private fun signedGet(path: String, params: String): JsonNode {
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

    private fun send(path: String, params: String): JsonNode {
        val offset = serverOffsetMillis ?: measureOffset()
        val prefix = if (params.isEmpty()) "" else "$params&"
        val base = prefix + "recvWindow=$recvWindowMillis&timestamp=${clock.millis() + offset}"
        return client.get(path, "$base&signature=${BinanceRequestSigner.sign(secretKey, base)}", apiKey)
    }

    private companion object {
        const val SYMBOL = "BTCUSDT"
        const val TRADE_LIMIT = 1000
        const val INCOME_LIMIT = 1000
        const val MAX_WINDOWS = 13
        const val WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
