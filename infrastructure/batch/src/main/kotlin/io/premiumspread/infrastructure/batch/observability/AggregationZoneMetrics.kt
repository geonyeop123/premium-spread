package io.premiumspread.infrastructure.batch.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.ZoneId

@Validated
@ConfigurationProperties(prefix = "aggregation")
data class AggregationZoneMetricProperties(
    @field:NotBlank val zone: String = "Asia/Seoul",
) {
    val zoneId: ZoneId = ZoneId.of(zone)
}

class AggregationZoneMetrics(
    private val properties: AggregationZoneMetricProperties,
) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        Gauge.builder("aggregation.zone.info") { 1.0 }
            .description("Configured business timezone for aggregation windows")
            .tag("zone", properties.zoneId.id)
            .register(registry)
    }
}
