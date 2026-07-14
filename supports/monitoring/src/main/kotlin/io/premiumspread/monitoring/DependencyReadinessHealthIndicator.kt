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
        if (availability.readinessState != ReadinessState.ACCEPTING_TRAFFIC) {
            return Health.outOfService().withDetail("application", "refusing_traffic").build()
        }

        val app = environment.getProperty("spring.application.name", "unknown")
        if (app != "api" && app != "premium-spread-batch") {
            return Health.up().withDetail("policy", "availability_only").build()
        }

        val db = checkDatabase(dataSources.getIfAvailable())
        if (db != null) return db

        val redis = checkRedis(redisTemplates.getIfAvailable())
        if (redis != null) return redis

        if (app == "premium-spread-batch" && environment.getProperty("batch.scheduling.enabled", Boolean::class.java, true)) {
            val contributors = ingestionHealth.orderedStream().toList()
            if (contributors.isEmpty()) {
                return Health.down().withDetail("ingestion", "missing_policy_contributor").build()
            }
            contributors.firstNotNullOfOrNull { contributor ->
                contributor.health().takeUnless { it.status == org.springframework.boot.actuate.health.Status.UP }
            }?.let { return it }
        }

        return Health.up()
            .withDetail("policy", if (app == "api") "db_redis" else "db_redis_ingestion")
            .build()
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

    private fun dependencyFailure(dependency: String, error: Throwable): Health =
        Health.down()
            .withDetail(dependency, "unavailable")
            .withDetail("errorType", error.javaClass.simpleName)
            .build()

    private companion object {
        const val DB_VALIDATION_TIMEOUT_SECONDS = 2
    }
}
