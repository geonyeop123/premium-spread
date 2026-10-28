package io.premiumspread.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJob
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.email.EmailSender
import io.premiumspread.infrastructure.batch.BatchJobSupportConfiguration
import io.premiumspread.infrastructure.batch.accountpnl.AccountPnlMonitorAdapterConfiguration
import io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesManualAccountAdapter
import io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapter
import io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbPrivateAccountAdapter
import io.premiumspread.infrastructure.batch.notification.AccountPnlEmailSenderAdapter
import io.premiumspread.infrastructure.batch.owneralert.OwnerAlertChannelConfiguration
import io.premiumspread.interfaces.scheduling.AccountPnlMonitorScheduler
import io.premiumspread.monitoring.AlertService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** 채널 bean 이 PnL 모니터와 독립으로 ready 조건만으로 뜬다 (owner-ops-alerts dod AC15). */
class OwnerAlertChannelIndependenceWiringTest {

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
            OwnerAlertChannelConfiguration::class.java,
        )

    private fun ApplicationContextRunner.assertChannelWithoutPnl(vararg props: String) {
        withPropertyValues(*enabledProps, *props).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).doesNotHaveBean(AccountPnlMonitorJob::class.java)
            assertThat(ctx).doesNotHaveBean(AccountPnlMonitorScheduler::class.java)
            assertThat(ctx).doesNotHaveBean(BithumbPrivateAccountAdapter::class.java)
            assertThat(ctx).doesNotHaveBean(BinanceFuturesPrivateAccountAdapter::class.java)
            assertThat(ctx).doesNotHaveBean(BinanceFuturesManualAccountAdapter::class.java)
            assertThat(ctx).hasSingleBean(AccountPnlEmailSenderAdapter::class.java)
            assertThat(ctx).hasSingleBean(OwnerAlertRouting::class.java)
        }
    }

    @Test
    fun `PnL 플래그가 false 여도 채널 bean 은 뜬다`() {
        runner.assertChannelWithoutPnl("account-pnl-monitor.enabled=false")
    }

    @Test
    fun `PnL 계정 key 하나가 비어도 채널 bean 은 뜬다`() {
        listOf("bithumb.access-key", "bithumb.secret-key", "binance.api-key", "binance.secret-key").forEach { key ->
            runner.assertChannelWithoutPnl("account-pnl-monitor.$key=")
        }
    }

    @Test
    fun `수신 주소가 비면 채널 bean 도 없다`() {
        runner.withPropertyValues(*enabledProps, "account-pnl-monitor.recipient=").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).doesNotHaveBean(AccountPnlEmailSenderAdapter::class.java)
            assertThat(ctx).doesNotHaveBean(OwnerAlertRouting::class.java)
        }
    }

    @Test
    fun `scheduling 이 꺼지면 채널 bean 도 없다`() {
        listOf("batch.scheduling.enabled=false", "scheduling.enabled=false").forEach { flag ->
            runner.withPropertyValues(*enabledProps, flag).run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(AccountPnlEmailSenderAdapter::class.java)
                assertThat(ctx).doesNotHaveBean(OwnerAlertRouting::class.java)
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    class Stubs {
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC)

        @Bean fun emailSender(): EmailSender = mockk(relaxed = true)

        @Bean fun fxRateReadPort(): FxRateReadPort = mockk(relaxed = true)

        @Bean fun accountPnlAlertStore(): AccountPnlAlertStore = mockk(relaxed = true)

        @Bean fun jobExecutor(): JobExecutor = mockk(relaxed = true)

        @Bean fun stringRedisTemplate(): StringRedisTemplate = mockk(relaxed = true)

        @Bean fun meterRegistry(): SimpleMeterRegistry = SimpleMeterRegistry()

        @Bean fun alertService(): AlertService = mockk(relaxed = true)
    }
}
