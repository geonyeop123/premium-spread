package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import io.premiumspread.domain.accountpnl.AppliedFx
import io.premiumspread.domain.accountpnl.ForeignLeg
import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource
import io.premiumspread.domain.accountpnl.ForeignRealized
import io.premiumspread.domain.accountpnl.KoreaLeg
import io.premiumspread.domain.accountpnl.KoreaRealized
import io.premiumspread.domain.accountpnl.PositionLegs
import io.premiumspread.domain.accountpnl.SettlementFailure
import io.premiumspread.domain.accountpnl.SettlementOutcome
import io.premiumspread.domain.accountpnl.SettlementStatus
import io.premiumspread.domain.accountpnl.SettlementTarget
import io.premiumspread.infrastructure.common.support.LatestSchemaFixture
import io.premiumspread.infrastructure.common.support.SharedMySqlContainer
import io.premiumspread.infrastructure.common.support.TestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/** design D2·D3·D5·D7, dod AC3 — 정산 대상 선정, 단조 기록, 환율 관측 행 선택. */
@Tag("integration")
class JdbcAccountRealizedPnlStoreIntegrationTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcAccountRealizedPnlStore

    @BeforeEach
    fun setUp() {
        latestSchema.reset()
        jdbcTemplate = JdbcTemplate(DriverManagerDataSource(mysql.utcJdbcUrl, mysql.username, mysql.password))
        store = JdbcAccountRealizedPnlStore(jdbcTemplate)
    }

    @Test
    fun `가장 오래된 대상 건을 돌려주고 그 건에서 조건을 만족하는 가장 최근 정리 사건을 목표로 삼는다`() {
        val older = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        event(older, 1, "OPENED", minutesAgo(30), "0.3", "-0.3")
        event(older, 2, "REDUCED", minutesAgo(25), "0.1", "-0.1")
        event(older, 3, "CLOSED", minutesAgo(20), "0", "0", ForeignPerpPositionSource.MANUAL_ENV)
        val newer = closedEpisode(opened = minutesAgo(15), closed = minutesAgo(10))
        event(newer, 1, "OPENED", minutesAgo(15), "0.2", "-0.2")
        event(newer, 2, "CLOSED", minutesAgo(10), "0", "0")

        val target = findTarget()!!

        assertThat(target.episodeId).isEqualTo(older)
        assertThat(target.seq).isEqualTo(3)
        assertThat(target.occurredAt).isEqualTo(minutesAgo(20))
        assertThat(target.legs).isEqualTo(PositionLegs(KoreaLeg.EMPTY, ForeignLeg.FLAT))
        assertThat(target.source).isEqualTo(ForeignPerpPositionSource.MANUAL_ENV)
        assertThat(target.attempts).isEqualTo(0)
        // newer 는 older 가 열린 뒤 닫혔으므로 앞 건이 아니다(prev.closed_at < ep.opened_at)
        assertThat(target.lowerExclusive).isEqualTo(minutesAgo(30).minus(LOOKBACK))
    }

    @Test
    fun `목표 사건 leg 는 사건 행의 문자열을 정규화해 복원한다`() {
        val episode = openEpisode(opened = minutesAgo(30))
        event(episode, 1, "OPENED", minutesAgo(30), "0.30", "-0.30")
        event(episode, 2, "ADJUSTED", minutesAgo(10), "0.12000", "-0.1200")

        val target = findTarget()!!

        assertThat(target.seq).isEqualTo(2)
        assertThat(target.legs).isEqualTo(
            PositionLegs(
                KoreaLeg.of(BigDecimal("0.12"), BigDecimal(KOREA_PRICE)),
                ForeignLeg.of(BigDecimal("-0.12"), BigDecimal(FOREIGN_PRICE)),
            ),
        )
        assertThat(target.source).isEqualTo(ForeignPerpPositionSource.EXCHANGE_API)
    }

    @Test
    fun `INCREASED 만 있는 건은 돌려주지 않는다`() {
        val episode = openEpisode(opened = minutesAgo(30))
        event(episode, 1, "OPENED", minutesAgo(30), "0.2", "-0.2")
        event(episode, 2, "INCREASED", minutesAgo(20), "0.3", "-0.3")

        assertThat(findTarget()).isNull()
    }

    @Test
    fun `2분이 안 된 사건은 목표가 아니다 — 그 앞의 조건을 만족하는 사건이 목표다`() {
        val episode = closedEpisode(opened = minutesAgo(30), closed = NOW.minusSeconds(60))
        event(episode, 1, "OPENED", minutesAgo(30), "0.3", "-0.3")
        event(episode, 2, "REDUCED", NOW.minusSeconds(120), "0.1", "-0.1")
        event(episode, 3, "CLOSED", NOW.minusSeconds(60), "0", "0")

        assertThat(findTarget()!!.seq).isEqualTo(2)

        jdbcTemplate.update("DELETE FROM account_position_event WHERE seq = 2")
        assertThat(findTarget()).isNull()
    }

    @Test
    fun `이미 따라잡은 건은 돌려주지 않는다`() {
        val episode = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        event(episode, 1, "OPENED", minutesAgo(30), "0.2", "-0.2")
        event(episode, 2, "CLOSED", minutesAgo(20), "0", "0")
        jdbcTemplate.update("UPDATE account_position_episode SET settled_seq = 2 WHERE id = ?", episode)

        assertThat(findTarget()).isNull()
    }

    @Test
    fun `settlement_next_at 이 아직 오지 않은 건은 돌려주지 않고 지나면 돌려준다`() {
        val episode = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        event(episode, 1, "OPENED", minutesAgo(30), "0.2", "-0.2")
        event(episode, 2, "CLOSED", minutesAgo(20), "0", "0")
        backoff(episode, targetSeq = 2, attempts = 3, nextAt = NOW.plusSeconds(1))

        assertThat(findTarget()).isNull()

        backoff(episode, targetSeq = 2, attempts = 3, nextAt = NOW)
        val target = findTarget()!!
        assertThat(target.episodeId).isEqualTo(episode)
        assertThat(target.attempts).isEqualTo(3)
    }

    @Test
    fun `backoff 중이라도 settled_seq 뒤에 더 새 정리 사건이 생겼으면 돌려준다`() {
        val episode = openEpisode(opened = minutesAgo(30))
        event(episode, 1, "OPENED", minutesAgo(30), "0.3", "-0.3")
        event(episode, 2, "REDUCED", minutesAgo(20), "0.2", "-0.2")
        backoff(episode, targetSeq = 2, attempts = 1, nextAt = NOW.plusSeconds(300))
        assertThat(findTarget()).isNull()

        event(episode, 3, "REDUCED", minutesAgo(10), "0.1", "-0.1")

        val target = findTarget()!!
        assertThat(target.episodeId).isEqualTo(episode)
        assertThat(target.seq).isEqualTo(3)
    }

    @Test
    fun `앞선 건이 backoff 중이면 그다음 건을 돌려준다`() {
        val older = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        event(older, 1, "OPENED", minutesAgo(30), "0.2", "-0.2")
        event(older, 2, "CLOSED", minutesAgo(20), "0", "0")
        backoff(older, targetSeq = 2, attempts = 2, nextAt = NOW.plusSeconds(600))
        val newer = closedEpisode(opened = minutesAgo(15), closed = minutesAgo(10))
        event(newer, 1, "OPENED", minutesAgo(15), "0.2", "-0.2")
        event(newer, 2, "CLOSED", minutesAgo(10), "0", "0")

        assertThat(findTarget()!!.episodeId).isEqualTo(newer)
    }

    @Test
    fun `lower 가 89일 전인 건은 돌려준다`() {
        val opened = NOW.minus(Duration.ofDays(89)).plus(LOOKBACK)
        val episode = closedEpisode(opened = opened, closed = opened.plusSeconds(600))
        event(episode, 1, "OPENED", opened, "0.2", "-0.2")
        event(episode, 2, "CLOSED", opened.plusSeconds(600), "0", "0")

        val target = findTarget()!!
        assertThat(target.episodeId).isEqualTo(episode)
        assertThat(target.lowerExclusive).isEqualTo(NOW.minus(Duration.ofDays(89)))
    }

    @Test
    fun `lower 가 91일 전인 건은 돌려주지 않는다`() {
        val opened = NOW.minus(Duration.ofDays(91)).plus(LOOKBACK)
        val episode = closedEpisode(opened = opened, closed = opened.plusSeconds(600))
        event(episode, 1, "OPENED", opened, "0.2", "-0.2")
        event(episode, 2, "CLOSED", opened.plusSeconds(600), "0", "0")

        assertThat(findTarget()).isNull()
    }

    @Test
    fun `lower 가 정확히 90일 전인 건은 돌려주지 않는다`() {
        val opened = NOW.minus(MAX_AGE).plus(LOOKBACK)
        val episode = closedEpisode(opened = opened, closed = opened.plusSeconds(600))
        event(episode, 1, "OPENED", opened, "0.2", "-0.2")
        event(episode, 2, "CLOSED", opened.plusSeconds(600), "0", "0")

        assertThat(findTarget()).isNull()
    }

    @Test
    fun `lower 는 앞 건이 없으면 opened_at 에서 1시간 전이다`() {
        val episode = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        event(episode, 1, "OPENED", minutesAgo(30), "0.2", "-0.2")
        event(episode, 2, "CLOSED", minutesAgo(20), "0", "0")

        assertThat(findTarget()!!.lowerExclusive).isEqualTo(minutesAgo(30).minus(LOOKBACK))
    }

    @Test
    fun `lower 는 앞 건이 진입 1시간 안에 닫혔으면 앞 건 가운데 가장 늦게 닫힌 건의 closed_at 이다`() {
        val first = closedEpisode(opened = minutesAgo(120), closed = minutesAgo(75))
        val second = closedEpisode(opened = minutesAgo(70), closed = minutesAgo(60))
        listOf(first, second).forEach { settled ->
            jdbcTemplate.update("UPDATE account_position_episode SET settled_seq = 9 WHERE id = ?", settled)
        }
        val episode = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        event(episode, 1, "OPENED", minutesAgo(30), "0.2", "-0.2")
        event(episode, 2, "CLOSED", minutesAgo(20), "0", "0")

        val target = findTarget()!!
        assertThat(target.episodeId).isEqualTo(episode)
        // 앞 건이 진입 30분 전에 닫혔다: max(closed_at, opened_at − 1h) = closed_at
        assertThat(target.lowerExclusive).isEqualTo(minutesAgo(60))
    }

    @Test
    fun `lower 는 앞 건이 진입 1시간보다 전에 닫혔으면 opened_at 에서 1시간 전이다`() {
        val previous = closedEpisode(opened = minutesAgo(300), closed = minutesAgo(200))
        jdbcTemplate.update("UPDATE account_position_episode SET settled_seq = 9 WHERE id = ?", previous)
        val episode = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        event(episode, 1, "OPENED", minutesAgo(30), "0.2", "-0.2")
        event(episode, 2, "CLOSED", minutesAgo(20), "0", "0")

        assertThat(findTarget()!!.lowerExclusive).isEqualTo(minutesAgo(30).minus(LOOKBACK))
    }

    @Test
    fun `앞 건이 200일 전에 닫혔어도 새 건은 lower 가 opened_at 에서 1시간 전이라 대상이다`() {
        val longAgo = minutesAgo(30).minus(Duration.ofDays(200))
        val previous = closedEpisode(opened = longAgo.minus(Duration.ofDays(1)), closed = longAgo)
        jdbcTemplate.update("UPDATE account_position_episode SET settled_seq = 9 WHERE id = ?", previous)
        val episode = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        event(episode, 1, "OPENED", minutesAgo(30), "0.2", "-0.2")
        event(episode, 2, "CLOSED", minutesAgo(20), "0", "0")

        val target = findTarget()!!
        assertThat(target.episodeId).isEqualTo(episode)
        assertThat(target.lowerExclusive).isEqualTo(minutesAgo(30).minus(LOOKBACK))
    }

    @Test
    fun `확정 기록은 값과 settled_seq 를 쓰고 backoff 를 비운다`() {
        val episode = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        backoff(episode, targetSeq = 2, attempts = 2, nextAt = NOW.plusSeconds(600))
        jdbcTemplate.update("UPDATE account_position_episode SET settlement_failure = 'FX' WHERE id = ?", episode)

        assertThat(store.record(confirmed(target(episode, 2)), null)).isTrue()

        val row = episodeRow(episode)
        assertThat(row["korea_settlement"]).isEqualTo("CONFIRMED")
        assertThat(row["foreign_settlement"]).isEqualTo("CONFIRMED")
        assertThat(row["korea_realized_krw"] as BigDecimal).isEqualByComparingTo("15000.1234")
        assertThat(row["foreign_realized_pnl_usdt"] as BigDecimal).isEqualByComparingTo("12.5")
        assertThat(row["foreign_commission_usdt"] as BigDecimal).isEqualByComparingTo("1.25")
        assertThat(row["foreign_funding_usdt"] as BigDecimal).isEqualByComparingTo("-0.5")
        assertThat(row["fx_rate"] as BigDecimal).isEqualByComparingTo("1385.5")
        assertThat(instant(row["fx_observed_at"])).isEqualTo(minutesAgo(20))
        assertThat(row["total_realized_krw"] as BigDecimal).isEqualByComparingTo("29547")
        assertThat(row["settled_seq"]).isEqualTo(2)
        assertThat(instant(row["settled_at"])).isEqualTo(NOW)
        assertThat(row["settlement_attempts"]).isNull()
        assertThat(row["settlement_next_at"]).isNull()
        assertThat(row["settlement_failure"]).isNull()
        assertThat(row["settlement_target_seq"]).isEqualTo(2)
    }

    @Test
    fun `시도 시작은 시도 횟수를 올리고 next_at 과 목표 seq 를 써서 그동안 대상에서 뺀다`() {
        val episode = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        event(episode, 1, "OPENED", minutesAgo(30), "0.2", "-0.2")
        event(episode, 2, "CLOSED", minutesAgo(20), "0", "0")
        val target = findTarget()!!

        assertThat(store.beginAttempt(target, NOW.plusSeconds(300))).isTrue()

        val row = episodeRow(episode)
        assertThat(row["settlement_attempts"]).isEqualTo(1)
        assertThat(instant(row["settlement_next_at"])).isEqualTo(NOW.plusSeconds(300))
        assertThat(row["settlement_target_seq"]).isEqualTo(2)
        assertThat(row["settled_seq"]).isNull()
        assertThat(store.findTarget(NOW.plusSeconds(60), SETTLE_DELAY, MAX_AGE, LOOKBACK)).isNull()

        val retried = store.findTarget(NOW.plusSeconds(300), SETTLE_DELAY, MAX_AGE, LOOKBACK)!!
        assertThat(retried.attempts).isEqualTo(1)
        assertThat(store.beginAttempt(retried, NOW.plusSeconds(900))).isTrue()
        assertThat(episodeRow(episode)["settlement_attempts"]).isEqualTo(2)
    }

    @Test
    fun `시도 시작은 이미 같거나 뒤 seq 로 확정된 건이면 false 이고 행을 바꾸지 않는다`() {
        val episode = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        assertThat(store.record(confirmed(target(episode, 3)), null)).isTrue()
        val before = episodeRow(episode)

        assertThat(store.beginAttempt(target(episode, 3), NOW.plusSeconds(300))).isFalse()
        assertThat(store.beginAttempt(target(episode, 2), NOW.plusSeconds(300))).isFalse()
        assertThat(episodeRow(episode)).isEqualTo(before)
        assertThat(store.beginAttempt(target(episode, 4), NOW.plusSeconds(300))).isTrue()
    }

    @Test
    fun `미확정 기록은 시도 횟수를 다시 세지 않고 settled_seq 는 그대로 둔다`() {
        val episode = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        assertThat(store.beginAttempt(target(episode, 3), NOW.plusSeconds(300))).isTrue()
        val pending = confirmed(target(episode, 3)).copy(
            foreignStatus = SettlementStatus.PENDING,
            foreign = null,
            fx = null,
            totalKrw = null,
            failure = SettlementFailure.FOREIGN_QUANTITY,
        )

        assertThat(store.record(pending, NOW.plusSeconds(300))).isTrue()
        assertThat(store.beginAttempt(target(episode, 3), NOW.plusSeconds(600))).isTrue()
        assertThat(store.record(pending, NOW.plusSeconds(600))).isTrue()

        val row = episodeRow(episode)
        assertThat(row["korea_settlement"]).isEqualTo("CONFIRMED")
        assertThat(row["foreign_settlement"]).isEqualTo("PENDING")
        assertThat(row["korea_realized_krw"] as BigDecimal).isEqualByComparingTo("15000.1234")
        assertThat(row["foreign_realized_pnl_usdt"]).isNull()
        assertThat(row["total_realized_krw"]).isNull()
        assertThat(row["settled_seq"]).isNull()
        assertThat(row["settled_at"]).isNull()
        assertThat(row["settlement_attempts"]).isEqualTo(2)
        assertThat(instant(row["settlement_next_at"])).isEqualTo(NOW.plusSeconds(600))
        assertThat(row["settlement_failure"]).isEqualTo("FOREIGN_QUANTITY")
        assertThat(row["settlement_target_seq"]).isEqualTo(3)
    }

    @Test
    fun `미확정 기록은 확정된 합계와 환율을 지우지 않고 확정된 쪽 값만 바꾼다`() {
        val episode = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        assertThat(store.record(confirmed(target(episode, 3)), null)).isTrue()
        val laterForeign = ForeignRealized(BigDecimal("20"), BigDecimal("2"), BigDecimal("-1"))
        val pending = confirmed(target(episode, 5)).copy(
            koreaStatus = SettlementStatus.PENDING,
            korea = null,
            foreign = laterForeign,
            fx = null,
            totalKrw = null,
            failure = SettlementFailure.KOREA_READ,
        )

        assertThat(store.record(pending, NOW.plusSeconds(300))).isTrue()

        val row = episodeRow(episode)
        assertThat(row["settled_seq"]).isEqualTo(3)
        assertThat(row["korea_settlement"]).isEqualTo("PENDING")
        assertThat(row["foreign_settlement"]).isEqualTo("CONFIRMED")
        assertThat(row["korea_realized_krw"] as BigDecimal).isEqualByComparingTo("15000.1234")
        assertThat(row["total_realized_krw"] as BigDecimal).isEqualByComparingTo("29547")
        assertThat(row["fx_rate"] as BigDecimal).isEqualByComparingTo("1385.5")
        assertThat(instant(row["fx_observed_at"])).isEqualTo(minutesAgo(20))
        assertThat(row["foreign_realized_pnl_usdt"] as BigDecimal).isEqualByComparingTo("20")
        assertThat(row["foreign_commission_usdt"] as BigDecimal).isEqualByComparingTo("2")
        assertThat(row["foreign_funding_usdt"] as BigDecimal).isEqualByComparingTo("-1")
        assertThat(row["settlement_failure"]).isEqualTo("KOREA_READ")
        assertThat(row["settlement_target_seq"]).isEqualTo(5)
        assertThat(instant(row["settlement_next_at"])).isEqualTo(NOW.plusSeconds(300))
    }

    @Test
    fun `환율 실패로 바이낸스가 PENDING 이어도 계산된 쪽 값은 저장하고 합계와 환율은 그대로 둔다`() {
        val episode = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        assertThat(store.record(confirmed(target(episode, 3)), null)).isTrue()
        val fxPending = confirmed(target(episode, 5)).copy(
            korea = KoreaRealized(BigDecimal("16000")),
            foreignStatus = SettlementStatus.PENDING,
            foreign = ForeignRealized(BigDecimal("30"), BigDecimal("3"), BigDecimal("-2")),
            fx = null,
            totalKrw = null,
            failure = SettlementFailure.FX,
        )

        assertThat(store.record(fxPending, NOW.plusSeconds(300))).isTrue()

        val row = episodeRow(episode)
        assertThat(row["settled_seq"]).isEqualTo(3)
        assertThat(row["foreign_settlement"]).isEqualTo("PENDING")
        assertThat(row["settlement_failure"]).isEqualTo("FX")
        assertThat(row["korea_realized_krw"] as BigDecimal).isEqualByComparingTo("16000")
        assertThat(row["foreign_realized_pnl_usdt"] as BigDecimal).isEqualByComparingTo("30")
        assertThat(row["foreign_commission_usdt"] as BigDecimal).isEqualByComparingTo("3")
        assertThat(row["foreign_funding_usdt"] as BigDecimal).isEqualByComparingTo("-2")
        assertThat(row["total_realized_krw"] as BigDecimal).isEqualByComparingTo("29547")
        assertThat(row["fx_rate"] as BigDecimal).isEqualByComparingTo("1385.5")
        assertThat(instant(row["fx_observed_at"])).isEqualTo(minutesAgo(20))
    }

    @Test
    fun `같은 건에 더 작은 seq 로 기록하면 false 이고 행을 바꾸지 않는다`() {
        val episode = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        assertThat(store.record(confirmed(target(episode, 3)), null)).isTrue()
        val before = episodeRow(episode)

        val stale = confirmed(target(episode, 2)).copy(korea = KoreaRealized(BigDecimal("1")))
        val stalePending = stale.copy(koreaStatus = SettlementStatus.PENDING, failure = SettlementFailure.KOREA_READ)

        assertThat(store.record(stale, null)).isFalse()
        assertThat(store.record(stalePending, NOW.plusSeconds(300))).isFalse()
        assertThat(store.record(confirmed(target(episode, 3)), null)).isFalse()
        assertThat(episodeRow(episode)).isEqualTo(before)
    }

    @Test
    fun `findFxAt 은 사건 시각의 관측 행을 먼저 쓴다`() {
        val episode = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        observation(episode, minutesAgo(21), "1380")
        observation(episode, minutesAgo(20), "1385.5")

        assertThat(store.findFxAt(minutesAgo(20), TOLERANCE))
            .isEqualTo(AppliedFx(BigDecimal("1385.50000000"), minutesAgo(20)))
    }

    @Test
    fun `findFxAt 은 사건 시각 행이 없으면 10분 이내 직전 행을 쓴다`() {
        val episode = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        observation(episode, minutesAgo(30), "1370")
        observation(episode, minutesAgo(28), "1375")
        observation(episode, minutesAgo(19), "1390")

        val fx = store.findFxAt(minutesAgo(20), TOLERANCE)!!

        assertThat(fx.rate).isEqualByComparingTo("1375")
        assertThat(fx.observedAt).isEqualTo(minutesAgo(28))
        assertThat(store.findFxAt(minutesAgo(18), TOLERANCE)!!.observedAt).isEqualTo(minutesAgo(19))
    }

    @Test
    fun `findFxAt 은 10분 이내 직전 행도 없으면 null 이다`() {
        val episode = closedEpisode(opened = minutesAgo(30), closed = minutesAgo(20))
        observation(episode, minutesAgo(30).minusSeconds(1), "1370")
        observation(episode, minutesAgo(19), "1390")

        assertThat(store.findFxAt(minutesAgo(20), TOLERANCE)).isNull()
        assertThat(store.findFxAt(minutesAgo(29), TOLERANCE)!!.observedAt).isEqualTo(minutesAgo(30).minusSeconds(1))
    }

    @Test
    fun `findFxAt 의 10분 하한은 포함이다`() {
        val episode = closedEpisode(opened = minutesAgo(40), closed = minutesAgo(20))
        observation(episode, minutesAgo(30), "1370")

        val fx = store.findFxAt(minutesAgo(20), TOLERANCE)!!

        assertThat(fx.observedAt).isEqualTo(minutesAgo(30))
        assertThat(fx.rate).isEqualByComparingTo("1370")
    }

    private fun findTarget(): SettlementTarget? = store.findTarget(NOW, SETTLE_DELAY, MAX_AGE, LOOKBACK)

    private fun target(episode: Long, seq: Int) = SettlementTarget(
        episodeId = episode,
        seq = seq,
        occurredAt = minutesAgo(20),
        legs = PositionLegs(KoreaLeg.EMPTY, ForeignLeg.FLAT),
        source = ForeignPerpPositionSource.EXCHANGE_API,
        lowerExclusive = minutesAgo(90),
        attempts = 0,
    )

    private fun confirmed(target: SettlementTarget) = SettlementOutcome(
        target = target,
        koreaStatus = SettlementStatus.CONFIRMED,
        korea = KoreaRealized(BigDecimal("15000.1234")),
        foreignStatus = SettlementStatus.CONFIRMED,
        foreign = ForeignRealized(BigDecimal("12.5"), BigDecimal("1.25"), BigDecimal("-0.5")),
        fx = AppliedFx(BigDecimal("1385.5"), minutesAgo(20)),
        totalKrw = BigDecimal("29547"),
        failure = null,
        decidedAt = NOW,
    )

    private fun openEpisode(opened: Instant): Long = insertEpisode("OPEN", opened, null)

    private fun closedEpisode(opened: Instant, closed: Instant): Long = insertEpisode("CLOSED", opened, closed)

    private fun insertEpisode(status: String, opened: Instant, closed: Instant?): Long {
        jdbcTemplate.update(
            """
            INSERT INTO account_position_episode (status, opened_at, closed_at, korea_quantity, korea_average_price,
                foreign_signed_quantity, foreign_entry_price, position_source, event_count, created_at, updated_at)
            VALUES (?, ?, ?, '0', '0', '0', '0', 'EXCHANGE_API', 1, ?, ?)
            """.trimIndent(),
            status,
            Timestamp.from(opened),
            closed?.let(Timestamp::from),
            Timestamp.from(NOW),
            Timestamp.from(NOW),
        )
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM account_position_episode", Long::class.java)!!
    }

    private fun event(
        episode: Long,
        seq: Int,
        type: String,
        at: Instant,
        koreaQuantity: String,
        foreignQuantity: String,
        source: ForeignPerpPositionSource = ForeignPerpPositionSource.EXCHANGE_API,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO account_position_event (episode_id, seq, event_type, occurred_at, korea_quantity,
                korea_average_price, foreign_signed_quantity, foreign_entry_price, hedged, position_source, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)
            """.trimIndent(),
            episode,
            seq,
            type,
            Timestamp.from(at),
            koreaQuantity,
            if (BigDecimal(koreaQuantity).signum() == 0) "0" else KOREA_PRICE,
            foreignQuantity,
            if (BigDecimal(foreignQuantity).signum() == 0) "0" else FOREIGN_PRICE,
            source.name,
            Timestamp.from(NOW),
        )
    }

    private fun backoff(episode: Long, targetSeq: Int, attempts: Int, nextAt: Instant) {
        jdbcTemplate.update(
            """
            UPDATE account_position_episode
            SET settlement_target_seq = ?, settlement_attempts = ?, settlement_next_at = ?
            WHERE id = ?
            """.trimIndent(),
            targetSeq,
            attempts,
            Timestamp.from(nextAt),
            episode,
        )
    }

    private fun observation(episode: Long, at: Instant, fxRate: String) {
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
            Timestamp.from(NOW),
        )
    }

    private fun episodeRow(id: Long): Map<String, Any?> =
        jdbcTemplate.queryForMap("SELECT * FROM account_position_episode WHERE id = ?", id)

    private fun instant(value: Any?): Instant = when (value) {
        is Timestamp -> value.toInstant()
        is java.time.LocalDateTime -> value.toInstant(java.time.ZoneOffset.UTC)
        else -> error("not a timestamp: ${value?.javaClass}")
    }

    private fun minutesAgo(n: Long): Instant = NOW.minusSeconds(60 * n)

    companion object {
        private val NOW = Instant.parse("2026-11-10T00:30:00Z")
        private val SETTLE_DELAY = Duration.ofMinutes(2)
        private val MAX_AGE = Duration.ofDays(90)
        private val LOOKBACK = Duration.ofHours(1)
        private val TOLERANCE = Duration.ofMinutes(10)
        private const val KOREA_PRICE = "113016278"
        private const val FOREIGN_PRICE = "83549.4"

        val mysql: TestDatabase = SharedMySqlContainer.database("premiumspread_account_realized_pnl")
        private val latestSchema = LatestSchemaFixture(mysql)
    }
}
