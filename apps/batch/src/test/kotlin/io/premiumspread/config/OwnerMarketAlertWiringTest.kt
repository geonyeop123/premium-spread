package io.premiumspread.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.job.ownermarket.OwnerMarketSignalJob
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlLatestStore
import io.premiumspread.domain.accountpnl.OwnerAlertOutbox
import io.premiumspread.domain.aggregation.PremiumAggregationReadPort
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.job.JobFailureStreakReadPort
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.market.LatestMarketTickReadPort
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.domain.ownermarket.MarketAlertSettings
import io.premiumspread.domain.ownermarket.OwnerMarketAlertStore
import io.premiumspread.domain.premium.PremiumReadPort
import io.premiumspread.infrastructure.batch.BatchJobSupportConfiguration
import io.premiumspread.infrastructure.batch.accountpnl.AccountPnlMonitorAdapterConfiguration
import io.premiumspread.infrastructure.batch.owneralert.OwnerAlertChannelConfiguration
import io.premiumspread.infrastructure.batch.owneralert.OwnerAlertProperties
import io.premiumspread.interfaces.scheduling.AccountPnlMonitorScheduler
import io.premiumspread.interfaces.scheduling.BatchSchedulingProperties
import io.premiumspread.interfaces.scheduling.OwnerAlertDeliveryScheduler
import io.premiumspread.interfaces.scheduling.OwnerOpsSignalScheduler
import io.premiumspread.interfaces.scheduling.OwnerMarketSignalScheduler
import io.premiumspread.monitoring.AlertService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** owner 시장 알림 Job·Scheduler 의 기동 조건과 설정 바인딩 (owner-market-alerts dod AC14 a~h). */
@ExtendWith(OutputCaptureExtension::class)
class OwnerMarketAlertWiringTest {

    private val webhook = "https://discord.example.test/api/webhooks/1/x"
    private val recipient = "owner@example.test"
    private val disabledLog = "owner-alert market alerts disabled by OWNER_ALERT_MARKET_ENABLED"

    private fun baseRunner(): ApplicationContextRunner {
        val configs = listOf(
            Stubs::class.java,
            BatchJobSupportConfiguration::class.java,
            AccountPnlMonitorAdapterConfiguration::class.java,
            AccountPnlMonitorConfiguration::class.java,
            AccountPnlMonitorScheduler::class.java,
            OwnerAlertChannelConfiguration::class.java,
            OwnerAlertDeliveryConfiguration::class.java,
            OwnerOpsAlertConfiguration::class.java,
            OwnerMarketAlertConfiguration::class.java,
            OwnerAlertDeliveryScheduler::class.java,
            OwnerOpsSignalScheduler::class.java,
            OwnerMarketSignalScheduler::class.java,
        )
        return ApplicationContextRunner().withUserConfiguration(*configs.toTypedArray())
    }

    private fun runner(vararg extra: String): ApplicationContextRunner =
        baseRunner().withPropertyValues(
            "owner-alert.discord.enabled=true",
            "owner-alert.discord.webhook.market=$webhook",
            "owner-alert.email.enabled=false",
            "account-pnl-monitor.enabled=false",
            *extra,
        )

    private fun causes(t: Throwable?): List<String> =
        generateSequence(t) { it.cause }.mapNotNull { it.message }.toList()

