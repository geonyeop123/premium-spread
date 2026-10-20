package io.premiumspread.infrastructure.batch.exchange.bithumb

import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
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
import java.net.URLDecoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BithumbPrivateAccountAdapterTest {
    private lateinit var server: MockWebServer
    private val clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC)
    private val responses = mutableMapOf<String, () -> MockResponse>()
    private val orderPages = mutableMapOf<Int, String>()
    private var accountsBody = fixture("accounts.json")

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                responses[path]?.let { return it() }
                return when (path) {
                    "/v1/accounts" -> json(accountsBody)
                    "/v1/orderbook" -> json(fixture("orderbook.json"))
                    "/v1/ticker" -> json(fixture("ticker.json"))
                    "/v1/orders/chance" -> json(fixture("orders-chance.json"))
                    "/v1/orders" -> {
                        val page = request.requestUrl!!.queryParameter("page")!!.toInt()
                        json(orderPages[page] ?: "[]")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        accountsBody = accounts(balance = "0.003", locked = "0.002")
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `Authorization 헤더 JWT 를 테스트 secret 으로 검증할 수 있고 payload 에 access_key nonce timestamp query_hash 가 있다`() {
        orderPages[1] = orders(order("u1", "0.005", "10"))

        adapter().read()

        val request = recorded().first { it.requestUrl!!.encodedPath == "/v1/orders" }
        val token = request.getHeader("Authorization")!!.removePrefix("Bearer ")
        val (header, payload, signature) = token.split(".")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("test-secret-key".toByteArray(), "HmacSHA256")) }
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("$header.$payload".toByteArray()))
        assertThat(signature).isEqualTo(expected)
        assertThat(String(Base64.getUrlDecoder().decode(header))).isEqualTo("""{"alg":"HS256","typ":"JWT"}""")
        val body = String(Base64.getUrlDecoder().decode(payload))
        assertThat(body).contains("\"access_key\":\"test-access-key\"", "\"timestamp\":${clock.millis()}", "\"nonce\":\"", "\"query_hash_alg\":\"SHA512\"")
        val query = request.requestUrl!!.encodedQuery!!
        assertThat(URLDecoder.decode(query, Charsets.UTF_8)).contains("states[]=done&states[]=cancel")
        assertThat(body).contains("\"query_hash\":\"${BithumbJwtSigner.sha512Hex(query)}\"")
    }

    @Test
    fun `파라미터 없는 요청은 query_hash 를 넣지 않는다`() {
        adapter().read()

        val request = recorded().first { it.requestUrl!!.encodedPath == "/v1/accounts" }
        val payload = request.getHeader("Authorization")!!.removePrefix("Bearer ").split(".")[1]
        assertThat(String(Base64.getUrlDecoder().decode(payload))).doesNotContain("query_hash")
    }

    @Test
    fun `보유 수량이 있는데 평단이 0 이면 UNEXPECTED`() {
        accountsBody = accounts(balance = "0.005", locked = "0").replace("36340973", "0")

        assertCategory(AccountReadFailureCategory.UNEXPECTED)
    }

    @Test
    fun `네트워크 실패와 타임아웃 예외에도 secret 이 없다`() {
        responses["/v1/accounts"] = { json(accountsBody).setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS) }
        val timedOut = catchRead(adapter(timeout = Duration.ofMillis(200)))
        val slow = adapter()
        server.shutdown()
        val refused = catchRead(slow)

        listOf(timedOut, refused).forEach { e ->
            val text = generateSequence<Throwable>(e) { it.cause }.joinToString(" ") { it.message.orEmpty() }
            assertThat(text).doesNotContain("test-secret-key").doesNotContain("test-access-key").doesNotContain("Bearer")
        }
    }

    @Test
    fun `잠긴 수량을 포함한다`() {
        assertThat(adapter().read().quantity).isEqualByComparingTo("0.005")
    }

    @Test
    fun `avg_buy_price_modified 를 그대로 전달한다`() {
        accountsBody = accounts(balance = "0.005", locked = "0", modified = true)

        val snapshot = adapter().read()

        assertThat(snapshot.averageBuyPriceModified).isTrue()
        assertThat(snapshot.averageBuyPrice).isEqualByComparingTo("36340973")
    }

    @Test
    fun `호가 현재가 매도수수료율을 정밀도 손실 없이 읽는다`() {
        val snapshot = adapter().read()

        assertThat(snapshot.lastPrice).isEqualByComparingTo("8621000")
        assertThat(snapshot.sellFeeRate).isEqualByComparingTo("0.0025")
        assertThat(snapshot.bidObservedAt).isEqualTo(Instant.ofEpochMilli(1529910247984))
        assertThat(snapshot.bidLevels.first().quantity.toPlainString()).isEqualTo("0.00718341")
    }

    @Test
    fun `orderbook bid 레벨을 가격 내림차순으로 낸다`() {
        responses["/v1/orderbook"] = {
            json(
                """[{"market":"KRW-BTC","timestamp":1,"orderbook_units":[
                {"ask_price":1,"bid_price":100,"ask_size":1,"bid_size":1.5},
                {"ask_price":1,"bid_price":300,"ask_size":1,"bid_size":0.25},
                {"ask_price":1,"bid_price":200,"ask_size":1,"bid_size":2}]}]""",
            )
        }

        assertThat(adapter().read().bidLevels.map { it.price.toInt() }).containsExactly(300, 200, 100)
    }

    @Test
    fun `0 이하 호가 가격이나 수량은 UNEXPECTED`() {
        responses["/v1/orderbook"] = {
            json("""[{"market":"KRW-BTC","timestamp":1,"orderbook_units":[{"ask_price":1,"bid_price":100,"ask_size":1,"bid_size":0}]}]""")
        }

        assertCategory(AccountReadFailureCategory.UNEXPECTED)
    }

    @Test
    fun `체결 주문 수수료를 보유 수량만큼 합산한다`() {
        orderPages[1] = orders(order("u1", "0.002", "5"), order("u2", "0.003", "7.5"), order("u3", "1", "999"))

        assertThat(adapter().read().entryFeeKrw).isEqualByComparingTo("12.5")
    }

    @Test
    fun `매도 주문과 체결 없는 주문은 합산하지 않는다`() {
        orderPages[1] = orders(
            order("u0", "0", "100"),
            order("u1", "0.001", "50", side = "ask"),
            order("u2", "0.005", "4"),
        )

        assertThat(adapter().read().entryFeeKrw).isEqualByComparingTo("4")
    }

    @Test
    fun `부분 체결 후 취소된 주문의 수수료도 합산한다`() {
        orderPages[1] = orders(order("u1", "0.002", "3", state = "cancel"), order("u2", "0.003", "6"))

        assertThat(adapter().read().entryFeeKrw).isEqualByComparingTo("9")
    }

    @Test
    fun `이력으로 보유 수량을 채우지 못하면 entryFeeKrw 는 null`() {
        orderPages[1] = orders(order("u1", "0.001", "5"))

        assertThat(adapter().read().entryFeeKrw).isNull()
    }

    @Test
    fun `누적이 수량을 넘으면 entryFeeKrw 는 null`() {
        orderPages[1] = orders(order("u1", "0.004", "5"), order("u2", "0.004", "5"))

        assertThat(adapter().read().entryFeeKrw).isNull()
    }

    @Test
    fun `페이지를 넘겨 이력을 걷는다`() {
        orderPages[1] = orders(*Array(100) { order("p1-$it", "0", "0") })
        orderPages[2] = orders(order("u1", "0.005", "8"))

        assertThat(adapter().read().entryFeeKrw).isEqualByComparingTo("8")
    }

    @Test
    fun `10 페이지를 넘으면 entryFeeKrw 는 null`() {
        for (page in 1..11) orderPages[page] = orders(*Array(100) { order("p$page-$it", "0", "0") })

        assertThat(adapter().read().entryFeeKrw).isNull()
        assertThat(orderRequestPages()).isEqualTo((1..10).toList())
    }

    @Test
    fun `같은 보유이고 새 체결이 없으면 두 번째 호출은 전체 이력을 다시 걷지 않는다`() {
        orderPages[1] = orders(*Array(99) { order("z$it", "0", "0") }, order("u1", "0.002", "3"))
        orderPages[2] = orders(order("u2", "0.003", "4"))
        val adapter = adapter()

        val first = adapter.read().entryFeeKrw
        val second = adapter.read().entryFeeKrw

        assertThat(first).isEqualByComparingTo("7")
        assertThat(second).isEqualByComparingTo("7")
        assertThat(orderRequestPages()).containsExactly(1, 2, 1)
    }

    @Test
    fun `수수료 미발견 null 결과도 캐시해 두 번째 호출은 page 1 만 조회한다`() {
        for (page in 1..11) orderPages[page] = orders(*Array(100) { order("p$page-$it", "0", "0") })
        val adapter = adapter()

        assertThat(adapter.read().entryFeeKrw).isNull()
        assertThat(adapter.read().entryFeeKrw).isNull()

        assertThat(orderRequestPages()).isEqualTo((1..10).toList() + 1)
    }

    @Test
    fun `호가는 계정 수수료율 현재가 주문이력 다음 마지막으로 조회한다`() {
        orderPages[1] = orders(order("u1", "0.005", "10"))

        adapter().read()

        val paths = recorded().map { it.requestUrl!!.encodedPath }.distinct()
        assertThat(paths).containsExactly("/v1/accounts", "/v1/orders/chance", "/v1/ticker", "/v1/orders", "/v1/orderbook")
    }

    @Test
    fun `같은 수량 평단이어도 새 체결이 있으면 수수료를 다시 계산한다`() {
        orderPages[1] = orders(order("u1", "0.005", "8"))
        val adapter = adapter()
        assertThat(adapter.read().entryFeeKrw).isEqualByComparingTo("8")

        orderPages[1] = orders(order("u9", "0.005", "11"), order("u1", "0.005", "8"))

        assertThat(adapter.read().entryFeeKrw).isEqualByComparingTo("11")
    }

    @Test
    fun `수량이나 평단이 바뀌면 캐시를 쓰지 않는다`() {
        orderPages[1] = orders(order("u1", "0.005", "8"), order("u0", "0.001", "2"))
        val adapter = adapter()
        assertThat(adapter.read().entryFeeKrw).isEqualByComparingTo("8")

        accountsBody = accounts(balance = "0.006", locked = "0")

        assertThat(adapter.read().entryFeeKrw).isEqualByComparingTo("10")
    }

    @Test
    fun `401 은 BITHUMB_AUTH`() {
        responses["/v1/accounts"] = { MockResponse().setResponseCode(401).setBody("""{"error":{"name":"NotAllowIP"}}""") }

        assertCategory(AccountReadFailureCategory.BITHUMB_AUTH)
    }

    @Test
    fun `429 와 5xx 는 NETWORK`() {
        responses["/v1/ticker"] = { MockResponse().setResponseCode(429) }
        assertCategory(AccountReadFailureCategory.NETWORK)
        responses["/v1/ticker"] = { MockResponse().setResponseCode(503) }
        assertCategory(AccountReadFailureCategory.NETWORK)
    }

    @Test
    fun `그 밖의 4xx 는 UNEXPECTED`() {
        responses["/v1/orders/chance"] = { MockResponse().setResponseCode(400) }

        assertCategory(AccountReadFailureCategory.UNEXPECTED)
    }

    @Test
    fun `깨진 JSON 은 UNEXPECTED`() {
        responses["/v1/orderbook"] = { json("{not json") }

        assertCategory(AccountReadFailureCategory.UNEXPECTED)
    }

    @Test
    fun `응답 시간이 초과되면 NETWORK`() {
        responses["/v1/accounts"] = { json(accountsBody).setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS) }

        assertCategory(AccountReadFailureCategory.NETWORK, adapter(timeout = Duration.ofMillis(200)))
    }

    @Test
    fun `연결할 수 없으면 NETWORK`() {
        val adapter = adapter()
        server.shutdown()

        assertCategory(AccountReadFailureCategory.NETWORK, adapter)
    }

    @Test
    fun `예외 메시지에 secret 이 없다`() {
        listOf(401, 429, 500, 400).forEach { status ->
            responses["/v1/accounts"] = { MockResponse().setResponseCode(status).setBody("test-secret-key test-access-key") }
            val e = catchRead()
            val text = generateSequence<Throwable>(e) { it.cause }.joinToString(" ") { it.message.orEmpty() }
            assertThat(text).doesNotContain("test-secret-key").doesNotContain("test-access-key").doesNotContain("Bearer")
        }
    }

    private fun adapter(timeout: Duration = Duration.ofSeconds(3)) = BithumbPrivateAccountAdapter(
        WebClient.builder().baseUrl(server.url("/").toString().removeSuffix("/")).build(),
        "test-access-key",
        "test-secret-key",
        clock,
        timeout,
    )

    private fun catchRead(adapter: BithumbPrivateAccountAdapter = adapter()): AccountReadException {
        var thrown: Throwable? = null
        try {
            adapter.read()
        } catch (e: Throwable) {
            thrown = e
        }
        assertThat(thrown).isInstanceOf(AccountReadException::class.java)
        return thrown as AccountReadException
    }

    private fun assertCategory(category: AccountReadFailureCategory, adapter: BithumbPrivateAccountAdapter = adapter()) {
        assertThat(catchRead(adapter).category).isEqualTo(category)
    }

    private fun recorded(): List<RecordedRequest> =
        generateSequence { server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS) }.toList()

    private fun orderRequestPages(): List<Int> =
        recorded().filter { it.requestUrl!!.encodedPath == "/v1/orders" }.map { it.requestUrl!!.queryParameter("page")!!.toInt() }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/bithumb-private/$name")!!.readText()

    private fun accounts(balance: String, locked: String, modified: Boolean = false) =
        """[{"currency":"BTC","balance":"$balance","locked":"$locked","avg_buy_price":"36340973","avg_buy_price_modified":$modified,"unit_currency":"KRW"}]"""

    private fun order(uuid: String, volume: String, fee: String, side: String = "bid", state: String = "done") =
        """{"uuid":"$uuid","side":"$side","state":"$state","market":"KRW-BTC","executed_volume":"$volume","paid_fee":"$fee"}"""

    private fun orders(vararg rows: String) = rows.joinToString(",", "[", "]")
}
