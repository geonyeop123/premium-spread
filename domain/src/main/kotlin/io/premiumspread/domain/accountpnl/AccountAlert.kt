package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.owneralert.OwnerAlertKind
import java.math.BigDecimal
import java.time.Instant

enum class SwingDirection { UP, DOWN }

/** 리마인더 항목 — 이 순서로 표시한다. */
enum class ReminderItem { KOREA_ENTRY_FEE, FOREIGN_ENTRY_FEE, FUNDING, MANUAL_FUNDING_STALE }

data class ManualFundingStaleness(val fundingUsdt: BigDecimal, val changedAt: Instant, val days: Long)

/** 계정 알림 판정 결과. Job·렌더러는 이 sealed 타입으로만 분기한다(OwnerAlertKind 전체 when 금지). */
sealed interface AccountAlert {
    val kind: OwnerAlertKind

    data class PositionChanged(
        val before: PositionLegs,
        val after: PositionLegs,
        val hedgeMismatchWarning: Boolean,
        val source: ForeignPerpPositionSource,
        val detectedAt: Instant,
    ) : AccountAlert {
        override val kind: OwnerAlertKind get() = OwnerAlertKind.POSITION_CHANGED
        val koreaChanged: Boolean get() = !before.korea.samePosition(after.korea)
        val foreignChanged: Boolean get() = !before.foreign.samePosition(after.foreign)

        init {
            require(koreaChanged || foreignChanged) { "position change requires a changed leg." }
            require(
                !hedgeMismatchWarning ||
                    (koreaChanged && !foreignChanged && source == ForeignPerpPositionSource.MANUAL_ENV),
            ) {
                "hedge mismatch warning applies only to a Bithumb-only change with a manual Binance leg."
            }
        }
    }

    data class NetPercentSwing(
        val direction: SwingDirection,
        val bucketStart: Instant,
        val baselinePercent: BigDecimal,
        val threshold: BigDecimal,
        val pnl: AccountNetPnl,
    ) : AccountAlert {
        override val kind: OwnerAlertKind get() = OwnerAlertKind.NET_PERCENT_SWING
        val delta: BigDecimal get() = pnl.netPercent.subtract(baselinePercent)
    }

    data class UnrecoveredReminder(
        val dayStart: Instant,
        val items: List<ReminderItem>,
        val source: ForeignPerpPositionSource,
        val manualFunding: ManualFundingStaleness?,
        val reminderHour: Int,
        val detectedAt: Instant,
    ) : AccountAlert {
        override val kind: OwnerAlertKind get() = OwnerAlertKind.UNRECOVERED_REMINDER

        init {
            require(items.isNotEmpty()) { "reminder requires at least one item." }
            require((ReminderItem.MANUAL_FUNDING_STALE in items) == (manualFunding != null)) {
                "manual funding staleness must match its item."
            }
        }
    }
}
