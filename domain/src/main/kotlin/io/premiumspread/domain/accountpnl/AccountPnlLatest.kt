package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * PnL 모니터의 최신 관측 한 건 (account_pnl_latest). [position] false = 포지션 없음(금액 null).
 * 금액은 실계정 값이므로 toString 에 넣지 않는다.
 */
data class AccountPnlLatest(
    val position: Boolean,
    val netPercent: BigDecimal?,
    val netKrw: BigDecimal?,
    val calculatedAt: Instant,
) {
    init {
        require(position == (netPercent != null) && position == (netKrw != null)) {
            "net values must be present exactly when a position exists."
        }
    }

    override fun toString(): String = "AccountPnlLatest(position=$position, calculatedAt=$calculatedAt)"

    companion object {
        /** netPercent = pnl.netPercent 그대로, netKrw = pnl.netKrw 의 scale 0 HALF_UP, calculatedAt = pnl.calculatedAt. */
        fun computed(pnl: AccountNetPnl): AccountPnlLatest =
            AccountPnlLatest(true, pnl.netPercent, pnl.netKrw.setScale(0, RoundingMode.HALF_UP), pnl.calculatedAt)

        fun noPosition(calculatedAt: Instant): AccountPnlLatest = AccountPnlLatest(false, null, null, calculatedAt)
    }
}

/** 실계정 최신 순수익률 read model. PnL 모니터가 쓰고 시장 일일 요약이 읽는다. */
interface AccountPnlLatestStore {
    /** 단일 행 upsert(last write wins). */
    fun record(latest: AccountPnlLatest)

    /** 행이 없으면 null. */
    fun find(): AccountPnlLatest?
}
