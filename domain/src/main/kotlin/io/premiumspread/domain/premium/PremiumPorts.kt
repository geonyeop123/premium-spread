package io.premiumspread.domain.premium

import io.premiumspread.domain.market.MarketPair

/**
 * 최신 premium 관측값 하나를 읽는 outbound port다. `null`은 "현재 관측값 없음"이고, 소비자는
 * 그것을 다른 pair·과거 값으로 보정하지 않는다 (`.ai/rules/architecture.md` MarketPair와 Read Model).
 *
 * 범위 조회를 두지 않는 이유: premium 범위 읽기의 실제 계약은 이미 `PremiumRepository`
 * (`findAllByPair`·`findAggregationByPair`)가 소유한다. 여기에 같은 이름의 범위 메서드를 두면
 * adapter가 구현할 수 없는 계약(현재 저장 모델에는 범위 snapshot 원천이 없다)이 되어 빈 목록이나
 * 예외로 거짓말하게 된다.
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
