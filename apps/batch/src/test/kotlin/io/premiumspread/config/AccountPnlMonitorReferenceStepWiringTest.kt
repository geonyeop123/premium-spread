package io.premiumspread.config

import io.mockk.every
import io.mockk.mockk
import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJob
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest.FakeForeign
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest.FakeFx
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest.FakeKorea
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest.FakeLastSuccess
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest.FakeSender
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest.FakeStore
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest.MutableClock
import io.premiumspread.domain.accountpnl.AccountAlertSettings
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.accountpnl.AccountReferencePositionReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.KoreaSpotAccountReadPort
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.util.ReflectionTestUtils
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

/** 기준 포지션 단계 설정·부트스트랩 port 배선 (#131 dod AC9). */
class AccountPnlMonitorReferenceStepWiringTest {

    private val enabledProps = arrayOf(
        "notification.email.enabled=true",
        "account-pnl-monitor.enabled=true",
        "account-pnl-monitor.recipient=owner@example.test",
        "account-pnl-monitor.bithumb.access-key=test-access",
        "account-pnl-monitor.bithumb.secret-key=test-secret",
        "account-pnl-monitor.binance.api-key=test-api",
        "account-pnl-monitor.binance.secret-key=test-api-secret",
    )

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(Stubs::class.java, AccountPnlMonitorConfiguration::class.java)

    @Test
    fun `미설정이면 단계 폭은 0_50 이다`() {
        runner.withPropertyValues(*enabledProps).run { ctx ->
            assertThat(ctx).hasNotFailed()
            val settings = ctx.getBean(AccountAlertSettings::class.java)
            assertThat(settings.referenceStepWidth).isEqualTo(BigDecimal("0.50"))
            assertThat(settings).isEqualTo(AccountAlertSettings())
        }
    }

    @Test
    fun `설정한 단계 폭이 반영된다`() {
        runner.withPropertyValues(*enabledProps, "account-pnl-monitor.account-alerts.reference-step-percent=1.0").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx.getBean(AccountAlertSettings::class.java).referenceStepWidth).isEqualByComparingTo(BigDecimal("1.0"))
        }
    }

    @Test
    fun `단계 폭이 0 이면 기동 실패다`() {
        runner.withPropertyValues(*enabledProps, "account-pnl-monitor.account-alerts.reference-step-percent=0").run { ctx ->
            assertThat(ctx).hasFailed()
            val messages = generateSequence(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.toList()
            assertThat(messages).anyMatch { it.contains("referenceStepWidth must be positive.") }
        }
    }

    @Test
    fun `이력 기능이 켜져 있으면 Job 이 부트스트랩 port 를 받는다`() {
        runner.withPropertyValues(*enabledProps).run { ctx ->
            assertThat(ReflectionTestUtils.getField(ctx.getBean(AccountPnlMonitorJob::class.java), "referencePositions"))
                .isSameAs(ctx.getBean(AccountReferencePositionReadPort::class.java))
        }
    }

    @Test
    fun `이력 기능을 끄면 부트스트랩 port 를 받지 않는다`() {
        runner.withPropertyValues(*enabledProps, "account-pnl-monitor.position-history.enabled=false").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ReflectionTestUtils.getField(ctx.getBean(AccountPnlMonitorJob::class.java), "referencePositions")).isNull()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class Stubs {
        private val clock = MutableClock(Instant.parse("2026-10-20T03:00:00Z"))

        @Bean fun clock(): Clock = clock

        @Bean fun korea(): KoreaSpotAccountReadPort = FakeKorea(clock)

        @Bean fun foreign(): ForeignPerpAccountReadPort = FakeForeign(clock)

        @Bean fun fx(): FxRateReadPort = FakeFx(clock)

        @Bean fun lastSuccess(): JobLastSuccessReadPort = FakeLastSuccess(clock)

        @Bean fun alertStore(): AccountPnlAlertStore = FakeStore()

        @Bean fun mailSender(): AccountPnlMailSender = FakeSender()

        @Bean fun jobConfigs(): JobConfigProvider = DefaultJobConfigProvider

        @Bean fun routing(): OwnerAlertRouting = OwnerAlertRouting.EMAIL_ONLY

        @Bean fun referencePositions(): AccountReferencePositionReadPort = AccountReferencePositionReadPort { null }

        @Bean
        fun jobExecutor(): JobExecutor {
            val executor = mockk<JobExecutor>()
            every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
            return executor
        }
    }
}
