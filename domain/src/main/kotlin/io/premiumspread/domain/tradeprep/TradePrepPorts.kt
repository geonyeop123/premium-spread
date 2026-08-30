package io.premiumspread.domain.tradeprep

/*
 * 잔고 조회 계약을 표시용·판정용 2단으로 나눈다 (design.md D2). 관례상 하나의 파일에 묶는다
 * (`PremiumPorts.kt`와 같은 패턴).
 *
 * 판정용은 같은 port의 다른 메서드가 아니라 반환 타입 자체가 다른 계약이다. VerifiedBalance는
 * UNVERIFIED·UNAVAILABLE 스냅샷에서 만들어지지 않으므로(VerifiedBalance.from), declared 전용
 * 어댑터(DeclaredBalanceAdapter)는 구조적으로 VerifiedBalanceReadPort를 구현할 수 없다 — 구현하면
 * 컴파일은 되지만 반환값이 항상 null이 되어 존재 이유가 없다.
 */

/** 표시용. 캐시를 허용한다 — `observedAt`·`balanceBasis`로 신선도를 드러낸다 (D3). */
fun interface BalanceSnapshotReadPort {
    fun findLatest(): BalanceSnapshot?
}

/**
 * 판정용(exposure-increasing). 캐시 불가 — 매 호출이 실제 조회다 (D2). 반환된 스냅샷 id에
 * 계획을 결속한다 (D5).
 */
fun interface VerifiedBalanceReadPort {
    fun findForDecision(): VerifiedBalance?
}
