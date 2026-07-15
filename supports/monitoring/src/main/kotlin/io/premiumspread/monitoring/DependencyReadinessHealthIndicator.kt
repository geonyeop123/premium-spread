package io.premiumspread.monitoring

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.ReadinessState
import org.springframework.core.env.Environment
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import javax.sql.DataSource

/** Batch adapter가 필수 ingestion 연결의 bounded 상태만 readiness에 제공한다. */
fun interface CriticalIngestionHealth {
    fun health(): Health
}

/**
 * Boot의 readinessState contributor를 확장해 실제 request/runtime 필수 dependency를 함께 확인한다.
 * API는 DB와 refresh-session Redis, Batch는 DB/Redis와 활성화된 필수 ingestion을 포함한다.
 */
class DependencyReadinessHealthIndicator(
    private val availability: ApplicationAvailability,
    private val environment: Environment,
    private val dataSources: ObjectProvider<DataSource>,
    private val redisTemplates: ObjectProvider<StringRedisTemplate>,
    private val ingestionHealth: ObjectProvider<CriticalIngestionHealth>,
) : HealthIndicator {
    override fun health(): Health {
        val application by lazy(::applicationPolicy)
        val orderedChecks = sequenceOf<() -> Health?>(
            ::checkAvailability,
            { checkApplication(application) },
            { checkDatabase(dataSources.getIfAvailable()) },
            { checkRedis(redisTemplates.getIfAvailable()) },
            { checkIngestion(application) },
        )
        return orderedChecks.mapNotNull { check -> check() }.firstOrNull() ?: ready(application)
    }

    private fun checkAvailability(): Health? =
        if (availability.readinessState == ReadinessState.ACCEPTING_TRAFFIC) {
            null
        } else {
            Health.outOfService().withDetail("application", "refusing_traffic").build()
        }

    private fun applicationPolicy(): ApplicationPolicy = when (environment.getProperty("spring.application.name", "unknown")) {
        "api" -> ApplicationPolicy.API
        "premium-spread-batch" -> ApplicationPolicy.BATCH
        else -> ApplicationPolicy.AVAILABILITY_ONLY
    }

    private fun checkApplication(application: ApplicationPolicy): Health? =
        if (application == ApplicationPolicy.AVAILABILITY_ONLY) {
            Health.up().withDetail("policy", "availability_only").build()
        } else {
            null
        }

    private fun checkDatabase(dataSource: DataSource?): Health? {
        if (dataSource == null) return Health.down().withDetail("database", "missing").build()
        return runCatching {
            dataSource.connection.use { connection ->
                if (!connection.isValid(DB_VALIDATION_TIMEOUT_SECONDS)) {
                    Health.down().withDetail("database", "invalid").build()
                } else {
                    null
                }
            }
        }.getOrElse { error -> dependencyFailure("database", error) }
    }

    private fun checkRedis(template: StringRedisTemplate?): Health? {
        if (template == null) return Health.down().withDetail("redis", "missing").build()
        return runCatching {
            val pong = template.execute(RedisCallback { connection -> connection.ping() })
            if (pong.isNullOrBlank()) Health.down().withDetail("redis", "invalid").build() else null
        }.getOrElse { error -> dependencyFailure("redis", error) }
    }

    private fun checkIngestion(application: ApplicationPolicy): Health? {
        if (application != ApplicationPolicy.BATCH || !isBatchSchedulingEnabled()) return null

        val contributors = ingestionHealth.orderedStream().iterator()
        if (!contributors.hasNext()) {
            return Health.down().withDetail("ingestion", "missing_policy_contributor").build()
        }
        return generateSequence { if (contributors.hasNext()) contributors.next() else null }
            .map(CriticalIngestionHealth::health)
            .firstOrNull { health -> health.status != org.springframework.boot.actuate.health.Status.UP }
    }

    private fun isBatchSchedulingEnabled(): Boolean =
        environment.getProperty("batch.scheduling.enabled", Boolean::class.java, true)

    private fun ready(application: ApplicationPolicy): Health = Health.up()
        .withDetail(
            "policy",
            if (application == ApplicationPolicy.API) "db_redis" else "db_redis_ingestion",
        ).build()

    private fun dependencyFailure(dependency: String, error: Throwable): Health =
        Health.down()
            .withDetail(dependency, "unavailable")
            .withDetail("errorType", error.javaClass.simpleName)
            .build()

    private companion object {
        const val DB_VALIDATION_TIMEOUT_SECONDS = 2
    }

    private enum class ApplicationPolicy {
        API,
        BATCH,
        AVAILABILITY_ONLY,
    }
}
