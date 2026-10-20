package io.premiumspread.config

import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJob
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.KoreaSpotAccountReadPort
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.FxRateReadPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import java.time.Clock

/**
 * 실계정 순손익 기능이 명시적으로 켜졌고 수신자·credential 이 모두 있을 때만 참이다.
 *
 * 앱 구성 클래스는 auto-configuration 보다 먼저 평가되므로 adapter bean 존재(`@ConditionalOnBean`)를 조건으로
 * 쓸 수 없다. 그래서 infrastructure 와 같은 환경 설정 키를 직접 읽는다 (app 은 infrastructure 타입을 import 하지 않는다).
 */
class AccountPnlMonitorEnabledCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val env = context.environment
        return env.getProperty("account-pnl-monitor.enabled") == "true" &&
            REQUIRED.all { !env.getProperty(it).isNullOrBlank() }
    }

    companion object {
        private val REQUIRED = listOf(
            "account-pnl-monitor.recipient",
            "account-pnl-monitor.bithumb.access-key",
            "account-pnl-monitor.bithumb.secret-key",
            "account-pnl-monitor.binance.api-key",
            "account-pnl-monitor.binance.secret-key",
        )
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "account-pnl-monitor", name = ["enabled"], havingValue = "true")
@Conditional(AccountPnlMonitorEnabledCondition::class)
class AccountPnlMonitorConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun accountPnlMonitorJob(
        korea: KoreaSpotAccountReadPort,
        foreign: ForeignPerpAccountReadPort,
        fx: FxRateReadPort,
        lastSuccess: JobLastSuccessReadPort,
        store: AccountPnlAlertStore,
        mailSender: AccountPnlMailSender,
        executor: JobExecutor,
        clock: Clock,
        jobConfigs: JobConfigProvider,
    ): AccountPnlMonitorJob =
        AccountPnlMonitorJob(korea, foreign, fx, lastSuccess, store, mailSender, executor, clock, jobConfigs = jobConfigs)
}
