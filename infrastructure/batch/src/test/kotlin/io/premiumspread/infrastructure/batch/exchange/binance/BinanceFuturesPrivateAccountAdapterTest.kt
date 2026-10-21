package io.premiumspread.infrastructure.batch.exchange.binance

import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class BinanceFuturesPrivateAccountAdapterTest {
    private lateinit var server: MockWebServer
    private val clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC)
    private val now = clock.millis()
    private val day = 86_400_000L

    private data class T(val id: Long, val time: Long, val side: String, val qty: String, val fee: String = "0.05", val asset: String = "USDT", val positionSide: String = "BOTH")
    private data class F(val tranId: Long, val time: Long, val income: String)

    private val trades = CopyOnWriteArrayList<T>()
    private val funding = CopyOnWriteArrayList<F>()
    private val responses = mutableMapOf<String, () -> MockResponse>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var positionBody = positionRisk("BOTH", "-0.002", "61000")
    private var bnbPrice = "600"
    private var serverTimeOffset = 0L

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val url = request.requestUrl!!
                val path = url.encodedPath
                responses[path]?.let { return it() }
                return when (path) {
                    "/fapi/v1/time" -> json("""{"serverTime":${now + serverTimeOffset}}""")

                    "/fapi/v3/positionRisk" -> json(positionBody)

                    "/fapi/v1/userTrades" -> json(userTrades(url))

                    "/fapi/v1/income" -> json(income(url))

                    "/fapi/v1/commissionRate" -> json(fixture("commission-rate.json"))

                    "/fapi/v1/depth" -> json(fixture("depth.json"))

                    "/fapi/v2/ticker/price" ->
                        if (url.queryParameter("symbol") == "BNBUSDT") {
                            json("""{"symbol":"BNBUSDT","price":"$bnbPrice","time":1}""")
                        } else {
                            json(fixture("ticker-price.json"))
                        }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `서명은 문서 예제 벡터와 같다`() {
        val signature = BinanceRequestSigner.sign(
            "2b5eb11e18796d12d88f13dc27dbbd02c2cc51ff7059765ed9821957d82bb4d9",
            "symbol=BTCUSDT&side=BUY&type=LIMIT&quantity=1&price=9000&timeInForce=GTC&recvWindow=5000&timestamp=1591702613943",
        )

        assertThat(signature).isEqualTo("3c661234138461fcc7a7d8746c6558c9842d4e10870d2ecbedf7777cad694af9")
    }

    @Test
    fun `X-MBX-APIKEY 헤더와 recvWindow=10000 이 붙고 서명은 실제 전송 query 와 같다`() {
        trades += T(1, now - day, "SELL", "0.002")

        adapter().read()

        val request = requests.first { it.requestUrl!!.encodedPath == "/fapi/v3/positionRisk" }
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.getHeader("X-MBX-APIKEY")).isEqualTo("test-api-key")
        val query = request.requestUrl!!.encodedQuery!!
        assertThat(query).contains("recvWindow=10000", "timestamp=$now")
        val signed = query.substringBefore("&signature=")
        assertThat(query.substringAfter("&signature=")).isEqualTo(BinanceRequestSigner.sign("test-secret-key", signed))
        assertThat(query).endsWith(query.substringAfterLast("&signature="))
        assertThat(requests.filter { it.requestUrl!!.encodedPath.startsWith("/fapi/v1/depth") }.first().getHeader("X-MBX-APIKEY")).isNull()
    }

    @Test
    fun `서명 timestamp 에 서버 시각 오프셋을 반영한다`() {
        serverTimeOffset = 3_000
        trades += T(1, now - day, "SELL", "0.002")

        adapter().read()

        val signedRequests = requests.filter { it.requestUrl!!.encodedQuery?.contains("signature=") == true }
        assertThat(signedRequests).isNotEmpty()
        assertThat(signedRequests).allSatisfy { assertThat(it.requestUrl!!.queryParameter("timestamp")).isEqualTo("${now + 3_000}") }
        assertThat(requests.count { it.requestUrl!!.encodedPath == "/fapi/v1/time" }).isEqualTo(1)
    }

    @Test
    fun `-1021 이면 오프셋을 다시 재서 한 번 재시도하고 계속 거부되면 NETWORK`() {
        var rejected = 1
        responses["/fapi/v3/positionRisk"] = {
            if (rejected-- > 0) {
                MockResponse().setResponseCode(400)
                    .setBody("""{"code":-1021,"msg":"Timestamp for this request is outside of the recvWindow."}""")
            } else {
                json(positionBody)
            }
        }
        trades += T(1, now - day, "SELL", "0.002")
        adapter().read()
        assertThat(requests.count { it.requestUrl!!.encodedPath == "/fapi/v1/time" }).isEqualTo(2)

        responses["/fapi/v3/positionRisk"] = { MockResponse().setResponseCode(400).setBody("""{"code":-1021,"msg":"x"}""") }
        assertCategory(AccountReadFailureCategory.NETWORK)
    }

    @Test
    fun `숏 수량과 진입가를 읽는다`() {
        trades += T(1, now - day, "SELL", "0.002")

        val snapshot = adapter().read()

        assertThat(snapshot.signedQuantity).isEqualByComparingTo("-0.002")
        assertThat(snapshot.entryPrice).isEqualByComparingTo("61000")
        assertThat(snapshot.takerFeeRate).isEqualByComparingTo("0.0004")
        assertThat(snapshot.lastPrice).isEqualByComparingTo("6000.01")
    }

    @Test
    fun `hedge mode 면 SHORT 행을 읽는다`() {
        positionBody = fixture("position-risk-hedge.json")
        trades += T(1, now - day, "SELL", "0.002", positionSide = "SHORT")
        trades += T(2, now - day / 2, "BUY", "0.005", positionSide = "LONG")

        val snapshot = adapter().read()

        assertThat(snapshot.signedQuantity).isEqualByComparingTo("-0.002")
        assertThat(snapshot.entryPrice).isEqualByComparingTo("61000.0")
        assertThat(snapshot.entryCommissionUsdt).isEqualByComparingTo("0.05")
    }

    @Test
    fun `포지션이 없으면 수량 0 이고 이력 조회를 하지 않는다`() {
        positionBody = "[]"

        val snapshot = adapter().read()

        assertThat(snapshot.signedQuantity.signum()).isZero()
        assertThat(snapshot.entryPrice.signum()).isZero()
        assertThat(requests.map { it.requestUrl!!.encodedPath }).doesNotContain("/fapi/v1/userTrades", "/fapi/v1/income")
    }

    @Test
    fun `체결 수수료를 포지션 수량만큼 합산한다`() {
        trades += T(1, now - 40 * day, "BUY", "0.001", fee = "9")
        trades += T(2, now - 40 * day + 1000, "SELL", "0.001", fee = "9")
        trades += T(3, now - 10 * day, "SELL", "0.001", fee = "0.03")
        trades += T(4, now - 9 * day, "SELL", "0.001", fee = "0.02")
        funding += F(1, now - 8 * day, "-0.10")

        val snapshot = adapter().read()

        assertThat(snapshot.entryCommissionUsdt).isEqualByComparingTo("0.05")
        assertThat(snapshot.fundingUsdt).isEqualByComparingTo("-0.10")
        val incomeStart = requests.first { it.requestUrl!!.encodedPath == "/fapi/v1/income" }.requestUrl!!.queryParameter("startTime")
        assertThat(incomeStart).isEqualTo("${now - 10 * day}")
    }

    @Test
    fun `BNB 수수료는 BNBUSDT 현재가로 환산해 합산한다`() {
        trades += T(1, now - day, "SELL", "0.001", fee = "0.01", asset = "USDT")
        trades += T(2, now - day / 2, "SELL", "0.001", fee = "0.002", asset = "BNB")

        val snapshot = adapter().read()

        assertThat(snapshot.entryCommissionUsdt).isEqualByComparingTo("1.21")
    }

    @Test
    fun `같은 포지션에서 BNBUSDT 가 바뀌면 다음 회차 환산 수수료도 바뀐다`() {
        trades += T(1, now - day, "SELL", "0.002", fee = "0.002", asset = "BNB")
        val adapter = adapter()
        assertThat(adapter.read().entryCommissionUsdt).isEqualByComparingTo("1.2")
        requests.clear()
        bnbPrice = "700"

        assertThat(adapter.read().entryCommissionUsdt).isEqualByComparingTo("1.4")

        val tradeCalls = requests.filter { it.requestUrl!!.encodedPath == "/fapi/v1/userTrades" }
        assertThat(tradeCalls).hasSize(1)
        assertThat(tradeCalls.single().requestUrl!!.queryParameter("fromId")).isEqualTo("2")
        assertThat(tradeCalls.single().requestUrl!!.queryParameter("startTime")).isNull()
    }

    @Test
    fun `같은 수량 진입가로 재진입해 새 체결이 생기면 진입 시각 수수료 펀딩을 다시 계산한다`() {
        trades += T(1, now - 5 * day, "SELL", "0.002", fee = "0.5")
        funding += F(1, now - 4 * day, "-1")
        val adapter = adapter()
        assertThat(adapter.read().fundingUsdt).isEqualByComparingTo("-1")
        trades += T(2, now - 3 * day, "BUY", "0.002", fee = "0.5")
        trades += T(3, now - 2 * day, "SELL", "0.002", fee = "0.07")
        funding += F(2, now - day, "-0.2")
        requests.clear()

        val snapshot = adapter.read()

        assertThat(snapshot.entryCommissionUsdt).isEqualByComparingTo("0.07")
        assertThat(snapshot.fundingUsdt).isEqualByComparingTo("-0.2")
        val incomeStart = requests.first { it.requestUrl!!.encodedPath == "/fapi/v1/income" }.requestUrl!!.queryParameter("startTime")
        assertThat(incomeStart).isEqualTo("${now - 2 * day}")
    }

    @Test
    fun `USDT BNB 외 수수료 자산이면 UNEXPECTED`() {
        trades += T(1, now - day, "SELL", "0.002", asset = "ETH")

        assertCategory(AccountReadFailureCategory.UNEXPECTED)
    }

    @Test
    fun `3개월 안에 누적이 맞지 않으면 수수료 펀딩 null 이고 창은 13개까지만 걷는다`() {
        trades += T(1, now - 100 * day, "SELL", "0.002")

        val snapshot = adapter().read()

        assertThat(snapshot.signedQuantity).isEqualByComparingTo("-0.002")
        assertThat(snapshot.entryCommissionUsdt).isNull()
        assertThat(snapshot.fundingUsdt).isNull()
        assertThat(requests.count { it.requestUrl!!.encodedPath == "/fapi/v1/userTrades" }).isEqualTo(13)
        assertThat(requests.none { it.requestUrl!!.encodedPath == "/fapi/v1/income" }).isTrue()
    }

    @Test
    fun `진입 미발견 13창 소진 결과도 캐시해 두 번째 회차는 창을 다시 걷지 않는다`() {
        trades += T(1, now - 100 * day, "SELL", "0.002")
        val clock = MutableClock(Instant.ofEpochMilli(now))
        val adapter = adapter(clock)
        assertThat(adapter.read().entryCommissionUsdt).isNull()
        assertThat(userTradeCalls()).hasSize(13)
        requests.clear()
        clock.advance(60_000)

        val second = adapter.read()

        assertThat(second.entryCommissionUsdt).isNull()
        val calls = userTradeCalls()
        assertThat(calls).describedAs("새 체결 감지 1건만").hasSize(1)
        assertThat(calls.single().requestUrl!!.queryParameter("startTime")).isEqualTo("${now + 1}")
    }

    @Test
    fun `1000건 창으로 진입을 못 찾은 결과도 캐시해 두 번째 회차는 창을 다시 걷지 않는다`() {
        (1..1000L).forEach { trades += T(it, now - day + it, if (it % 2 == 0L) "BUY" else "SELL", "0.002") }
        val clock = MutableClock(Instant.ofEpochMilli(now))
        val adapter = adapter(clock)
        assertThat(adapter.read().entryCommissionUsdt).isNull()
        requests.clear()
        clock.advance(60_000)

        adapter.read()

        val calls = userTradeCalls()
        assertThat(calls).describedAs("새 체결 감지 1건만").hasSize(1)
        assertThat(calls.single().requestUrl!!.queryParameter("startTime")).describedAs("7일 창이 아니라 미발견 시점 이후")
            .isEqualTo("${now + 1}")
    }

    @Test
    fun `미발견 캐시는 새 체결이 생기면 다시 걷는다`() {
        trades += T(1, now - 100 * day, "SELL", "0.002")
        val clock = MutableClock(Instant.ofEpochMilli(now))
        val adapter = adapter(clock)
        assertThat(adapter.read().entryCommissionUsdt).isNull()
        trades += T(2, now + 30_000, "BUY", "0.001")
        trades += T(3, now + 40_000, "SELL", "0.001", fee = "0.03")
        clock.advance(60_000)
        requests.clear()

        adapter.read()

        assertThat(userTradeCalls().size).isGreaterThan(1)
    }

    @Test
    fun `깊이는 포지션 이력 수수료율 현재가 다음 마지막으로 조회한다`() {
        trades += T(1, now - day, "SELL", "0.002", asset = "BNB")

        adapter().read()

        val paths = requests.map { it.requestUrl!!.encodedPath }.filterNot { it == "/fapi/v1/time" }
        assertThat(paths.last()).isEqualTo("/fapi/v1/depth")
        assertThat(paths.count { it == "/fapi/v1/depth" }).isEqualTo(1)
        assertThat(paths.indexOf("/fapi/v2/ticker/price")).isLessThan(paths.indexOf("/fapi/v1/depth"))
    }

    @Test
    fun `userTrades 창은 7일을 넘지 않고 겹치지 않는다`() {
        adapter().read()

        val windows = requests.filter { it.requestUrl!!.encodedPath == "/fapi/v1/userTrades" }
            .map { it.requestUrl!!.queryParameter("startTime")!!.toLong() to it.requestUrl!!.queryParameter("endTime")!!.toLong() }
        assertThat(windows).allSatisfy { assertThat(it.second - it.first).isLessThanOrEqualTo(7 * day) }
        assertThat(windows.first().second).isEqualTo(now)
        windows.zipWithNext().forEach { (a, b) -> assertThat(b.second).isLessThan(a.first) }
    }

    @Test
    fun `펀딩을 시각 기반 여러 페이지에 걸쳐 합산하고 경계 중복 tranId 는 한 번만 센다`() {
        trades += T(1, now - 20 * day, "SELL", "0.002")
        val base = now - 19 * day
        (0 until 1000).forEach { funding += F(it + 1L, base + it, "0.001") }
        funding += F(1001, base + 999, "0.001")
        funding += F(1002, base + 1500, "0.001")

        val snapshot = adapter().read()

        assertThat(snapshot.fundingUsdt).isEqualByComparingTo("1.002")
        val starts = requests.filter { it.requestUrl!!.encodedPath == "/fapi/v1/income" }.map { it.requestUrl!!.queryParameter("startTime")!!.toLong() }
        assertThat(starts).containsExactly(now - 20 * day, base + 999)
        assertThat(requests.first { it.requestUrl!!.encodedPath == "/fapi/v1/income" }.requestUrl!!.encodedQuery)
            .contains("incomeType=FUNDING_FEE", "symbol=BTCUSDT", "limit=1000")
    }

    @Test
    fun `두 번째 회차는 펀딩 증분만 요청한다`() {
        trades += T(1, now - 20 * day, "SELL", "0.002")
        funding += F(1, now - 10 * day, "-0.5")
        funding += F(2, now - 3 * day, "-0.25")
        val adapter = adapter()
        assertThat(adapter.read().fundingUsdt).isEqualByComparingTo("-0.75")
        funding += F(3, now - day, "-0.1")
        requests.clear()

        val second = adapter.read()

        assertThat(second.fundingUsdt).isEqualByComparingTo("-0.85")
        val incomeCalls = requests.filter { it.requestUrl!!.encodedPath == "/fapi/v1/income" }
        assertThat(incomeCalls).hasSize(1)
        assertThat(incomeCalls.single().requestUrl!!.queryParameter("startTime")).isEqualTo("${now - 3 * day}")
    }

    @Test
    fun `-2015 는 BINANCE_AUTH`() {
        responses["/fapi/v3/positionRisk"] = { MockResponse().setResponseCode(401).setBody("""{"code":-2015,"msg":"Invalid API-key, IP, or permissions for action."}""") }
        assertCategory(AccountReadFailureCategory.BINANCE_AUTH)
        responses["/fapi/v3/positionRisk"] = { MockResponse().setResponseCode(400).setBody("""{"code":-1022,"msg":"Signature for this request is not valid."}""") }
        assertCategory(AccountReadFailureCategory.BINANCE_AUTH)
        responses["/fapi/v3/positionRisk"] = { MockResponse().setResponseCode(400).setBody("""{"code":-2014,"msg":"API-key format invalid."}""") }
        assertCategory(AccountReadFailureCategory.BINANCE_AUTH)
    }

    @Test
    fun `429 418 5xx 는 NETWORK 이고 그 밖의 4xx 는 UNEXPECTED`() {
        listOf(429, 418, 503).forEach { status ->
            responses["/fapi/v1/depth"] = { MockResponse().setResponseCode(status) }
            trades += T(1, now - day, "SELL", "0.002")
            assertCategory(AccountReadFailureCategory.NETWORK)
        }
        responses["/fapi/v1/depth"] = { MockResponse().setResponseCode(400).setBody("""{"code":-1100,"msg":"bad"}""") }
        assertCategory(AccountReadFailureCategory.UNEXPECTED)
    }

    @Test
    fun `깨진 JSON 은 UNEXPECTED`() {
        responses["/fapi/v1/commissionRate"] = { json("{not json") }
        trades += T(1, now - day, "SELL", "0.002")

        assertCategory(AccountReadFailureCategory.UNEXPECTED)
    }

    @Test
    fun `음수 또는 0 가격 수량은 UNEXPECTED`() {
        trades += T(1, now - day, "SELL", "0.002")
        responses["/fapi/v1/commissionRate"] = { json("""{"symbol":"BTCUSDT","takerCommissionRate":"-0.1"}""") }
        assertCategory(AccountReadFailureCategory.UNEXPECTED)
        responses.remove("/fapi/v1/commissionRate")
        responses["/fapi/v1/depth"] = { json("""{"T":1,"asks":[["0","1"]]}""") }
        assertCategory(AccountReadFailureCategory.UNEXPECTED)
    }

    @Test
    fun `depth ask 레벨을 가격 오름차순으로 낸다`() {
        trades += T(1, now - day, "SELL", "0.002")
        responses["/fapi/v1/depth"] = { json("""{"lastUpdateId":1,"E":1,"T":1589436922959,"bids":[],"asks":[["102.5","1"],["100.1","2"],["101","3"]]}""") }

        val snapshot = adapter().read()

        assertThat(snapshot.askLevels.map { it.price.toPlainString() }).containsExactly("100.1", "101", "102.5")
        assertThat(snapshot.askObservedAt).isEqualTo(Instant.ofEpochMilli(1589436922959))
        val depth = requests.first { it.requestUrl!!.encodedPath == "/fapi/v1/depth" }
        assertThat(depth.requestUrl!!.encodedQuery).isEqualTo("symbol=BTCUSDT&limit=20")
    }

    @Test
    fun `예외 메시지에 secret 과 key 가 없다`() {
        responses["/fapi/v3/positionRisk"] = { MockResponse().setResponseCode(401).setBody("""{"code":-2015,"msg":"test-secret-key test-api-key"}""") }
        val auth = catchRead()
        val refused = adapter()
        server.shutdown()
        val network = catchRead(refused)

        listOf(auth, network).forEach { e ->
            val text = generateSequence<Throwable>(e) { it.cause }.joinToString(" ") { it.message.orEmpty() }
            assertThat(text).doesNotContain("test-secret-key").doesNotContain("test-api-key").doesNotContain("signature=")
        }
    }

    @Test
    fun `주문 출금 이체 경로는 요청하지 않는다`() {
        trades += T(1, now - day, "SELL", "0.002")
        adapter().read()

        assertThat(requests.map { it.method }.toSet()).containsExactly("GET")
    }

    private fun adapter(clock: Clock = this.clock) = BinanceFuturesPrivateAccountAdapter(
        WebClient.builder().baseUrl(server.url("/").toString().removeSuffix("/")).build(),
        "test-api-key",
        "test-secret-key",
        clock,
        timeout = java.time.Duration.ofSeconds(3),
    )

    private fun userTradeCalls() = requests.filter { it.requestUrl!!.encodedPath == "/fapi/v1/userTrades" }

    private class MutableClock(private var instant: Instant) : Clock() {
        fun advance(millis: Long) {
            instant = instant.plusMillis(millis)
        }

        override fun instant(): Instant = instant
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
    }

    private fun catchRead(adapter: BinanceFuturesPrivateAccountAdapter = adapter()): AccountReadException {
        var thrown: Throwable? = null
        try {
            adapter.read()
        } catch (expected: Throwable) {
            thrown = expected
        }
        assertThat(thrown).isInstanceOf(AccountReadException::class.java)
        return thrown as AccountReadException
    }

    private fun assertCategory(category: AccountReadFailureCategory) {
        assertThat(catchRead().category).isEqualTo(category)
    }

    private fun userTrades(url: okhttp3.HttpUrl): String {
        val fromId = url.queryParameter("fromId")?.toLong()
        val start = url.queryParameter("startTime")?.toLong()
        val end = url.queryParameter("endTime")?.toLong()
        val limit = url.queryParameter("limit")?.toInt() ?: 500
        val rows = trades.filter { t ->
            if (fromId != null) t.id >= fromId else t.time >= start!! && t.time <= end!!
        }.sortedBy { it.id }.take(limit)
        return rows.joinToString(",", "[", "]") {
            """{"id":${it.id},"time":${it.time},"side":"${it.side}","qty":"${it.qty}","price":"61000","commission":"${it.fee}","commissionAsset":"${it.asset}","positionSide":"${it.positionSide}","symbol":"BTCUSDT"}"""
        }
    }

    private fun income(url: okhttp3.HttpUrl): String {
        val start = url.queryParameter("startTime")!!.toLong()
        val limit = url.queryParameter("limit")!!.toInt()
        return funding.filter { it.time >= start }.sortedBy { it.time }.take(limit).joinToString(",", "[", "]") {
            """{"symbol":"BTCUSDT","incomeType":"FUNDING_FEE","income":"${it.income}","asset":"USDT","time":${it.time},"tranId":${it.tranId}}"""
        }
    }

    private fun positionRisk(side: String, amt: String, entry: String) =
        """[{"symbol":"BTCUSDT","positionSide":"$side","positionAmt":"$amt","entryPrice":"$entry"}]"""

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun fixture(name: String): String = javaClass.getResource("/fixtures/binance-private/$name")!!.readText()
}
