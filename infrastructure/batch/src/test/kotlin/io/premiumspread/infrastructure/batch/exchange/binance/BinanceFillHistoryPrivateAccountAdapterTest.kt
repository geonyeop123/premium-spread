package io.premiumspread.infrastructure.batch.exchange.binance

import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.FillHistoryWindowLimitException
import io.premiumspread.domain.accountpnl.ForeignPerpFill
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AC6 — 바이낸스 adapter는 `lower`부터 `upper`까지 7일 이하 창으로 GET `/fapi/v1/userTrades`를 보내고,
 * GET `/fapi/v1/income`은 `incomeType=FUNDING_FEE`와 `symbol=BTCUSDT`를 붙여 보낸다. 응답에 섞인 다른 심볼
 * 펀딩 행은 버리고, 창들이 같은 id를 돌려주면 한 번만 담는다. USDT·BNB 수수료가 음수로 와도 절댓값으로 돌려준다.
 * BNB 수수료는 체결 시각 1분봉(GET `/fapi/v1/klines` BNBUSDT 1m)의 시가로 환산하고, 같은 입력을 두 번 호출하면
 * 같은 USDT 값이 나온다. 창 하나가 1000건이면 `WINDOW_LIMIT`로 실패한다. 숏 체결은 음수 부호 수량이다.
 */
class BinanceFillHistoryPrivateAccountAdapterTest {
    private lateinit var server: MockWebServer
    private val clock = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val from = Instant.parse("2026-10-01T00:00:00Z")
    private val to = from.plus(Duration.ofDays(20))
    private val f = from.toEpochMilli()
    private val day = 86_400_000L
    private val week = 7 * day

    private data class T(
        val id: Long,
        val time: Long,
        val side: String,
        val qty: String,
        val pnl: String = "0",
        val fee: String = "0.01",
        val asset: String = "USDT",
        val positionSide: String = "BOTH",
    )

    private data class F(val tranId: Long, val time: Long, val income: String, val symbol: String = "BTCUSDT", val asset: String = "USDT")

