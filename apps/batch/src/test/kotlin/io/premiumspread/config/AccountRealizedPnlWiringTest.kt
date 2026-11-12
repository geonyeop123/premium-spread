package io.premiumspread.config

import io.mockk.every
import io.mockk.mockk
import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.accountpnl.AccountRealizedPnlJob
import io.premiumspread.application.job.accountpnl.AccountRealizedPnlJobTest.Companion.NOW
import io.premiumspread.application.job.accountpnl.AccountRealizedPnlJobTest.FakeForeignFills
import io.premiumspread.application.job.accountpnl.AccountRealizedPnlJobTest.FakeFx
import io.premiumspread.application.job.accountpnl.AccountRealizedPnlJobTest.FakeKoreaFills
import io.premiumspread.application.job.accountpnl.AccountRealizedPnlJobTest.FakeLastSuccess
import io.premiumspread.application.job.accountpnl.AccountRealizedPnlJobTest.FakeStore
import io.premiumspread.domain.accountpnl.ForeignPerpFillReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource
import io.premiumspread.domain.accountpnl.KoreaSpotFillReadPort
import io.premiumspread.domain.accountpnl.SettlementStatus
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.email.EmailSender
import io.premiumspread.infrastructure.batch.accountpnl.AccountPnlMonitorAdapterConfiguration
import io.premiumspread.infrastructure.batch.exchange.binance.BinanceFillHistoryPrivateAccountAdapter
import io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbFillHistoryPrivateAccountAdapter
import io.premiumspread.interfaces.scheduling.AccountRealizedPnlScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

/** 확정 손익 Job 배선 (#130 dod AC9). 모니터 활성 조건 + `realized-pnl.enabled`, 수동 모드는 바이낸스 체결 port 없이 MANUAL. */
class AccountRealizedPnlWiringTest {

    private val apiProps = arrayOf(
        "notification.email.enabled=true",
        "account-pnl-monitor.enabled=true",
        "account-pnl-monitor.recipient=owner@example.test",
        "account-pnl-monitor.bithumb.access-key=test-access",
        "account-pnl-monitor.bithumb.secret-key=test-secret",
        "account-pnl-monitor.binance.api-key=test-api",
        "account-pnl-monitor.binance.secret-key=test-api-secret",
    )

    private val manualProps = arrayOf(
        "notification.email.enabled=true",
        "account-pnl-monitor.enabled=true",
        "account-pnl-monitor.recipient=owner@example.test",
        "account-pnl-monitor.bithumb.access-key=test-access",
        "account-pnl-monitor.bithumb.secret-key=test-secret",
        "account-pnl-monitor.binance.mode=manual",
        "account-pnl-monitor.binance.manual.short-quantity=0.2661",
        "account-pnl-monitor.binance.manual.entry-price=83549.47",
    )

    /** infrastructure adapter 구성까지 올린다 — bean 존재만 본다(실제 거래소를 부르지 않는다). */
    private val infraRunner = ApplicationContextRunner()
        .withUserConfiguration(
            Stubs::class.java,
            AccountPnlMonitorAdapterConfiguration::class.java,
            AccountRealizedPnlConfiguration::class.java,
            AccountRealizedPnlScheduler::class.java,
        )

    /** 앱 구성만 올리고 체결 port 는 가짜로 둔다 — Job 을 한 번 돌려 결과를 본다. */
    private val appRunner = ApplicationContextRunner()
        .withUserConfiguration(Stubs::class.java, FakePorts::class.java, AccountRealizedPnlConfiguration::class.java)

