package io.premiumspread.infrastructure.batch.exchange.binance

import com.fasterxml.jackson.databind.JsonNode
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.FillHistoryWindowLimitException
import io.premiumspread.domain.accountpnl.ForeignFunding
import io.premiumspread.domain.accountpnl.ForeignPerpFill
import io.premiumspread.domain.accountpnl.ForeignPerpFillReadPort
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 바이낸스 USDⓈ-M BTCUSDT 체결·펀딩 이력 조회. 모든 호출은 GET 이며 실패는 [AccountReadException] 으로 던진다
 * (창 한도 초과만 [FillHistoryWindowLimitException]).
 *
 * 범위 `(fromExclusive, toInclusive]` 를 `fromExclusive + 1ms` 부터 7일(−1ms) 이하 창으로 앞으로 나눠 읽는다.
 * 체결은 모든 positionSide 를 담고 BUY 는 +, SELL 은 − 수량이다. 수수료는 절댓값이며 BNB 는 체결 분의 1분봉 시가로
 * 환산한다(같은 체결 집합이면 재실행해도 같은 값).
 */
class BinanceFillHistoryPrivateAccountAdapter(
    webClient: WebClient,
    apiKey: String,
    secretKey: String,
    clock: Clock,
    recvWindowMillis: Long = 10_000,
    timeout: Duration = Duration.ofSeconds(5),
) : ForeignPerpFillReadPort {
    private val client = BinanceFuturesReadClient(webClient, timeout)
    private val signed = BinanceFuturesSignedReadClient(client, apiKey, secretKey, clock, recvWindowMillis)

    override fun fills(fromExclusive: Instant, toInclusive: Instant): List<ForeignPerpFill> {
        val lower = fromExclusive.toEpochMilli()
        val upper = toInclusive.toEpochMilli()
        val seen = HashSet<Long>()
        val bnbOpens = HashMap<Long, BigDecimal>()
        val fills = mutableListOf<ForeignPerpFill>()
        for (window in windows(lower, upper)) {
            val rows = signed.get(
                "/fapi/v1/userTrades",
                "symbol=$SYMBOL&startTime=${window.first}&endTime=${window.last}&limit=$PAGE_LIMIT",
            ).requireArray("userTrades")
            if (rows.size() >= PAGE_LIMIT) throw FillHistoryWindowLimitException(TRADE_LIMIT_MESSAGE)
            rows.map(::parseTrade)
                .filter { it.time in (lower + 1)..upper && seen.add(it.id) }
                .forEach { fills += it.toFill(bnbOpens) }
        }
        return fills.sortedWith(compareBy<ForeignPerpFill> { it.filledAt }.thenBy { it.id })
    }

    override fun funding(fromExclusive: Instant, toInclusive: Instant): List<ForeignFunding> {
        val lower = fromExclusive.toEpochMilli()
        val upper = toInclusive.toEpochMilli()
        val seen = HashSet<Long>()
        val rows = mutableListOf<ForeignFunding>()
        for (window in windows(lower, upper)) {
            var start = window.first
            while (true) {
                val page = signed.get(
                    "/fapi/v1/income",
                    "incomeType=FUNDING_FEE&symbol=$SYMBOL&startTime=$start&endTime=${window.last}&limit=$PAGE_LIMIT",
                ).requireArray("income").map(::parseIncome)
                page.filter { it.symbol == SYMBOL && it.time in (lower + 1)..upper && seen.add(it.tranId) }
                    .forEach { rows += ForeignFunding(it.tranId, it.income, Instant.ofEpochMilli(it.time)) }
                if (page.size < PAGE_LIMIT) break
                val last = page.maxOf { it.time }
                // 한 페이지가 같은 시각으로 꽉 차면 시각 기준으로 이어 읽을 수 없다.
                if (last <= start) throw FillHistoryWindowLimitException(INCOME_LIMIT_MESSAGE)
                start = last
            }
        }
        return rows.sortedWith(compareBy<ForeignFunding> { it.at }.thenBy { it.id })
    }

    private class Trade(
        val id: Long,
        val time: Long,
        val signedQty: BigDecimal,
        val realizedPnl: BigDecimal,
        val commission: BigDecimal,
        val asset: String,
    ) {
        override fun toString(): String = "Trade(id=$id)"
    }

    private class Income(val tranId: Long, val time: Long, val symbol: String, val income: BigDecimal) {
        override fun toString(): String = "Income(tranId=$tranId)"
    }

    private fun parseTrade(node: JsonNode): Trade {
        val qty = node.requireDecimal("qty")
        if (qty.signum() <= 0) binanceUnexpected("trade quantity is not positive")
        val signedQty = when (node.path("side").asText()) {
            "BUY" -> qty
            "SELL" -> qty.negate()
            else -> binanceUnexpected("trade side is unknown")
        }
        return Trade(
            id = node.requireLong("id"),
            time = node.requireLong("time"),
            signedQty = signedQty,
            realizedPnl = node.requireDecimal("realizedPnl"),
            commission = node.requireDecimal("commission").abs(),
            asset = node.path("commissionAsset").asText(),
        )
    }

    private fun parseIncome(node: JsonNode): Income {
        val income =
            Income(
                node.requireLong("tranId"),
                node.requireLong("time"),
                node.path("symbol").asText(),
                node.requireDecimal("income"),
            )
        if (income.symbol == SYMBOL && node.path("asset").asText() != "USDT") binanceUnexpected("unsupported funding asset")
        return income
    }

    private fun Trade.toFill(bnbOpens: MutableMap<Long, BigDecimal>): ForeignPerpFill {
        val commissionUsdt = when (asset) {
            "USDT" -> commission

            "BNB" -> {
                val minute = Math.floorDiv(time, MINUTE_MILLIS) * MINUTE_MILLIS
                commission.multiply(bnbOpens.getOrPut(minute) { bnbOpenAt(minute) }, MathContext.DECIMAL128)
            }

            else -> binanceUnexpected("unsupported commission asset")
        }
        return ForeignPerpFill(id, signedQty, realizedPnl, commissionUsdt, Instant.ofEpochMilli(time))
    }

    /** 공개 1분봉 `[openTime, open, ...]` 의 시가. 응답 봉의 시작 시각이 [minute] 와 다르면 실패한다. */
    private fun bnbOpenAt(minute: Long): BigDecimal {
        val rows = client.get("/fapi/v1/klines", "symbol=BNBUSDT&interval=1m&startTime=$minute&limit=1").requireArray("klines")
        val row = rows.firstOrNull()?.takeIf { it.isArray && it.size() >= 2 } ?: binanceUnexpected("BNB kline is missing")
        val openTime = row.get(0).takeIf { it.isIntegralNumber }?.asLong()
        if (openTime != minute) binanceUnexpected("BNB kline open time does not match the fill minute")
        val open = row.get(1).toDecimal("kline open")
        if (open.signum() <= 0) binanceUnexpected("BNB kline open is not positive")
        return open
    }

    /** `[lower + 1, upper]` 를 앞으로 나눈 닫힌 구간들. 각 구간 길이는 7일 − 1ms 이하다. */
    private fun windows(lower: Long, upper: Long): List<LongRange> {
        val result = mutableListOf<LongRange>()
        var start = lower + 1
        while (start <= upper) {
            val end = minOf(start + WINDOW_MILLIS - 1, upper)
            result += start..end
            start = end + 1
        }
        return result
    }

    private fun JsonNode.requireArray(name: String): JsonNode = if (isArray) {
        this
    } else {
        binanceUnexpected(
            "$name response is not an array",
        )
    }

    private fun JsonNode.requireLong(field: String): Long =
        get(field)?.takeIf { it.isIntegralNumber }?.asLong() ?: binanceUnexpected("field $field is missing")

    private companion object {
        const val SYMBOL = "BTCUSDT"
        const val PAGE_LIMIT = 1000
        const val MINUTE_MILLIS = 60_000L
        const val WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val TRADE_LIMIT_MESSAGE = "Binance userTrades window reached $PAGE_LIMIT rows"
        const val INCOME_LIMIT_MESSAGE = "Binance income page is full at a single timestamp"
    }
}