    private val trades = CopyOnWriteArrayList<T>()
    private val tradesInEveryWindow = CopyOnWriteArrayList<T>()
    private val funding = CopyOnWriteArrayList<F>()
    private val klineOpens = mutableMapOf<Long, String>()
    private val klineOpenTimeShift = mutableMapOf<Long, Long>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var bnbTicker = "999"

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val url = request.requestUrl!!
                return when (url.encodedPath) {
                    "/fapi/v1/time" -> json("""{"serverTime":${clock.millis()}}""")
                    "/fapi/v1/userTrades" -> json(userTrades(url.queryParameter("startTime")!!.toLong(), url.queryParameter("endTime")!!.toLong()))
                    "/fapi/v1/income" -> json(income(url.queryParameter("startTime")!!.toLong(), url.queryParameter("endTime")!!.toLong()))
                    "/fapi/v1/klines" -> kline(url.queryParameter("startTime")!!.toLong())
                    "/fapi/v2/ticker/price" -> json("""{"symbol":"BNBUSDT","price":"$bnbTicker","time":1}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `20일 창은 7일 이하 3구간으로 앞으로 걸어 GET userTrades 를 보내고 중복 id 와 창 밖 체결은 빼고 숏은 음수다`() {
        tradesInEveryWindow += T(10, f, "SELL", "0.009")
        trades += T(11, f + day, "SELL", "0.003", pnl = "0", fee = "-0.05")
        tradesInEveryWindow += T(12, f + week, "BUY", "0.001", pnl = "1.5", fee = "0.02", positionSide = "SHORT")
        trades += T(13, f + 15 * day, "BUY", "0.002", pnl = "-2.25", fee = "0.03", positionSide = "LONG")

        val fills = adapter().fills(from, to)

        assertThat(fills.map(::describe)).containsExactly(
            "11 -0.003 0 0.05 ${Instant.ofEpochMilli(f + day)}",
            "12 0.001 1.5 0.02 ${Instant.ofEpochMilli(f + week)}",
            "13 0.002 -2.25 0.03 ${Instant.ofEpochMilli(f + 15 * day)}",
        )
        val calls = calls("/fapi/v1/userTrades")
        assertThat(calls.map { it.requestUrl!!.queryParameter("startTime") to it.requestUrl!!.queryParameter("endTime") }).containsExactly(
            "${f + 1}" to "${f + week}",
            "${f + week + 1}" to "${f + 2 * week}",
            "${f + 2 * week + 1}" to "${f + 20 * day}",
        )
        assertThat(calls).allSatisfy {
            val url = it.requestUrl!!
            assertThat(url.queryParameter("symbol")).isEqualTo("BTCUSDT")
            assertThat(url.queryParameter("limit")).isEqualTo("1000")
            assertThat(url.queryParameter("endTime")!!.toLong() - url.queryParameter("startTime")!!.toLong()).isLessThan(week)
            assertThat(it.getHeader("X-MBX-APIKEY")).isEqualTo("test-api-key")
            val query = url.encodedQuery!!
            assertThat(query.substringAfter("&signature=")).isEqualTo(BinanceRequestSigner.sign("test-secret-key", query.substringBefore("&signature=")))
        }
        assertThat(requests.map { it.method }.toSet()).containsExactly("GET")
    }

    @Test
    fun `창 하나가 1000건이면 WindowLimit 으로 실패한다`() {
        (1..1000).forEach { trades += T(it.toLong(), f + week + it, "BUY", "0.001") }

        assertThatThrownBy { adapter().fills(from, to) }.isInstanceOf(FillHistoryWindowLimitException::class.java)
    }

    @Test
    fun `창 하나가 999건이면 실패하지 않는다`() {
        (1..999).forEach { trades += T(it.toLong(), f + week + it, "BUY", "0.001") }

        assertThat(adapter().fills(from, to)).hasSize(999)
    }

    @Test
    fun `하한이 상한보다 늦거나 같으면 거래소를 부르지 않고 빈 목록이다`() {
        assertThat(adapter().fills(to, to)).isEmpty()
        assertThat(adapter().funding(to, from)).isEmpty()
        assertThat(requests).isEmpty()
    }

    @Test
    fun `BNB 수수료는 체결 분의 1분봉 시가로 환산하고 같은 분은 한 번만 읽으며 두 번 호출해도 같은 값이다`() {
        val minute = f + 2 * day + 5 * 60_000
        klineOpens[minute] = "600.5"
        klineOpens[minute + 60_000] = "601"
        trades += T(21, minute + 30_000, "SELL", "0.001", fee = "-0.0001", asset = "BNB")
        trades += T(22, minute + 45_000, "SELL", "0.001", fee = "0.0002", asset = "BNB")
        trades += T(23, minute + 60_001, "SELL", "0.001", fee = "-0.0003", asset = "BNB")

        val first = adapter().fills(from, to)
        bnbTicker = "1"
        val second = adapter().fills(from, to)

        assertThat(first.map { it.commissionUsdt }).usingElementComparator(BigDecimal::compareTo)
            .containsExactly(BigDecimal("0.06005"), BigDecimal("0.1201"), BigDecimal("0.1803"))
        assertThat(second.map { it.commissionUsdt }).isEqualTo(first.map { it.commissionUsdt })
        val klineCalls = calls("/fapi/v1/klines")
        assertThat(klineCalls.map { it.requestUrl!!.encodedQuery }).containsExactly(
            "symbol=BNBUSDT&interval=1m&startTime=$minute&limit=1",
            "symbol=BNBUSDT&interval=1m&startTime=${minute + 60_000}&limit=1",
            "symbol=BNBUSDT&interval=1m&startTime=$minute&limit=1",
            "symbol=BNBUSDT&interval=1m&startTime=${minute + 60_000}&limit=1",
        )
        assertThat(klineCalls).allSatisfy { assertThat(it.getHeader("X-MBX-APIKEY")).isNull() }
        assertThat(calls("/fapi/v2/ticker/price")).isEmpty()
        assertThat(requests.map { it.method }.toSet()).containsExactly("GET")
    }

    @Test
    fun `1분봉 응답의 시작 시각이 체결 분과 다르면 UNEXPECTED 로 실패한다`() {
        val minute = f + day
        klineOpens[minute] = "600"
        klineOpenTimeShift[minute] = 60_000
        trades += T(31, minute + 1, "SELL", "0.001", fee = "0.0001", asset = "BNB")

        assertUnexpected { adapter().fills(from, to) }
    }

    @Test
    fun `1분봉이 비어 있으면 UNEXPECTED 로 실패한다`() {
        trades += T(32, f + day, "SELL", "0.001", fee = "0.0001", asset = "BNB")

        assertUnexpected { adapter().fills(from, to) }
    }

    @Test
    fun `USDT BNB 외 수수료 자산은 UNEXPECTED 로 실패한다`() {
        trades += T(33, f + day, "SELL", "0.001", asset = "BTC")

        assertUnexpected { adapter().fills(from, to) }
    }

    @Test
    fun `알 수 없는 side 는 UNEXPECTED 로 실패한다`() {
        trades += T(34, f + day, "HOLD", "0.001")

        assertUnexpected { adapter().fills(from, to) }
    }

    @Test
    fun `income 은 FUNDING_FEE 와 BTCUSDT 심볼을 붙여 같은 7일 창으로 GET 하고 다른 심볼 행은 버린다`() {
        funding += F(1, f + 8 * 3_600_000, "-0.01")
        funding += F(2, f + 16 * 3_600_000, "5", symbol = "ETHUSDT")
        funding += F(3, f + 9 * day, "0.02")
        funding += F(4, f, "7")

        val rows = adapter().funding(from, to)

        assertThat(rows.map { "${it.id} ${it.incomeUsdt.toPlainString()} ${it.at}" }).containsExactly(
            "1 -0.01 ${Instant.ofEpochMilli(f + 8 * 3_600_000)}",
            "3 0.02 ${Instant.ofEpochMilli(f + 9 * day)}",
        )
        val calls = calls("/fapi/v1/income")
        assertThat(calls.map { it.requestUrl!!.queryParameter("startTime") to it.requestUrl!!.queryParameter("endTime") }).containsExactly(
            "${f + 1}" to "${f + week}",
            "${f + week + 1}" to "${f + 2 * week}",
            "${f + 2 * week + 1}" to "${f + 20 * day}",
        )
        assertThat(calls).allSatisfy {
            val url = it.requestUrl!!
            assertThat(url.queryParameter("incomeType")).isEqualTo("FUNDING_FEE")
            assertThat(url.queryParameter("symbol")).isEqualTo("BTCUSDT")
            assertThat(url.queryParameter("limit")).isEqualTo("1000")
            assertThat(it.getHeader("X-MBX-APIKEY")).isEqualTo("test-api-key")
        }
        assertThat(requests.map { it.method }.toSet()).containsExactly("GET")
    }

    @Test
    fun `income 창 하나가 1000행이면 마지막 시각부터 이어 읽고 tranId 로 한 번만 담는다`() {
        (0 until 1000).forEach { funding += F(100L + it, f + 1 + it * 1_000L, "0.001") }
        val last = f + 1 + 999 * 1_000L
        funding += F(5000, last + 1, "0.002")

        val rows = adapter().funding(from, to)

        assertThat(rows).hasSize(1001)
        assertThat(rows.map { it.id }.toSet()).hasSize(1001)
        val firstWindow = calls("/fapi/v1/income").map { it.requestUrl!!.queryParameter("startTime") to it.requestUrl!!.queryParameter("endTime") }
        assertThat(firstWindow.take(2)).containsExactly("${f + 1}" to "${f + week}", "$last" to "${f + week}")
        assertThat(firstWindow).hasSize(4)
    }

    @Test
    fun `income 한 페이지 1000행이 모두 같은 시각이면 이어 읽을 수 없어 WindowLimit 으로 실패한다`() {
        (0 until 1000).forEach { funding += F(100L + it, f + day, "0.001") }

        assertThatThrownBy { adapter().funding(from, to) }.isInstanceOf(FillHistoryWindowLimitException::class.java)
    }

    @Test
    fun `BTCUSDT income 의 asset 이 USDT 가 아니면 UNEXPECTED 로 실패한다`() {
        funding += F(1, f + day, "0.01", asset = "BNB")

        assertUnexpected { adapter().funding(from, to) }
    }

    private fun assertUnexpected(call: () -> Unit) {
        assertThatThrownBy { call() }
            .isInstanceOfSatisfying(AccountReadException::class.java) { assertThat(it.category).isEqualTo(AccountReadFailureCategory.UNEXPECTED) }
    }

    private fun describe(fill: ForeignPerpFill) =
        "${fill.id} ${fill.signedQuantity.toPlainString()} ${fill.realizedPnlUsdt.toPlainString()} ${fill.commissionUsdt.toPlainString()} ${fill.filledAt}"

    private fun calls(path: String) = requests.filter { it.requestUrl!!.encodedPath == path }

    private fun userTrades(start: Long, end: Long): String {
        val rows = trades.filter { it.time in start..end }.sortedBy { it.time }.take(LIMIT) + tradesInEveryWindow
        return rows.joinToString(",", "[", "]") {
            """{"symbol":"BTCUSDT","id":${it.id},"orderId":1,"side":"${it.side}","price":"60000","qty":"${it.qty}",""" +
                """"realizedPnl":"${it.pnl}","commission":"${it.fee}","commissionAsset":"${it.asset}",""" +
                """"positionSide":"${it.positionSide}","buyer":false,"maker":false,"time":${it.time}}"""
        }
    }

    private fun income(start: Long, end: Long): String = funding.filter { it.time in start..end }.sortedBy { it.time }.take(LIMIT)
        .joinToString(",", "[", "]") {
            """{"symbol":"${it.symbol}","incomeType":"FUNDING_FEE","income":"${it.income}","asset":"${it.asset}",""" +
                """"info":"FUNDING_FEE","time":${it.time},"tranId":${it.tranId},"tradeId":""}"""
        }

    private fun kline(start: Long): MockResponse {
        val open = klineOpens[start] ?: return json("[]")
        val openTime = start + (klineOpenTimeShift[start] ?: 0L)
        return json("""[[$openTime,"$open","700","500","650","10",${openTime + 59_999},"6500",5,"5","3250","0"]]""")
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun adapter() = BinanceFillHistoryPrivateAccountAdapter(
        WebClient.builder().baseUrl(server.url("/").toString().removeSuffix("/")).build(),
        "test-api-key",
        "test-secret-key",
        clock,
        timeout = Duration.ofSeconds(3),
    )

    private companion object {
        const val LIMIT = 1000
    }
}
