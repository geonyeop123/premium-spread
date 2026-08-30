package io.premiumspread.config

import io.premiumspread.domain.tradeprep.TradePreparationFreshnessPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * 조건 평가가 관측값을 받아들이는 최대 나이다 (design.md D14, `dod.md` AC17).
 *
 * 기본값 `10s` 는 코드에 박은 상수가 아니라 **수집 계약에서 유도한 값**이다 — ingestion 은
 * "관측값이 10초보다 오래되면 seconds 기록을 중단"하므로(`.ai/rules/batch.md`), 그보다 오래된
 * premium 은 stream 이 이미 멈춘 구간의 값이다. 더 크게 잡으면 멈춘 stream 의 값으로 `ARMED` 에
 * 도달하고, 더 작게 잡으면 정상 운영에서도 평가가 서지 않는다.
 */
@Validated
@ConfigurationProperties(prefix = "trade-preparation.evaluation")
data class TradePreparationEvaluationProperties(val maxAge: Duration = Duration.ofSeconds(10)) {

    init {
        require(!maxAge.isZero && !maxAge.isNegative) { "trade-preparation.evaluation.max-age must be positive" }
    }

    fun toPolicy(): TradePreparationFreshnessPolicy = TradePreparationFreshnessPolicy(maxAge)
}
