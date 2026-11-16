package io.premiumspread.config

import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.job.accountpnl.AccountObservationRetentionJob
import io.premiumspread.domain.accountpnl.AccountObservationRetentionStore
import io.premiumspread.interfaces.scheduling.ConditionalOnBatchScheduling
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.validation.annotation.Validated
import java.time.Clock
import java.time.Duration

/**
 * 매분 관측 이력 보존 정리 Job (#148 design D2·D5). 모니터·정산 on/off 와 무관하다 — 모니터를 꺼도 쌓인 행은 정리돼야 한다.
 *
 * 시작 검증: 보존 기간이 정산 근거(`realized-pnl.max-age`) + 1일 이하면 정산이 아직 읽을 관측을 지우게 되므로 시작하지 않는다.
 * max-age 는 정산 Properties bean 이 없을 수 있어(모니터·정산 비활성) [Binder] 로 직접 읽는다. 없으면 기본 90일이다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBatchScheduling
@Conditional(AccountObservationRetentionEnabledCondition::class)
@EnableConfigurationProperties(AccountObservationRetentionProperties::class)
class AccountObservationRetentionConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun accountObservationRetentionJob(
        store: AccountObservationRetentionStore,
        executor: JobExecutor,
        clock: Clock,
        jobConfigs: JobConfigProvider,
        properties: AccountObservationRetentionProperties,
        environment: Environment,
    ): AccountObservationRetentionJob {
        val maxAge = Binder.get(environment)
            .bind(MAX_AGE_KEY, Duration::class.java)
            .orElse(DEFAULT_MAX_AGE)
        check(properties.retention > maxAge.plus(MARGIN)) {
            "$RETENTION_KEY (${properties.retention}) must be longer than $MAX_AGE_KEY ($maxAge) plus $MARGIN"
        }
        return AccountObservationRetentionJob(
            store = store,
            executor = executor,
            clock = clock,
            settings = AccountObservationRetentionJob.Settings(
                properties.retention,
                properties.batchSize,
                properties.maxBatchesPerRun,
            ),
            jobConfigs = jobConfigs,
        )
    }

    private companion object {
        const val MAX_AGE_KEY = "account-pnl-monitor.realized-pnl.max-age"
        const val RETENTION_KEY = "account-pnl-monitor.position-history.retention.retention"
        val DEFAULT_MAX_AGE: Duration = Duration.ofDays(90)
        val MARGIN: Duration = Duration.ofDays(1)
    }
}

/** `account-pnl-monitor.position-history.retention.*`. [enabled] 는 [AccountObservationRetentionEnabledCondition] 이 읽는다(바인딩은 기록용). */
@Validated
@ConfigurationProperties(prefix = "account-pnl-monitor.position-history.retention")
data class AccountObservationRetentionProperties(
    val enabled: Boolean = true,
    /** `observed_at` 이 이보다 오래된 관측·레그 행을 지운다. */
    val retention: Duration = Duration.ofDays(365),
    @field:Min(1) @field:Max(10_000) val batchSize: Int = 1000,
    @field:Min(1) @field:Max(10_000) val maxBatchesPerRun: Int = 50,
    /** Scheduler 의 `fixedDelayString` 이 같은 키를 직접 읽는다. */
    val interval: Duration = Duration.ofHours(1),
) {
    init {
        require(!retention.isZero && !retention.isNegative) { "retention must be positive" }
        require(!interval.isZero && !interval.isNegative) { "interval must be positive" }
    }
}

/**
 * `account-pnl-monitor.position-history.retention.enabled` 가 없거나 true 일 때 참이다. Scheduler 도 이 조건을 쓴다.
 * 모니터 활성 조건을 보지 않는다.
 */
class AccountObservationRetentionEnabledCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean = context.environment.getProperty(ENABLED_KEY)?.equals("true", ignoreCase = true) ?: true

    private companion object {
        const val ENABLED_KEY = "account-pnl-monitor.position-history.retention.enabled"
    }
}
