package io.premiumspread.infrastructure.batch.exchange.bithumb

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.KoreaSpotAccountReadPort
import io.premiumspread.domain.accountpnl.KoreaSpotAccountSnapshot
import io.premiumspread.domain.accountpnl.PriceLevel
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeoutException

/** 빗썸 read-only 계정 조회. 모든 호출은 GET 이며 실패는 [AccountReadException] 으로만 던진다. */
class BithumbPrivateAccountAdapter(
    private val webClient: WebClient,
    private val accessKey: String,
    private val secretKey: String,
    private val clock: Clock,
    private val timeout: Duration = Duration.ofSeconds(5),
) : KoreaSpotAccountReadPort {
    private val mapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    @Volatile
    private var feeCache: FeeCache? = null

    @Synchronized
    override fun read(): KoreaSpotAccountSnapshot {
        val account = readAccount()
        val orderbook = readOrderbook()
        val lastPrice = readLastPrice()
        val sellFeeRate = readSellFeeRate()
        val entryFee = if (account.quantity.signum() > 0) readEntryFee(account.quantity, account.averageBuyPrice) else null
        return KoreaSpotAccountSnapshot(
            quantity = account.quantity,
            averageBuyPrice = account.averageBuyPrice,
            averageBuyPriceModified = account.modified,
            entryFeeKrw = entryFee,
            sellFeeRate = sellFeeRate,
            bidLevels = orderbook.first,
            lastPrice = lastPrice,
            bidObservedAt = orderbook.second,
        )
    }

    private data class Account(val quantity: BigDecimal, val averageBuyPrice: BigDecimal, val modified: Boolean)

    private data class FeeCache(
        val quantity: BigDecimal,
        val averageBuyPrice: BigDecimal,
        val newestOrderId: String?,
        val fee: BigDecimal?,
    )

    private fun readAccount(): Account {
        val root = getJson("/v1/accounts", null, signed = true)
        val rows = root.requireArray("accounts")
        val row = rows.firstOrNull { it.path("currency").asText() == "BTC" && it.path("unit_currency").asText() == "KRW" }
            ?: return Account(BigDecimal.ZERO, BigDecimal.ZERO, false)
        val balance = row.dec("balance")
        val locked = row.dec("locked")
        val avg = row.dec("avg_buy_price")
        if (balance.signum() < 0 || locked.signum() < 0 || avg.signum() < 0) unexpected("account value is negative")
        if (balance.add(locked).signum() > 0 && avg.signum() == 0) unexpected("average buy price is zero for a held position")
        return Account(balance.add(locked), avg, row.path("avg_buy_price_modified").asBoolean(false))
    }

    private fun readOrderbook(): Pair<List<PriceLevel>, Instant> {
        val root = getJson("/v1/orderbook", "markets=KRW-BTC", signed = false)
        val book = root.requireArray("orderbook").firstOrNull() ?: unexpected("orderbook is empty")
        val units = (book.get("orderbook_units") ?: unexpected("orderbook_units is missing")).requireArray("orderbook_units")
        val levels = units.map {
            val price = it.dec("bid_price")
            val size = it.dec("bid_size")
            if (price.signum() <= 0 || size.signum() <= 0) unexpected("orderbook level is not positive")
            PriceLevel(price, size)
        }.sortedByDescending { it.price }
        if (levels.isEmpty()) unexpected("orderbook has no bid levels")
        val timestamp = book.get("timestamp")?.takeIf { it.isNumber }?.asLong() ?: unexpected("orderbook timestamp is missing")
        return levels to Instant.ofEpochMilli(timestamp)
    }

    private fun readLastPrice(): BigDecimal {
        val root = getJson("/v1/ticker", "markets=KRW-BTC", signed = false)
        val price = root.requireArray("ticker").firstOrNull()?.dec("trade_price") ?: unexpected("ticker is empty")
        if (price.signum() <= 0) unexpected("last price is not positive")
        return price
    }

    private fun readSellFeeRate(): BigDecimal {
        val fee = getJson("/v1/orders/chance", "market=KRW-BTC", signed = true).dec("ask_fee")
        if (fee.signum() < 0) unexpected("ask fee is negative")
        return fee
    }

    private fun readEntryFee(quantity: BigDecimal, averageBuyPrice: BigDecimal): BigDecimal? {
        val firstPage = readOrderPage(1)
        val newest = firstPage.firstOrNull { it.qualifies() }?.id
        val cached = feeCache
        if (cached != null && cached.quantity.compareTo(quantity) == 0 &&
            cached.averageBuyPrice.compareTo(averageBuyPrice) == 0 && cached.newestOrderId == newest
        ) {
            return cached.fee
        }
        val fee = walkHistory(quantity, firstPage)
        feeCache = FeeCache(quantity, averageBuyPrice, newest, fee)
        return fee
    }

    private fun walkHistory(quantity: BigDecimal, firstPage: List<OrderRow>): BigDecimal? {
        var accumulated = BigDecimal.ZERO
        var fee = BigDecimal.ZERO
        var page = 1
        var rows = firstPage
        while (true) {
            for (order in rows) {
                if (!order.qualifies()) continue
                accumulated = accumulated.add(order.executedVolume)
                fee = fee.add(order.paidFee)
                val cmp = accumulated.compareTo(quantity)
                if (cmp == 0) return fee
                if (cmp > 0) return null
            }
            if (rows.size < ORDER_PAGE_SIZE || page >= MAX_ORDER_PAGES) return null
            page += 1
            rows = readOrderPage(page)
        }
    }

    private data class OrderRow(val id: String, val side: String, val executedVolume: BigDecimal, val paidFee: BigDecimal) {
        fun qualifies() = side == "bid" && executedVolume.signum() > 0
    }

    private fun readOrderPage(page: Int): List<OrderRow> {
        val query = "market=KRW-BTC&states[]=done&states[]=cancel&order_by=desc&limit=$ORDER_PAGE_SIZE&page=$page"
        val root = getJson("/v1/orders", query, signed = true)
        return root.requireArray("orders").map {
            val executed = it.dec("executed_volume")
            val paid = it.dec("paid_fee")
            if (executed.signum() < 0 || paid.signum() < 0) unexpected("order value is negative")
            OrderRow(it.path("uuid").asText().ifEmpty { unexpected("order id is missing") }, it.path("side").asText(), executed, paid)
        }
    }

    private fun getJson(path: String, query: String?, signed: Boolean): JsonNode {
        val body = try {
            var wireQuery: String? = null
            webClient.get()
                .uri { builder ->
                    val uri = builder.path(path).also { if (query != null) it.query(query) }.build()
                    wireQuery = uri.rawQuery
                    uri
                }
                .headers { headers ->
                    if (signed) {
                        val jwt = BithumbJwtSigner.sign(
                            accessKey, secretKey, wireQuery, UUID.randomUUID().toString(), clock.millis(),
                        )
                        headers.setBearerAuth(jwt)
                    }
                }
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(timeout)
                .block()
        } catch (e: WebClientResponseException) {
            throw AccountReadException(statusCategory(e.statusCode.value()), "Bithumb $path responded ${e.statusCode.value()}", e)
        } catch (e: WebClientRequestException) {
            throw AccountReadException(AccountReadFailureCategory.NETWORK, "Bithumb $path request failed", e)
        } catch (e: AccountReadException) {
            throw e
        } catch (e: RuntimeException) {
            val timedOut = generateSequence<Throwable>(e) { it.cause }.any { it is TimeoutException }
            val category = if (timedOut) AccountReadFailureCategory.NETWORK else AccountReadFailureCategory.UNEXPECTED
            throw AccountReadException(category, "Bithumb $path call failed", e)
        } ?: unexpected("Bithumb $path returned an empty body")
        return try {
            mapper.readTree(body) ?: unexpected("Bithumb $path returned an empty body")
        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
            throw AccountReadException(AccountReadFailureCategory.UNEXPECTED, "Bithumb $path returned malformed JSON", e)
        }
    }

    private fun statusCategory(status: Int) = when {
        status == 401 -> AccountReadFailureCategory.BITHUMB_AUTH
        status == 429 || status >= 500 -> AccountReadFailureCategory.NETWORK
        else -> AccountReadFailureCategory.UNEXPECTED
    }

    private fun JsonNode.requireArray(name: String): JsonNode =
        if (isArray) this else unexpected("$name response is not an array")

    private fun JsonNode.dec(field: String): BigDecimal {
        val node = get(field) ?: unexpected("field $field is missing")
        return try {
            when {
                node.isNumber -> node.decimalValue()
                node.isTextual -> BigDecimal(node.asText())
                else -> unexpected("field $field is not a number")
            }
        } catch (e: NumberFormatException) {
            throw AccountReadException(AccountReadFailureCategory.UNEXPECTED, "field $field is not a number", e)
        }
    }

    private fun unexpected(message: String): Nothing =
        throw AccountReadException(AccountReadFailureCategory.UNEXPECTED, message)

    private companion object {
        const val ORDER_PAGE_SIZE = 100
        const val MAX_ORDER_PAGES = 10
    }
}
