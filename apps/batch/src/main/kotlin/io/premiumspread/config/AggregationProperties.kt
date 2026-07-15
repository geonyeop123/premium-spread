package io.premiumspread.config

import io.premiumspread.domain.aggregation.AggregationZone
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "aggregation")
data class AggregationProperties(
    @field:NotBlank
    val zone: String = "Asia/Seoul",
) {
    val aggregationZone: AggregationZone = AggregationZone.of(zone)
}
