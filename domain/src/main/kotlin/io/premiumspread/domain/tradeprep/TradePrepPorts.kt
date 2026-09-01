package io.premiumspread.domain.tradeprep

/*
 * 잔고 조회 계약을 표시용·판정용 2단으로 나눈다 (design.md D2). 관례상 하나의 파일에 묶는다
 * (`PremiumPorts.kt`와 같은 패턴).
 *
 * 판정용은 같은 port의 다른 메서드가 아니라 반환 타입 자체가 다른 계약이다. VerifiedBalance는
 * UNVERIFIED·UNAVAILABLE 스냅샷에서 만들어지지 않으므로(VerifiedBalance.from), declared 전용
 * 어댑터(DeclaredBalanceAdapter)가 VerifiedBalanceReadPort를 구현하는 것 자체는 컴파일된다 —
 * 다만 findForDecision()이 내부적으로 VerifiedBalance.from()을 거치는 한 반환값이 항상 null이
 * 되어 구현할 이유가 없다.
 */

/** 표시용. 캐시를 허용한다 — `observedAt`·`balanceBasis`로 신선도를 드러낸다 (D3). */
fun interface BalanceSnapshotReadPort {
    fun findLatest(): BalanceSnapshot?
}

/**
 * 판정용(exposure-increasing). 캐시 불가 — 매 호출이 실제 조회다 (D2). 반환된 스냅샷 id에
 * 계획을 결속한다 (D5).
 *
 * **구현 계약: 잔고가 그대로인 동안에는 안정된 스냅샷 id를 내야 한다.** 관측 시각이나 호출
 * 횟수가 아니라 잔고 자체가 바뀔 때만 id가 바뀌어야 한다.
 *
 * 이것이 계약인 이유는 reconcile Job(T8)이 결속된 id와 현재 id의 **부등치**만으로 무효화를
 * 판정하기 때문이다 (D5·D17, AC5). 매 관측마다 새 id를 만드는 구현을 끼우면 모든 owner의 모든
 * 활성 계획이 매 사이클 무효화된다 — 잔고는 그대로인데 계획만 사라진다.
 *
 * 지금 트리에는 매 관측마다 새 id를 만드는 코드가 둘 있지만 **서로 다른 이유로** 이 계약에
 * 걸리지 않는다. 둘 다 실원천(`ExchangeBalanceAdapter`, `ACT-2` 이후)이 따라가도 되는 본보기가
 * 아니다.
 *
 * - [BalanceSnapshot.declared]의 `declared-{UUID}` — 이 port의 구현이 아니다. 신고값은
 *   `UNVERIFIED`라 [VerifiedBalance.from]이 판정용으로 승격시키지 않는다 (D9).
 * - test fixture `RecordedBalanceAdapter`의 `recorded-{observedAt}` — 이 port의 **구현이 맞다.**
 *   무해한 이유는 그것이 `domain`의 test source set에만 있어 production 배선에 닿지 않기
 *   때문이다 (D22, AC20이 강제).
 */
fun interface VerifiedBalanceReadPort {
    fun findForDecision(): VerifiedBalance?
}
