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
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.accountpnl.AccountPositionHistoryStore
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.KoreaSpotAccountReadPort
import io.premiumspread.domain.accountpnl.PositionHistoryRound
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Instant

/** 실계정 모니터의 포지션 이력 배선 (#129 dod AC7). 회차를 한 번 돌려 store 호출 횟수로 관찰한다. */
class AccountPnlMonitorPositionHistoryWiringTest {

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

    private fun callsAfterOneRound(vararg props: String): Int {
        var calls = -1
        runner.withPropertyValues(*enabledProps, *props).run { ctx ->
            assertThat(ctx).hasNotFailed()
            val job = ctx.getBean(AccountPnlMonitorJob::class.java)
            assertThat(job.run()).isEqualTo(JobResult.Success)
            calls = ctx.getBean(CountingHistoryStore::class.java).calls
        }
        return calls
    }

    @Test
    fun `설정이 없으면 Job 이 이력 store 를 받아 회차마다 한 번 기록한다`() {
        assertThat(callsAfterOneRound()).isEqualTo(1)
    }

    @Test
    fun `true 로 명시해도 한 번 기록한다`() {
        assertThat(callsAfterOneRound("account-pnl-monitor.position-history.enabled=true")).isEqualTo(1)
    }

    @Test
    fun `false 면 store bean 이 있어도 기록하지 않는다`() {
        assertThat(callsAfterOneRound("account-pnl-monitor.position-history.enabled=false")).isZero()
    }

    @Test
    fun `설정이 없고 이력 store bean 이 없어도 context 가 뜨고 회차는 Success 다`() {
        runner.withPropertyValues(*enabledProps, "test.history-store=false").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).doesNotHaveBean(AccountPositionHistoryStore::class.java)
            assertThat(ctx.getBean(AccountPnlMonitorJob::class.java).run()).isEqualTo(JobResult.Success)
        }
    }

    class CountingHistoryStore : AccountPositionHistoryStore {
        var calls = 0

        override fun record(round: PositionHistoryRound) {
            calls += 1
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

        @ConditionalOnProperty("test.history-store", matchIfMissing = true)
        @Bean
        fun history(): CountingHistoryStore = CountingHistoryStore()

        @Bean
        fun jobExecutor(): JobExecutor {
            val executor = mockk<JobExecutor>()
            every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
            return executor
        }
    }
}
