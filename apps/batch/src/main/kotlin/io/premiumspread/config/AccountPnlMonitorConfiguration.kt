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
import io.premiumspread.interfaces.scheduling.ConditionalOnBatchScheduling
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.core.type.ClassMetadata
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
        val featureOn = env.getProperty("account-pnl-monitor.enabled").equals("true", ignoreCase = true) &&
            REQUIRED.all { !env.getProperty(it).isNullOrBlank() }
        if (!featureOn) return false
        // EmailAutoConfiguration 은 notification.email.enabled=true 일 때만 EmailSender 를 만든다.
        val emailOn = env.getProperty("notification.email.enabled").equals("true", ignoreCase = true)
        if (!emailOn && (metadata as? ClassMetadata)?.className == AccountPnlMonitorConfiguration::class.java.name) {
            LoggerFactory.getLogger(AccountPnlMonitorEnabledCondition::class.java)
                .warn("account-pnl-monitor enabled but email disabled; monitor not started")
        }
        return emailOn
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

/** scheduling 이 꺼지면(`batch.scheduling.enabled=false`) Job 도 만들지 않는다 (dod AC11). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBatchScheduling
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
