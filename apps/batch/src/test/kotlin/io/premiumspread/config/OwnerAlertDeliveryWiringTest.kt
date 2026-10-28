package io.premiumspread.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJob
import io.premiumspread.application.job.ownerops.OwnerOpsSignalJob
import io.premiumspread.application.notification.owneralert.OwnerAlertDeliveryJob
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.accountpnl.OwnerAlertOutbox
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.job.JobFailureStreakReadPort
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.LatestMarketTickReadPort
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.owneralert.OwnerAlertChannels
import io.premiumspread.domain.owneralert.OwnerAlertDiscordSender
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.domain.ownerops.OpsAlertSettings
import io.premiumspread.email.EmailSender
import io.premiumspread.infrastructure.batch.BatchJobSupportConfiguration
import io.premiumspread.infrastructure.batch.accountpnl.AccountPnlMonitorAdapterConfiguration
import io.premiumspread.infrastructure.batch.owneralert.OwnerAlertChannelConfiguration
import io.premiumspread.interfaces.scheduling.AccountPnlMonitorScheduler
import io.premiumspread.interfaces.scheduling.BatchSchedulingProperties
import io.premiumspread.interfaces.scheduling.OwnerAlertDeliveryScheduler
import io.premiumspread.interfaces.scheduling.OwnerOpsSignalScheduler
import io.premiumspread.monitoring.AlertService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** owner 전달 Job·운영 신호 Job 의 기동 조건과 로그 (owner-ops-alerts dod AC11 a~g, i). */
@ExtendWith(OutputCaptureExtension::class)
class OwnerAlertDeliveryWiringTest {

    private val webhook = "https://discord.example.test/api/webhooks/secret-token"
    private val recipient = "owner@example.test"

    private fun runner(withEmailSender: Boolean = false): ApplicationContextRunner {
        val configs = buildList {
            add(Stubs::class.java)
            if (withEmailSender) add(EmailStub::class.java)
            add(BatchJobSupportConfiguration::class.java)
            add(AccountPnlMonitorAdapterConfiguration::class.java)
            add(AccountPnlMonitorConfiguration::class.java)
            add(AccountPnlMonitorScheduler::class.java)
            add(OwnerAlertChannelConfiguration::class.java)
            add(OwnerAlertDeliveryConfiguration::class.java)
            add(OwnerOpsAlertConfiguration::class.java)
            add(OwnerAlertDeliveryScheduler::class.java)
            add(OwnerOpsSignalScheduler::class.java)
        }
        return ApplicationContextRunner().withUserConfiguration(*configs.toTypedArray())
    }

    private fun AssertableApplicationContext.assertNothingStarted() {
        assertThat(this).hasNotFailed()
        assertThat(this).doesNotHaveBean(OwnerAlertDeliveryJob::class.java)
        assertThat(this).doesNotHaveBean(OwnerOpsSignalJob::class.java)
        assertThat(this).doesNotHaveBean(OwnerAlertDeliveryScheduler::class.java)
        assertThat(this).doesNotHaveBean(OwnerOpsSignalScheduler::class.java)
        assertThat(this).doesNotHaveBean(OwnerAlertRouting::class.java)
    }

