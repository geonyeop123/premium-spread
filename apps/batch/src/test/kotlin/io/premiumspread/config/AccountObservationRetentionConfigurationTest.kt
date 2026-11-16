package io.premiumspread.config

import io.mockk.every
import io.mockk.mockk
import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.job.accountpnl.AccountObservationRetentionJob
import io.premiumspread.application.job.accountpnl.AccountObservationRetentionJobTest.FakeStore
import io.premiumspread.interfaces.scheduling.AccountObservationRetentionScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** 관측 보존 정리 배선과 시작 검증 (#148 dod AC4~AC6). 모니터·정산 bean 에 의존하지 않는다. */
class AccountObservationRetentionConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration::class.java))
        .withUserConfiguration(
            Stubs::class.java,
            AccountObservationRetentionConfiguration::class.java,
            AccountObservationRetentionScheduler::class.java,
        )

    @Test
    fun `보존 기간이 max-age 더하기 1일 이하면 시작하지 않는다`() {
        runner.withPropertyValues("$PREFIX.retention=91d").run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(rootCause(ctx.startupFailure)).isInstanceOf(IllegalStateException::class.java)
        }
        runner.withPropertyValues("$PREFIX.retention=92d").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(AccountObservationRetentionJob::class.java)
        }
    }

    @Test
    fun `max-age 를 바꾸면 경계도 따라간다`() {
        runner.withPropertyValues("$PREFIX.retention=100d", "$MAX_AGE=99d").run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(rootCause(ctx.startupFailure)).isInstanceOf(IllegalStateException::class.java)
        }
        runner.withPropertyValues("$PREFIX.retention=100d", "$MAX_AGE=98d").run { ctx ->
            assertThat(ctx).hasNotFailed()
        }
        runner.withPropertyValues("$PREFIX.retention=100d", "$MAX_AGE=30d").run { ctx ->
            assertThat(ctx).hasNotFailed()
        }
    }

    @Test
    fun `모니터가 꺼져 있어도 사용자 max-age 로 검증한다`() {
        runner.withPropertyValues("account-pnl-monitor.enabled=false", "$PREFIX.retention=100d", "$MAX_AGE=99d")
            .run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(rootCause(ctx.startupFailure)).isInstanceOf(IllegalStateException::class.java)
            }
    }

    @Test
    fun `정산이 꺼져 있어도 사용자 max-age 로 검증한다`() {
        runner.withPropertyValues(
            "account-pnl-monitor.realized-pnl.enabled=false",
            "$PREFIX.retention=100d",
            "$MAX_AGE=99d",
        ).run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(rootCause(ctx.startupFailure)).isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `batch-size 가 범위를 벗어나면 시작하지 않는다`() {
        listOf("0", "10001").forEach { size ->
            runner.withPropertyValues("$PREFIX.batch-size=$size").run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(rootCause(ctx.startupFailure)).isInstanceOf(org.springframework.boot.context.properties.bind.validation.BindValidationException::class.java)
            }
        }
        runner.withPropertyValues("$PREFIX.batch-size=10000").run { ctx -> assertThat(ctx).hasNotFailed() }
    }

    @Test
    fun `보존 기간이 0 이거나 음수면 시작하지 않는다`() {
        listOf("0d", "-1d").forEach { retention ->
            runner.withPropertyValues("$PREFIX.retention=$retention").run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(rootCause(ctx.startupFailure)).isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }

    @Test
    fun `enabled false 면 scheduler 가 없다`() {
        runner.withPropertyValues("$PREFIX.enabled=false").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).doesNotHaveBean(AccountObservationRetentionScheduler::class.java)
            assertThat(ctx).doesNotHaveBean(AccountObservationRetentionJob::class.java)
        }
    }

    @Test
    fun `모니터가 꺼져 있어도 기본값이면 scheduler 가 있다`() {
        runner.withPropertyValues("account-pnl-monitor.enabled=false").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(AccountObservationRetentionScheduler::class.java)
            assertThat(ctx).hasSingleBean(AccountObservationRetentionJob::class.java)
        }
    }

    @Test
    fun `설정이 없으면 기본값 365d 1000 50 으로 시작한다`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            val store = ctx.getBean(FakeStore::class.java)
            store.results.addAll(List(60) { 1000 })

            assertThat(ctx.getBean(AccountObservationRetentionJob::class.java).run()).isEqualTo(JobResult.Success)

            assertThat(store.calls).hasSize(50)
            assertThat(store.calls.first()).isEqualTo(NOW.minus(Duration.ofDays(365)) to 1000)
        }
    }

    private fun rootCause(failure: Throwable): Throwable = generateSequence(failure) { it.cause }.last()

    @Configuration(proxyBeanMethods = false)
    class Stubs {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean fun retentionStore(): FakeStore = FakeStore()

        @Bean fun jobConfigs(): JobConfigProvider = DefaultJobConfigProvider

        @Bean
        fun jobExecutor(): JobExecutor {
            val executor = mockk<JobExecutor>()
            every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
            return executor
        }
    }

    private companion object {
        const val PREFIX = "account-pnl-monitor.position-history.retention"
        const val MAX_AGE = "account-pnl-monitor.realized-pnl.max-age"
        val NOW: Instant = Instant.parse("2027-03-01T00:00:00Z")
    }
}
