package io.premiumspread.application.job.ownerops

import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.OwnerAlertOutbox
import io.premiumspread.domain.accountpnl.OwnerAlertSignalState
import io.premiumspread.domain.job.JobFailureStreak
import io.premiumspread.domain.job.JobFailureStreakReadPort
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.LatestMarketTickReadPort
import io.premiumspread.domain.market.MarketTick
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 단일 스레드 in-memory [OwnerAlertOutbox]. version 조건부 저장, (eventKey, channel) 중복 무시. */
class FakeOwnerAlertOutbox : OwnerAlertOutbox {
    val states = mutableMapOf<String, OwnerAlertSignalState>()
    val rows = mutableListOf<AccountPnlMail>()
    var enqueueCalls = 0
    var loadFailure: Exception? = null
    var nextEnqueueFailure: Exception? = null
    var nextEnqueueConflict = false

    override fun loadSignals(signalKeys: Collection<String>): Map<String, OwnerAlertSignalState> {
        loadFailure?.let { throw it }
        return states.filterKeys { it in signalKeys }
    }

    override fun enqueue(signals: List<OwnerAlertSignalState>, rows: List<AccountPnlMail>): Boolean {
        enqueueCalls++
        nextEnqueueFailure?.let {
            nextEnqueueFailure = null
            throw it
        }
        if (nextEnqueueConflict) {
            nextEnqueueConflict = false
            return false
        }
        if (signals.any { (states[it.signalKey]?.version ?: 0L) != it.version }) return false
        signals.forEach { states[it.signalKey] = it.copy(version = it.version + 1) }
        rows.forEach { row ->
            if (this.rows.none { it.eventKey == row.eventKey && it.channel == row.channel }) this.rows += row
        }
        return true
    }
}

/** 마지막 성공 fake. 기본값은 호출 시점 `clock.instant() − 1분`(정상). [overrides] 의 값(null 포함)이 우선한다. */
class FakeLastSuccess(private val clock: Clock) : JobLastSuccessReadPort {
    val overrides = mutableMapOf<JobId, Instant?>()
    val calls = mutableListOf<JobId>()
    val failures = mutableMapOf<JobId, Exception>()

    override fun findLastSuccess(jobId: JobId): Instant? {
        calls += jobId
        failures[jobId]?.let { throw it }
        return if (jobId in overrides) overrides[jobId] else clock.instant().minus(Duration.ofMinutes(1))
    }
}

/** 연속 실패 fake. 기본값 null(실패 없음). */
class FakeFailureStreaks : JobFailureStreakReadPort {
    val streaks = mutableMapOf<JobId, JobFailureStreak>()
    val calls = mutableListOf<JobId>()
    val failures = mutableMapOf<JobId, Exception>()
    var onCall: ((JobId) -> Unit)? = null

    override fun findFailureStreak(jobId: JobId): JobFailureStreak? {
        calls += jobId
        onCall?.invoke(jobId)
        failures[jobId]?.let { throw it }
        return streaks[jobId]
    }
}

/** 최신 tick fake. 기본값은 호출 시점 `clock.instant() − 1초`(정상). [overrides] 의 값(null 포함)이 우선한다. */
class FakeTicks(private val clock: Clock) : LatestMarketTickReadPort {
    val overrides = mutableMapOf<Pair<Exchange, Quote>, Instant?>()
    val calls = mutableListOf<Pair<Exchange, Quote>>()
    val failures = mutableMapOf<Pair<Exchange, Quote>, Exception>()

    /** 요청 쌍 대신 이 quote 의 tick 을 돌려준다 (다른 MarketPair 의 tick 이 섞인 경우). */
    val answerQuotes = mutableMapOf<Pair<Exchange, Quote>, Quote>()

    override fun findLatest(exchange: Exchange, quote: Quote): MarketTick? {
        val key = exchange to quote
        calls += key
        failures[key]?.let { throw it }
        val observedAt = if (key in overrides) overrides[key] else clock.instant().minus(Duration.ofSeconds(1))
        return observedAt?.let { MarketTick(exchange, answerQuotes[key] ?: quote, BigDecimal("100"), it) }
    }
}
