package io.premiumspread.config

import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.job.ownermarket.OwnerMarketSignalJob
import io.premiumspread.domain.accountpnl.AccountPnlLatestStore
import io.premiumspread.domain.aggregation.PremiumAggregationReadPort
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.domain.ownermarket.OwnerMarketAlertStore
import io.premiumspread.domain.premium.PremiumReadPort
import io.premiumspread.interfaces.scheduling.ConditionalOnBatchScheduling
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.core.type.ClassMetadata
import java.time.Clock
import java.time.ZoneId

/**
 * [OwnerAlertReadiness] ∧ `owner-alert.market.enabled`(빈 값·없음 = true).
 * 꺼져 있으면 [OwnerMarketAlertConfiguration] 평가 때만 INFO.
 */
class OwnerMarketAlertEnabledCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val env = context.environment
        if (!OwnerAlertReadiness.ready(env, logging = false)) return false
        val raw = env.getProperty(MARKET_ENABLED_KEY)?.trim().orEmpty()
        val enabled = raw.isEmpty() || raw.equals("true", ignoreCase = true)
        val logging = (metadata as? ClassMetadata)?.className == OwnerMarketAlertConfiguration::class.java.name
        if (!enabled && logging) log.info("owner-alert market alerts disabled by OWNER_ALERT_MARKET_ENABLED")
        return enabled
    }

    private companion object {
        const val MARKET_ENABLED_KEY = "owner-alert.market.enabled"
        val log = LoggerFactory.getLogger(OwnerMarketAlertEnabledCondition::class.java)
    }
}

/** 김프 급변·기준선 돌파·신고가/신저가·일일 요약을 outbox 에 쌓는다 (design §7). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBatchScheduling
@Conditional(OwnerMarketAlertEnabledCondition::class)
@EnableConfigurationProperties(OwnerMarketAlertProperties::class)
class OwnerMarketAlertConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun ownerMarketSignalJob(
        premiums: PremiumReadPort,
        aggregations: PremiumAggregationReadPort,
        fx: FxRateReadPort,
        pnlLatest: AccountPnlLatestStore,
        market: BatchMarketProvider,
        store: OwnerMarketAlertStore,
        routing: OwnerAlertRouting,
        executor: JobExecutor,
        clock: Clock,
        properties: OwnerMarketAlertProperties,
        @Value("\${aggregation.zone:Asia/Seoul}") zone: String,
        jobConfigs: JobConfigProvider,
    ): OwnerMarketSignalJob = OwnerMarketSignalJob(
        premiums,
        aggregations,
        fx,
        pnlLatest,
        market,
        store,
        routing,
        executor,
        clock,
        properties.toSettings(),
        ZoneId.of(zone),
        jobConfigs,
    )
}
