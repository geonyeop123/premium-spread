package io.premiumspread.application.job

import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.accountpnl.AccountRealizedPnlJob
import io.premiumspread.domain.accountpnl.AccountRealizedPnlStore
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.job.JobLock
import io.premiumspread.domain.job.JobRunOutcome
import io.premiumspread.domain.job.JobRunRecorder
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.infrastructure.batch.exchange.binance.BinanceFillHistoryPrivateAccountAdapter
import io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbFillHistoryPrivateAccountAdapter
import io.premiumspread.support.BatchIntegrationTestBase
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * #130 dod AC2 — 확정 손익 정산의 끝 단 검증.
 *
 * 실제 [BithumbFillHistoryPrivateAccountAdapter]·[BinanceFillHistoryPrivateAccountAdapter] 를 이 테스트의 MockWebServer
 * fixture 에 붙이고, 실제 [AccountRealizedPnlStore] (Spring context 의 JDBC 구현)를 MySQL 에 붙여 실제
 * [AccountRealizedPnlJob] 을 돌린다. 건·사건·관측 행은 SQL fixture 다. 거래소 밖 경계(lock·실행 기록·운영 알림·FX 최신 값)만 가짜다.
 *
 * 기대값은 fixture 체결을 손으로 합한 상수다(계산은 각 상수 옆 주석). design D4:
 * - 빗썸 실현 = 이동평균 원가(매수 수수료 포함). 전량 정리면 Σ매도(funds − fee) − Σ매수(funds + fee).
 * - 바이낸스 순 = Σ realizedPnl − Σ|commission| + Σ FUNDING_FEE(BTCUSDT).
 * - 합계 = 빗썸 실현 + 바이낸스 순 × 사건 시각 관측 행의 환율, 원 단위 HALF_UP.
 */
class AccountRealizedPnlSettlementIntegrationTest : BatchIntegrationTestBase() {

    @Autowired
    private lateinit var store: AccountRealizedPnlStore

    private lateinit var bithumbServer: MockWebServer
    private lateinit var binanceServer: MockWebServer
    private val clock = MutableClock(Instant.parse("2026-11-10T00:00:00Z"))
    private val executors = mutableListOf<JobExecutor>()

    /** 빗썸 주문(목록 행 + 상세). 목록은 created_at 내림차순으로 돌려준다. */
    private val bithumbOrders = CopyOnWriteArrayList<BithumbOrder>()

    /** 바이낸스 체결. 요청 창 `[startTime, endTime]` 안의 행만 돌려준다. */
    private val binanceTrades = CopyOnWriteArrayList<BinanceTrade>()

    /** 창과 무관하게 모든 userTrades 응답에 덧붙이는 체결(겹친 7일 창이 같은 id 를 다시 돌려주는 상황). */
    private val binanceTradesInEveryWindow = CopyOnWriteArrayList<BinanceTrade>()
    private val binanceFunding = CopyOnWriteArrayList<BinanceIncome>()

    /** 남은 횟수만큼 `/fapi/v1/income` 이 503 을 돌려준다. */
    private val incomeFailuresLeft = AtomicInteger(0)
    private val binanceRequests = CopyOnWriteArrayList<RecordedRequest>()
    private val bithumbRequests = CopyOnWriteArrayList<RecordedRequest>()

