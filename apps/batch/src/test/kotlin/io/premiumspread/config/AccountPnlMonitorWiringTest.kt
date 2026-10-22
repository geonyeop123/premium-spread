package io.premiumspread.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJob
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.KoreaSpotAccountReadPort
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.email.EmailSender
import io.premiumspread.infrastructure.batch.BatchJobSupportConfiguration
import io.premiumspread.infrastructure.batch.accountpnl.AccountPnlMonitorAdapterConfiguration
import io.premiumspread.infrastructure.batch.accountpnl.AccountPnlMonitorProperties
import io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesManualAccountAdapter
import io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapter
import io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbPrivateAccountAdapter
import io.premiumspread.infrastructure.batch.job.RedisJobRunRecorderAdapter
import io.premiumspread.infrastructure.batch.notification.AccountPnlEmailSenderAdapter
import io.premiumspread.interfaces.scheduling.AccountPnlMonitorScheduler
import io.premiumspread.monitoring.AlertService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(OutputCaptureExtension::class)
class AccountPnlMonitorWiringTest {

    private val enabledProps = arrayOf(
        "notification.email.enabled=true",
        "account-pnl-monitor.enabled=true",
        "account-pnl-monitor.recipient=owner@example.test",
        "account-pnl-monitor.bithumb.access-key=BITHUMB-ACCESS-SECRET-VALUE",
        "account-pnl-monitor.bithumb.secret-key=BITHUMB-SECRET-SECRET-VALUE",
        "account-pnl-monitor.binance.api-key=BINANCE-API-SECRET-VALUE",
        "account-pnl-monitor.binance.secret-key=BINANCE-SECRET-SECRET-VALUE",
    )

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(
            Stubs::class.java,
            BatchJobSupportConfiguration::class.java,
            AccountPnlMonitorAdapterConfiguration::class.java,
            AccountPnlMonitorConfiguration::class.java,
            AccountPnlMonitorScheduler::class.java,
        )

