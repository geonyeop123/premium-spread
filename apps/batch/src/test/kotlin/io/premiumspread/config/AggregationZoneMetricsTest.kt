package io.premiumspread.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AggregationZoneMetricsTest {
    @Test
    fun `fixed info metric exposes configured zone as a bounded tag`() {
        val registry = SimpleMeterRegistry()
        AggregationZoneMetrics(AggregationProperties(zone = "Asia/Seoul")).bindTo(registry)

        val gauge = registry.find("aggregation.zone.info")
            .tag("zone", "Asia/Seoul")
            .gauge()

        assertThat(gauge).isNotNull
        assertThat(gauge!!.value()).isEqualTo(1.0)
        assertThat(registry.meters).hasSize(1)
    }
}