    @BeforeEach
    fun startServers() {
        bithumbServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    bithumbRequests += request
                    val url = request.requestUrl!!
                    return when (url.encodedPath) {
                        "/v1/orders" -> json(bithumbOrderList(url.queryParameter("page")!!.toInt()))

                        "/v1/order" -> bithumbOrders.firstOrNull { it.uuid == url.queryParameter("uuid") }
                            ?.let { json(it.detailJson()) }
                            ?: MockResponse().setResponseCode(404)

                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        binanceServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    binanceRequests += request
                    val url = request.requestUrl!!
                    return when (url.encodedPath) {
                        "/fapi/v1/time" -> json("""{"serverTime":${clock.millis()}}""")

                        "/fapi/v1/userTrades" -> json(
                            binanceUserTrades(url.queryParameter("startTime")!!.toLong(), url.queryParameter("endTime")!!.toLong()),
                        )

                        "/fapi/v1/income" -> if (incomeFailuresLeft.getAndUpdate { maxOf(0, it - 1) } > 0) {
                            MockResponse().setResponseCode(503)
                        } else {
                            json(binanceIncome(url.queryParameter("startTime")!!.toLong(), url.queryParameter("endTime")!!.toLong()))
                        }

                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
    }

    @AfterEach
    fun stopServers() {
        executors.forEach { it.close() }
        bithumbServer.shutdown()
        binanceServer.shutdown()
    }

    // ---------------------------------------------------------------- (a) 전량 정리

    @Test
    fun `a - 전량 정리된 건은 fixture 체결을 손으로 합한 값으로 두 쪽 CONFIRMED 이고 settled_seq 는 CLOSED seq 다`() {
        val episode = seedEpisodeA()
        clock.set(Instant.parse("2026-10-12T00:05:00Z"))

        assertThat(newJob().run()).isEqualTo(JobResult.Success)

        val row = settlement(episode)
        assertThat(row["korea_settlement"]).isEqualTo("CONFIRMED")
        assertThat(row["foreign_settlement"]).isEqualTo("CONFIRMED")
        assertEpisodeAValues(episode, row)
        assertThat(row["settled_seq"]).isEqualTo(EPISODE_A_CLOSED_SEQ)
        assertThat(row["settlement_failure"]).isNull()
        assertThat(row["settlement_attempts"]).isNull()
        assertThat(row["settlement_next_at"]).isNull()
        // 바이낸스 범위 (09-30T23:00, 10-12T00:00] 는 11일 1시간이므로 7일 이하 창 2개로 나뉜다 — 두 창 모두 조회됐다
        assertThat(userTradesWindowStarts()).hasSize(2)
        assertThat(binanceRequests.map { it.method } + bithumbRequests.map { it.method }).containsOnly("GET")
    }

    // ---------------------------------------------------------------- (b) REDUCED → CLOSED 누적

    @Test
    fun `b - REDUCED 뒤 실행하면 부분 정리분이 저장되고 CLOSED 뒤 실행하면 부분분을 포함한 전체 합으로 바뀐다`() {
        val episode = insertEpisode(opened = B_OPENED_AT)
        insertEvent(episode, 1, "OPENED", B_OPENED_AT.plusSeconds(60), korea = "0.03", foreign = "-0.03")
        insertEvent(episode, 2, "REDUCED", B_REDUCED_AT, korea = "0.01", foreign = "-0.01")
        insertObservation(episode, B_REDUCED_AT, "1390.00")
        // 빗썸: 0.03 매수, 0.02 매도 (REDUCED 전)
        bithumbOrders += BithumbOrder(
            "b-buy",
            "bid",
            "2026-10-20T00:00:10Z",
            "45",
            listOf(BithumbTrade("bt1", "0.03", "3000000", "2026-10-20T00:00:10Z")),
        )
        bithumbOrders += BithumbOrder(
            "b-sell-1",
            "ask",
            "2026-10-24T23:59:00Z",
            "32",
            listOf(BithumbTrade("bt2", "0.02", "2080000", "2026-10-24T23:59:00Z")),
        )
        // 바이낸스: 0.03 숏 진입, 0.02 환매 (REDUCED 전)
        binanceTrades += BinanceTrade(201, "SELL", "0.03", "0", "0.72", "2026-10-20T00:00:30Z")
        binanceTrades += BinanceTrade(202, "BUY", "0.02", "4.00", "0.50", "2026-10-24T23:59:30Z")
        binanceFunding += BinanceIncome(9101, "BTCUSDT", "0.90", "2026-10-22T00:00:00Z")

        clock.set(B_REDUCED_AT.plus(Duration.ofMinutes(5)))
        assertThat(newJob().run()).isEqualTo(JobResult.Success)

        val partial = settlement(episode)
        assertThat(partial["korea_settlement"]).isEqualTo("CONFIRMED")
        assertThat(partial["foreign_settlement"]).isEqualTo("CONFIRMED")
        // 매수 원가 = 3,000,000 + 45 = 3,000,045 (0.03)
        // 매도 0.02 의 원가 = 0.02 × 3,000,045 / 0.03 = 2,000,030
        // 부분 실현 = (2,080,000 − 32) − 2,000,030 = 79,938
        assertThat(partial.decimal("korea_realized_krw")).isEqualByComparingTo(B_PARTIAL_KOREA_KRW)
        // realizedPnl 0 + 4.00 = 4.00
        assertThat(partial.decimal("foreign_realized_pnl_usdt")).isEqualByComparingTo("4.00")
        // |0.72| + |0.50| = 1.22
        assertThat(partial.decimal("foreign_commission_usdt")).isEqualByComparingTo("1.22")
        // 0.90 (9101)
        assertThat(partial.decimal("foreign_funding_usdt")).isEqualByComparingTo("0.90")
        assertThat(partial.decimal("fx_rate")).isEqualByComparingTo("1390.00")
        assertThat(fxObservedAt(episode)).isEqualTo(B_REDUCED_AT)
        // 순 = 4.00 − 1.22 + 0.90 = 3.68 USDT, 3.68 × 1390.00 = 5,115.2
        // 합계 = 79,938 + 5,115.2 = 85,053.2 → HALF_UP 85,053
        assertThat(partial.decimal("total_realized_krw")).isEqualByComparingTo("85053")
        assertThat(partial["settled_seq"]).isEqualTo(2)

        // 이어서 나머지를 정리한다: 빗썸 0.01 매도, 바이낸스 0.01 환매, 펀딩 한 건 더
        bithumbOrders += BithumbOrder(
            "b-sell-2",
            "ask",
            "2026-10-27T23:59:00Z",
            "16",
            listOf(BithumbTrade("bt3", "0.01", "1050000", "2026-10-27T23:59:00Z")),
        )
        binanceTrades += BinanceTrade(203, "BUY", "0.01", "-1.20", "0.25", "2026-10-27T23:59:30Z")
        binanceFunding += BinanceIncome(9102, "BTCUSDT", "0.30", "2026-10-26T00:00:00Z")
        insertEvent(episode, 3, "CLOSED", B_CLOSED_AT, korea = "0", foreign = "0")
        insertObservation(episode, B_CLOSED_AT, "1400.50")
        closeEpisode(episode, B_CLOSED_AT)

        clock.set(B_CLOSED_AT.plus(Duration.ofMinutes(5)))
        assertThat(newJob().run()).isEqualTo(JobResult.Success)

        val full = settlement(episode)
        assertThat(full["korea_settlement"]).isEqualTo("CONFIRMED")
        assertThat(full["foreign_settlement"]).isEqualTo("CONFIRMED")
        // 남은 0.01 의 원가 = 3,000,045 − 2,000,030 = 1,000,015
        // 두 번째 매도 실현 = (1,050,000 − 16) − 1,000,015 = 49,969
        // 전체 = 79,938(부분분) + 49,969 = 129,907
        //   = Σ매도(funds − fee) − Σ매수(funds + fee) = (2,079,968 + 1,049,984) − 3,000,045 = 129,907
        assertThat(full.decimal("korea_realized_krw")).isEqualByComparingTo("129907")
        assertThat(full.decimal("korea_realized_krw")).isEqualByComparingTo(B_PARTIAL_KOREA_KRW.add(BigDecimal("49969")))
        // 4.00 + (−1.20) = 2.80
        assertThat(full.decimal("foreign_realized_pnl_usdt")).isEqualByComparingTo("2.80")
        // 0.72 + 0.50 + 0.25 = 1.47
        assertThat(full.decimal("foreign_commission_usdt")).isEqualByComparingTo("1.47")
        // 0.90 + 0.30 = 1.20
        assertThat(full.decimal("foreign_funding_usdt")).isEqualByComparingTo("1.20")
        assertThat(full.decimal("fx_rate")).isEqualByComparingTo("1400.50")
        assertThat(fxObservedAt(episode)).isEqualTo(B_CLOSED_AT)
        // 순 = 2.80 − 1.47 + 1.20 = 2.53 USDT, 2.53 × 1400.50 = 3,543.265
        // 합계 = 129,907 + 3,543.265 = 133,450.265 → HALF_UP 133,450
        assertThat(full.decimal("total_realized_krw")).isEqualByComparingTo("133450")
        assertThat(full["settled_seq"]).isEqualTo(3)
    }

    // ---------------------------------------------------------------- (c) 재실행·새 인스턴스·겹친 창

    @Test
    fun `c - 재시도·같은 회차 재실행·새 Job 인스턴스와 겹친 7일 창의 같은 체결 id 는 저장 값을 바꾸지 않는다`() {
        val episode = seedEpisodeA()
        // 첫 창(09-30T23:00 ~ 10-07T23:00)과 둘째 창이 모두 체결 102 를 돌려준다
        binanceTradesInEveryWindow += EPISODE_A_CLOSING_TRADE
        incomeFailuresLeft.set(1)
        clock.set(Instant.parse("2026-10-12T00:05:00Z"))

        // 1회차: 바이낸스 펀딩 조회가 실패한다 → 빗썸만 확정, 바이낸스 PENDING, settled_seq 그대로
        val firstJob = newJob()
        assertThat(firstJob.run()).isEqualTo(JobResult.Success)
        val failed = settlement(episode)
        assertThat(failed["korea_settlement"]).isEqualTo("CONFIRMED")
        assertThat(failed["foreign_settlement"]).isEqualTo("PENDING")
        assertThat(failed["settlement_failure"]).isEqualTo("FOREIGN_READ")
        assertThat(failed.decimal("korea_realized_krw")).isEqualByComparingTo(A_KOREA_KRW)
        assertThat(failed["settled_seq"]).isNull()

        // 재시작: backoff(5분)가 지난 뒤 새 Job 인스턴스가 두 쪽을 처음부터 다시 계산한다
        clock.advance(Duration.ofMinutes(6))
        binanceRequests.clear()
        assertThat(newJob().run()).isEqualTo(JobResult.Success)
        val settled = settlement(episode)
        assertThat(settled["korea_settlement"]).isEqualTo("CONFIRMED")
        assertThat(settled["foreign_settlement"]).isEqualTo("CONFIRMED")
        // 체결 102 가 두 창에서 두 번 왔지만 한 번만 셌다 — 두 번 셌다면 부호 수량 합이 +0.02 라 PENDING 이었다
        assertEpisodeAValues(episode, settled)
        assertThat(settled["settled_seq"]).isEqualTo(EPISODE_A_CLOSED_SEQ)
        val windowStarts = userTradesWindowStarts()
        assertThat(windowStarts).hasSize(2)
        assertThat(windowStarts.toSet()).hasSize(2)

        // 같은 회차를 같은 인스턴스로 다시 실행한다
        assertThat(firstJob.run()).isEqualTo(JobResult.Success)
        assertThat(settlement(episode)).isEqualTo(settled)

        // 새 Job 인스턴스로, 시간이 더 지난 뒤 다시 실행한다
        clock.advance(Duration.ofMinutes(5))
        assertThat(newJob().run()).isEqualTo(JobResult.Success)
        assertThat(settlement(episode)).isEqualTo(settled)
    }

    // ---------------------------------------------------------------- fixture: 건 A (전량 정리)

    /**
     * 건 A: 2026-10-01 진입, 2026-10-12 전량 정리. 앞 건이 없으므로 범위는 (opened_at − 1h, CLOSED occurred_at]
     * = (2026-09-30T23:00Z, 2026-10-12T00:00Z] 이다.
     */
    private fun seedEpisodeA(): Long {
        val opened = Instant.parse("2026-10-01T00:00:00Z")
        val closed = Instant.parse("2026-10-12T00:00:00Z")
        val episode = insertEpisode(opened = opened)
        insertEvent(episode, 1, "OPENED", opened, korea = "0.02", foreign = "-0.02")
        insertEvent(episode, EPISODE_A_CLOSED_SEQ, "CLOSED", closed, korea = "0", foreign = "0")
        closeEpisode(episode, closed)
        // 사건 시각의 관측 행이 쓰인다. 앞뒤 1분 행은 쓰이지 않아야 한다.
        insertObservation(episode, closed.minusSeconds(60), "1390.00")
        insertObservation(episode, closed, "1385.20")
        insertObservation(episode, closed.plusSeconds(60), "1377.00")

        // 빗썸 매수 주문 1건(체결 2건, paid_fee 25 → funds 비율 10 / 15), 매도 주문 1건(paid_fee 30)
        bithumbOrders += BithumbOrder(
            "a-buy",
            "bid",
            "2026-10-01T00:00:10Z",
            "25",
            listOf(
                BithumbTrade("at1", "0.01", "1000000", "2026-10-01T00:00:10Z"),
                BithumbTrade("at2", "0.01", "1500000", "2026-10-01T00:00:20Z"),
            ),
        )
        bithumbOrders += BithumbOrder(
            "a-sell",
            "ask",
            "2026-10-11T23:59:00Z",
            "30",
            listOf(BithumbTrade("at3", "0.02", "2600000", "2026-10-11T23:59:00Z")),
        )
        // 범위 밖(앞) 주문 — 창보다 이른 체결은 세지 않는다
        bithumbOrders += BithumbOrder(
            "a-before",
            "bid",
            "2026-09-30T22:00:00Z",
            "99",
            listOf(BithumbTrade("at0", "0.05", "5000000", "2026-09-30T22:00:00Z")),
        )

        // 바이낸스 숏 0.02 진입(첫 창), 환매 0.02(둘째 창)
        binanceTrades += BinanceTrade(101, "SELL", "0.02", "0", "0.48", "2026-10-01T00:00:30Z")
        binanceTrades += EPISODE_A_CLOSING_TRADE
        binanceFunding += BinanceIncome(9001, "BTCUSDT", "1.25", "2026-10-05T00:00:00Z")
        binanceFunding += BinanceIncome(9002, "BTCUSDT", "0.75", "2026-10-09T08:00:00Z")
        // 다른 심볼 펀딩은 버린다
        binanceFunding += BinanceIncome(9003, "ETHUSDT", "5.00", "2026-10-09T08:00:00Z")
        return episode
    }

    private fun assertEpisodeAValues(episode: Long, row: Map<String, Any?>) {
        // 매수 원가 = (1,000,000 + 10) + (1,500,000 + 15) = 2,500,025
        // 실현 = (2,600,000 − 30) − 2,500,025 = 99,945
        assertThat(row.decimal("korea_realized_krw")).isEqualByComparingTo(A_KOREA_KRW)
        // realizedPnl 0 + (−15.50) = −15.50
        assertThat(row.decimal("foreign_realized_pnl_usdt")).isEqualByComparingTo("-15.50")
        // |0.48| + |−0.47| = 0.95
        assertThat(row.decimal("foreign_commission_usdt")).isEqualByComparingTo("0.95")
        // 1.25 + 0.75 = 2.00 (ETHUSDT 5.00 제외)
        assertThat(row.decimal("foreign_funding_usdt")).isEqualByComparingTo("2.00")
        assertThat(row.decimal("fx_rate")).isEqualByComparingTo("1385.20")
        assertThat(fxObservedAt(episode)).isEqualTo(Instant.parse("2026-10-12T00:00:00Z"))
        // 순 = −15.50 − 0.95 + 2.00 = −14.45 USDT, −14.45 × 1385.20 = −20,016.14
        // 합계 = 99,945 − 20,016.14 = 79,928.86 → HALF_UP 79,929
        assertThat(row.decimal("total_realized_krw")).isEqualByComparingTo("79929")
    }

    // ---------------------------------------------------------------- SQL fixture

    private fun insertEpisode(opened: Instant): Long {
        jdbcTemplate.update(
            """
            INSERT INTO account_position_episode (status, opened_at, closed_at, korea_quantity, korea_average_price,
                foreign_signed_quantity, foreign_entry_price, position_source, event_count, created_at, updated_at)
            VALUES ('OPEN', ?, NULL, '0', '0', '0', '0', 'EXCHANGE_API', 1, ?, ?)
            """.trimIndent(),
            Timestamp.from(opened),
            Timestamp.from(opened),
            Timestamp.from(opened),
        )
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM account_position_episode", Long::class.java)!!
    }

    private fun closeEpisode(episode: Long, closedAt: Instant) {
        jdbcTemplate.update(
            "UPDATE account_position_episode SET status = 'CLOSED', closed_at = ?, updated_at = ? WHERE id = ?",
            Timestamp.from(closedAt),
            Timestamp.from(closedAt),
            episode,
        )
    }

    private fun insertEvent(episode: Long, seq: Int, type: String, at: Instant, korea: String, foreign: String) {
        jdbcTemplate.update(
            """
            INSERT INTO account_position_event (episode_id, seq, event_type, occurred_at, korea_quantity,
                korea_average_price, foreign_signed_quantity, foreign_entry_price, hedged, position_source, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, 'EXCHANGE_API', ?)
            """.trimIndent(),
            episode,
            seq,
            type,
            Timestamp.from(at),
            korea,
            if (korea == "0") "0" else "100000000",
            foreign,
            if (foreign == "0") "0" else "70000",
            Timestamp.from(at),
        )
    }

    private fun insertObservation(episode: Long, at: Instant, fxRate: String) {
        jdbcTemplate.update(
            """
            INSERT INTO account_pnl_observation (episode_id, kind, observed_at, net_percent, net_krw,
                current_premium_percent, entry_premium_percent, premium_gap_percent_point, fx_rate,
                korea_exit_price, foreign_exit_price, created_at)
            VALUES (?, 'FLAT', ?, NULL, NULL, 1.0, 1.0, 0.0, ?, 100, 70, ?)
            """.trimIndent(),
            episode,
            Timestamp.from(at),
            BigDecimal(fxRate),
            Timestamp.from(at),
        )
    }

    private fun settlement(episode: Long): Map<String, Any?> = jdbcTemplate.queryForMap(
        """
        SELECT korea_settlement, foreign_settlement, korea_realized_krw, foreign_realized_pnl_usdt,
               foreign_commission_usdt, foreign_funding_usdt, fx_rate, fx_observed_at, total_realized_krw,
               settled_seq, settled_at, settlement_attempts, settlement_failure, settlement_next_at, settlement_target_seq
        FROM account_position_episode WHERE id = ?
        """.trimIndent(),
        episode,
    )

    private fun Map<String, Any?>.decimal(column: String): BigDecimal = this[column] as BigDecimal

    /** store 와 같은 경로(Timestamp)로 읽는다. */
    private fun fxObservedAt(episode: Long): Instant = jdbcTemplate.queryForObject(
        "SELECT fx_observed_at FROM account_position_episode WHERE id = ?",
        Timestamp::class.java,
        episode,
    )!!.toInstant()

    private fun userTradesWindowStarts(): List<Long> = binanceRequests
        .filter { it.requestUrl!!.encodedPath == "/fapi/v1/userTrades" }
        .map { it.requestUrl!!.queryParameter("startTime")!!.toLong() }

    // ---------------------------------------------------------------- Job·adapter 조립

    private fun newJob(): AccountRealizedPnlJob {
        val executor = JobExecutor(AlwaysLock, NoopRecorder, { }, clock).also { executors += it }
        val korea = BithumbFillHistoryPrivateAccountAdapter(
            WebClient.builder().baseUrl(bithumbServer.url("/").toString().removeSuffix("/")).build(),
            "test-access-key",
            "test-secret-key",
            clock,
        )
        val foreign = BinanceFillHistoryPrivateAccountAdapter(
            WebClient.builder().baseUrl(binanceServer.url("/").toString().removeSuffix("/")).build(),
            "test-api-key",
            "test-secret-key",
            clock,
        )
        return AccountRealizedPnlJob(
            korea = korea,
            foreign = foreign,
            store = store,
            fx = NoLatestFx,
            // 사건 시각 관측 행이 있으므로 최신 FX 경로는 타지 않는다
            lastSuccess = JobLastSuccessReadPort { clock.instant() },
            executor = executor,
            clock = clock,
        )
    }

    // ---------------------------------------------------------------- 거래소 응답

    private fun bithumbOrderList(page: Int): String {
        if (page != 1) return "[]"
        return bithumbOrders.sortedByDescending { Instant.parse(it.createdAt) }.joinToString(",", "[", "]") { it.listJson() }
    }

    private fun binanceUserTrades(start: Long, end: Long): String =
        (binanceTrades.filter { it.timeMillis in start..end } + binanceTradesInEveryWindow)
            .joinToString(",", "[", "]") { it.json() }

    private fun binanceIncome(start: Long, end: Long): String =
        binanceFunding.filter { it.timeMillis in start..end }.joinToString(",", "[", "]") { it.json() }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private data class BithumbTrade(val uuid: String, val volume: String, val funds: String, val createdAt: String) {
        fun json() = """{"market":"KRW-BTC","uuid":"$uuid","volume":"$volume","funds":"$funds","created_at":"$createdAt"}"""
    }

    private data class BithumbOrder(
        val uuid: String,
        val side: String,
        val createdAt: String,
        val paidFee: String,
        val trades: List<BithumbTrade>,
    ) {
        private val executedVolume: String
            get() = trades.fold(BigDecimal.ZERO) { sum, trade -> sum + BigDecimal(trade.volume) }.toPlainString()

        fun listJson() =
            """{"uuid":"$uuid","side":"$side","state":"done","market":"KRW-BTC","created_at":"$createdAt",""" +
                """"executed_volume":"$executedVolume","paid_fee":"$paidFee"}"""

        fun detailJson() =
            """{"uuid":"$uuid","side":"$side","state":"done","market":"KRW-BTC","paid_fee":"$paidFee",""" +
                """"executed_volume":"$executedVolume","trades":${trades.joinToString(",", "[", "]") { it.json() }}}"""
    }

    private data class BinanceTrade(
        val id: Long,
        val side: String,
        val qty: String,
        val realizedPnl: String,
        val commission: String,
        val time: String,
    ) {
        val timeMillis: Long get() = Instant.parse(time).toEpochMilli()

        fun json() =
            """{"symbol":"BTCUSDT","id":$id,"orderId":$id,"side":"$side","price":"70000","qty":"$qty",""" +
                """"realizedPnl":"$realizedPnl","commission":"$commission","commissionAsset":"USDT",""" +
                """"positionSide":"BOTH","buyer":${side == "BUY"},"maker":false,"time":$timeMillis}"""
    }

    private data class BinanceIncome(val tranId: Long, val symbol: String, val income: String, val time: String) {
        val timeMillis: Long get() = Instant.parse(time).toEpochMilli()

        fun json() =
            """{"symbol":"$symbol","incomeType":"FUNDING_FEE","income":"$income","asset":"USDT",""" +
                """"info":"FUNDING_FEE","time":$timeMillis,"tranId":$tranId,"tradeId":""}"""
    }

    private class MutableClock(private var now: Instant) : Clock() {
        @Synchronized fun set(instant: Instant) {
            now = instant
        }

        @Synchronized fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        @Synchronized override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZONE

        override fun withZone(zone: ZoneId?): Clock = this
    }

    private object AlwaysLock : JobLock {
        override fun tryAcquire(key: String, owner: String, lease: Duration, acquiredAt: Instant) = true

        override fun renew(key: String, owner: String, lease: Duration) = true

        override fun release(key: String, owner: String) = Unit
    }

    private object NoopRecorder : JobRunRecorder {
        override fun started(jobId: JobId, runId: String, at: Instant) = Unit

        override fun completed(jobId: JobId, runId: String, outcome: JobRunOutcome, at: Instant, detail: String?) = Unit
    }

    /** 최신 FX 가 쓰이면 FX 사유로 PENDING 이 되어 단언이 깨진다 — 환율은 관측 행에서만 와야 한다. */
    private object NoLatestFx : FxRateReadPort {
        override fun findLatest(base: Currency, quote: Currency): ExchangeRateSnapshot? = null
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        const val EPISODE_A_CLOSED_SEQ = 2
        val A_KOREA_KRW = BigDecimal("99945")
        val EPISODE_A_CLOSING_TRADE = BinanceTrade(102, "BUY", "0.02", "-15.50", "-0.47", "2026-10-11T23:59:30Z")

        val B_OPENED_AT: Instant = Instant.parse("2026-10-20T00:00:00Z")
        val B_REDUCED_AT: Instant = Instant.parse("2026-10-25T00:00:00Z")
        val B_CLOSED_AT: Instant = Instant.parse("2026-10-28T00:00:00Z")
        val B_PARTIAL_KOREA_KRW = BigDecimal("79938")
    }
}
