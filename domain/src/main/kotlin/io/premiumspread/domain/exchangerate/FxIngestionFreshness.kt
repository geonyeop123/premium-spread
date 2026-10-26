package io.premiumspread.domain.exchangerate

import java.time.Duration
import java.time.Instant

/**
 * FX 수집이 마지막으로 성공한 뒤 환율을 믿을 수 있는 최대 공백.
 * 값의 나이(제공자 timestamp)가 아니라 수집 성공 시각 기준이다 — 주말엔 원천 값이 바뀌지 않는다.
 * RedisTtl.FX 는 이보다 5분 이상 길어야 하고(FxCacheTtlContractTest), 수집 주기는 이것의 절반 이하여야 한다(BatchSchedulingProperties).
 */
object FxIngestionFreshness {
    val MAX_INGESTION_GAP: Duration = Duration.ofMinutes(30)

    fun isStale(
        lastSuccessAt: Instant,
        now: Instant,
    ): Boolean = Duration.between(lastSuccessAt, now) > MAX_INGESTION_GAP
}
