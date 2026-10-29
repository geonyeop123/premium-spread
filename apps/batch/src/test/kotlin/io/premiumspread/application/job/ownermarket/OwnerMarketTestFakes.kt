package io.premiumspread.application.job.ownermarket

import io.premiumspread.domain.accountpnl.AccountPnlLatest
import io.premiumspread.domain.accountpnl.AccountPnlLatestStore
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.aggregation.AggregationUnit
import io.premiumspread.domain.aggregation.AggregationWindow
import io.premiumspread.domain.aggregation.PremiumAggregationReadPort
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ownermarket.MarketAlertState
import io.premiumspread.domain.ownermarket.OwnerMarketAlertStore
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.premium.PremiumReadPort
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Currency

class FakePremiumRead(var next: (MarketPair) -> PremiumSnapshot?) : PremiumReadPort {
    override fun findLatest(pair: MarketPair): PremiumSnapshot? = next(pair)
}

class FakeAggregationRead(
    var minutes: () -> List<PremiumAggregationSnapshot>,
    var hours: () -> List<PremiumAggregationSnapshot>,
    var day: () -> List<PremiumAggregationSnapshot>,
) : PremiumAggregationReadPort {
    val calls = mutableListOf<AggregationUnit>()

    override fun read(
        pair: MarketPair,
        window: AggregationWindow,
        unit: AggregationUnit,
    ): List<PremiumAggregationSnapshot> {
        calls += unit
        return when (unit) {
            AggregationUnit.MINUTE -> minutes()
            AggregationUnit.HOUR -> hours()
            AggregationUnit.DAY -> day()
        }
    }
}

class FakeFxRead(var next: () -> ExchangeRateSnapshot?) : FxRateReadPort {
    var calls = 0

    override fun findLatest(base: Currency, quote: Currency): ExchangeRateSnapshot? {
        calls++
        return next()
    }
}

class FakeAccountPnlLatest(var latest: () -> AccountPnlLatest?) : AccountPnlLatestStore {
    var finds = 0

    override fun record(latest: AccountPnlLatest) = Unit

    override fun find(): AccountPnlLatest? {
        finds++
        return latest()
    }
}

/** 단일 스레드 in-memory [OwnerMarketAlertStore]. version 조건부 저장, (eventKey, channel) 중복 무시. */
class FakeOwnerMarketAlertStore : OwnerMarketAlertStore {
    val states = mutableMapOf<String, MarketAlertState>()
    val rows = mutableListOf<AccountPnlMail>()
    val loadedKeys = mutableListOf<Collection<String>>()
    var enqueueCalls = 0
    var loadFailure: Exception? = null
    var nextEnqueueFailure: Exception? = null
    var nextEnqueueConflict = false

    override fun load(signalKeys: Collection<String>): Map<String, MarketAlertState> {
        loadedKeys += signalKeys.toList()
        loadFailure?.let { throw it }
        return states.filterKeys { it in signalKeys }
    }

    override fun enqueue(states: List<MarketAlertState>, rows: List<AccountPnlMail>): Boolean {
        enqueueCalls++
        nextEnqueueFailure?.let {
            nextEnqueueFailure = null
            throw it
        }
        if (nextEnqueueConflict) {
            nextEnqueueConflict = false
            return false
        }
        if (states.any { (this.states[it.signalKey]?.version ?: 0L) != it.version }) return false
        states.forEach { this.states[it.signalKey] = it.copy(version = it.version + 1) }
        rows.forEach { row ->
            if (this.rows.none { it.eventKey == row.eventKey && it.channel == row.channel }) this.rows += row
        }
        return true
    }
}
