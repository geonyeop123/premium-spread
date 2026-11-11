package io.premiumspread.infrastructure.batch.exchange.bithumb

import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.FillHistoryWindowLimitException
import io.premiumspread.domain.accountpnl.KoreaSpotFill
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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.TimeUnit

/** AC5 — 빗썸 체결 이력 조회 adapter. */
class BithumbFillHistoryPrivateAccountAdapterTest {
    private lateinit var server: MockWebServer
    private val zone = ZoneId.of("Asia/Seoul")
    private val clock = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), zone)
    private val from = Instant.parse("2026-10-19T00:00:00Z")
    private val to = Instant.parse("2026-10-20T00:00:00Z")
    private val orderPages = mutableMapOf<Int, String>()
    private val details = mutableMapOf<String, String>()
    private var listResponse: (() -> MockResponse)? = null

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                return when (url.encodedPath) {
                    "/v1/orders" -> listResponse?.invoke()
                        ?: json(orderPages[url.queryParameter("page")!!.toInt()] ?: "[]")

                    "/v1/order" -> details[url.queryParameter("uuid")]?.let(::json)
                        ?: MockResponse().setResponseCode(404)

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `창을 걸친 주문은 창 안 체결만 돌려주고 GET 으로 주문 목록과 주문 상세 두 경로만 부른다`() {
        orderPages[1] = orders(
            row("o-future", "done", "0.01", "2026-10-20T09:00:01+09:00"),
            row("o-wait", "wait", "0.002", "2026-10-19T19:00:00+09:00"),
            row("o-zero", "cancel", "0", "2026-10-19T14:00:00+09:00"),
            row("o-span", "done", "0.06", "2026-10-19T08:00:00+09:00"),
        )
        details["o-wait"] = detail("o-wait", "bid", "5", trade("w1", "0.002", "200000", "2026-10-19T20:00:00+09:00"))
        details["o-span"] = fixture("order-detail-span.json")

        val fills = adapter().read(from, to)

        assertThat(fills.map(::describe)).containsExactly(
            "o-span:t3 SELL 0.01 1000 1.66666667 2026-10-19T06:00:00Z",
            "o-wait:w1 BUY 0.002 200000 5 2026-10-19T11:00:00Z",
            "o-span:t4 SELL 0.03 3000 4.99999999 2026-10-20T00:00:00Z",
        )
        val requests = recorded()
        assertThat(requests.map { it.method }.toSet()).containsExactly("GET")
        assertThat(requests.map { it.requestUrl!!.encodedPath }.toSet()).containsExactlyInAnyOrder("/v1/orders", "/v1/order")
        assertThat(requests.filter { it.requestUrl!!.encodedPath == "/v1/order" }.map { it.requestUrl!!.queryParameter("uuid") })
            .containsExactly("o-wait", "o-span")
        val listQueries = requests.filter { it.requestUrl!!.encodedPath == "/v1/orders" }.map(::decodedQuery)
        assertThat(listQueries).containsExactly(
            "market=KRW-BTC&states[]=wait&states[]=done&states[]=cancel&order_by=desc&limit=100&page=1",
        )
    }

    @Test
    fun `한 주문의 체결 수수료 합은 paid_fee 와 같고 나머지는 created_at 과 uuid 순 마지막 체결에 간다`() {
        orderPages[1] = orders(row("o1", "done", "0.003", "2026-10-19T10:00:00+09:00"))
        val at = "2026-10-19T12:00:00+09:00"
        details["o1"] = detail("o1", "bid", "0.01", trade("c", "0.001", "1", at), trade("a", "0.001", "1", at), trade("b", "0.001", "1", at))

        val fills = adapter().read(from, to)

        assertThat(fills.associate { it.id to it.feeKrw.toPlainString() }).containsExactlyInAnyOrderEntriesOf(
            mapOf("o1:a" to "0.0033333333", "o1:b" to "0.0033333333", "o1:c" to "0.0033333334"),
        )
        assertThat(fills.fold(BigDecimal.ZERO) { sum, fill -> sum.add(fill.feeKrw) }).isEqualByComparingTo("0.01")
    }

    @Test
    fun `창 밖 체결을 포함한 주문 전체의 수수료 배분 합도 paid_fee 와 같다`() {
        orderPages[1] = orders(row("o-span", "done", "0.06", "2026-10-19T08:00:00+09:00"))
        details["o-span"] = fixture("order-detail-span.json")

        val fills = adapter().read(Instant.parse("2026-10-18T00:00:00Z"), to)

        assertThat(fills.map { it.id }).containsExactly("o-span:t1", "o-span:t2", "o-span:t3", "o-span:t4")
        assertThat(fills.fold(BigDecimal.ZERO) { sum, fill -> sum.add(fill.feeKrw) }).isEqualByComparingTo("10")
    }

    @Test
    fun `페이지 마지막 행이 lower 7일 전보다 오래되면 다음 페이지를 읽지 않는다`() {
        orderPages[1] = fullPage(last = "2026-10-12T08:59:59+09:00")
        orderPages[2] = fullPage(last = "2026-10-01T00:00:00+09:00")

        adapter().read(from, to)

        assertThat(listPages()).containsExactly(1)
    }

    @Test
    fun `페이지 마지막 행이 정확히 lower 7일 전이면 다음 페이지를 읽는다`() {
        orderPages[1] = fullPage(last = "2026-10-12T09:00:00+09:00")

        adapter().read(from, to)

        assertThat(listPages()).containsExactly(1, 2)
    }

    @Test
    fun `20페이지를 읽고도 멈추지 못하면 WindowLimit 으로 실패한다`() {
        (1..21).forEach { orderPages[it] = fullPage(last = "2026-10-19T00:00:00+09:00") }

        assertThatThrownBy { adapter().read(from, to) }.isInstanceOf(FillHistoryWindowLimitException::class.java)
        assertThat(listPages()).containsExactlyElementsOf(1..20)
    }

    @Test
    fun `20번째 페이지가 100건 미만이면 한도 실패가 아니다`() {
        (1..19).forEach { orderPages[it] = fullPage(last = "2026-10-19T00:00:00+09:00") }
        orderPages[20] = orders(row("o-last", "cancel", "0", "2026-10-19T00:00:00+09:00"))

        assertThat(adapter().read(from, to)).isEmpty()
        assertThat(listPages()).containsExactlyElementsOf(1..20)
    }

    @Test
    fun `401 은 BITHUMB_AUTH 다`() {
        listResponse = { MockResponse().setResponseCode(401).setBody("""{"error":{"name":"invalid_access_key"}}""") }

        assertThatThrownBy { adapter().read(from, to) }
            .isInstanceOfSatisfying(AccountReadException::class.java) {
                assertThat(it.category).isEqualTo(AccountReadFailureCategory.BITHUMB_AUTH)
            }
    }

    @Test
    fun `목록과 상세 서명은 디코딩된 query 의 SHA512 query_hash 를 담는다`() {
        orderPages[1] = orders(row("o1", "done", "0.001", "2026-10-19T10:00:00+09:00"))
        details["o1"] = detail("o1", "bid", "1", trade("a", "0.001", "1000", "2026-10-19T12:00:00+09:00"))

        adapter().read(from, to)

        val requests = recorded()
        assertThat(requests).hasSize(2)
        requests.forEach { request ->
            val decoded = decodedQuery(request)
            assertThat(queryHash(request)).isEqualTo(BithumbJwtSigner.sha512Hex(decoded))
        }
        assertThat(decodedQuery(requests.last())).isEqualTo("uuid=o1")
    }

    @Test
    fun `상세 필드가 없거나 숫자 시각 형식이 아니면 UNEXPECTED 다`() {
        orderPages[1] = orders(row("o1", "done", "0.001", "2026-10-19T10:00:00+09:00"))
        val ok = trade("a", "0.001", "1000", "2026-10-19T12:00:00+09:00")
        val broken = listOf(
            """{"uuid":"o1","side":"bid","paid_fee":"1"}""",
            detail("o1", "bid", "fee", ok),
            detail("o1", "hold", "1", ok),
            detail("o1", "bid", "1", trade("a", "0.001", "many", "2026-10-19T12:00:00+09:00")),
            detail("o1", "bid", "1", trade("a", "0.001", "1000", "yesterday")),
            detail("o1", "bid", "1", """{"volume":"0.001","funds":"1000","created_at":"2026-10-19T12:00:00+09:00"}"""),
        )

        broken.forEach { body ->
            details["o1"] = body
            assertThatThrownBy { adapter().read(from, to) }
                .isInstanceOfSatisfying(AccountReadException::class.java) {
                    assertThat(it.category).isEqualTo(AccountReadFailureCategory.UNEXPECTED)
                }
        }
    }

    private fun adapter() = BithumbFillHistoryPrivateAccountAdapter(
        WebClient.builder().baseUrl(server.url("/").toString().removeSuffix("/")).build(),
        "test-access-key",
        "test-secret-key",
        clock,
    )

    private fun describe(fill: KoreaSpotFill): String = listOf(
        fill.id,
        fill.side.name,
        fill.quantity.stripTrailingZeros().toPlainString(),
        fill.fundsKrw.stripTrailingZeros().toPlainString(),
        fill.feeKrw.stripTrailingZeros().toPlainString(),
        fill.filledAt.toString(),
    ).joinToString(" ")

    private fun recorded(): List<RecordedRequest> =
        generateSequence { server.takeRequest(10, TimeUnit.MILLISECONDS) }.toList()

    private fun listPages(): List<Int> = recorded()
        .filter { it.requestUrl!!.encodedPath == "/v1/orders" }
        .map { it.requestUrl!!.queryParameter("page")!!.toInt() }

    private fun decodedQuery(request: RecordedRequest): String =
        URLDecoder.decode(request.requestUrl!!.encodedQuery!!, StandardCharsets.UTF_8)

    private fun queryHash(request: RecordedRequest): String {
        val payload = request.getHeader("Authorization")!!.removePrefix("Bearer ").split(".")[1]
        val body = String(Base64.getUrlDecoder().decode(payload))
        return Regex("\"query_hash\":\"([0-9a-f]+)\"").find(body)!!.groupValues[1]
    }

    private fun fullPage(last: String): String = orders(
        *(1..99).map { row("p$it", "cancel", "0", "2026-10-19T10:00:00+09:00") }.toTypedArray(),
        row("p-last", "cancel", "0", last),
    )

    private fun orders(vararg rows: String) = rows.joinToString(",", "[", "]")

    private fun row(uuid: String, state: String, executed: String, createdAt: String) =
        """{"uuid":"$uuid","side":"bid","state":"$state","market":"KRW-BTC","created_at":"$createdAt",""" +
            """"executed_volume":"$executed","paid_fee":"0"}"""

    private fun detail(uuid: String, side: String, paidFee: String, vararg trades: String) =
        """{"uuid":"$uuid","side":"$side","state":"done","market":"KRW-BTC","paid_fee":"$paidFee",""" +
            """"trades":${trades.joinToString(",", "[", "]")}}"""

    private fun trade(uuid: String, volume: String, funds: String, createdAt: String) =
        """{"market":"KRW-BTC","uuid":"$uuid","volume":"$volume","funds":"$funds","created_at":"$createdAt"}"""

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/bithumb-private/$name")!!.readText()
}