    @Test
    fun `a 채널 ready 기본이면 Job 과 Scheduler 가 뜬다`() {
        runner().run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(OwnerMarketSignalJob::class.java)
            assertThat(ctx).hasSingleBean(OwnerMarketSignalScheduler::class.java)
            assertThat(ctx).hasSingleBean(OwnerAlertRouting::class.java)
        }
    }

    @Test
    fun `b enabled false 면 없고 INFO 한 줄 빈 값이면 존재한다`(output: CapturedOutput) {
        runner("owner-alert.market.enabled=false").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).doesNotHaveBean(OwnerMarketSignalJob::class.java)
            assertThat(ctx).doesNotHaveBean(OwnerMarketSignalScheduler::class.java)
        }
        val line = output.all.lines().single { disabledLog in it }
        assertThat(line).contains("INFO").endsWith(disabledLog)

        runner("owner-alert.market.enabled=").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(OwnerMarketSignalJob::class.java)
            assertThat(ctx).hasSingleBean(OwnerMarketSignalScheduler::class.java)
        }
        assertThat(output.all.lines().count { disabledLog in it }).isEqualTo(1)
    }

    @Test
    fun `c 채널이 없으면 없고 이 조건의 로그도 없다`(output: CapturedOutput) {
        baseRunner().withPropertyValues("owner-alert.market.enabled=false")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(OwnerMarketSignalJob::class.java)
                assertThat(ctx).doesNotHaveBean(OwnerMarketSignalScheduler::class.java)
            }
        assertThat(output.all).doesNotContain(disabledLog)
    }

    @Test
    fun `d scheduling 이 꺼지면 둘 다 없다`() {
        listOf("batch.scheduling.enabled=false", "scheduling.enabled=false").forEach { flag ->
            runner(flag).run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(OwnerMarketSignalJob::class.java)
                assertThat(ctx).doesNotHaveBean(OwnerMarketSignalScheduler::class.java)
            }
        }
    }

    @Test
    fun `e 11개 값이 설정에 반영되고 빈 기준선은 null 이다`() {
        runner(
            "owner-alert.market.swing-window=15m",
            "owner-alert.market.swing-threshold=0.7",
            "owner-alert.market.swing-cooldown=20m",
            "owner-alert.market.upper-threshold=3.0",
            "owner-alert.market.lower-threshold=-1.5",
            "owner-alert.market.threshold-hysteresis=0.2",
            "owner-alert.market.extreme-min-delta=0.3",
            "owner-alert.market.extreme-hour-cooldown=40m",
            "owner-alert.market.extreme-day-cooldown=3h",
            "owner-alert.market.summary-hour=21",
            "owner-alert.market.summary-pnl-max-age=7m",
        ).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx.getBean(OwnerMarketAlertProperties::class.java).toSettings()).isEqualTo(
                MarketAlertSettings(
                    Duration.ofMinutes(15),
                    BigDecimal("0.7"),
                    Duration.ofMinutes(20),
                    BigDecimal("3.0"),
                    BigDecimal("-1.5"),
                    BigDecimal("0.2"),
                    BigDecimal("0.3"),
                    Duration.ofMinutes(40),
                    Duration.ofHours(3),
                    21,
                    Duration.ofMinutes(7),
                ),
            )
        }
        runner(
            "owner-alert.market.upper-threshold=",
            "owner-alert.market.lower-threshold=",
        ).run { ctx ->
            assertThat(ctx).hasNotFailed()
            val settings = ctx.getBean(OwnerMarketAlertProperties::class.java).toSettings()
            assertThat(settings.upperThreshold).isNull()
            assertThat(settings.lowerThreshold).isNull()
            assertThat(settings).isEqualTo(MarketAlertSettings())
        }
    }

    @Test
    fun `e 잘못된 값은 design 메시지로 기동 실패한다`() {
        mapOf(
            "owner-alert.market.swing-threshold=0" to "swingThreshold must be positive.",
            "owner-alert.market.upper-threshold=3.12345" to "at most 4 decimal places",
            "owner-alert.market.summary-hour=0" to "summaryHour must be between 1 and 23.",
        ).forEach { (prop, message) ->
            runner(prop).run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(causes(ctx.startupFailure)).describedAs(prop).anyMatch { message in it }
            }
        }
    }

    @Test
    fun `f infra OwnerAlertProperties 는 owner-alert market 값이 있어도 바인딩된다`() {
        runner(
            "owner-alert.market.enabled=true",
            "owner-alert.market.upper-threshold=",
            "owner-alert.market.swing-window=10m",
        ).run { ctx ->
            assertThat(ctx).hasNotFailed()
            val bound = Binder.get(ctx.environment).bind("owner-alert", OwnerAlertProperties::class.java)
            assertThat(bound.isBound).isTrue()
        }
    }

    @Test
    fun `g yml 의 owner-alert market 12키 기본값이 design 과 같다`() {
        val lines = Files.readAllLines(Path.of("src/main/resources/application.yml")).map { it.trimEnd('\r') }
        val start = lines.indexOf("owner-alert:")
        assertThat(start).isGreaterThanOrEqualTo(0)
        val section = lines.drop(start + 1).takeWhile { it.isBlank() || it.startsWith(" ") }
        val marketAt = section.indexOf("  market:")
        assertThat(marketAt).isGreaterThanOrEqualTo(0)
        val block = section.drop(marketAt + 1).takeWhile { it.startsWith("    ") }
        val actual = block.filter { it.isNotBlank() && !it.trim().startsWith("#") }
            .associate { it.trim().substringBefore(":") to it.trim().substringAfter(": ").trim() }
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "enabled" to "\${OWNER_ALERT_MARKET_ENABLED:true}",
                "swing-window" to "\${OWNER_ALERT_MARKET_SWING_WINDOW:10m}",
                "swing-threshold" to "\${OWNER_ALERT_MARKET_SWING_THRESHOLD:0.5}",
                "swing-cooldown" to "\${OWNER_ALERT_MARKET_SWING_COOLDOWN:30m}",
                "upper-threshold" to "\${OWNER_ALERT_MARKET_UPPER_THRESHOLD:}",
                "lower-threshold" to "\${OWNER_ALERT_MARKET_LOWER_THRESHOLD:}",
                "threshold-hysteresis" to "\${OWNER_ALERT_MARKET_THRESHOLD_HYSTERESIS:0.1}",
                "extreme-min-delta" to "\${OWNER_ALERT_MARKET_EXTREME_MIN_DELTA:0.1}",
                "extreme-hour-cooldown" to "\${OWNER_ALERT_MARKET_EXTREME_HOUR_COOLDOWN:30m}",
                "extreme-day-cooldown" to "\${OWNER_ALERT_MARKET_EXTREME_DAY_COOLDOWN:2h}",
                "summary-hour" to "\${OWNER_ALERT_MARKET_SUMMARY_HOUR:9}",
                "summary-pnl-max-age" to "\${OWNER_ALERT_MARKET_SUMMARY_PNL_MAX_AGE:10m}",
            ),
        )
    }

    @Test
    fun `h 출력에 webhook 과 주소 값이 없다`(output: CapturedOutput) {
        runner("owner-alert.market.enabled=false").run { }
        runner("owner-alert.market.summary-hour=0", "account-pnl-monitor.recipient=$recipient").run { }
        assertThat(output.all).doesNotContain("discord.example.test").doesNotContain(recipient)
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BatchSchedulingProperties::class)
    class Stubs {
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC)

        @Bean fun accountPnlAlertStore(): AccountPnlAlertStore = mockk(relaxed = true)

        @Bean fun jobExecutor(): JobExecutor = mockk(relaxed = true)

        @Bean fun jobConfigProvider(): JobConfigProvider = DefaultJobConfigProvider

        @Bean fun premiumReadPort(): PremiumReadPort = mockk(relaxed = true)

        @Bean fun aggregationReadPort(): PremiumAggregationReadPort = mockk(relaxed = true)

        @Bean fun fxRateReadPort(): FxRateReadPort = mockk(relaxed = true)

        @Bean fun pnlLatest(): AccountPnlLatestStore = mockk(relaxed = true)

        @Bean fun lastSuccess(): JobLastSuccessReadPort = mockk(relaxed = true)

        @Bean fun failureStreaks(): JobFailureStreakReadPort = mockk(relaxed = true)

        @Bean fun ticks(): LatestMarketTickReadPort = mockk(relaxed = true)

        @Bean fun outbox(): OwnerAlertOutbox = mockk(relaxed = true)

        @Bean fun market(): BatchMarketProvider = mockk(relaxed = true)

        @Bean fun marketStore(): OwnerMarketAlertStore = mockk(relaxed = true)

        @Bean fun stringRedisTemplate(): StringRedisTemplate = mockk(relaxed = true)

        @Bean fun meterRegistry(): SimpleMeterRegistry = SimpleMeterRegistry()

        @Bean fun alertService(): AlertService = mockk(relaxed = true)
    }
}
