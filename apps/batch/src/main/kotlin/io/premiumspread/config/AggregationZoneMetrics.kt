package io.premiumspread.config

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component

@Component
class AggregationZoneMetrics(
    private val properties: AggregationProperties,
) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        Gauge.builder("aggregation.zone.info") { 1.0 }
            .description("Configured business timezone for aggregation windows")
            .tag("zone", properties.aggregationZone.zoneId.id)
            .register(registry)
    }
}