    @Test
    fun `모니터 활성 조건을 만족하면 Job·Scheduler 와 두 체결 adapter 가 각 1개다`() {
        infraRunner.withPropertyValues(*apiProps).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(AccountRealizedPnlJob::class.java)
            assertThat(ctx).hasSingleBean(AccountRealizedPnlScheduler::class.java)
            assertThat(ctx).hasSingleBean(BithumbFillHistoryPrivateAccountAdapter::class.java)
            assertThat(ctx).hasSingleBean(BinanceFillHistoryPrivateAccountAdapter::class.java)
        }
    }

    @Test
    fun `realized-pnl enabled=false 면 Job·Scheduler 가 생기지 않는다`() {
        infraRunner.withPropertyValues(*apiProps, "account-pnl-monitor.realized-pnl.enabled=false").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).doesNotHaveBean(AccountRealizedPnlJob::class.java)
            assertThat(ctx).doesNotHaveBean(AccountRealizedPnlScheduler::class.java)
        }
    }

    @Test
    fun `모니터가 꺼졌거나 필수 key 가 비었거나 scheduling 이 꺼지면 Job 이 생기지 않는다`() {
        listOf(
            "account-pnl-monitor.enabled=false",
            "account-pnl-monitor.bithumb.access-key=",
            "account-pnl-monitor.binance.secret-key=",
            "batch.scheduling.enabled=false",
        ).forEach { override ->
            infraRunner.withPropertyValues(*apiProps, override).run { ctx ->
                assertThat(ctx).`as`(override).hasNotFailed()
                assertThat(ctx).`as`(override).doesNotHaveBean(AccountRealizedPnlJob::class.java)
                assertThat(ctx).`as`(override).doesNotHaveBean(AccountRealizedPnlScheduler::class.java)
            }
        }
    }

    @Test
    fun `바이낸스 manual 모드면 바이낸스 체결 port 없이 Job 이 생긴다`() {
        infraRunner.withPropertyValues(*manualProps).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(AccountRealizedPnlJob::class.java)
            assertThat(ctx).hasSingleBean(BithumbFillHistoryPrivateAccountAdapter::class.java)
            assertThat(ctx).doesNotHaveBean(ForeignPerpFillReadPort::class.java)
        }
    }

    @Test
    fun `manual 모드 Job 은 EXCHANGE_API 사건이어도 바이낸스 쪽을 MANUAL 로 다룬다`() {
        appRunner.withPropertyValues(*manualProps, "test.foreign-fills=false").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).doesNotHaveBean(ForeignPerpFillReadPort::class.java)

            assertThat(ctx.getBean(AccountRealizedPnlJob::class.java).run()).isEqualTo(JobResult.Success)

            val (outcome, _) = ctx.getBean(FakeStore::class.java).records.single()
            assertThat(outcome.target.source).isEqualTo(ForeignPerpPositionSource.EXCHANGE_API)
            assertThat(outcome.foreignStatus).isEqualTo(SettlementStatus.MANUAL)
            assertThat(outcome.foreign).isNull()
            assertThat(outcome.totalKrw).isNull()
            assertThat(outcome.koreaStatus).isEqualTo(SettlementStatus.CONFIRMED)
        }
    }

    @Test
    fun `api 모드 Job 은 바이낸스 체결 port 를 받아 두 쪽을 확정한다`() {
        appRunner.withPropertyValues(*apiProps).run { ctx ->
            assertThat(ctx).hasNotFailed()

            ctx.getBean(AccountRealizedPnlJob::class.java).run()

            val (outcome, _) = ctx.getBean(FakeStore::class.java).records.single()
            assertThat(outcome.foreignStatus).isEqualTo(SettlementStatus.CONFIRMED)
            assertThat(outcome.totalKrw).isNotNull()
            assertThat(ctx.getBean(FakeForeignFills::class.java).fillWindows).hasSize(1)
        }
    }

    @Test
    fun `대상 선정 기간은 realized-pnl 설정 값이고 없으면 기본값이다`() {
        appRunner.withPropertyValues(*apiProps).run { ctx ->
            ctx.getBean(AccountRealizedPnlJob::class.java).run()
            assertThat(ctx.getBean(FakeStore::class.java).targetQueries.single())
                .containsExactly(NOW, Duration.ofMinutes(2), Duration.ofDays(90), Duration.ofHours(1))
        }
        appRunner.withPropertyValues(
            *apiProps,
            "account-pnl-monitor.realized-pnl.settle-delay=3m",
            "account-pnl-monitor.realized-pnl.first-episode-lookback=2h",
            "account-pnl-monitor.realized-pnl.max-age=30d",
        ).run { ctx ->
            ctx.getBean(AccountRealizedPnlJob::class.java).run()
            assertThat(ctx.getBean(FakeStore::class.java).targetQueries.single())
                .containsExactly(NOW, Duration.ofMinutes(3), Duration.ofDays(30), Duration.ofHours(2))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class FakePorts {
        @Bean fun koreaFills(): KoreaSpotFillReadPort = FakeKoreaFills()

        @ConditionalOnProperty("test.foreign-fills", matchIfMissing = true)
        @Bean
        fun foreignFills(): FakeForeignFills = FakeForeignFills()
    }

    @Configuration(proxyBeanMethods = false)
    class Stubs {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean fun emailSender(): EmailSender = mockk(relaxed = true)

        @Bean fun fxRateReadPort(): FxRateReadPort = FakeFx()

        @Bean fun lastSuccess(): JobLastSuccessReadPort = FakeLastSuccess()

        @Bean fun realizedStore(): FakeStore = FakeStore()

        @Bean fun jobConfigs(): JobConfigProvider = DefaultJobConfigProvider

        @Bean
        fun jobExecutor(): JobExecutor {
            val executor = mockk<JobExecutor>()
            every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
            return executor
        }
    }
}
