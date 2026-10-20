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
            .withUserConfiguration(AccountPnlMonitorAdapterConfiguration::class.java, ClockOnly::class.java,
                AccountPnlMonitorConfiguration::class.java, AccountPnlMonitorScheduler::class.java)
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
                "BITHUMB-ACCESS-SECRET-VALUE", "BITHUMB-SECRET-SECRET-VALUE",
                "BINANCE-API-SECRET-VALUE", "BINANCE-SECRET-SECRET-VALUE", "owner@example.test",
            )
            assertThat(text).contains("***")
            assertThat(AccountPnlMonitorProperties().toString()).contains("<empty>")
        }
    }

    @Test
    fun `startup 로그에 secret 값이 없다`(output: CapturedOutput) {
        runner.withPropertyValues(*enabledProps).run { ctx -> assertThat(ctx).hasNotFailed() }
        assertThat(output.all).doesNotContain(
            "BITHUMB-ACCESS-SECRET-VALUE", "BITHUMB-SECRET-SECRET-VALUE",
            "BINANCE-API-SECRET-VALUE", "BINANCE-SECRET-SECRET-VALUE",
        )
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
