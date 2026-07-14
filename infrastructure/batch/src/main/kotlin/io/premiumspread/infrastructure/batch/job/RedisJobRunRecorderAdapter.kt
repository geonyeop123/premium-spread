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
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class RedisJobRunRecorderAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
) : JobRunRecorder {
    private val log = LoggerFactory.getLogger(javaClass)
    private val startedAt = ConcurrentHashMap<String, Instant>()

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
            redisTemplate.opsForValue().set(
                RedisKeyGenerator.batchLastRunKey(jobId.tag),
                at.toEpochMilli().toString(),
                RedisTtl.BATCH_HEALTH,
            )
        }
        if (detail != null) {
            log.debug("Batch job completed: job={}, runId={}, outcome={}, detail={}", jobId.tag, runId, outcome, detail)
        }
    }
}
