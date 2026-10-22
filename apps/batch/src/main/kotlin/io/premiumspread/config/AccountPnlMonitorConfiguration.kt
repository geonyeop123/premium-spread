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
import org.springframework.beans.factory.annotation.Value
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
 * 실계정 순손익 기능이 명시적으로 켜졌고 수신자·빗썸 credential·바이낸스 모드별 필수값이 모두 있을 때만 참이다.
 *
 * 앱 구성 클래스는 auto-configuration 보다 먼저 평가되므로 adapter bean 존재(`@ConditionalOnBean`)를 조건으로
 * 쓸 수 없다. 그래서 infrastructure 와 같은 환경 설정 키·모드 규칙을 직접 읽는다 (app 은 infrastructure 타입을
 * import 하지 않는다). 두 쪽의 일치는 `AccountPnlMonitorWiringTest` 의 모드 × 누락 키 행렬이 고정한다.
 * 로그에는 해석된 모드와 env 이름만 남기고 설정 값은 남기지 않는다.
 */
class AccountPnlMonitorEnabledCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val env = context.environment
        if (!env.getProperty("account-pnl-monitor.enabled").equals("true", ignoreCase = true)) return false
        val manualMode = isManualMode(env.getProperty(MODE_KEY))
        val missing = (COMMON + if (manualMode) MANUAL else API)
            .filter { (key, _) -> env.getProperty(key).isNullOrBlank() }
            .map { (_, envName) -> envName }
        val logging = (metadata as? ClassMetadata)?.className == AccountPnlMonitorConfiguration::class.java.name
        if (missing.isNotEmpty()) {
            if (logging) {
                log.warn(
                    "account-pnl-monitor enabled but required settings missing; monitor not started: binanceMode={}, missing={}",
                    if (manualMode) "manual" else "api",
                    missing,
                )
            }
            return false
        }
        // EmailAutoConfiguration 은 notification.email.enabled=true 일 때만 EmailSender 를 만든다.
        val emailOn = env.getProperty("notification.email.enabled").equals("true", ignoreCase = true)
        if (!emailOn && logging) {
            log.warn("account-pnl-monitor enabled but email disabled; monitor not started")
        }
        return emailOn
    }

    private companion object {
        val log = LoggerFactory.getLogger(AccountPnlMonitorEnabledCondition::class.java)
        const val MODE_KEY = "account-pnl-monitor.binance.mode"
        const val INVALID_MODE_MESSAGE = "account-pnl-monitor.binance.mode must be api or manual"

        val COMMON = listOf(
            "account-pnl-monitor.recipient" to "ACCOUNT_PNL_ALERT_RECIPIENT",
            "account-pnl-monitor.bithumb.access-key" to "BITHUMB_ACCESS_KEY",
            "account-pnl-monitor.bithumb.secret-key" to "BITHUMB_SECRET_KEY",
        )
        val API = listOf(
            "account-pnl-monitor.binance.api-key" to "BINANCE_API_KEY",
            "account-pnl-monitor.binance.secret-key" to "BINANCE_SECRET_KEY",
        )
        val MANUAL = listOf(
            "account-pnl-monitor.binance.manual.short-quantity" to "BINANCE_MANUAL_SHORT_QUANTITY",
            "account-pnl-monitor.binance.manual.entry-price" to "BINANCE_MANUAL_ENTRY_PRICE",
        )

        /** infra `BinanceLegMode` 해석과 같은 규칙: 공백/없음 → api, api/manual 대소문자 무관, 그 밖 → 값 없는 예외. */
        fun isManualMode(raw: String?): Boolean {
            val trimmed = raw?.trim().orEmpty()
            return when {
                trimmed.isEmpty() || trimmed.equals("api", ignoreCase = true) -> false
                trimmed.equals("manual", ignoreCase = true) -> true
                else -> error(INVALID_MODE_MESSAGE)
            }
        }
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
        @Value("\${account-pnl-monitor.hourly-report.enabled:true}") hourlyReportEnabled: Boolean,
    ): AccountPnlMonitorJob = AccountPnlMonitorJob(
        korea,
        foreign,
        fx,
        lastSuccess,
        store,
        mailSender,
        executor,
        clock,
        jobConfigs = jobConfigs,
        hourlyReportEnabled = hourlyReportEnabled,
    )
}
