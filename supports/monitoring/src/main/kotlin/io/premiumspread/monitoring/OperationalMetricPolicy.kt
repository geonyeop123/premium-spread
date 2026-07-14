package io.premiumspread.monitoring

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.config.MeterFilterReply

/** 운영 metric 이름과 tag cardinality 계약의 단일 목록. */
object OperationalMetricPolicy {
    object Names {
        const val HTTP_REQUESTS = "http.server.requests"
        const val CACHE_READ = "cache.read.total"
        const val JOB_RUN = "batch.job.run"
        const val JOB_DURATION = "batch.job.duration"
        const val JOB_LAST_SUCCESS_AGE = "batch.job.last.success.age"
        const val LOCK_OPERATION = "batch.job.lock"
        const val PREMIUM_CALCULATION = "premium.calculation"
        const val NOTIFICATION_DELIVERY = "premiumspread.notification.delivery.transitions"
    }

    val allowedTagKeys: Set<String> = setOf(
        "api",
        "base",
        "cache",
        "error",
        "error_type",
        "exception",
        "exchange",
        "job",
        "market",
        "method",
        "outcome",
        "provider",
        "quote",
        "status",
        "symbol",
        "uri",
        "zone",
    )

    val forbiddenTagKeys: Set<String> = setOf(
        "cookie",
        "deliveryId",
        "email",
        "exceptionMessage",
        "memberId",
        "message",
        "owner",
        "password",
        "runId",
        "token",
    )

    private val ownedPrefixes = listOf(
        "aggregation.",
        "batch.",
        "cache.",
        "external.",
        "fx.",
        "premium.",
        "premiumspread.",
        "ticker.",
        "ws.",
    )

    fun isOwnedMetric(name: String): Boolean = name == Names.HTTP_REQUESTS || ownedPrefixes.any(name::startsWith)
}

/** 잘못된 custom tag를 registry 진입점에서 거부해 PII/cardinality 사고를 fail-closed 처리한다. */
class BoundedMetricTagFilter : MeterFilter {
    override fun accept(id: Meter.Id): MeterFilterReply {
        if (!OperationalMetricPolicy.isOwnedMetric(id.name)) return MeterFilterReply.NEUTRAL
        val invalid = id.tags.any { tag ->
            tag.key in OperationalMetricPolicy.forbiddenTagKeys ||
                tag.key !in OperationalMetricPolicy.allowedTagKeys
        }
        return if (invalid) MeterFilterReply.DENY else MeterFilterReply.NEUTRAL
    }
}
