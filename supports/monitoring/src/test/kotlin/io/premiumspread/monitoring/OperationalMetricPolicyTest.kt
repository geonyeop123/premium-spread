package io.premiumspread.monitoring

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OperationalMetricPolicyTest {
    @Test
    fun `owned metric accepts documented bounded tags`() {
        val registry = SimpleMeterRegistry().apply { config().meterFilter(BoundedMetricTagFilter()) }

        registry.counter(OperationalMetricPolicy.Names.JOB_RUN, "job", "premium_realtime", "outcome", "succeeded")
            .increment()

        assertThat(registry.find(OperationalMetricPolicy.Names.JOB_RUN).counter()?.count()).isEqualTo(1.0)
    }

    @Test
    fun `owned metric rejects pii and undocumented tag keys`() {
        val registry = SimpleMeterRegistry().apply { config().meterFilter(BoundedMetricTagFilter()) }

        registry.counter(OperationalMetricPolicy.Names.JOB_RUN, "email", "user@example.com").increment()
        registry.counter(OperationalMetricPolicy.Names.JOB_RUN, "arbitrary", "value").increment()

        assertThat(registry.meters).isEmpty()
    }
}
