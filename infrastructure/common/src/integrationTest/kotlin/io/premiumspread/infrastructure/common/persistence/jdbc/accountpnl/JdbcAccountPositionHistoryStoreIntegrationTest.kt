package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import io.premiumspread.domain.accountpnl.AccountHedgePosition
import io.premiumspread.domain.accountpnl.AccountNetPnl
import io.premiumspread.domain.accountpnl.ForeignLeg
import io.premiumspread.domain.accountpnl.ForeignLegObservation
import io.premiumspread.domain.accountpnl.ForeignPerpMarginType
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource
import io.premiumspread.domain.accountpnl.KoreaLeg
import io.premiumspread.domain.accountpnl.KoreaLegObservation
import io.premiumspread.domain.accountpnl.KoreaSpotAccountSnapshot
import io.premiumspread.domain.accountpnl.LegObservations
import io.premiumspread.domain.accountpnl.MarketQuote
import io.premiumspread.domain.accountpnl.PositionHistoryRound
import io.premiumspread.domain.accountpnl.PositionLegs
import io.premiumspread.domain.accountpnl.PriceLevel
import io.premiumspread.infrastructure.common.support.LatestSchemaFixture
import io.premiumspread.infrastructure.common.support.SharedMySqlContainer
import io.premiumspread.infrastructure.common.support.TestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

@Tag("integration")
class JdbcAccountPositionHistoryStoreIntegrationTest {

    private lateinit var dataSource: DataSource
    private lateinit var transactionManager: DataSourceTransactionManager
    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcAccountPositionHistoryStore

    @BeforeEach
    fun setUp() {
        latestSchema.reset()
        dataSource = DriverManagerDataSource(mysql.utcJdbcUrl, mysql.username, mysql.password)
        transactionManager = DataSourceTransactionManager(dataSource)
        jdbcTemplate = JdbcTemplate(dataSource)
        store = newStore(jdbcTemplate)
    }