    private fun ApplicationContextRunner.assertNoBeans(): ApplicationContextRunner = also {
        it.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).doesNotHaveBean(AccountPnlMonitorJob::class.java)
            assertThat(ctx).doesNotHaveBean(AccountPnlMonitorScheduler::class.java)
            assertThat(ctx).doesNotHaveBean(BithumbPrivateAccountAdapter::class.java)
            assertThat(ctx).doesNotHaveBean(BinanceFuturesPrivateAccountAdapter::class.java)
            assertThat(ctx).doesNotHaveBean(BinanceFuturesManualAccountAdapter::class.java)
            assertThat(ctx).doesNotHaveBean(AccountPnlEmailSenderAdapter::class.java)
        }
    }

    @Test
    fun `플래그가 없으면 bean 0개`() {
        runner.assertNoBeans()
    }

    @Test
    fun `플래그 false 면 0개`() {
        runner.withPropertyValues(*enabledProps, "account-pnl-monitor.enabled=false").assertNoBeans()
    }

    @Test
    fun `key 하나라도 비면 0개`() {
        listOf("bithumb.access-key", "bithumb.secret-key", "binance.api-key", "binance.secret-key", "recipient").forEach { key ->
            runner.withPropertyValues(*enabledProps, "account-pnl-monitor.$key=").assertNoBeans()
        }
    }

    @Test
    fun `scheduling 비활성이면 Job·Scheduler·두 private adapter·메일 adapter 0개`() {
        runner.withPropertyValues(*enabledProps, "batch.scheduling.enabled=false").assertNoBeans()
    }

    @Test
    fun `legacy scheduling 비활성 별칭이어도 0개`() {
        runner.withPropertyValues(*enabledProps, "scheduling.enabled=false").assertNoBeans()
    }

    @Test
    fun `플래그·key·EmailSender 가 있으면 Job·Scheduler·두 adapter 각 1개`() {
        runner.withPropertyValues(*enabledProps).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(AccountPnlMonitorJob::class.java)
            assertThat(ctx).hasSingleBean(AccountPnlMonitorScheduler::class.java)
            assertThat(ctx).hasSingleBean(BithumbPrivateAccountAdapter::class.java)
            assertThat(ctx).hasSingleBean(BinanceFuturesPrivateAccountAdapter::class.java)
            assertThat(ctx).hasSingleBean(AccountPnlEmailSenderAdapter::class.java)
        }
    }

    @Test
    fun `JobLastSuccessReadPort 는 기존 RedisJobRunRecorderAdapter 로 해석된다`() {
        runner.withPropertyValues(*enabledProps).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx.getBeansOfType(JobLastSuccessReadPort::class.java).values)
                .singleElement().isInstanceOf(RedisJobRunRecorderAdapter::class.java)
        }
    }

    @Test
    fun `메일이 비활성이면 Job·Scheduler 0개이고 context 는 기동한다`(output: CapturedOutput) {
        runner.withPropertyValues(*enabledProps, "notification.email.enabled=false").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).doesNotHaveBean(AccountPnlMonitorJob::class.java)
            assertThat(ctx).doesNotHaveBean(AccountPnlMonitorScheduler::class.java)
        }
        assertThat(output.all).contains("account-pnl-monitor enabled but email disabled")
            .doesNotContain("BITHUMB-ACCESS-SECRET-VALUE", "BINANCE-API-SECRET-VALUE")
    }

    @Test
    fun `메일 설정이 없고 EmailSender 도 없으면 Job·Scheduler 0개`() {
        ApplicationContextRunner()
            .withUserConfiguration(
                AccountPnlMonitorAdapterConfiguration::class.java,
                ClockOnly::class.java,
                AccountPnlMonitorConfiguration::class.java,
                AccountPnlMonitorScheduler::class.java,
            )
            .withPropertyValues(*enabledProps.filterNot { it.startsWith("notification.") }.toTypedArray())
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(AccountPnlMonitorJob::class.java)
                assertThat(ctx).doesNotHaveBean(AccountPnlMonitorScheduler::class.java)
            }
    }

    @Test
    fun `플래그 값은 대소문자를 구분하지 않는다`() {
        runner.withPropertyValues(*enabledProps, "account-pnl-monitor.enabled=TRUE").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(AccountPnlMonitorJob::class.java)
        }
    }

    @Test
    fun `EmailSender 가 없으면 adapter 0개`() {
        ApplicationContextRunner()
            .withUserConfiguration(AccountPnlMonitorAdapterConfiguration::class.java, ClockOnly::class.java)
            .withPropertyValues(*enabledProps)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(BithumbPrivateAccountAdapter::class.java)
            }
    }

    @Test
    fun `Properties toString 에 secret 값이 없다`() {
        runner.withPropertyValues(*enabledProps).run { ctx ->
            val text = ctx.getBean(AccountPnlMonitorProperties::class.java).toString()
            assertThat(text).doesNotContain(
                "BITHUMB-ACCESS-SECRET-VALUE",
                "BITHUMB-SECRET-SECRET-VALUE",
                "BINANCE-API-SECRET-VALUE",
                "BINANCE-SECRET-SECRET-VALUE",
                "owner@example.test",
            )
            assertThat(text).contains("***")
            assertThat(AccountPnlMonitorProperties().toString()).contains("<empty>")
        }
    }

    @Test
    fun `startup 로그에 secret 값이 없다`(output: CapturedOutput) {
        runner.withPropertyValues(*enabledProps).run { ctx -> assertThat(ctx).hasNotFailed() }
        assertThat(output.all).doesNotContain(
            "BITHUMB-ACCESS-SECRET-VALUE",
            "BITHUMB-SECRET-SECRET-VALUE",
            "BINANCE-API-SECRET-VALUE",
            "BINANCE-SECRET-SECRET-VALUE",
        )
    }

    // ---- 바이낸스 leg 모드 (live-account-pnl-manual-binance AC5·AC6) ----

    private val manualProps = arrayOf(
        "notification.email.enabled=true",
        "account-pnl-monitor.enabled=true",
        "account-pnl-monitor.recipient=owner@example.test",
        "account-pnl-monitor.bithumb.access-key=BITHUMB-ACCESS-SECRET-VALUE",
        "account-pnl-monitor.bithumb.secret-key=BITHUMB-SECRET-SECRET-VALUE",
        "account-pnl-monitor.binance.mode=manual",
        "account-pnl-monitor.binance.manual.short-quantity=0.2661",
        "account-pnl-monitor.binance.manual.entry-price=83549.47",
        "account-pnl-monitor.binance.manual.entry-fee-usdt=8.91",
        "account-pnl-monitor.binance.manual.funding-usdt=-3.21",
    )

    private val binanceKeyProps = arrayOf(
        "account-pnl-monitor.binance.api-key=BINANCE-API-SECRET-VALUE",
        "account-pnl-monitor.binance.secret-key=BINANCE-SECRET-SECRET-VALUE",
    )

    private val settingValues = arrayOf(
        "BITHUMB-ACCESS-SECRET-VALUE",
        "BITHUMB-SECRET-SECRET-VALUE",
        "BINANCE-API-SECRET-VALUE",
        "BINANCE-SECRET-SECRET-VALUE",
        "owner@example.test",
        "0.2661",
        "83549.47",
        "8.91",
        "-3.21",
    )

    private fun causeChainMessages(failure: Throwable): List<String> =
        generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()

    /** 원인 체인 메시지 중 하나에 [key] 가 있고, 체인 전체·stack trace·이번 실행 출력 어디에도 [value] 가 없다. */
    private fun assertFailsWithoutValue(output: CapturedOutput, key: String, value: String, vararg props: String) {
        val start = output.all.length
        runner.withPropertyValues(*props).run { ctx ->
            assertThat(ctx).`as`(value).hasFailed()
            val failure = ctx.startupFailure
            val messages = causeChainMessages(failure)
            assertThat(messages).`as`(value).anyMatch { it.contains(key) }
            assertThat(messages).`as`(value).allMatch { !it.contains(value) }
            assertThat(failure.stackTraceToString()).`as`(value).doesNotContain(value)
        }
        assertThat(output.all.substring(start)).`as`(value).doesNotContain(value)
    }

    @Test
    fun `manual 모드면 Job·Scheduler·빗썸 adapter·manual adapter 각 1개이고 private 바이낸스 adapter 0개`() {
        runner.withPropertyValues(*manualProps).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(AccountPnlMonitorJob::class.java)
            assertThat(ctx).hasSingleBean(AccountPnlMonitorScheduler::class.java)
            assertThat(ctx).hasSingleBean(BithumbPrivateAccountAdapter::class.java)
            assertThat(ctx).hasSingleBean(BinanceFuturesManualAccountAdapter::class.java)
            assertThat(ctx).hasSingleBean(AccountPnlEmailSenderAdapter::class.java)
            assertThat(ctx).doesNotHaveBean(BinanceFuturesPrivateAccountAdapter::class.java)
            assertThat(ctx.getBeansOfType(ForeignPerpAccountReadPort::class.java).values)
                .singleElement().isInstanceOf(BinanceFuturesManualAccountAdapter::class.java)
        }
    }

    @Test
    fun `manual 모드면 바이낸스 key 가 있어도 private 바이낸스 adapter 0개`() {
        runner.withPropertyValues(*manualProps, *binanceKeyProps).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(AccountPnlMonitorJob::class.java)
            assertThat(ctx).hasSingleBean(BinanceFuturesManualAccountAdapter::class.java)
            assertThat(ctx).doesNotHaveBean(BinanceFuturesPrivateAccountAdapter::class.java)
        }
    }

    @Test
    fun `mode 미설정·빈 값·api·API 면 private adapter 1개 manual adapter 0개`() {
        listOf(
            emptyList(),
            listOf("account-pnl-monitor.binance.mode="),
            listOf("account-pnl-monitor.binance.mode=api"),
            listOf("account-pnl-monitor.binance.mode=API"),
        ).forEach { modeProps ->
            runner.withPropertyValues(*enabledProps, *modeProps.toTypedArray()).run { ctx ->
                val description = modeProps.joinToString()
                assertThat(ctx).`as`(description).hasNotFailed()
                assertThat(ctx).`as`(description).hasSingleBean(AccountPnlMonitorJob::class.java)
                assertThat(ctx).`as`(description).hasSingleBean(BinanceFuturesPrivateAccountAdapter::class.java)
                assertThat(ctx).`as`(description).doesNotHaveBean(BinanceFuturesManualAccountAdapter::class.java)
                assertThat(ctx.getBean(AccountPnlMonitorProperties::class.java).binance.resolvedMode())
                    .isEqualTo(AccountPnlMonitorProperties.BinanceLegMode.API)
            }
        }
    }

    @Test
    fun `api 모드에서 manual 키 다섯 개가 모두 빈 값이어도 기동한다`() {
        runner.withPropertyValues(
            *enabledProps,
            "account-pnl-monitor.binance.manual.short-quantity=",
            "account-pnl-monitor.binance.manual.entry-price=",
            "account-pnl-monitor.binance.manual.entry-fee-usdt=",
            "account-pnl-monitor.binance.manual.funding-usdt=",
            "account-pnl-monitor.binance.manual.taker-fee-rate=",
        ).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(BinanceFuturesPrivateAccountAdapter::class.java)
            assertThat(ctx).doesNotHaveBean(BinanceFuturesManualAccountAdapter::class.java)
        }
    }

    @Test
    fun `manual 모드 필수값 하나라도 비면 0개이고 WARN 에 env 이름만 있다`(output: CapturedOutput) {
        listOf(
            "recipient" to "ACCOUNT_PNL_ALERT_RECIPIENT",
            "bithumb.access-key" to "BITHUMB_ACCESS_KEY",
            "bithumb.secret-key" to "BITHUMB_SECRET_KEY",
            "binance.manual.short-quantity" to "BINANCE_MANUAL_SHORT_QUANTITY",
            "binance.manual.entry-price" to "BINANCE_MANUAL_ENTRY_PRICE",
        ).forEach { (key, envName) ->
            val start = output.all.length
            runner.withPropertyValues(*manualProps, "account-pnl-monitor.$key=").assertNoBeans()
            assertThat(output.all.substring(start)).`as`(key)
                .contains("account-pnl-monitor enabled but required settings missing; monitor not started")
                .contains("binanceMode=manual, missing=[$envName]")
                .doesNotContain("BITHUMB-ACCESS-SECRET-VALUE", "owner@example.test", "0.2661", "83549.47")
        }
    }

    @Test
    fun `api 모드 필수값이 비면 WARN 에 BINANCE_API_KEY 가 있다`(output: CapturedOutput) {
        runner.withPropertyValues(*enabledProps, "account-pnl-monitor.binance.api-key=").assertNoBeans()
        assertThat(output.all)
            .contains("required settings missing")
            .contains("binanceMode=api, missing=[BINANCE_API_KEY]")
            .doesNotContain(*settingValues)
    }

    @Test
    fun `app 조건과 infra 조건은 모드별로 같은 답을 낸다`() {
        val base = listOf("notification.email.enabled=true", "account-pnl-monitor.enabled=true")
        val common = listOf(
            "account-pnl-monitor.recipient=owner@example.test",
            "account-pnl-monitor.bithumb.access-key=BITHUMB-ACCESS-SECRET-VALUE",
            "account-pnl-monitor.bithumb.secret-key=BITHUMB-SECRET-SECRET-VALUE",
        )
        val apiKeys = binanceKeyProps.toList()
        val manualKeys = listOf(
            "account-pnl-monitor.binance.manual.short-quantity=0.2661",
            "account-pnl-monitor.binance.manual.entry-price=83549.47",
        )
        listOf(null, "api", "manual", "MANUAL").forEach { mode ->
            val modeProp = listOfNotNull(mode?.let { "account-pnl-monitor.binance.mode=$it" })
            val isManual = mode.equals("manual", ignoreCase = true)
            val required = common + if (isManual) manualKeys else apiKeys
            val other = if (isManual) apiKeys else manualKeys
            val cases = buildList {
                add(Triple("all required", required, 1))
                required.forEach { prop ->
                    val blanked = required.map { if (it == prop) "${prop.substringBefore('=')}=" else it }
                    add(Triple("missing ${prop.substringBefore('=')}", blanked, 0))
                }
                add(Triple("other mode keys only", common + other, 0))
            }
            cases.forEach { (label, props, expected) ->
                runner.withPropertyValues(*(base + modeProp + props).toTypedArray()).run { ctx ->
                    val description = "mode=$mode, $label"
                    assertThat(ctx).`as`(description).hasNotFailed()
                    val jobs = ctx.getBeansOfType(AccountPnlMonitorJob::class.java).size
                    val ports = ctx.getBeansOfType(ForeignPerpAccountReadPort::class.java).size
                    assertThat(jobs).`as`(description).isEqualTo(ports).isIn(0, 1).isEqualTo(expected)
                }
            }
        }
    }

    @Test
    fun `알 수 없는 mode 는 기동 실패이고 메시지에 값이 없다`(output: CapturedOutput) {
        assertFailsWithoutValue(
            output,
            "account-pnl-monitor.binance.mode",
            "fooModeX7",
            *enabledProps,
            "account-pnl-monitor.binance.mode=fooModeX7",
        )
    }

    @Test
    fun `manual 입력이 잘못되면 기동 실패하고 입력 값은 어디에도 없다`(output: CapturedOutput) {
        listOf(
            "short-quantity" to "qtyBADx9",
            "short-quantity" to "-0.98765",
            "entry-price" to "0.000000",
            "entry-fee-usdt" to "-1.234567",
            "taker-fee-rate" to "1.000007",
        ).forEach { (key, value) ->
            val property = "account-pnl-monitor.binance.manual.$key"
            assertFailsWithoutValue(output, property, value, *manualProps, "$property=$value")
        }
    }

    @Test
    fun `api 모드여도 구성이 바인딩되면 manual 값 형식을 검증한다`(output: CapturedOutput) {
        val property = "account-pnl-monitor.binance.manual.short-quantity"
        assertFailsWithoutValue(output, property, "-1.98765", *enabledProps, "$property=-1.98765")
    }

    @Test
    fun `펀딩 음수와 수량 0 은 허용`() {
        listOf(
            "account-pnl-monitor.binance.manual.funding-usdt=-3.2",
            "account-pnl-monitor.binance.manual.short-quantity=0",
        ).forEach { prop ->
            runner.withPropertyValues(*manualProps, prop).run { ctx ->
                assertThat(ctx).`as`(prop).hasNotFailed()
                assertThat(ctx).`as`(prop).hasSingleBean(BinanceFuturesManualAccountAdapter::class.java)
            }
        }
    }

    @Test
    fun `taker 율과 진입 수수료가 빈 값이면 미입력으로 해석한다`() {
        runner.withPropertyValues(
            *manualProps,
            "account-pnl-monitor.binance.manual.taker-fee-rate=",
            "account-pnl-monitor.binance.manual.entry-fee-usdt=",
        ).run { ctx ->
            assertThat(ctx).hasNotFailed()
            val manual = ctx.getBean(AccountPnlMonitorProperties::class.java).binance.manual
            assertThat(manual.resolvedTakerFeeRate().compareTo(BigDecimal("0.0005"))).isZero()
            assertThat(manual.resolvedEntryFeeUsdt()).isNull()
            assertThat(manual.resolvedShortQuantity()).isEqualByComparingTo(BigDecimal("0.2661"))
            assertThat(manual.resolvedEntryPrice()).isEqualByComparingTo(BigDecimal("83549.47"))
            assertThat(manual.resolvedFundingUsdt()).isEqualByComparingTo(BigDecimal("-3.21"))
        }
    }

    @Test
    fun `manual Properties toString 에 입력 값이 없다`() {
        runner.withPropertyValues(*manualProps, *binanceKeyProps).run { ctx ->
            assertThat(ctx).hasNotFailed()
            val text = ctx.getBean(AccountPnlMonitorProperties::class.java).toString()
            assertThat(text).doesNotContain(*settingValues)
            assertThat(text).contains("mode=MANUAL", "set")
            assertThat(AccountPnlMonitorProperties.ManualPosition().toString())
                .contains("<empty>", "takerFeeRate=default")
        }
    }

    @Test
    fun `manual 기동 로그에 모드 INFO 가 있고 입력 값이 없다`(output: CapturedOutput) {
        runner.withPropertyValues(*manualProps).run { ctx -> assertThat(ctx).hasNotFailed() }
        assertThat(output.all)
            .contains("account-pnl-monitor binance leg: mode=manual, entryFeeUsdt=set, fundingUsdt=set, takerFeeRate=default")
            .doesNotContain(*settingValues)
    }

    @Configuration(proxyBeanMethods = false)
    class ClockOnly {
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC)
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BatchJobProperties::class)
    class Stubs {
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC)

        @Bean fun emailSender(): EmailSender = mockk(relaxed = true)

        @Bean fun fxRateReadPort(): FxRateReadPort = mockk(relaxed = true)

        @Bean fun accountPnlAlertStore(): AccountPnlAlertStore = mockk(relaxed = true)

        @Bean fun jobExecutor(): JobExecutor = mockk(relaxed = true)

        @Bean fun stringRedisTemplate(): StringRedisTemplate = mockk(relaxed = true)

        @Bean fun meterRegistry(): SimpleMeterRegistry = SimpleMeterRegistry()

        @Bean fun alertService(): AlertService = mockk(relaxed = true)
    }
}
