package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
import java.time.Instant

enum class PositionEventType { OPENED, INCREASED, REDUCED, ADJUSTED, CLOSED }

/** 건의 기준 평단 — 마지막으로 헤지가 성립했던 leg. [foreignQuantity] 는 절댓값. 실계정 값이므로 toString 은 타입명만. */
data class ReferenceLegs(
    val koreaQuantity: BigDecimal,
    val koreaAveragePrice: BigDecimal,
    val foreignQuantity: BigDecimal,
    val foreignEntryPrice: BigDecimal,
) {
    override fun toString(): String = "ReferenceLegs"
}

/** 열려 있는 포지션 한 건. */
data class OpenEpisode(val id: Long, val legs: PositionLegs, val reference: ReferenceLegs?, val eventCount: Int) {
    override fun toString(): String = "OpenEpisode(id=$id, eventCount=$eventCount)"
}

/** 사건 한 건. [legs] 는 변경 뒤 정규화 leg. */
data class PositionEvent(
    val type: PositionEventType,
    val legs: PositionLegs,
    val hedged: Boolean,
    val source: ForeignPerpPositionSource,
    val occurredAt: Instant,
)

/** [reference] 는 사건 뒤 건의 reference. */
data class EpisodeDecision(val event: PositionEvent?, val reference: ReferenceLegs?, val closes: Boolean)