    private fun newStore(template: JdbcTemplate) =
        JdbcAccountPositionHistoryStore(template, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC))

    @Test
    fun `진입 추가진입 부분정리 전량정리는 건 1행과 사건 4행이고 재진입은 새 건을 연다`() {
        store.record(hedgedRound("0.2", "-0.2", minute(1)))
        store.record(hedgedRound("0.3", "-0.3", minute(2)))
        store.record(hedgedRound("0.1", "-0.1", minute(3)))
        store.record(round(EMPTY_LEGS, null, minute(4)))

        val episode = episodes().single()
        assertThat(episode["status"]).isEqualTo("CLOSED")
        assertThat(episode["event_count"]).isEqualTo(4)
        assertThat(instant(episode["closed_at"])).isEqualTo(minute(4))
        assertThat(instant(episode["opened_at"])).isEqualTo(minute(1))
        assertThat(episode["open_marker"]).isNull()
        assertThat(events().map { it["seq"] }).containsExactly(1, 2, 3, 4)
        assertThat(events().map { it["event_type"] }).containsExactly("OPENED", "INCREASED", "REDUCED", "CLOSED")
        assertThat(events().map { it["hedged"] }).containsExactly(true, true, true, false)
        assertThat(events().map { instant(it["occurred_at"]) })
            .containsExactly(minute(1), minute(2), minute(3), minute(4))
        assertThat(events().last()["korea_quantity"]).isEqualTo("0")
        // CLOSED 는 직전 reference 를 유지한다
        assertThat(BigDecimal(episode["reference_korea_quantity"] as String)).isEqualByComparingTo("0.1")

        store.record(hedgedRound("0.5", "-0.5", minute(5)))

        assertThat(episodes()).hasSize(2)
        assertThat(count("SELECT COUNT(*) FROM account_position_episode WHERE status = 'OPEN'")).isEqualTo(1)
        val reopened = episodes().last()
        assertThat(reopened["event_count"]).isEqualTo(1)
        assertThat(events().last()["episode_id"]).isEqualTo(reopened["id"])
        assertThat(events().last()["seq"]).isEqualTo(1)
    }

    @Test
    fun `선체결 빗썸만 잡으면 reference 가 비고 양쪽이 되면 채워진다`() {
        store.record(round(legs("0.2", "100000000", "0", "0"), null, minute(1)))

        assertThat(events().map { it["hedged"] }).containsExactly(false)
        assertThat(episodes().single()["reference_korea_quantity"]).isNull()
        assertThat(episodes().single()["reference_foreign_price"]).isNull()

        store.record(hedgedRound("0.2", "-0.2", minute(2)))

        assertThat(events().map { it["event_type"] }).containsExactly("OPENED", "INCREASED")
        val episode = episodes().single()
        assertThat(BigDecimal(episode["reference_korea_quantity"] as String)).isEqualByComparingTo("0.2")
        assertThat(BigDecimal(episode["reference_korea_price"] as String)).isEqualByComparingTo(KOREA_PRICE)
        assertThat(BigDecimal(episode["reference_foreign_quantity"] as String)).isEqualByComparingTo("0.2")
        assertThat(BigDecimal(episode["reference_foreign_price"] as String)).isEqualByComparingTo(FOREIGN_PRICE)
    }

    @Test
    fun `idempotent 같은 round 는 행을 늘리지 않고 새 인스턴스의 같은 leg 는 사건 없이 관측만 더한다`() {
        val first = hedgedRound("0.2", "-0.2", minute(1))
        store.record(first)
        store.record(first)

        assertThat(episodes()).hasSize(1)
        assertThat(events()).hasSize(1)
        assertThat(observations()).hasSize(1)

        val restarted = newStore(jdbcTemplate)
        restarted.record(hedgedRound("0.2", "-0.2", minute(2)))

        assertThat(episodes().single()["event_count"]).isEqualTo(1)
        assertThat(events()).hasSize(1)
        assertThat(observations()).hasSize(2)
    }

    @Test
    fun `idempotent 소수 8자리를 넘는 진입가와 scale 만 다른 수량은 사건을 늘리지 않고 원본과 같게 복원된다`() {
        val entry = "28536.842105263158"
        val koreaPrice = "143016278.123456789012"
        store.record(round(legs("0.266", koreaPrice, "-0.266", entry), pnl("0.266", "-0.266", minute(1)), minute(1)))
        // 같은 포지션, scale 만 다른 표현(KoreaLeg/ForeignLeg 생성자 직접 — of 의 정규화를 거치지 않는다)
        val rescaled = PositionLegs(
            KoreaLeg(BigDecimal("0.26600000"), BigDecimal("$koreaPrice" + "000")),
            ForeignLeg(BigDecimal("-0.2660"), BigDecimal(entry + "00")),
        )
        newStore(jdbcTemplate).record(round(rescaled, null, minute(2)))
        newStore(jdbcTemplate).record(round(legs("0.266", koreaPrice, "-0.266", entry), null, minute(3)))

        assertThat(events()).hasSize(1)
        val episode = episodes().single()
        assertThat(episode["event_count"]).isEqualTo(1)
        assertThat(BigDecimal(episode["foreign_entry_price"] as String)).isEqualByComparingTo(entry)
        assertThat(BigDecimal(episode["korea_average_price"] as String)).isEqualByComparingTo(koreaPrice)
        assertThat(BigDecimal(episode["reference_korea_quantity"] as String)).isEqualByComparingTo("0.266")
        assertThat(BigDecimal(episode["reference_korea_price"] as String)).isEqualByComparingTo(koreaPrice)
        assertThat(BigDecimal(episode["reference_foreign_quantity"] as String)).isEqualByComparingTo("0.266")
        assertThat(BigDecimal(episode["reference_foreign_price"] as String)).isEqualByComparingTo(entry)
        assertThat(BigDecimal(events().single()["foreign_entry_price"] as String)).isEqualByComparingTo(entry)
    }

    @Test
    fun `singleOpenEpisode 열린 건이 있으면 OPEN 행을 하나 더 넣을 수 없다`() {
        store.record(hedgedRound("0.2", "-0.2", minute(1)))

        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO account_position_episode (
                    status, opened_at, korea_quantity, korea_average_price, foreign_signed_quantity,
                    foreign_entry_price, position_source, event_count, created_at, updated_at
                ) VALUES ('OPEN', ?, '1', '1', '-1', '1', 'EXCHANGE_API', 1, ?, ?)
                """.trimIndent(),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
            )
        }.isInstanceOf(DuplicateKeyException::class.java)
    }

    @Test
    fun `관측 보유 회차는 HOLDING 이고 정리 회차와 그 다음 회차는 닫힌 건의 FLAT 이다`() {
        store.record(hedgedRound("0.2", "-0.2", minute(1)))
        store.record(round(EMPTY_LEGS, null, minute(2)))
        store.record(round(EMPTY_LEGS, null, minute(3)))

        val episodeId = episodes().single()["id"]
        val rows = observations()
        assertThat(rows.map { it["kind"] }).containsExactly("HOLDING", "FLAT", "FLAT")
        assertThat(rows.map { it["episode_id"] }).containsOnly(episodeId)
        assertThat(rows.map { instant(it["observed_at"]) }).containsExactly(minute(1), minute(2), minute(3))
        assertThat(rows[0]["net_percent"]).isNotNull()
        assertThat(rows[0]["net_krw"]).isNotNull()
        assertThat(rows.drop(1).map { it["net_percent"] }).containsOnlyNulls()
        assertThat(rows.drop(1).map { it["net_krw"] }).containsOnlyNulls()
        assertThat(rows.drop(1).map { it["fx_rate"] as BigDecimal }).allSatisfy { assertThat(it).isEqualByComparingTo(FX) }
    }

    @Test
    fun `관측 기준 건이 없거나 한쪽 leg 만 열렸거나 pnl 이 없으면 0행이다`() {
        store.record(round(EMPTY_LEGS, null, minute(1))) // 열린 건도 기준 건도 없음
        store.record(round(legs("0.2", "100000000", "0", "0"), null, minute(2))) // 한쪽 leg 만
        store.record(round(EMPTY_LEGS, null, minute(3))) // 헤지 미성립으로 닫힘 → 기준 건 없음
        store.record(round(EMPTY_LEGS, null, minute(4)))
        store.record(round(legs("0.2", KOREA_PRICE, "-0.2", FOREIGN_PRICE), null, minute(5))) // 헤지지만 pnl 없음

        assertThat(observations()).isEmpty()
        assertThat(events().map { it["event_type"] }).containsExactly("OPENED", "CLOSED", "OPENED")
    }

    @Test
    fun `트랜잭션 관측 INSERT 가 실패하면 같은 회차의 건과 사건도 롤백된다`() {
        val failing = ObservationFailingJdbcTemplate(dataSource)
        val failingStore = newStore(failing)

        assertThatThrownBy { failingStore.record(hedgedRound("0.2", "-0.2", minute(1))) }
            .isInstanceOf(DataAccessResourceFailureException::class.java)
        assertThat(episodes()).isEmpty()
        assertThat(events()).isEmpty()

        store.record(hedgedRound("0.2", "-0.2", minute(2)))
        assertThatThrownBy { failingStore.record(hedgedRound("0.3", "-0.3", minute(3))) }
            .isInstanceOf(DataAccessResourceFailureException::class.java)

        val episode = episodes().single()
        assertThat(episode["event_count"]).isEqualTo(1)
        assertThat(BigDecimal(episode["korea_quantity"] as String)).isEqualByComparingTo("0.2")
        assertThat(events().map { it["event_type"] }).containsExactly("OPENED")
        assertThat(observations()).hasSize(1)
    }

    @Test
    fun `새 인스턴스가 직전과 같은 round 를 다시 기록해도 건 사건 관측 행 수는 그대로다`() {
        val same = hedgedRound("0.2", "-0.2", minute(1))
        store.record(same)

        newStore(jdbcTemplate).record(same)

        assertThat(episodes()).hasSize(1)
        assertThat(events()).hasSize(1)
        assertThat(observations()).hasSize(1)
    }

    @Test
    fun `평단 0 으로 OPENED 해도 건과 사건은 커밋되고 관측은 0행이다`() {
        val zeroAvg = legs("0.2", "0", "-0.2", FOREIGN_PRICE)
        val base = pnl("0.2", "-0.2", minute(1))
        val zeroPnl = base.copy(
            position = base.position.copy(korea = base.position.korea.copy(averageBuyPrice = BigDecimal.ZERO)),
        )

        store.record(round(zeroAvg, zeroPnl, minute(1)))

        assertThat(episodes().single()["status"]).isEqualTo("OPEN")
        assertThat(events().map { it["event_type"] }).containsExactly("OPENED")
        assertThat(observations()).isEmpty()
    }

    @Test
    fun `reference 평단이 0 인 건을 CLOSED 해도 CLOSED 는 커밋되고 관측은 0행이다`() {
        store.record(round(legs("0.2", "0", "-0.2", FOREIGN_PRICE), null, minute(1)))

        store.record(round(EMPTY_LEGS, null, minute(2)))

        assertThat(episodes().single()["status"]).isEqualTo("CLOSED")
        assertThat(events().map { it["event_type"] }).containsExactly("OPENED", "CLOSED")
        assertThat(observations()).isEmpty()
    }

    /** 관측 INSERT 만 실패시키는 위임 래퍼. 같은 DataSource 라 트랜잭션 연결을 공유한다. */
    private class ObservationFailingJdbcTemplate(dataSource: DataSource) : JdbcTemplate(dataSource) {
        override fun update(sql: String, vararg args: Any?): Int {
            if (sql.contains("INSERT INTO account_pnl_observation")) {
                throw DataAccessResourceFailureException("injected observation failure")
            }
            return super.update(sql, *args)
        }
    }

    @Test
    fun `레그 HOLDING 회차는 관측 바이낸스 빗썸 레그가 같은 키로 1행씩이고 값이 입력과 같다`() {
        store.record(hedgedRound("0.2", "-0.2", minute(1), FULL_LEGS))

        val observation = observations().single()
        assertThat(observation["kind"]).isEqualTo("HOLDING")
        val foreign = foreignLegs().single()
        val korea = koreaLegs().single()
        assertThat(foreign["episode_id"]).isEqualTo(observation["episode_id"])
        assertThat(korea["episode_id"]).isEqualTo(observation["episode_id"])
        assertThat(instant(foreign["observed_at"])).isEqualTo(instant(observation["observed_at"]))
        assertThat(instant(korea["observed_at"])).isEqualTo(instant(observation["observed_at"]))
        assertThat(instant(foreign["observed_at"])).isEqualTo(minute(1))

        assertThat(foreign["leverage"]).isEqualTo(3)
        assertThat(foreign["margin_type"]).isEqualTo("ISOLATED")
        assertThat(foreign["liquidation_price"] as BigDecimal).isEqualByComparingTo("112345.12345678")
        assertThat(foreign["mark_price"] as BigDecimal).isEqualByComparingTo("83550.1")
        assertThat(foreign["isolated_margin"] as BigDecimal).isEqualByComparingTo("5570.25")
        assertThat(foreign["maint_margin"] as BigDecimal).isEqualByComparingTo("66.84")
        assertThat(foreign["entry_commission_usdt"] as BigDecimal).isEqualByComparingTo("8.35494")
        assertThat(foreign["funding_usdt"] as BigDecimal).isEqualByComparingTo("-1.2345")
        assertThat(foreign["position_source"]).isEqualTo("EXCHANGE_API")
        assertThat(instant(foreign["created_at"])).isEqualTo(NOW)
        assertThat(korea["entry_fee_krw"] as BigDecimal).isEqualByComparingTo("9041.3022")
        assertThat(instant(korea["created_at"])).isEqualTo(NOW)
    }

    @Test
    fun `레그 바이낸스 레그가 없으면 바이낸스 행은 0행이고 빗썸 행은 남는다`() {
        store.record(hedgedRound("0.2", "-0.2", minute(1), LegObservations(FULL_LEGS.korea, null)))

        assertThat(observations()).hasSize(1)
        assertThat(foreignLegs()).isEmpty()
        assertThat(koreaLegs().single()["entry_fee_krw"] as BigDecimal).isEqualByComparingTo("9041.3022")
    }

    @Test
    fun `레그 미반영 값은 0 이 아니라 NULL 로 저장된다`() {
        val unknown = LegObservations(
            korea = KoreaLegObservation(null),
            foreign = ForeignLegObservation(null, null, null, null, null, null, null, null),
        )
        store.record(hedgedRound("0.2", "-0.2", minute(1), unknown))

        val foreign = foreignLegs().single()
        listOf(
            "leverage",
            "margin_type",
            "liquidation_price",
            "mark_price",
            "isolated_margin",
            "maint_margin",
            "entry_commission_usdt",
            "funding_usdt",
        ).forEach { column -> assertThat(foreign[column]).describedAs(column).isNull() }
        assertThat(foreign["position_source"]).isEqualTo("EXCHANGE_API")
        assertThat(koreaLegs().single()["entry_fee_krw"]).isNull()
    }

    @Test
    fun `레그 FLAT 관측 회차에는 레그 행이 없다`() {
        store.record(hedgedRound("0.2", "-0.2", minute(1), FULL_LEGS))
        store.record(round(EMPTY_LEGS, null, minute(2), FULL_LEGS))
        store.record(round(EMPTY_LEGS, null, minute(3), FULL_LEGS))

        assertThat(observations().map { it["kind"] }).containsExactly("HOLDING", "FLAT", "FLAT")
        assertThat(foreignLegs().map { instant(it["observed_at"]) }).containsExactly(minute(1))
        assertThat(koreaLegs().map { instant(it["observed_at"]) }).containsExactly(minute(1))
    }

    @Test
    fun `레그 같은 round 재기록은 레그 행을 늘리지 않는다`() {
        val same = hedgedRound("0.2", "-0.2", minute(1), FULL_LEGS)
        store.record(same)
        store.record(same)
        newStore(jdbcTemplate).record(same)

        assertThat(observations()).hasSize(1)
        assertThat(foreignLegs()).hasSize(1)
        assertThat(koreaLegs()).hasSize(1)
    }

    @Test
    fun `트랜잭션 레그 INSERT 가 실패하면 같은 회차의 관측 건 사건도 롤백된다`() {
        val failingStore = newStore(ForeignLegFailingJdbcTemplate(dataSource))

        assertThatThrownBy { failingStore.record(hedgedRound("0.2", "-0.2", minute(1), FULL_LEGS)) }
            .isInstanceOf(DataAccessResourceFailureException::class.java)
        assertThat(episodes()).isEmpty()
        assertThat(events()).isEmpty()
        assertThat(observations()).isEmpty()
        assertThat(foreignLegs()).isEmpty()
        assertThat(koreaLegs()).isEmpty()

        store.record(hedgedRound("0.2", "-0.2", minute(2), FULL_LEGS))

        assertThat(episodes()).hasSize(1)
        assertThat(events().map { it["event_type"] }).containsExactly("OPENED")
        assertThat(observations()).hasSize(1)
        assertThat(foreignLegs()).hasSize(1)
        assertThat(koreaLegs()).hasSize(1)
    }

    /** 바이낸스 레그 INSERT 만 실패시키는 위임 래퍼. 같은 DataSource 라 트랜잭션 연결을 공유한다. */
    private class ForeignLegFailingJdbcTemplate(dataSource: DataSource) : JdbcTemplate(dataSource) {
        override fun update(sql: String, vararg args: Any?): Int {
            if (sql.contains("INSERT INTO account_foreign_leg_observation")) {
                throw DataAccessResourceFailureException("injected foreign leg failure")
            }
            return super.update(sql, *args)
        }
    }

    @Test
    fun `latestReference 열린 건의 reference 가 닫힌 건보다 우선한다`() {
        store.record(hedgedRound("0.2", "-0.2", minute(1)))
        store.record(round(EMPTY_LEGS, null, minute(2)))
        store.record(hedgedRound("0.5", "-0.5", minute(3)))

        val reference = store.findLatestReference()!!

        assertThat(reference.koreaQuantity).isEqualByComparingTo("0.5")
        assertThat(reference.koreaAveragePrice).isEqualByComparingTo(KOREA_PRICE)
        assertThat(reference.foreignQuantity).isEqualByComparingTo("0.5")
        assertThat(reference.foreignEntryPrice).isEqualByComparingTo(FOREIGN_PRICE)
    }

    @Test
    fun `latestReference 열린 건에 reference 가 없으면 가장 최근 닫힌 건의 reference 를 쓴다`() {
        store.record(hedgedRound("0.2", "-0.2", minute(1)))
        store.record(round(EMPTY_LEGS, null, minute(2)))
        store.record(round(legs("0.3", "100000000", "0", "0"), null, minute(3))) // 한쪽 leg 만 → 열린 건 reference 없음

        val reference = store.findLatestReference()!!

        assertThat(reference.koreaQuantity).isEqualByComparingTo("0.2")
        assertThat(reference.foreignQuantity).isEqualByComparingTo("0.2")
    }

    @Test
    fun `latestReference 열린 건도 reference 있는 닫힌 건도 없으면 null 이다`() {
        assertThat(store.findLatestReference()).isNull()

        store.record(round(legs("0.2", "100000000", "0", "0"), null, minute(1)))
        assertThat(store.findLatestReference()).isNull()

        store.record(round(EMPTY_LEGS, null, minute(2)))
        assertThat(store.findLatestReference()).isNull()
    }

    private fun hedgedRound(
        koreaQuantity: String,
        foreignSigned: String,
        at: Instant,
        legObservations: LegObservations = LegObservations.NONE,
    ) = round(
        legs(koreaQuantity, KOREA_PRICE, foreignSigned, FOREIGN_PRICE),
        pnl(koreaQuantity, foreignSigned, at),
        at,
        legObservations,
    )

    private fun legs(koreaQuantity: String, koreaPrice: String, foreignSigned: String, foreignEntry: String) =
        PositionLegs(
            KoreaLeg.of(BigDecimal(koreaQuantity), BigDecimal(koreaPrice)),
            ForeignLeg.of(BigDecimal(foreignSigned), BigDecimal(foreignEntry)),
        )

    private fun round(
        legs: PositionLegs,
        pnl: AccountNetPnl?,
        at: Instant,
        legObservations: LegObservations = LegObservations.NONE,
    ) = PositionHistoryRound(legs, ForeignPerpPositionSource.EXCHANGE_API, pnl, MARKET, at, legObservations)

    private fun pnl(koreaQuantity: String, foreignSigned: String, at: Instant): AccountNetPnl {
        val korea = KoreaSpotAccountSnapshot(
            BigDecimal(koreaQuantity),
            BigDecimal(KOREA_PRICE),
            false,
            BigDecimal("12000"),
            BigDecimal("0.0004"),
            KOREA_BIDS,
            BigDecimal("109575000"),
            at,
        )
        val foreign = ForeignPerpAccountSnapshot(
            BigDecimal(foreignSigned),
            BigDecimal(FOREIGN_PRICE),
            BigDecimal("8.9"),
            BigDecimal("3.2"),
            BigDecimal("0.0005"),
            FOREIGN_ASKS,
            BigDecimal("81577"),
            at,
        )
        return AccountNetPnl.calculate(AccountHedgePosition(korea, foreign), FX, at, at)
    }

    private fun episodes() = jdbcTemplate.queryForList("SELECT * FROM account_position_episode ORDER BY id")

    private fun events() = jdbcTemplate.queryForList("SELECT * FROM account_position_event ORDER BY episode_id, seq")

    private fun observations() =
        jdbcTemplate.queryForList("SELECT * FROM account_pnl_observation ORDER BY observed_at, id")

    private fun foreignLegs() =
        jdbcTemplate.queryForList("SELECT * FROM account_foreign_leg_observation ORDER BY episode_id, observed_at")

    private fun koreaLegs() =
        jdbcTemplate.queryForList("SELECT * FROM account_korea_leg_observation ORDER BY episode_id, observed_at")

    private fun count(sql: String): Int = jdbcTemplate.queryForObject(sql, Int::class.java)!!

    private fun instant(value: Any?): Instant = when (value) {
        is Timestamp -> value.toInstant()
        is java.time.LocalDateTime -> value.toInstant(ZoneOffset.UTC)
        else -> error("not a timestamp: ${value?.javaClass}")
    }

    private fun minute(n: Long): Instant = BASE.plusSeconds(60 * n)

    companion object {
        private val NOW = Instant.parse("2026-11-10T00:30:00Z")
        private val BASE = Instant.parse("2026-11-10T00:00:00Z")
        private const val KOREA_PRICE = "113016278"
        private const val FOREIGN_PRICE = "83549.4"
        private val FX = BigDecimal("1328.5")
        private val EMPTY_LEGS = PositionLegs(KoreaLeg.EMPTY, ForeignLeg.FLAT)
        private val KOREA_BIDS = listOf(PriceLevel(BigDecimal("109500000"), BigDecimal("10.0")))
        private val FOREIGN_ASKS = listOf(PriceLevel(BigDecimal("81580"), BigDecimal("50.0")))
        private val MARKET = MarketQuote(KOREA_BIDS, FOREIGN_ASKS, FX)
        private val FULL_LEGS = LegObservations(
            korea = KoreaLegObservation(BigDecimal("9041.3022")),
            foreign = ForeignLegObservation(
                leverage = 3,
                marginType = ForeignPerpMarginType.ISOLATED,
                liquidationPrice = BigDecimal("112345.12345678"),
                markPrice = BigDecimal("83550.1"),
                isolatedMargin = BigDecimal("5570.25"),
                maintMargin = BigDecimal("66.84"),
                entryCommissionUsdt = BigDecimal("8.35494"),
                fundingUsdt = BigDecimal("-1.2345"),
            ),
        )

        val mysql: TestDatabase = SharedMySqlContainer.database("premiumspread_account_position_history")
        private val latestSchema = LatestSchemaFixture(mysql)
    }
}
