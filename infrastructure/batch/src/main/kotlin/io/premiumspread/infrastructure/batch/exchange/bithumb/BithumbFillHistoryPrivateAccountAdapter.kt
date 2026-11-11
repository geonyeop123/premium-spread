package io.premiumspread.infrastructure.batch.exchange.bithumb

import com.fasterxml.jackson.databind.JsonNode
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.FillHistoryWindowLimitException
import io.premiumspread.domain.accountpnl.FillSide
import io.premiumspread.domain.accountpnl.KoreaSpotFill
import io.premiumspread.domain.accountpnl.KoreaSpotFillReadPort
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * 빗썸 KRW-BTC 체결 이력 조회. 모든 호출은 GET 이며 실패는 [AccountReadException] 으로 던진다
 * (페이지 한도 초과만 [FillHistoryWindowLimitException]).
 *
 * 주문 목록(wait·done·cancel, 최신순)을 페이지로 읽고, 체결 수량이 있는 주문마다 주문 상세의 체결 목록을 읽는다.
 * 페이지가 다 차지 않았거나 페이지 마지막 주문이 `fromExclusive − 7일` 보다 오래되면 멈춘다.
 */
class BithumbFillHistoryPrivateAccountAdapter(
    webClient: WebClient,
    accessKey: String,
    secretKey: String,
    clock: Clock,
    timeout: Duration = Duration.ofSeconds(5),
) : KoreaSpotFillReadPort {
    private val client = BithumbPrivateReadClient(webClient, accessKey, secretKey, clock, timeout)

    override fun read(fromExclusive: Instant, toInclusive: Instant): List<KoreaSpotFill> {
        val cutoff = fromExclusive.minus(LOOKBACK)
        val seen = mutableSetOf<String>()
        val fills = mutableListOf<KoreaSpotFill>()
        var page = 1
        while (true) {
            val rows = readOrderPage(page)
            rows.filter { it.executedVolume.signum() > 0 && it.createdAt <= toInclusive && seen.add(it.id) }
                .forEach { fills += readOrderFills(it, fromExclusive, toInclusive) }
            if (rows.size < ORDER_PAGE_SIZE || rows.last().createdAt < cutoff) break
            if (page >= MAX_ORDER_PAGES) throw FillHistoryWindowLimitException(WINDOW_LIMIT_MESSAGE)
            page += 1
        }
        return fills.sortedWith(compareBy<KoreaSpotFill> { it.filledAt }.thenBy { it.id })
    }

    private data class OrderRow(val id: String, val state: String, val executedVolume: BigDecimal, val createdAt: Instant) {
        override fun toString(): String = "OrderRow(createdAt=$createdAt)"
    }

    private data class Trade(val id: String, val volume: BigDecimal, val funds: BigDecimal, val createdAt: Instant) {
        override fun toString(): String = "Trade(createdAt=$createdAt)"
    }

    private fun readOrderPage(page: Int): List<OrderRow> {
        val query = "market=KRW-BTC&states[]=wait&states[]=done&states[]=cancel&order_by=desc&limit=$ORDER_PAGE_SIZE&page=$page"
        return client.get("/v1/orders", query, signed = true).requireArray("orders").map {
            val executed = it.dec("executed_volume")
            if (executed.signum() < 0) bithumbUnexpected("order executed volume is negative")
            OrderRow(it.requireId("uuid"), it.path("state").asText(), executed, it.instant("created_at"))
        }
    }

    private fun readOrderFills(row: OrderRow, fromExclusive: Instant, toInclusive: Instant): List<KoreaSpotFill> {
        val orderId = row.id
        val order = client.get("/v1/order", "uuid=$orderId", signed = true)
        val side = when (order.path("side").asText()) {
            "bid" -> FillSide.BUY
            "ask" -> FillSide.SELL
            else -> bithumbUnexpected("order side is not bid or ask")
        }
        val paidFee = order.dec("paid_fee")
        if (paidFee.signum() < 0) bithumbUnexpected("order paid fee is negative")
        val tradesNode = order.get("trades") ?: bithumbUnexpected("order trades are missing")
        val trades = tradesNode.requireArray("trades").map(::parseTrade)
            .sortedWith(compareBy<Trade> { it.createdAt }.thenBy { it.id })
        // wait 주문은 목록 조회와 상세 조회 사이에 더 체결될 수 있어 대조하지 않는다.
        val tradedVolume = trades.fold(BigDecimal.ZERO) { sum, trade -> sum.add(trade.volume) }
        if (row.state != "wait" && tradedVolume.compareTo(row.executedVolume) != 0) {
            bithumbUnexpected("order trades do not cover the executed volume")
        }
        val fees = allocateFee(paidFee, trades)
        return trades.indices
            .filter { trades[it].createdAt > fromExclusive && trades[it].createdAt <= toInclusive }
            .map {
                val trade = trades[it]
                KoreaSpotFill("$orderId:${trade.id}", side, trade.volume, trade.funds, fees[it], trade.createdAt)
            }
    }

    private fun parseTrade(node: JsonNode): Trade {
        val volume = node.dec("volume")
        val funds = node.dec("funds")
        if (volume.signum() <= 0 || funds.signum() <= 0) bithumbUnexpected("trade value is not positive")
        return Trade(node.requireId("uuid"), volume, funds, node.instant("created_at"))
    }

    /** 주문 전체 체결의 funds 비율로 나누고, 반올림 나머지는 마지막 체결([trades] 정렬 순)에 넣어 합을 [paidFee] 와 같게 한다. */
    private fun allocateFee(paidFee: BigDecimal, trades: List<Trade>): List<BigDecimal> {
        if (trades.isEmpty()) return emptyList()
        val totalFunds = trades.fold(BigDecimal.ZERO) { sum, trade -> sum.add(trade.funds) }
        val scale = paidFee.scale() + FEE_EXTRA_SCALE
        val shares = trades.dropLast(1).map {
            paidFee.multiply(it.funds).divide(totalFunds, scale, RoundingMode.HALF_DOWN)
        }
        val allocated = shares.fold(BigDecimal.ZERO) { sum, share -> sum.add(share) }
        val remainder = paidFee.subtract(allocated)
        if (remainder.signum() < 0) bithumbUnexpected("fee allocation remainder is negative")
        return shares + remainder
    }

    private fun JsonNode.requireId(field: String): String {
        val id = path(field).asText()
        if (!ID_PATTERN.matches(id)) bithumbUnexpected("field $field is missing or malformed")
        return id
    }

    private fun JsonNode.instant(field: String): Instant {
        val text = get(field)?.takeIf { it.isTextual }?.asText() ?: bithumbUnexpected("field $field is missing")
        return try {
            OffsetDateTime.parse(text).toInstant()
        } catch (e: DateTimeParseException) {
            throw AccountReadException(AccountReadFailureCategory.UNEXPECTED, "field $field is not a timestamp", e)
        }
    }

    private companion object {
        const val ORDER_PAGE_SIZE = 100
        const val MAX_ORDER_PAGES = 20
        const val FEE_EXTRA_SCALE = 8
        const val WINDOW_LIMIT_MESSAGE = "Bithumb order history exceeded $MAX_ORDER_PAGES pages"
        val LOOKBACK: Duration = Duration.ofDays(7)

        /** 주문·체결 id 는 query 에 그대로 붙으므로 영숫자와 `-` 만 받는다. */
        val ID_PATTERN = Regex("[A-Za-z0-9-]+")
    }
}
