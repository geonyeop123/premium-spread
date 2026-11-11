package io.premiumspread.config

import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.job.accountpnl.AccountRealizedPnlJob
import io.premiumspread.domain.accountpnl.AccountRealizedPnlStore
import io.premiumspread.domain.accountpnl.ForeignPerpFillReadPort
import io.premiumspread.domain.accountpnl.KoreaSpotFillReadPort
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.interfaces.scheduling.ConditionalOnBatchScheduling
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import java.time.Clock
import java.time.Duration

/**
 * 확정 손익 정산 Job (#130 design D1·D8). 배선 조건은 모니터와 같고(`AccountPnlMonitorEnabledCondition`)
 * `account-pnl-monitor.realized-pnl.enabled`(기본 true)가 더해진다.
 * 바이낸스 체결 port 는 api 모드에서만 있다 — 수동 모드면 없이 만들고 Job 이 바이낸스 쪽을 MANUAL 로 다룬다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBatchScheduling
@ConditionalOnProperty(prefix = "account-pnl-monitor", name = ["enabled"], havingValue = "true")
@Conditional(AccountPnlMonitorEnabledCondition::class, RealizedPnlEnabledCondition::class)
@EnableConfigurationProperties(AccountRealizedPnlProperties::class)
class AccountRealizedPnlConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun accountRealizedPnlJob(
        korea: KoreaSpotFillReadPort,
        foreign: ObjectProvider<ForeignPerpFillReadPort>,
        store: AccountRealizedPnlStore,
        fx: FxRateReadPort,
        lastSuccess: JobLastSuccessReadPort,
        executor: JobExecutor,
        clock: Clock,
        jobConfigs: JobConfigProvider,
        properties: AccountRealizedPnlProperties,
    ): AccountRealizedPnlJob = AccountRealizedPnlJob(
        korea = korea,
        foreign = foreign.ifAvailable,
        store = store,
        fx = fx,
        lastSuccess = lastSuccess,
        executor = executor,
        clock = clock,
        settings = AccountRealizedPnlJob.Settings(properties.settleDelay, properties.firstEpisodeLookback, properties.maxAge),
        jobConfigs = jobConfigs,
    )
}

/** `account-pnl-monitor.realized-pnl.*`. [enabled] 는 [RealizedPnlEnabledCondition] 이 읽는다(바인딩은 기록용). */
@ConfigurationProperties(prefix = "account-pnl-monitor.realized-pnl")
data class AccountRealizedPnlProperties(
    val enabled: Boolean = true,
    /** 사건 뒤 이만큼 지나야 정산한다(거래소 체결 반영 지연). */
    val settleDelay: Duration = Duration.ofMinutes(2),
    /** 앞선 건이 없는 첫 건의 하한 = opened_at − 이 값. */
    val firstEpisodeLookback: Duration = Duration.ofHours(1),
    /** 하한이 이보다 오래된 건은 정산하지 않는다(바이낸스 펀딩 이력 보관 기간). */
    val maxAge: Duration = Duration.ofDays(90),
)

/**
 * `account-pnl-monitor.realized-pnl.enabled` 가 없거나 true 일 때 참이다(`@ConditionalOnProperty(matchIfMissing = true)` 와 같다).
 * 같은 클래스에 `@ConditionalOnProperty` 를 두 번 달 수 없어 조건 클래스로 둔다. Scheduler 도 이 조건을 쓴다.
 */
class RealizedPnlEnabledCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean = context.environment.getProperty(ENABLED_KEY)?.equals("true", ignoreCase = true) ?: true

    private companion object {
        const val ENABLED_KEY = "account-pnl-monitor.realized-pnl.enabled"
    }
}
