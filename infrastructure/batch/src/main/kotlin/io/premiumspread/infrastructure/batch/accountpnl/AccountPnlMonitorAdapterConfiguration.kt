package io.premiumspread.infrastructure.batch.accountpnl

import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.email.EmailSender
import io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapter
import io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbPrivateAccountAdapter
import io.premiumspread.infrastructure.batch.notification.AccountPnlEmailSenderAdapter
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.web.reactive.function.client.WebClient
import java.time.Clock

/** 수신자와 두 거래소 credential 이 모두 비어 있지 않을 때만 참이다. 값은 읽기만 하고 기록하지 않는다. */
class AccountPnlCredentialsPresentCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val env = context.environment
        return REQUIRED.all { !env.getProperty(it).isNullOrBlank() }
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

/**
 * 실계정 조회 adapter 와 메일 발송 adapter. 플래그·credential·EmailSender 가 모두 있을 때만 존재한다.
 * 이 설정은 `BatchInfrastructureAutoConfiguration` 이 import 하므로 EmailSender 는 이미 등록돼 있다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "account-pnl-monitor", name = ["enabled"], havingValue = "true")
@Conditional(AccountPnlCredentialsPresentCondition::class)
@ConditionalOnBean(EmailSender::class)
@EnableConfigurationProperties(AccountPnlMonitorProperties::class)
class AccountPnlMonitorAdapterConfiguration {
    @Bean
    fun bithumbPrivateAccountAdapter(
        properties: AccountPnlMonitorProperties,
        clock: Clock,
    ): BithumbPrivateAccountAdapter = BithumbPrivateAccountAdapter(
        webClient = client(properties.bithumb.baseUrl),
        accessKey = properties.bithumb.accessKey,
        secretKey = properties.bithumb.secretKey,
        clock = clock,
    )

    @Bean
    fun binanceFuturesPrivateAccountAdapter(
        properties: AccountPnlMonitorProperties,
        clock: Clock,
    ): BinanceFuturesPrivateAccountAdapter = BinanceFuturesPrivateAccountAdapter(
        webClient = client(properties.binance.baseUrl),
        apiKey = properties.binance.apiKey,
        secretKey = properties.binance.secretKey,
        clock = clock,
    )

    @Bean
    fun accountPnlMailSender(emailSender: EmailSender, properties: AccountPnlMonitorProperties): AccountPnlMailSender =
        AccountPnlEmailSenderAdapter(emailSender, properties.recipient)

    private fun client(baseUrl: String): WebClient = WebClient.builder().baseUrl(baseUrl.removeSuffix("/")).build()
}
