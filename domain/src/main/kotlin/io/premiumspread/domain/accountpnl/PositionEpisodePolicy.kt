package io.premiumspread.domain.accountpnl

import java.time.Instant

/** 포지션 한 건의 경계·사건·기준 평단 판정 (design D1~D3). 순수 함수. */
object PositionEpisodePolicy {
    private val EMPTY_LEGS = PositionLegs(KoreaLeg.EMPTY, ForeignLeg.FLAT)

    /** 바이낸스 롱은 포지션으로 보지 않는다 — [ForeignLeg.FLAT] 으로 바꾼다. */
    fun normalize(legs: PositionLegs): PositionLegs =
        if (legs.foreign.signedQuantity.signum() > 0) legs.copy(foreign = ForeignLeg.FLAT) else legs

    /** [legs] 는 정규화 전 값을 받는다. */
    fun decide(open: OpenEpisode?, legs: PositionLegs, source: ForeignPerpPositionSource, at: Instant): EpisodeDecision {
        val now = normalize(legs)
        val isOpen = !now.korea.isEmpty || !now.foreign.isFlat
        val hedged = !now.korea.isEmpty && !now.foreign.isFlat

        if (open == null) return opening(now, isOpen, hedged, source, at)

        if (!isOpen) {
            val event = PositionEvent(PositionEventType.CLOSED, EMPTY_LEGS, false, source, at)
            return EpisodeDecision(event, open.reference, true)
        }

        val before = normalize(open.legs)
        if (before.korea.samePosition(now.korea) && before.foreign.samePosition(now.foreign)) {
            return EpisodeDecision(null, open.reference, false)
        }

        val koreaCmp = now.korea.quantity.compareTo(before.korea.quantity)
        val foreignCmp = now.foreign.signedQuantity.abs().compareTo(before.foreign.signedQuantity.abs())
        val type = when {
            koreaCmp >= 0 && foreignCmp >= 0 && (koreaCmp > 0 || foreignCmp > 0) -> PositionEventType.INCREASED
            koreaCmp <= 0 && foreignCmp <= 0 && (koreaCmp < 0 || foreignCmp < 0) -> PositionEventType.REDUCED
            else -> PositionEventType.ADJUSTED
        }
        val event = PositionEvent(type, now, hedged, source, at)
        return EpisodeDecision(event, if (hedged) referenceOf(now) else open.reference, false)
    }

    /** 열린 건이 없는 회차: 열림 조건이면 `OPENED`, 아니면 사건 없음. */
    private fun opening(
        now: PositionLegs,
        isOpen: Boolean,
        hedged: Boolean,
        source: ForeignPerpPositionSource,
        at: Instant,
    ): EpisodeDecision {
        if (!isOpen) return EpisodeDecision(null, null, false)
        val event = PositionEvent(PositionEventType.OPENED, now, hedged, source, at)
        return EpisodeDecision(event, if (hedged) referenceOf(now) else null, false)
    }

    private fun referenceOf(legs: PositionLegs) = ReferenceLegs(
        koreaQuantity = legs.korea.quantity,
        koreaAveragePrice = legs.korea.averageBuyPrice,
        foreignQuantity = legs.foreign.signedQuantity.abs(),
        foreignEntryPrice = legs.foreign.entryPrice,
    )
}
