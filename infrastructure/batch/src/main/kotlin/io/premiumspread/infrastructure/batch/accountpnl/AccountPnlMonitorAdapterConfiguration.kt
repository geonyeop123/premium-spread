package io.premiumspread.infrastructure.batch.accountpnl

import io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesManualAccountAdapter
import io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapter
import io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbPrivateAccountAdapter
import io.premiumspread.infrastructure.batch.owneralert.OwnerAlertChannelAvailableCondition
import io.premiumspread.infrastructure.batch.owneralert.OwnerAlertChannelRequirements
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.PropertyResolver
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.web.reactive.function.client.WebClient
import java.time.Clock

/**
 * infra 세 조건이 공유하는 기동 요건. app `AccountPnlMonitorEnabledCondition` 이 같은 키 목록·모드 규칙을 따로 갖는다
 * (app 은 infrastructure 타입을 import 하지 않는다). 두 쪽의 일치는 wiring 테스트의 모드 × 누락 키 행렬이 고정한다.
 * 값은 비었는지만 읽고 기록하지 않는다.
 */
internal object AccountPnlMonitorRequirements {
    const val MODE_KEY = "account-pnl-monitor.binance.mode"
    private const val ENABLED_KEY = "account-pnl-monitor.enabled"
    private val COMMON = listOf(
        "account-pnl-monitor.bithumb.access-key",
        "account-pnl-monitor.bithumb.secret-key",
    )
    private val API = listOf("account-pnl-monitor.binance.api-key", "account-pnl-monitor.binance.secret-key")
    private val MANUAL = listOf(
        "account-pnl-monitor.binance.manual.short-quantity",
        "account-pnl-monitor.binance.manual.entry-price",
    )

    fun enabled(env: PropertyResolver): Boolean = env.getProperty(ENABLED_KEY).equals("true", ignoreCase = true)

    /** 공백/없음 → API, `api`/`manual` 대소문자 무관(trim), 그 밖 → 값 없는 메시지의 IllegalStateException. */
    fun binanceMode(env: PropertyResolver): AccountPnlMonitorProperties.BinanceLegMode =
        AccountPnlMonitorProperties.BinanceLegMode.parse(env.getProperty(MODE_KEY))

    /**
     * 채널 필수 키(메일 채널이 쓰일 때만 수신 주소) + 공통 + 해석된 모드의 필수 키 중 비어 있는 key, 선언 순서
     * (recipient → bithumb → 모드).
     */
    fun missingKeys(env: PropertyResolver): List<String> {
        val modeKeys = when (binanceMode(env)) {
            AccountPnlMonitorProperties.BinanceLegMode.API -> API
            AccountPnlMonitorProperties.BinanceLegMode.MANUAL -> MANUAL
        }
        return OwnerAlertChannelRequirements.missingKeys(env) + (COMMON + modeKeys).filter { env.getProperty(it).isNullOrBlank() }
    }
}

/**
 * 기능이 켜졌고 수신자(메일 채널이 쓰일 때만)·빗썸 credential·바이낸스 모드별 필수값이 모두 있으며 owner 알림 채널이 준비됐을 때만
 * 참이다 (design §7.2). 로그는 남기지 않는다.
 */
class AccountPnlCredentialsPresentCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val env = context.environment
        return AccountPnlMonitorRequirements.enabled(env) &&
            AccountPnlMonitorRequirements.missingKeys(env).isEmpty() &&
            !OwnerAlertChannelRequirements.discordMisconfigured(env) &&
            OwnerAlertChannelRequirements.anyChannel(env)
    }
}

/** 바이낸스 leg 가 서명 private API 모드일 때 참이다. */
class BinanceApiModeCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val env = context.environment
        return AccountPnlMonitorRequirements.enabled(env) &&
            AccountPnlMonitorRequirements.binanceMode(env) == AccountPnlMonitorProperties.BinanceLegMode.API
    }
}

/** 바이낸스 leg 가 수동 입력 모드일 때 참이다. */
class BinanceManualModeCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val env = context.environment
        return AccountPnlMonitorRequirements.enabled(env) &&
            AccountPnlMonitorRequirements.binanceMode(env) == AccountPnlMonitorProperties.BinanceLegMode.MANUAL
    }
}

/**
 * 실계정 조회 adapter 와 owner 알림 채널(메일·Discord) sender·라우팅. 플래그·credential 이 있고 쓸 수 있는 채널이 하나 이상일 때만
 * 존재한다 — 채널 = [EmailSender bean ∧ 메일 채널 켜짐] ∨ [Discord 켜짐 ∧ webhook 설정] (design §7.2).
 * 이 설정은 `BatchInfrastructureAutoConfiguration` 이 import 하므로 EmailSender 는 (켜졌다면) 이미 등록돼 있다.
 *
 * scheduling 이 꺼지면 소비자(Job)가 없으므로 adapter 도 만들지 않는다 (dod AC11). 조건식은 apps:batch 의
 * `ConditionalOnBatchScheduling` 과 같은 키·기본값이다 — infrastructure 는 앱 타입을 import 하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("\${batch.scheduling.enabled:true} and \${scheduling.enabled:true}")
@ConditionalOnProperty(prefix = "account-pnl-monitor", name = ["enabled"], havingValue = "true")
@Conditional(AccountPnlCredentialsPresentCondition::class, OwnerAlertChannelAvailableCondition::class)
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
    @Conditional(BinanceApiModeCondition::class)
    fun binanceFuturesPrivateAccountAdapter(
        properties: AccountPnlMonitorProperties,
        clock: Clock,
    ): BinanceFuturesPrivateAccountAdapter = BinanceFuturesPrivateAccountAdapter(
        webClient = client(properties.binance.baseUrl),
        apiKey = properties.binance.apiKey,
        secretKey = properties.binance.secretKey,
        clock = clock,
    )

    /** 수동 입력 모드 — 포지션은 env 입력, 가격은 공개 호가. 조건이 필수값 존재를, 바인딩이 형식을 이미 보장한다. */
    @Bean
    @Conditional(BinanceManualModeCondition::class)
    fun binanceFuturesManualAccountAdapter(properties: AccountPnlMonitorProperties): BinanceFuturesManualAccountAdapter {
        val manual = properties.binance.manual
        val adapter = BinanceFuturesManualAccountAdapter(
            webClient = client(properties.binance.baseUrl),
            shortQuantity = checkNotNull(manual.resolvedShortQuantity()) {
                "account-pnl-monitor.binance.manual.short-quantity is required in manual mode"
            },
            entryPrice = checkNotNull(manual.resolvedEntryPrice()) {
                "account-pnl-monitor.binance.manual.entry-price is required in manual mode"
            },
            entryFeeUsdt = manual.resolvedEntryFeeUsdt(),
            fundingUsdt = manual.resolvedFundingUsdt(),
            takerFeeRate = manual.resolvedTakerFeeRate(),
        )
        // 값은 남기지 않고 선택 입력의 존재 여부만 남긴다.
        log.info(
            "account-pnl-monitor binance leg: mode=manual, entryFeeUsdt={}, fundingUsdt={}, takerFeeRate={}",
            if (manual.resolvedEntryFeeUsdt() != null) "set" else "unset",
            if (manual.resolvedFundingUsdt() != null) "set" else "unset",
            if (manual.takerFeeRate.isNullOrBlank()) "default" else "set",
        )
        return adapter
    }

    private fun client(baseUrl: String): WebClient = WebClient.builder().baseUrl(baseUrl.removeSuffix("/")).build()

    private companion object {
        private val log = LoggerFactory.getLogger(AccountPnlMonitorAdapterConfiguration::class.java)
    }
}
