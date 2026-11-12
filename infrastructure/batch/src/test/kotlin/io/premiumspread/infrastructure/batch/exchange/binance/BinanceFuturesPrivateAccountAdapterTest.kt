package io.premiumspread.infrastructure.batch.exchange.binance

import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.ForeignPerpMarginType
import io.premiumspread.domain.accountpnl.ForeignPerpPositionRisk
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.web.reactive.function.client.WebClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@ExtendWith(OutputCaptureExtension::class)
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
    private var accountBody = fixture("account.json")
    private var symbolConfigBody = fixture("symbol-config.json")
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

                    "/fapi/v3/account" -> json(accountBody)

                    "/fapi/v1/symbolConfig" -> json(symbolConfigBody)

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

    @Test
    fun `USDT 잔고는 BNB 항목이 먼저여도 USDT 항목에서 읽는다`(output: CapturedOutput) {
        val snapshot = adapter().read()

        assertThat(snapshot.balance!!.marginBalanceUsdt).isEqualByComparingTo("10000.50")
        assertThat(snapshot.balance!!.availableBalanceUsdt).isEqualByComparingTo("8000.25")
        assertSecretsAbsent(output)
    }

    @Test
    fun `account 요청은 서명된 GET 한 건이다`() {
        adapter().read()

        val calls = requests.filter { it.requestUrl!!.encodedPath == "/fapi/v3/account" }
        assertThat(calls).hasSize(1)
        assertThat(calls.single().method).isEqualTo("GET")
        assertThat(calls.single().getHeader("X-MBX-APIKEY")).isEqualTo("test-api-key")
        assertThat(calls.single().requestUrl!!.encodedQuery).contains("recvWindow=", "timestamp=", "signature=")
    }

    @Test
    fun `account 는 commissionRate 뒤 ticker 앞이고 depth 가 마지막이다`() {
        adapter().read()

        val paths = requests.map { it.requestUrl!!.encodedPath }.filterNot { it == "/fapi/v1/time" }
        assertThat(paths.indexOf("/fapi/v3/account")).isGreaterThan(paths.indexOf("/fapi/v1/commissionRate"))
        assertThat(paths.indexOf("/fapi/v3/account")).isLessThan(paths.indexOf("/fapi/v2/ticker/price"))
        assertThat(paths.last()).isEqualTo("/fapi/v1/depth")
    }

    @Test
    fun `account 요청 실패는 balance null 로 흡수하고 사유만 WARN 한다`(output: CapturedOutput) {
        val cases = listOf<Triple<() -> MockResponse, String, String>>(
            Triple(
                { MockResponse().setResponseCode(401).setBody("""{"code":-2015,"msg":"Invalid API-key"}""") },
                "category=BINANCE_AUTH",
                "error=Unauthorized",
            ),
            Triple({ MockResponse().setResponseCode(503) }, "category=NETWORK", "error=ServiceUnavailable"),
            Triple({ json("{not json") }, "category=UNEXPECTED", "error=JsonParseException"),
        )
        cases.forEach { (response, category, error) ->
            responses["/fapi/v3/account"] = response
            val before = output.all.length

            val snapshot = adapter().read()

            assertThat(snapshot.balance).isNull()
            assertThat(snapshot.askLevels).isNotEmpty()
            assertThat(snapshot.lastPrice).isEqualByComparingTo("6000.01")
            assertThat(output.all.substring(before)).contains("item=BINANCE_USDT_ASSET", "reason=REQUEST_FAILED", category, error)
        }
        assertSecretsAbsent(output)
    }

    @Test
    fun `account 해석 실패는 balance null 로 흡수하고 사유만 WARN 한다`(output: CapturedOutput) {
        val cases = listOf(
            """{"assets":[{"asset":"BNB","marginBalance":"1","availableBalance":"1"}]}""" to "MISSING",
            """{"totalWalletBalance":"1"}""" to "MISSING",
            """{"assets":[{"asset":"USDT","marginBalance":"1"}]}""" to "NOT_NUMBER",
            """{"assets":[{"asset":"USDT","marginBalance":"1","availableBalance":"abc"}]}""" to "NOT_NUMBER",
            """{"assets":[{"asset":"USDT","marginBalance":"-1","availableBalance":"1"}]}""" to "NEGATIVE",
        )
        cases.forEach { (body, reason) ->
            accountBody = body
            val before = output.all.length

            val snapshot = adapter().read()

            assertThat(snapshot.balance).isNull()
            assertThat(snapshot.askLevels).isNotEmpty()
            assertThat(output.all.substring(before))
                .contains("item=BINANCE_USDT_ASSET", "reason=$reason", "category=-", "error=-")
        }
        assertSecretsAbsent(output)
    }

    @Test
    fun `account 가 -1021 로 거부되면 서버 시각을 다시 재지 않고 한 번만 보낸 뒤 balance null 로 흡수한다`(output: CapturedOutput) {
        responses["/fapi/v3/account"] = {
            MockResponse().setResponseCode(400).setBody("""{"code":-1021,"msg":"outside of the recvWindow."}""")
        }

        val snapshot = adapter().read()

        assertThat(snapshot.balance).isNull()
        assertThat(requests.count { it.requestUrl!!.encodedPath == "/fapi/v3/account" }).isEqualTo(1)
        // 첫 서명 요청 직전의 오프셋 측정 1회뿐 — 잔고 경로는 재측정하지 않는다
        assertThat(requests.count { it.requestUrl!!.encodedPath == "/fapi/v1/time" }).isEqualTo(1)
        assertThat(output.all).contains("item=BINANCE_USDT_ASSET", "reason=REQUEST_FAILED", "category=NETWORK")
        assertSecretsAbsent(output)
    }

    @Test
    fun `account 가 잔고 전용 timeout 을 넘기면 balance null 로 흡수하고 depth 를 마지막에 부른다`(output: CapturedOutput) {
        // 응답을 끝내 보내지 않는다(10초 이상 지연과 같다). setBodyDelay 는 서버 스레드를 재워 shutdown 이 5초 기다리다 실패하지만
        // NO_RESPONSE 는 소켓만 열어 두므로 client 가 끊거나 shutdown 이 소켓을 닫으면 바로 끝나 다음 테스트로 새지 않는다
        responses["/fapi/v3/account"] = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        // 생성자 timeout 을 넘기지 않아 다른 호출은 운영 기본값 5초를 쓴다
        val adapter = BinanceFuturesPrivateAccountAdapter(
            WebClient.builder().baseUrl(server.url("/").toString().removeSuffix("/")).build(),
            "test-api-key",
            "test-secret-key",
            clock,
        )

        // System.nanoTime 은 업무 시각이 아니라 timeout 상한을 재는 데만 쓴다. 회차 전체가 기본 timeout 5초보다 짧으면
        // 응답 없는 잔고 호출을 기본값이 아니라 잔고 전용 1.5초 timeout 이 끊었다는 뜻이다
        val started = System.nanoTime()
        val snapshot = adapter.read()
        val elapsed = java.time.Duration.ofNanos(System.nanoTime() - started)

        assertThat(snapshot.balance).isNull()
        assertThat(snapshot.askLevels).isNotEmpty()
        assertThat(requests.map { it.requestUrl!!.encodedPath }.last()).isEqualTo("/fapi/v1/depth")
        assertThat(output.all)
            .contains("item=BINANCE_USDT_ASSET", "reason=REQUEST_FAILED", "category=NETWORK", "error=TimeoutException")
        assertThat(elapsed).isLessThan(java.time.Duration.ofSeconds(5))
        assertSecretsAbsent(output)
    }

    @Test
    fun `account 요청 중 취소되면 흡수하지 않고 던지며 depth 를 부르지 않는다`(output: CapturedOutput) {
        val testThread = Thread.currentThread()
        responses["/fapi/v3/account"] = {
            testThread.interrupt()
            json(fixture("account.json")).setBodyDelay(1, TimeUnit.SECONDS)
        }
        try {
            catchRead()

            assertThat(requests.none { it.requestUrl!!.encodedPath == "/fapi/v1/depth" }).isTrue()
            assertThat(output.all).doesNotContain("reason=REQUEST_FAILED")
            assertSecretsAbsent(output)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `positionRisk 위험 지표를 숏 롱 hedge 에서 읽는다`() {
        val short = adapter().read().positionRisk
        assertRisk(short, "91234.56", "60500.10", "12.3", "12.0", "0.48", "24.2", "-121.0002")

        positionBody = positionRisk("BOTH", "0.002", "61000", "liquidationPrice" to "30500.25", "notional" to "121.0002")
        val long = adapter().read().positionRisk
        assertRisk(long, "30500.25", "60500.10", "12.3", "12.0", "0.48", "24.2", "121.0002")

        positionBody = fixture("position-risk-hedge.json")
        val hedge = adapter().read().positionRisk
        assertRisk(hedge, "92000.10000000", "60000.00000000", "0", "0", "0.48000000", "6.00000000", "-120.00000000")
    }

    @Test
    fun `청산가 0 이면 청산가만 없음이다`() {
        positionBody = positionRisk("BOTH", "-0.002", "61000", "liquidationPrice" to "0")

        val risk = adapter().read().positionRisk

        assertThat(risk).isNotNull()
        assertThat(risk!!.liquidationPrice).isNull()
        assertThat(risk.markPrice).isEqualByComparingTo("60500.10")
        assertThat(risk.isolatedMargin).isEqualByComparingTo("12.3")
        assertThat(risk.isolatedWallet).isEqualByComparingTo("12.0")
        assertThat(risk.maintMargin).isEqualByComparingTo("0.48")
        assertThat(risk.positionInitialMargin).isEqualByComparingTo("24.2")
        assertThat(risk.notional).isEqualByComparingTo("-121.0002")
    }

    @Test
    fun `symbolConfig 레버리지와 마진 모드를 서명 GET 한 건으로 읽는다`() {
        val isolated = adapter().read().symbolConfig

        assertThat(isolated).isNotNull()
        assertThat(isolated!!.leverage).isEqualTo(5)
        assertThat(isolated.marginType).isEqualTo(ForeignPerpMarginType.ISOLATED)
        val calls = requests.filter { it.requestUrl!!.encodedPath == "/fapi/v1/symbolConfig" }
        assertThat(calls).hasSize(1)
        assertThat(calls.single().method).isEqualTo("GET")
        assertThat(calls.single().getHeader("X-MBX-APIKEY")).isEqualTo("test-api-key")
        val query = calls.single().requestUrl!!.encodedQuery!!
        assertThat(query).startsWith("symbol=BTCUSDT&").contains("recvWindow=10000", "timestamp=$now", "signature=")
        assertThat(query.substringAfter("&signature=")).isEqualTo(BinanceRequestSigner.sign("test-secret-key", query.substringBefore("&signature=")))
        val paths = requests.map { it.requestUrl!!.encodedPath }.filterNot { it == "/fapi/v1/time" }
        assertThat(paths.indexOf("/fapi/v1/symbolConfig")).isGreaterThan(paths.indexOf("/fapi/v1/commissionRate"))
        assertThat(paths.indexOf("/fapi/v1/symbolConfig")).isLessThan(paths.indexOf("/fapi/v3/account"))
        assertThat(paths.last()).isEqualTo("/fapi/v1/depth")

        symbolConfigBody = """[{"symbol":"ETHUSDT","marginType":"ISOLATED","isAutoAddMargin":"false","leverage":3,"maxNotionalValue":"1"},""" +
            """{"symbol":"BTCUSDT","marginType":"CROSSED","isAutoAddMargin":"false","leverage":20,"maxNotionalValue":"1000000"}]"""
        requests.clear()
        val crossed = adapter().read().symbolConfig

        assertThat(crossed).isNotNull()
        assertThat(crossed!!.leverage).isEqualTo(20)
        assertThat(crossed.marginType).isEqualTo(ForeignPerpMarginType.CROSSED)
        assertThat(requests.count { it.requestUrl!!.encodedPath == "/fapi/v1/symbolConfig" }).isEqualTo(1)
        assertThat(requests.map { it.requestUrl!!.encodedPath }.last()).isEqualTo("/fapi/v1/depth")
    }

    @Test
    fun `포지션이 없으면 위험 지표가 없고 symbolConfig 를 부르지 않는다`() {
        listOf("[]", positionRisk("BOTH", "0", "0")).forEach { body ->
            positionBody = body
            requests.clear()

            val snapshot = adapter().read()

            assertThat(snapshot.signedQuantity.signum()).isZero()
            assertThat(snapshot.positionRisk).isNull()
            assertThat(snapshot.symbolConfig).isNull()
            assertThat(requests.map { it.requestUrl!!.encodedPath }).doesNotContain("/fapi/v1/symbolConfig")
            assertThat(requests.map { it.requestUrl!!.encodedPath }.last()).isEqualTo("/fapi/v1/depth")
        }
    }

    @Test
    fun `symbolConfig 실패는 그 항목만 null 로 흡수하고 사유만 WARN 한다`(output: CapturedOutput) {
        val baseline = adapter().read()
        assertThat(baseline.symbolConfig).isNotNull()
        assertThat(baseline.positionRisk).isNotNull()
        assertThat(baseline.balance).isNotNull()
        val fixtureBody = symbolConfigBody

        data class Case(val response: (() -> MockResponse)?, val body: String?, val expected: List<String>)
        val cases = listOf(
            Case({ MockResponse().setResponseCode(401).setBody("""{"code":-2015,"msg":"Invalid API-key"}""") }, null, listOf("reason=REQUEST_FAILED", "category=BINANCE_AUTH", "error=Unauthorized")),
            Case({ MockResponse().setResponseCode(503) }, null, listOf("reason=REQUEST_FAILED", "category=NETWORK", "error=ServiceUnavailable")),
            Case({ json("{not json") }, null, listOf("reason=REQUEST_FAILED", "category=UNEXPECTED", "error=JsonParseException")),
            Case({ MockResponse().setResponseCode(400).setBody("""{"code":-1021,"msg":"outside of the recvWindow."}""") }, null, listOf("reason=REQUEST_FAILED", "category=NETWORK")),
            Case({ MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }, null, listOf("reason=REQUEST_FAILED", "category=NETWORK", "error=TimeoutException")),
            Case(null, """[{"symbol":"ETHUSDT","marginType":"ISOLATED","leverage":7}]""", listOf("reason=MISSING", "category=-", "error=-")),
            Case(null, """{"symbol":"BTCUSDT","marginType":"ISOLATED","leverage":7}""", listOf("reason=MISSING", "category=-", "error=-")),
            Case(null, """[{"symbol":"BTCUSDT","marginType":"ISOLATED","leverage":"lev-value-x"}]""", listOf("reason=NOT_NUMBER", "category=-", "error=-")),
            Case(null, """[{"symbol":"BTCUSDT","marginType":"ISOLATED","leverage":0}]""", listOf("reason=NOT_POSITIVE", "category=-", "error=-")),
            Case(null, """[{"symbol":"BTCUSDT","marginType":"MARGIN_MODE_X","leverage":7}]""", listOf("reason=UNKNOWN", "category=-", "error=-")),
        )
        cases.forEach { case ->
            responses.remove("/fapi/v1/symbolConfig")
            case.response?.let { responses["/fapi/v1/symbolConfig"] = it }
            symbolConfigBody = case.body ?: fixtureBody
            requests.clear()
            val before = output.all.length

            val snapshot = adapter().read()

            assertThat(snapshot.symbolConfig).describedAs(case.expected.toString()).isNull()
            assertThat(snapshot.signedQuantity).isEqualTo(baseline.signedQuantity)
            assertThat(snapshot.entryPrice).isEqualTo(baseline.entryPrice)
            assertThat(snapshot.takerFeeRate).isEqualTo(baseline.takerFeeRate)
            assertThat(snapshot.balance).isEqualTo(baseline.balance)
            assertThat(snapshot.askLevels).isEqualTo(baseline.askLevels)
            assertThat(snapshot.positionRisk).isEqualTo(baseline.positionRisk)
            assertThat(requests.count { it.requestUrl!!.encodedPath == "/fapi/v1/symbolConfig" }).describedAs("한 번만 보낸다").isEqualTo(1)
            assertThat(requests.count { it.requestUrl!!.encodedPath == "/fapi/v1/time" }).describedAs("서버 시각을 다시 재지 않는다").isEqualTo(1)
            assertThat(requests.map { it.requestUrl!!.encodedPath }.last()).isEqualTo("/fapi/v1/depth")
            assertThat(output.all.substring(before)).contains(listOf("item=BINANCE_SYMBOL_CONFIG") + case.expected)
        }
        assertSecretsAbsent(output)
        assertThat(output.all).doesNotContain("lev-value-x", "MARGIN_MODE_X", "leverage")
    }

    @Test
    fun `symbolConfig 요청 중 취소되면 흡수하지 않고 던진다`(output: CapturedOutput) {
        val testThread = Thread.currentThread()
        responses["/fapi/v1/symbolConfig"] = {
            testThread.interrupt()
            json(fixture("symbol-config.json")).setBodyDelay(1, TimeUnit.SECONDS)
        }
        try {
            catchRead()

            val paths = requests.map { it.requestUrl!!.encodedPath }
            assertThat(paths).contains("/fapi/v1/symbolConfig")
            assertThat(paths).doesNotContain("/fapi/v3/account", "/fapi/v1/depth")
            assertThat(output.all).doesNotContain("item=BINANCE_SYMBOL_CONFIG")
            assertSecretsAbsent(output)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `positionRisk 위험 필드 해석 실패는 그 항목만 null 로 흡수한다`(output: CapturedOutput) {
        val cases = listOf(
            positionRisk("BOTH", "-0.002", "61000", "markPrice" to null) to "MISSING",
            positionRisk("BOTH", "-0.002", "61000", "maintMargin" to "abc") to "NOT_NUMBER",
            positionRisk("BOTH", "-0.002", "61000", "isolatedMargin" to "-1") to "NEGATIVE",
            positionRisk("BOTH", "-0.002", "61000", "liquidationPrice" to "-5") to "NEGATIVE",
            positionRisk("BOTH", "-0.002", "61000", "markPrice" to "0") to "NOT_POSITIVE",
        )
        cases.forEach { (body, reason) ->
            positionBody = body
            val before = output.all.length

            val snapshot = adapter().read()

            assertThat(snapshot.positionRisk).describedAs(reason).isNull()
            assertThat(snapshot.signedQuantity).isEqualByComparingTo("-0.002")
            assertThat(snapshot.entryPrice).isEqualByComparingTo("61000")
            assertThat(snapshot.symbolConfig).isNotNull()
            assertThat(snapshot.askLevels).isNotEmpty()
            assertThat(output.all.substring(before)).contains("item=BINANCE_POSITION_RISK", "reason=$reason", "category=-", "error=-")
        }
        assertSecretsAbsent(output)
        assertThat(output.all).doesNotContain("60500.10", "91234.56", "121.0002")
    }

    private fun assertRisk(
        risk: ForeignPerpPositionRisk?,
        liquidation: String,
        mark: String,
        isolatedMargin: String,
        isolatedWallet: String,
        maint: String,
        positionInitial: String,
        notional: String,
    ) {
        assertThat(risk).isNotNull()
        assertThat(risk!!.liquidationPrice).isEqualByComparingTo(liquidation)
        assertThat(risk.markPrice).isEqualByComparingTo(mark)
        assertThat(risk.isolatedMargin).isEqualByComparingTo(isolatedMargin)
        assertThat(risk.isolatedWallet).isEqualByComparingTo(isolatedWallet)
        assertThat(risk.maintMargin).isEqualByComparingTo(maint)
        assertThat(risk.positionInitialMargin).isEqualByComparingTo(positionInitial)
        assertThat(risk.notional).isEqualByComparingTo(notional)
    }

    private fun assertSecretsAbsent(output: CapturedOutput) {
        assertThat(output.all).doesNotContain("test-api-key", "test-secret-key", "signature=", "10000.50", "8000.25")
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

    /** 숏 BOTH 표본의 위험 필드. [risk] 로 값을 바꾸고, 값이 null 이면 그 필드를 뺀다. */
    private fun positionRisk(side: String, amt: String, entry: String, vararg risk: Pair<String, String?>): String {
        val fields = linkedMapOf<String, String?>(
            "liquidationPrice" to "91234.56",
            "markPrice" to "60500.10",
            "isolatedMargin" to "12.3",
            "isolatedWallet" to "12.0",
            "maintMargin" to "0.48",
            "positionInitialMargin" to "24.2",
            "notional" to "-121.0002",
        )
        fields.putAll(risk)
        val extra = fields.entries.filter { it.value != null }.joinToString("") { ""","${it.key}":"${it.value}"""" }
        return """[{"symbol":"BTCUSDT","positionSide":"$side","positionAmt":"$amt","entryPrice":"$entry"$extra}]"""
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun fixture(name: String): String = javaClass.getResource("/fixtures/binance-private/$name")!!.readText()
}
