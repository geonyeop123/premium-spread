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
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.TimeUnit

class BithumbOrderQueryHashTest {
    private lateinit var server: MockWebServer
    private val clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC)
    private var orderbookBody: String? = null

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.requestUrl!!.encodedPath) {
                    "/v1/accounts" -> json(
                        """[{"currency":"BTC","balance":"0.005","locked":"0","avg_buy_price":"36340973",""" +
                            """"avg_buy_price_modified":false,"unit_currency":"KRW"}]""",
                    )

                    "/v1/orderbook" -> json(orderbookBody ?: fixture("orderbook.json"))

                    "/v1/ticker" -> json(fixture("ticker.json"))

                    "/v1/orders/chance" -> json(fixture("orders-chance.json"))

                    "/v1/orders" -> json(
                        """[{"uuid":"u1","side":"bid","state":"done","market":"KRW-BTC",""" +
                            """"executed_volume":"0.005","paid_fee":"10"}]""",
                    )

                    else -> MockResponse().setResponseCode(404)
                }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `주문 이력 query_hash 는 디코딩된 query 의 SHA512 이다`() {
        adapter().read()

        val request = requestOf("/v1/orders")
        val encoded = request.requestUrl!!.encodedQuery!!
        val decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8)
        assertThat(decoded).contains("states[]=done&states[]=cancel")
        assertThat(encoded).isNotEqualTo(decoded)
        val hash = queryHash(request)
        assertThat(hash).isEqualTo(BithumbJwtSigner.sha512Hex(decoded))
        assertThat(hash).isNotEqualTo(BithumbJwtSigner.sha512Hex(encoded))
    }

    @Test
    fun `wire 의 query 는 여전히 인코딩된 형태다`() {
        adapter().read()

        val requestLine = requestOf("/v1/orders").requestLine
        assertThat(requestLine).contains("states%5B%5D=done")
        assertThat(requestLine).doesNotContain("states[]")
    }

    @Test
    fun `orders chance query_hash 는 변하지 않는다`() {
        adapter().read()

        assertThat(queryHash(requestOf("/v1/orders/chance"))).isEqualTo(BithumbJwtSigner.sha512Hex("market=KRW-BTC"))
    }

    @Test
    fun `bid_size 가 0 인 단계는 건너뛰고 나머지를 가격 내림차순으로 낸다`() {
        orderbookBody = book(unit(100, "1.5"), unit(400, "0"), unit(300, "0.25"), unit(500, "0"), unit(200, "2"))

        val levels = adapter().read().bidLevels

        assertThat(levels.map { it.price.toInt() }).containsExactly(300, 200, 100)
    }

    @Test
    fun `모든 bid_size 가 0 이면 UNEXPECTED`() {
        orderbookBody = book(unit(100, "0"), unit(200, "0"))

        assertUnexpected("orderbook has no bid levels")
    }

    @Test
    fun `음수 bid_size 는 UNEXPECTED`() {
        orderbookBody = book(unit(100, "1"), unit(200, "-1"))

        assertUnexpected("orderbook level is not positive")
    }

    private fun assertUnexpected(message: String) {
        val e = assertThatThrownBy { adapter().read() }.isInstanceOf(AccountReadException::class.java)
        e.hasMessageContaining(message)
        e.matches { (it as AccountReadException).category == AccountReadFailureCategory.UNEXPECTED }
    }

    private fun unit(bidPrice: Int, bidSize: String) =
        """{"ask_price":1,"bid_price":$bidPrice,"ask_size":1,"bid_size":$bidSize}"""

    private fun book(vararg units: String) =
        """[{"market":"KRW-BTC","timestamp":1,"orderbook_units":[${units.joinToString(",")}]}]"""

    private fun queryHash(request: RecordedRequest): String {
        val payload = request.getHeader("Authorization")!!.removePrefix("Bearer ").split(".")[1]
        val body = String(Base64.getUrlDecoder().decode(payload))
        return Regex("\"query_hash\":\"([0-9a-f]+)\"").find(body)!!.groupValues[1]
    }

    private fun requestOf(path: String): RecordedRequest =
        generateSequence { server.takeRequest(10, TimeUnit.MILLISECONDS) }
            .first { it.requestUrl!!.encodedPath == path }

    private fun adapter() = BithumbPrivateAccountAdapter(
        WebClient.builder().baseUrl(server.url("/").toString().removeSuffix("/")).build(),
        "test-access-key",
        "test-secret-key",
        clock,
    )

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/bithumb-private/$name")!!.readText()
}
