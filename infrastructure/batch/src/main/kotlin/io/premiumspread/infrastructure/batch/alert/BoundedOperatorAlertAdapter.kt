package io.premiumspread.infrastructure.batch.alert

import io.micrometer.core.instrument.MeterRegistry
import io.premiumspread.domain.job.AlertSeverity
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.domain.job.OperatorAlertMessage
import io.premiumspread.monitoring.AlertService
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedOperatorAlertAdapter(
    private val delegate: AlertService,
    private val meterRegistry: MeterRegistry,
    properties: OperatorAlertExecutorProperties,
) : OperatorAlert {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = ThreadPoolExecutor(
        properties.threads,
        properties.threads,
        properties.keepAlive.toMillis(),
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(properties.queueCapacity),
        AlertThreadFactory(),
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    override fun send(alert: OperatorAlertMessage) {
        try {
            val context = MDC.getCopyOfContextMap()
            executor.execute {
                val previous = MDC.getCopyOfContextMap()
                try {
                    if (context.isNullOrEmpty()) MDC.clear() else MDC.setContextMap(context)
                    deliver(alert)
                } finally {
                    if (previous.isNullOrEmpty()) MDC.clear() else MDC.setContextMap(previous)
                }
            }
            meterRegistry.counter(METRIC_NAME, "outcome", "accepted").increment()
        } catch (_: RejectedExecutionException) {
            meterRegistry.counter(METRIC_NAME, "outcome", "dropped").increment()
            log.error(
                "Operator alert queue saturated; alert dropped. code={}, severity={}, message={}",
                alert.code,
                alert.severity,
                alert.message,
            )
        }
    }

    private fun deliver(alert: OperatorAlertMessage) {
        try {
            delegate.sendAlert(alert.message, alert.severity.toLegacySeverity())
            meterRegistry.counter(METRIC_NAME, "outcome", "dispatched").increment()
        } catch (exception: Exception) {
            meterRegistry.counter(METRIC_NAME, "outcome", "failed").increment()
            log.error(
                "Operator alert delivery failed; falling back to log. code={}, severity={}, message={}",
                alert.code,
                alert.severity,
                alert.message,
                exception,
            )
        }
    }

    @PreDestroy
    fun close() {
        executor.shutdownNow()
    }

    private fun AlertSeverity.toLegacySeverity(): AlertService.Severity = when (this) {
        AlertSeverity.INFO -> AlertService.Severity.INFO
        AlertSeverity.WARNING -> AlertService.Severity.WARNING
        AlertSeverity.CRITICAL -> AlertService.Severity.CRITICAL
    }

    private class AlertThreadFactory : ThreadFactory {
        private val sequence = AtomicInteger()

        override fun newThread(task: Runnable): Thread = Thread(task, "operator-alert-${sequence.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    private companion object {
        const val METRIC_NAME = "batch.operator.alert"
    }
}
