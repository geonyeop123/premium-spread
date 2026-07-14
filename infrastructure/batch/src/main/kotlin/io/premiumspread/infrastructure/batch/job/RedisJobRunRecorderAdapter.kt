package io.premiumspread.infrastructure.batch.job

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.job.JobRunOutcome
import io.premiumspread.domain.job.JobRunRecorder
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class RedisJobRunRecorderAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
) : JobRunRecorder {
    private val log = LoggerFactory.getLogger(javaClass)
    private val startedAt = ConcurrentHashMap<String, Instant>()
    private val lastSuccessAt = ConcurrentHashMap<String, AtomicLong>()

    override fun started(jobId: JobId, runId: String, at: Instant) {
        startedAt[runId] = at
    }

    override fun completed(
        jobId: JobId,
        runId: String,
        outcome: JobRunOutcome,
        at: Instant,
        detail: String?,
    ) {
        meterRegistry.counter(
            "batch.job.run",
            "job",
            jobId.tag,
            "outcome",
            outcome.name.lowercase(),
        ).increment()

        startedAt.remove(runId)?.let { started ->
            Timer.builder("batch.job.duration")
                .tag("job", jobId.tag)
                .register(meterRegistry)
                .record(Duration.between(started, at).coerceAtLeast(Duration.ZERO))
        }

        if (outcome == JobRunOutcome.SUCCEEDED) {
            val successRef = lastSuccessAt.computeIfAbsent(jobId.tag) { job ->
                val initial = AtomicLong(at.toEpochMilli())
                io.micrometer.core.instrument.Gauge.builder("batch.job.last.success.age", initial) {
                    ((clock.millis() - it.get()).coerceAtLeast(0L)) / 1000.0
                }.tag("job", job)
                    .description("Seconds since the last successful batch job completion")
                    .register(meterRegistry)
                initial
            }
            successRef.set(at.toEpochMilli())
            redisTemplate.opsForValue().set(
                RedisKeyGenerator.batchLastRunKey(jobId.tag),
                at.toEpochMilli().toString(),
                RedisTtl.BATCH_HEALTH,
            )
        }
        if (jobId == JobId.PREMIUM_REALTIME) {
            val calculationOutcome = when {
                outcome == JobRunOutcome.SUCCEEDED -> "success"
                outcome == JobRunOutcome.FAILED -> "failure"
                detail == "invalid_price" -> "invalid"
                else -> "skipped"
            }
            meterRegistry.counter("premium.calculation", "outcome", calculationOutcome).increment()
        }
        if (detail != null) {
            log.debug("Batch job completed: job={}, runId={}, outcome={}, detail={}", jobId.tag, runId, outcome, detail)
        }
    }
}