    @Test
    fun `a PnL 꺼짐 Discord 켜짐 ops webhook 이면 두 Job 과 Scheduler 가 뜬다`() {
        runner().withPropertyValues(
            "owner-alert.discord.enabled=true",
            "owner-alert.discord.webhook.ops=$webhook",
        ).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx.getBean(OwnerAlertRouting::class.java).channels).isEqualTo(OwnerAlertChannels(false, true))
            assertThat(ctx).hasSingleBean(OwnerAlertDiscordSender::class.java)
            assertThat(ctx).hasSingleBean(OwnerAlertDeliveryJob::class.java)
            assertThat(ctx).hasSingleBean(OwnerOpsSignalJob::class.java)
            assertThat(ctx).hasSingleBean(OwnerAlertDeliveryScheduler::class.java)
            assertThat(ctx).hasSingleBean(OwnerOpsSignalScheduler::class.java)
            assertThat(ctx).doesNotHaveBean(AccountPnlMonitorJob::class.java)
        }
    }

    @Test
    fun `PnL 켜짐이면 PnL 모니터와 두 Job 과 Scheduler 3개가 각각 하나씩 뜬다`() {
        runner(withEmailSender = true).withPropertyValues(
            "notification.email.enabled=true",
            "account-pnl-monitor.enabled=true",
            "account-pnl-monitor.recipient=$recipient",
            "account-pnl-monitor.bithumb.access-key=BITHUMB-ACCESS-SECRET-VALUE",
            "account-pnl-monitor.bithumb.secret-key=BITHUMB-SECRET-SECRET-VALUE",
            "account-pnl-monitor.binance.api-key=BINANCE-API-SECRET-VALUE",
            "account-pnl-monitor.binance.secret-key=BINANCE-SECRET-SECRET-VALUE",
        ).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(AccountPnlMonitorJob::class.java)
            assertThat(ctx).hasSingleBean(OwnerAlertDeliveryJob::class.java)
            assertThat(ctx).hasSingleBean(OwnerOpsSignalJob::class.java)
            assertThat(ctx).hasSingleBean(AccountPnlMonitorScheduler::class.java)
            assertThat(ctx).hasSingleBean(OwnerAlertDeliveryScheduler::class.java)
            assertThat(ctx).hasSingleBean(OwnerOpsSignalScheduler::class.java)
        }
    }

    @Test
    fun `b PnL 꺼짐 SMTP 와 수신 주소면 메일 sender 와 두 Job 이 뜨고 Discord 는 없다`() {
        runner(withEmailSender = true).withPropertyValues(
            "notification.email.enabled=true",
            "account-pnl-monitor.recipient=$recipient",
        ).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(AccountPnlMailSender::class.java)
            assertThat(ctx).doesNotHaveBean(OwnerAlertDiscordSender::class.java)
            assertThat(ctx.getBean(OwnerAlertRouting::class.java).channels).isEqualTo(OwnerAlertChannels(true, false))
            assertThat(ctx).hasSingleBean(OwnerAlertDeliveryJob::class.java)
            assertThat(ctx).hasSingleBean(OwnerOpsSignalJob::class.java)
        }
    }

    @Test
    fun `c 채널이 없으면 Job 도 채널 bean 도 없고 WARN 도 INFO 도 없다`(output: CapturedOutput) {
        runner().run { ctx -> ctx.assertNothingStarted() }
        assertThat(output.all).doesNotContain("not started").doesNotContain("ops alerts disabled")
    }

    @Test
    fun `d Discord 켜짐 webhook 없음이면 WARN 이고 값은 로그에 없다`(output: CapturedOutput) {
        runner(withEmailSender = true).withPropertyValues(
            "owner-alert.discord.enabled=true",
            "notification.email.enabled=true",
            "account-pnl-monitor.recipient=$recipient",
        ).run { ctx -> ctx.assertNothingStarted() }
        val line = output.all.lines().single { "owner-alert channel not ready" in it }
        assertThat(line).contains("WARN")
        assertThat(line).endsWith(
            "owner-alert channel not ready; owner alert delivery and ops alerts not started: " +
                "missing=[], discordWebhookMissing=true",
        )
        assertThat(output.all).doesNotContain("discord.example.test").doesNotContain(recipient)
    }

    @Test
    fun `d SMTP 켜짐 수신 주소 없음이면 INFO 이고 WARN 행이 없다`(output: CapturedOutput) {
        runner(withEmailSender = true).withPropertyValues(
            "notification.email.enabled=true",
            "owner-alert.discord.webhook.ops=$webhook",
        ).run { ctx -> ctx.assertNothingStarted() }
        val line = output.all.lines().single { "has no owner recipient" in it }
        assertThat(line).contains("INFO")
        assertThat(line).endsWith(
            "owner-alert email channel has no owner recipient; owner alert delivery and ops alerts not started: " +
                "missing=[ACCOUNT_PNL_ALERT_RECIPIENT]",
        )
        assertThat(output.all.lines().filter { "WARN" in it }).isEmpty()
        assertThat(output.all).doesNotContain("discord.example.test").doesNotContain(recipient)
    }

    @Test
    fun `e ops 가 꺼지면 전달 Job 만 있고 INFO 를 남긴다`(output: CapturedOutput) {
        runner().withPropertyValues(
            "owner-alert.discord.enabled=true",
            "owner-alert.discord.webhook.ops=$webhook",
            "owner-alert.ops.enabled=false",
        ).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(OwnerAlertDeliveryJob::class.java)
            assertThat(ctx).hasSingleBean(OwnerAlertDeliveryScheduler::class.java)
            assertThat(ctx).doesNotHaveBean(OwnerOpsSignalJob::class.java)
            assertThat(ctx).doesNotHaveBean(OwnerOpsSignalScheduler::class.java)
        }
        val line = output.all.lines().single { "ops alerts disabled" in it }
        assertThat(line).contains("INFO").endsWith("owner-alert ops alerts disabled by OWNER_ALERT_OPS_ENABLED")
    }

    @Test
    fun `f scheduling 이 꺼지면 둘 다 없다`() {
        listOf("batch.scheduling.enabled=false", "scheduling.enabled=false").forEach { flag ->
            runner().withPropertyValues(
                "owner-alert.discord.enabled=true",
                "owner-alert.discord.webhook.ops=$webhook",
                flag,
            ).run { ctx -> ctx.assertNothingStarted() }
        }
    }

    @Test
    fun `g ops 임계값 4개가 설정에 반영된다`() {
        runner().withPropertyValues(
            "owner-alert.discord.enabled=true",
            "owner-alert.discord.webhook.ops=$webhook",
            "owner-alert.ops.market-stale-after=7m",
            "owner-alert.ops.job-failure-count=4",
            "owner-alert.ops.job-failure-duration=10m",
            "owner-alert.ops.realert-interval=3h",
        ).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx.getBean(OwnerOpsAlertProperties::class.java).toSettings()).isEqualTo(
                OpsAlertSettings(Duration.ofMinutes(7), 4, Duration.ofMinutes(10), Duration.ofHours(3)),
            )
        }
    }

    @Test
    fun `g realert-interval 0s 는 기동 실패다`() {
        runner().withPropertyValues(
            "owner-alert.discord.enabled=true",
            "owner-alert.discord.webhook.ops=$webhook",
            "owner-alert.ops.realert-interval=0s",
        ).run { ctx ->
            assertThat(ctx).hasFailed()
            val messages = generateSequence<Throwable>(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.toList()
            assertThat(messages).anyMatch { "realertInterval must be positive." in it }
        }
    }

    @Test
    fun `i 48조합에서 전달 Job 존재 여부는 ready 규칙과 같다`() {
        var checked = 0
        for (emailRequest in listOf("", "true", "false")) {
            for (smtp in listOf(true, false)) {
                for (hasRecipient in listOf(true, false)) {
                    for (discordRequest in listOf(true, false)) {
                        for (hasWebhook in listOf(true, false)) {
                            val emailEffective = emailRequest != "false" && smtp
                            val expected = !(emailEffective && !hasRecipient) &&
                                !(discordRequest && !hasWebhook) &&
                                (emailEffective || (discordRequest && hasWebhook))
                            val props = buildList {
                                add("owner-alert.email.enabled=$emailRequest")
                                add("notification.email.enabled=$smtp")
                                add("account-pnl-monitor.recipient=${if (hasRecipient) recipient else ""}")
                                add("owner-alert.discord.enabled=$discordRequest")
                                add("owner-alert.discord.webhook.ops=${if (hasWebhook) webhook else ""}")
                            }
                            runner(withEmailSender = smtp).withPropertyValues(*props.toTypedArray()).run { ctx ->
                                assertThat(ctx).hasNotFailed()
                                assertThat(ctx.getBeansOfType(OwnerAlertDeliveryJob::class.java).isNotEmpty())
                                    .describedAs("combination %s", props)
                                    .isEqualTo(expected)
                                assertThat(ctx.getBeansOfType(OwnerAlertRouting::class.java).isNotEmpty())
                                    .describedAs("routing %s", props)
                                    .isEqualTo(expected)
                            }
                            checked++
                        }
                    }
                }
            }
        }
        assertThat(checked).isEqualTo(48)
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BatchSchedulingProperties::class)
    class Stubs {
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC)

        @Bean fun accountPnlAlertStore(): AccountPnlAlertStore = mockk(relaxed = true)

        @Bean fun jobExecutor(): JobExecutor = mockk(relaxed = true)

        @Bean fun jobConfigProvider(): JobConfigProvider = DefaultJobConfigProvider

        @Bean fun lastSuccess(): JobLastSuccessReadPort = mockk(relaxed = true)

        @Bean fun failureStreaks(): JobFailureStreakReadPort = mockk(relaxed = true)

        @Bean fun ticks(): LatestMarketTickReadPort = mockk(relaxed = true)

        @Bean fun market(): BatchMarketProvider = mockk(relaxed = true)

        @Bean fun outbox(): OwnerAlertOutbox = mockk(relaxed = true)

        @Bean fun fxRateReadPort(): FxRateReadPort = mockk(relaxed = true)

        @Bean fun stringRedisTemplate(): StringRedisTemplate = mockk(relaxed = true)

        @Bean fun meterRegistry(): SimpleMeterRegistry = SimpleMeterRegistry()

        @Bean fun alertService(): AlertService = mockk(relaxed = true)
    }

    @Configuration(proxyBeanMethods = false)
    class EmailStub {
        @Bean fun emailSender(): EmailSender = mockk(relaxed = true)
    }
}
