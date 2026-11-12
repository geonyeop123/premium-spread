package io.premiumspread.infrastructure.batch.exchange.binance

import com.fasterxml.jackson.databind.JsonNode
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import io.premiumspread.domain.accountpnl.ForeignPerpBalance
import io.premiumspread.domain.accountpnl.ForeignPerpMarginType
import io.premiumspread.domain.accountpnl.ForeignPerpPositionRisk
import io.premiumspread.domain.accountpnl.ForeignPerpSymbolConfig
import io.premiumspread.infrastructure.batch.exchange.AccountBalanceLog
import io.premiumspread.infrastructure.batch.exchange.BalanceItem
import io.premiumspread.infrastructure.batch.exchange.BalanceUnavailableReason
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration

/** 바이낸스 USDⓈ-M 선물 read-only 계정 조회. 모든 호출은 GET 이며 실패는 [AccountReadException] 으로만 던진다. */
class BinanceFuturesPrivateAccountAdapter(
    webClient: WebClient,
    apiKey: String,
    secretKey: String,
    clock: Clock,
    recvWindowMillis: Long = 10_000,
    timeout: Duration = Duration.ofSeconds(5),
) : ForeignPerpAccountReadPort {
    private val client = BinanceFuturesReadClient(webClient, timeout)
    private val signed = BinanceFuturesSignedReadClient(client, apiKey, secretKey, clock, recvWindowMillis)

    private var cache: EntryCache? = null

    /**
     * 깊이(호가)는 마지막에 조회한다 — 체결·펀딩 이력 탐색이 길어져도 판정 호가 관측 시각이 빗썸 호가와 벌어지지
     * 않게 한다(시세 신선도 skew 5초).
     */
    @Synchronized
    override fun read(): ForeignPerpAccountSnapshot {
        val position = readPosition()
        val open = position.amount.signum() != 0
        val history = if (open) resolveHistory(position) else null
        val fee = readTakerFeeRate()
        val symbolConfig = if (open) readSymbolConfig() else null
        val balance = readBalance()
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
            balance = balance,
            positionRisk = position.risk,
            symbolConfig = symbolConfig,
        )
    }

    /**
     * /fapi/v3/account 의 USDT 잔고. 요청 실패·해석 실패는 null + WARN. 취소는 다시 던진다.
     * 이 호출은 깊이 조회 앞이라 회차 시세 신선도를 잡아먹지 않게 [sendOnceOrNull] 로 한 번만 보낸다.
     */
    private fun readBalance(): ForeignPerpBalance? {
        val root = sendOnceOrNull("/fapi/v3/account", "", BalanceItem.BINANCE_USDT_ASSET) ?: return null
        return valueOrWarn(BalanceItem.BINANCE_USDT_ASSET, parseBalance(root))
    }

    /**
     * /fapi/v1/symbolConfig 의 BTCUSDT 레버리지·마진 모드. 열린 포지션이 있을 때만 부른다.
     * 요청 실패·해석 실패는 null + WARN, 취소는 다시 던진다(잔고와 같은 계약).
     */
    private fun readSymbolConfig(): ForeignPerpSymbolConfig? {
        val root = sendOnceOrNull("/fapi/v1/symbolConfig", "symbol=$SYMBOL", BalanceItem.BINANCE_SYMBOL_CONFIG) ?: return null
        return valueOrWarn(BalanceItem.BINANCE_SYMBOL_CONFIG, parseSymbolConfig(root))
    }

    /**
     * 부분 실패를 허용하는 서명 GET. [BALANCE_TIMEOUT] 으로 한 번만 보낸다 — `-1021` 이어도 서버 시각을 다시 재거나
     * 재시도하지 않고 요청 실패로 본다. 요청 실패는 null + WARN 으로 흡수하고 취소(인터럽트)는 다시 던진다.
     */
    private fun sendOnceOrNull(path: String, params: String, item: BalanceItem): JsonNode? = try {
        try {
            signed.send(path, params, BALANCE_TIMEOUT)
        } catch (e: BinanceTimestampRejectedException) {
            throw AccountReadException(AccountReadFailureCategory.NETWORK, "Binance $path timestamp rejected", e)
        }
    } catch (e: AccountReadException) {
        if (AccountBalanceLog.isCancellation(e)) throw e
        AccountBalanceLog.unavailable(
            item,
            BalanceUnavailableReason.REQUEST_FAILED,
            e.category,
            AccountBalanceLog.rootErrorClass(e),
        )
        null
    }

    private fun <T : Any> valueOrWarn(item: BalanceItem, parsed: Parsed<T>): T? = when (parsed) {
        is Parsed.Ok -> parsed.value

        is Parsed.Unavailable -> {
            AccountBalanceLog.unavailable(item, parsed.reason)
            null
        }
    }

    /** 응답 해석. 예외를 던지지 않고 사유를 돌려준다. */
    private fun parseBalance(root: JsonNode): Parsed<ForeignPerpBalance> {
        val assets = root.get("assets")?.takeIf { it.isArray }
        val usdt = assets?.firstOrNull { it.path("asset").asText() == "USDT" }
            ?: return Parsed.Unavailable(BalanceUnavailableReason.MISSING)
        val margin = usdt.get("marginBalance")?.let { decimalOrNull(it) }
        val available = usdt.get("availableBalance")?.let { decimalOrNull(it) }
        return when {
            margin == null || available == null -> Parsed.Unavailable(BalanceUnavailableReason.NOT_NUMBER)
            margin.signum() < 0 || available.signum() < 0 -> Parsed.Unavailable(BalanceUnavailableReason.NEGATIVE)
            else -> Parsed.Ok(ForeignPerpBalance(margin, available))
        }
    }

    /** symbolConfig 응답 배열에서 BTCUSDT 행을 해석한다. 예외를 던지지 않고 사유를 돌려준다. */
    private fun parseSymbolConfig(root: JsonNode): Parsed<ForeignPerpSymbolConfig> {
        val row = root.takeIf { it.isArray }?.firstOrNull { it.path("symbol").asText() == SYMBOL }
            ?: return Parsed.Unavailable(BalanceUnavailableReason.MISSING)
        val leverageNode = row.get("leverage") ?: return Parsed.Unavailable(BalanceUnavailableReason.MISSING)
        val leverage = intOrNull(leverageNode) ?: return Parsed.Unavailable(BalanceUnavailableReason.NOT_NUMBER)
        if (leverage < 1) return Parsed.Unavailable(BalanceUnavailableReason.NOT_POSITIVE)
        val marginType = when (row.path("marginType").asText()) {
            "CROSSED" -> ForeignPerpMarginType.CROSSED
            "ISOLATED" -> ForeignPerpMarginType.ISOLATED
            else -> return Parsed.Unavailable(BalanceUnavailableReason.UNKNOWN)
        }
        return Parsed.Ok(ForeignPerpSymbolConfig(leverage, marginType))
    }

    /**
     * positionRisk 행의 위험 필드 7개. 하나라도 없거나 숫자가 아니거나 범위를 벗어나면 묶음 전체를 버린다 — 필수 필드와 달리
     * 회차를 실패시키지 않는다. `liquidationPrice` 0 은 청산가 없음(null)이다.
     */
    private fun parsePositionRisk(row: JsonNode): Parsed<ForeignPerpPositionRisk> {
        val values = HashMap<String, BigDecimal>()
        for (name in RISK_FIELDS) {
            val node = row.get(name) ?: return Parsed.Unavailable(BalanceUnavailableReason.MISSING)
            values[name] = decimalOrNull(node) ?: return Parsed.Unavailable(BalanceUnavailableReason.NOT_NUMBER)
        }
        val liquidation = values.getValue("liquidationPrice")
        val mark = values.getValue("markPrice")
        val margins = MARGIN_FIELDS.map { values.getValue(it) }
        if (mark.signum() <= 0) return Parsed.Unavailable(BalanceUnavailableReason.NOT_POSITIVE)
        if (liquidation.signum() < 0 || margins.any { it.signum() < 0 }) {
            return Parsed.Unavailable(BalanceUnavailableReason.NEGATIVE)
        }
        return try {
            Parsed.Ok(
                ForeignPerpPositionRisk(
                    liquidationPrice = liquidation.takeIf { it.signum() != 0 },
                    markPrice = mark,
                    isolatedMargin = values.getValue("isolatedMargin"),
                    isolatedWallet = values.getValue("isolatedWallet"),
                    maintMargin = values.getValue("maintMargin"),
                    positionInitialMargin = values.getValue("positionInitialMargin"),
                    notional = values.getValue("notional"),
                ),
            )
        } catch (_: IllegalArgumentException) {
            // 위 사전 검사가 도메인 불변식과 갈라져도 회차를 깨지 않는다
            Parsed.Unavailable(BalanceUnavailableReason.NEGATIVE)
        }
    }

    private fun intOrNull(node: JsonNode): Int? = when {
        node.isIntegralNumber && node.canConvertToInt() -> node.intValue()
        node.isTextual -> node.asText().toIntOrNull()
        else -> null
    }

    private fun decimalOrNull(node: JsonNode): BigDecimal? = try {
        when {
            node.isNumber -> node.decimalValue()
            node.isTextual -> BigDecimal(node.asText())
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }

    /** 부분 실패 허용 항목의 해석 결과. */
    private sealed interface Parsed<out T : Any> {
        class Ok<T : Any>(val value: T) : Parsed<T>

        class Unavailable(val reason: BalanceUnavailableReason) : Parsed<Nothing>
    }

    private data class Position(
        val amount: BigDecimal,
        val entryPrice: BigDecimal,
        val positionSide: String,
        val risk: ForeignPerpPositionRisk? = null,
    )

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
        val risk = valueOrWarn(BalanceItem.BINANCE_POSITION_RISK, parsePositionRisk(row))
        return Position(amount, entry, row.path("positionSide").asText(), risk)
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

    private fun nowMillis() = signed.nowMillis()

    private fun signedGet(path: String, params: String): JsonNode = signed.get(path, params)

    private companion object {
        const val SYMBOL = "BTCUSDT"
        const val TRADE_LIMIT = 1000
        const val INCOME_LIMIT = 1000
        const val MAX_WINDOWS = 13
        const val WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000

        val RISK_FIELDS = listOf(
            "liquidationPrice",
            "markPrice",
            "isolatedMargin",
            "isolatedWallet",
            "maintMargin",
            "positionInitialMargin",
            "notional",
        )
        val MARGIN_FIELDS = listOf("isolatedMargin", "isolatedWallet", "maintMargin", "positionInitialMargin")

        /** 잔고·symbolConfig(부분 실패 허용 호출) 전용 timeout. 다른 호출은 생성자 timeout(기본 5초)을 쓴다. */
        val BALANCE_TIMEOUT: Duration = Duration.ofMillis(1500)
    }
}
