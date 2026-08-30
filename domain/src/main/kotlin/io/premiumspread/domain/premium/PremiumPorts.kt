package io.premiumspread.domain.premium

import io.premiumspread.domain.market.MarketPair

/**
 * 최신 premium 관측값 하나를 읽는 outbound port다. `null`은 "현재 관측값 없음"이고, 소비자는
 * 그것을 다른 pair·과거 값으로 보정하지 않는다 (`.ai/rules/architecture.md` MarketPair와 Read Model).
 *
 * 범위 조회를 두지 않는 이유: 구현체도 호출자도 없는 죽은 계약이었고, premium 범위 읽기의 실제
 * 계약은 이미 `PremiumRepository`(`findAllByPair`·`findAggregationByPair`)가 소유한다. 구현했다면
 * 아무도 부르지 않는 죽은 코드가 됐을 것이다.
 */
fun interface PremiumReadPort {
    fun findLatest(pair: MarketPair): PremiumSnapshot?
}

interface PremiumWritePort {
    fun save(snapshot: PremiumSnapshot)
}

/** 실시간 premium의 current/seconds/history 저장소 조합을 application에서 분리한다. */
interface PremiumRealtimeWritePort {
    fun saveCurrent(snapshot: PremiumSnapshot)

    fun saveSecond(snapshot: PremiumSnapshot)

    fun saveHistory(snapshot: PremiumSnapshot)
}

/** 계산 완료된 premium을 알림 임계치 정책에 전달하는 outbound port다. */
fun interface PremiumThresholdEvaluator {
    fun evaluate(snapshot: PremiumSnapshot)
}
