package io.premiumspread.domain.premium

import io.premiumspread.domain.market.MarketPair
import java.time.Instant

interface PremiumReadPort {
    fun findLatest(pair: MarketPair): PremiumSnapshot?

    fun findAll(pair: MarketPair, from: Instant, to: Instant): List<PremiumSnapshot>
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
