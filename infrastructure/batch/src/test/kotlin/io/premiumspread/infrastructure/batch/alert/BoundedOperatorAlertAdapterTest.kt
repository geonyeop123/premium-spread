package io.premiumspread.infrastructure.batch.alert

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.premiumspread.domain.job.AlertSeverity
import io.premiumspread.domain.job.OperatorAlertMessage
import io.premiumspread.monitoring.AlertService
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class BoundedOperatorAlertAdapterTest {
    @Test
    fun `bounded alert worker propagates correlation MDC without leaking it`() {
        val observed = AtomicReference<String?>()
        val dispatched = CountDownLatch(1)
        val adapter = BoundedOperatorAlertAdapter(
            delegate = object : AlertService {
                override fun sendAlert(message: String, severity: AlertService.Severity) {
                    observed.set(MDC.get("requestId"))
                    dispatched.countDown()
                }
            },
            meterRegistry = SimpleMeterRegistry(),
            properties = OperatorAlertExecutorProperties(threads = 1, queueCapacity = 1),
        )
        try {
            MDC.put("requestId", "correlation-1")
            adapter.send(message(1))
            assertThat(dispatched.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            assertThat(observed.get()).isEqualTo("correlation-1")
        } finally {
            MDC.clear()
            adapter.close()
        }
    }

    @Test
    fun `saturated alert queue drops work and exposes a bounded metric`() {
        val blocker = CountDownLatch(1)
        val registry = SimpleMeterRegistry()
        val adapter = BoundedOperatorAlertAdapter(
            delegate = object : AlertService {
                override fun sendAlert(message: String, severity: AlertService.Severity) {
                    blocker.await()
                }
            },
            meterRegistry = registry,
            properties = OperatorAlertExecutorProperties(threads = 1, queueCapacity = 1),
        )

        repeat(20) { adapter.send(message(it)) }

        await().atMost(Duration.ofSeconds(2)).untilAsserted {
            assertThat(registry.find("batch.operator.alert").tag("outcome", "dropped").counter()?.count())
                .isGreaterThan(0.0)
        }
        blocker.countDown()
        adapter.close()
    }

    private fun message(index: Int) = OperatorAlertMessage(
        code = "test.$index",
        message = "alert-$index",
        severity = AlertSeverity.WARNING,
        occurredAt = Instant.EPOCH,
    )
}
