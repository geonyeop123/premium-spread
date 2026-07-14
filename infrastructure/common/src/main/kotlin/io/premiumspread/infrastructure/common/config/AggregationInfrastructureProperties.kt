package io.premiumspread.infrastructure.common.config

import io.premiumspread.domain.aggregation.AggregationZone
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/** JDBC 일 집계 조회가 scheduler와 동일한 업무 시간대를 사용하도록 하는 typed contract. */
@Validated
@ConfigurationProperties(prefix = "aggregation")
data class AggregationInfrastructureProperties(
    @field:NotBlank val zone: String = "Asia/Seoul",
) {
    val aggregationZone: AggregationZone = AggregationZone.of(zone)
}
