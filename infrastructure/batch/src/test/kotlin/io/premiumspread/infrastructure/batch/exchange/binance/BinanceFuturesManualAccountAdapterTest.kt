package io.premiumspread.infrastructure.batch.exchange.binance

import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource
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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class BinanceFuturesManualAccountAdapterTest {
    private lateinit var server: MockWebServer
    private var depthBody: () -> MockResponse = { json(fixture("depth.json")) }
    private var tickerBody: () -> MockResponse = { json(fixture("ticker-price.json")) }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/fapi/v1/depth" -> depthBody()
                "/fapi/v2/ticker/price" -> tickerBody()
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun adapter(
        quantity: String = "0.266",
        entry: String = "83549.4",
        fee: String? = "8.9",
        funding: String? = "-3.2",
        timeout: Duration = Duration.ofSeconds(5),
        url: String = server.url("/").toString(),
    ) = BinanceFuturesManualAccountAdapter(
        WebClient.builder().baseUrl(url.trimEnd('/')).build(),
        BigDecimal(quantity),
        BigDecimal(entry),
        fee?.let { BigDecimal(it) },
        funding?.let { BigDecimal(it) },
        BigDecimal("0.0005"),
        timeout,
    )

    @Test
    fun `입력 수량을 음수 signedQuantity 로 내고 진입가·수수료·펀딩·taker 율은 입력 그대로`() {
        val snapshot = adapter().read()

        assertThat(snapshot.signedQuantity).isEqualByComparingTo(BigDecimal("-0.266"))
        assertThat(snapshot.entryPrice).isEqualByComparingTo(BigDecimal("83549.4"))
        assertThat(snapshot.entryCommissionUsdt).isEqualByComparingTo(BigDecimal("8.9"))
        assertThat(snapshot.fundingUsdt).isEqualByComparingTo(BigDecimal("-3.2"))
        assertThat(snapshot.takerFeeRate).isEqualByComparingTo(BigDecimal("0.0005"))
    }

    @Test
    fun `출처는 MANUAL_ENV`() {
        assertThat(adapter().read().positionSource).isEqualTo(ForeignPerpPositionSource.MANUAL_ENV)
    }

    @Test
    fun `진입 수수료와 펀딩 미입력이면 null`() {
        val snapshot = adapter(fee = null, funding = null).read()

        assertThat(snapshot.entryCommissionUsdt).isNull()
        assertThat(snapshot.fundingUsdt).isNull()
    }

    @Test
    fun `수량 0 이면 signedQuantity 0 이고 호가는 조회한다`() {
        val snapshot = adapter(quantity = "0").read()

        assertThat(snapshot.signedQuantity.signum()).isZero()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `ask 레벨은 가격 오름차순이고 관측 시각은 depth 의 T`() {
        depthBody = {
            json("""{"lastUpdateId":1,"T":1589436922959,"bids":[],"asks":[["4.3","1"],["4.1","2"],["4.2","3"]]}""")
        }

        val snapshot = adapter().read()

        assertThat(snapshot.askLevels.map { it.price.toPlainString() }).containsExactly("4.1", "4.2", "4.3")
        assertThat(snapshot.askObservedAt).isEqualTo(Instant.ofEpochMilli(1589436922959))
    }

    @Test
    fun `최근 체결가는 ticker price 의 price`() {
        assertThat(adapter().read().lastPrice).isEqualByComparingTo(BigDecimal("6000.01"))
    }

    @Test
    fun `숫자형 JSON 가격도 정밀도를 보존한다`() {
        depthBody = { json("""{"T":1589436922959,"bids":[],"asks":[[81580.123456789012, 1.000000000001]]}""") }
        tickerBody = { json("""{"symbol":"BTCUSDT","price": 81577.987654321098}""") }

        val snapshot = adapter().read()

        val expected = BigDecimal("81580.123456789012")
        assertThat(snapshot.askLevels[0].price).isEqualByComparingTo(expected)
        assertThat(snapshot.askLevels[0].price.toPlainString()).isEqualTo(expected.toPlainString())
        assertThat(snapshot.lastPrice).isEqualByComparingTo(BigDecimal("81577.987654321098"))
        assertThat(snapshot.lastPrice.toPlainString()).isEqualTo("81577.987654321098")
    }

    @Test
    fun `한 회차 요청은 ticker 다음 depth 두 건이고 서명·API key·서버 시각이 없다`() {
        adapter().read()

        val first = server.takeRequest(1, TimeUnit.SECONDS)!!
        val second = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertThat(first.path).isEqualTo("/fapi/v2/ticker/price?symbol=BTCUSDT")
        assertThat(second.path).isEqualTo("/fapi/v1/depth?symbol=BTCUSDT&limit=20")
        listOf(first, second).forEach {
            assertThat(it.method).isEqualTo("GET")
            assertThat(it.getHeader("X-MBX-APIKEY")).isNull()
            val query = it.requestUrl!!.encodedQuery!!.lowercase()
            assertThat(query).doesNotContain("signature", "timestamp", "recvwindow")
        }
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `두 번 읽으면 매번 다시 조회한다`() {
        val adapter = adapter()

        adapter.read()
        adapter.read()

        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test
    fun `429 는 NETWORK`() {
        tickerBody = { MockResponse().setResponseCode(429) }

        assertFails(AccountReadFailureCategory.NETWORK)
    }

    @Test
    fun `503 은 NETWORK`() {
        depthBody = { MockResponse().setResponseCode(503) }

        assertFails(AccountReadFailureCategory.NETWORK)
    }

    @Test
    fun `깨진 JSON 은 UNEXPECTED`() {
        tickerBody = { json("{not-json") }

        assertFails(AccountReadFailureCategory.UNEXPECTED)
    }

    @Test
    fun `ask 레벨이 비면 UNEXPECTED`() {
        depthBody = { json("""{"T":1589436922959,"bids":[],"asks":[]}""") }

        assertFails(AccountReadFailureCategory.UNEXPECTED, prefixed = false)
    }

    @Test
    fun `연결이 거부되면 NETWORK`() {
        val url = server.url("/").toString()
        server.shutdown()

        assertFails(AccountReadFailureCategory.NETWORK, adapter(url = url))
    }

    @Test
    fun `응답이 timeout 보다 늦으면 NETWORK`() {
        tickerBody = { json(fixture("ticker-price.json")).setBodyDelay(1, TimeUnit.SECONDS) }

        assertFails(AccountReadFailureCategory.NETWORK, adapter(timeout = Duration.ofMillis(200)))
    }

    @Test
    fun `200 빈 본문은 UNEXPECTED`() {
        tickerBody = { MockResponse().setResponseCode(200) }

        assertFails(AccountReadFailureCategory.UNEXPECTED)
    }

    private fun assertFails(
        category: AccountReadFailureCategory,
        target: BinanceFuturesManualAccountAdapter = adapter(),
        prefixed: Boolean = true,
    ) {
        assertThatThrownBy { target.read() }
            .isInstanceOfSatisfying(AccountReadException::class.java) {
                assertThat(it.category).isEqualTo(category)
                if (prefixed) assertThat(it.message).startsWith("Binance ")
                assertThat(it.message).doesNotContain("symbol=", "?", "limit=")
            }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/binance-private/$name")!!.readText()
}
