package io.premiumspread.infrastructure.batch.owneralert

import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.owneralert.OwnerAlertRouteOverride
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class OwnerAlertPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(OwnerAlertProperties::class)
    class TestConfig

    private val runner = ApplicationContextRunner().withUserConfiguration(TestConfig::class.java)

    private val validUrl = "https://discord.example.test/api/webhooks/101/TOKENpnlQ7"

    private fun chain(failure: Throwable?): List<Throwable> = generateSequence(failure) { it.cause }.toList()

    private fun assertFailsWithoutValue(properties: Array<String>, key: String, secret: String) {
        runner.withPropertyValues(*properties).run { context ->
            assertThat(context).hasFailed()
            val failure = context.startupFailure
            assertThat(chain(failure).mapNotNull { it.message }).anyMatch { it.contains(key) }
            assertThat(chain(failure).mapNotNull { it.message }).noneMatch { it.contains(secret) }
            assertThat(failure!!.stackTraceToString()).doesNotContain(secret)
        }
    }

    @Test
    fun `webhook 빈 값과 공백은 미설정이고 https URL 은 설정이다`() {
        runner.withPropertyValues(
            "owner-alert.discord.webhook.pnl=",
            "owner-alert.discord.webhook.market=   ",
            "owner-alert.discord.webhook.ops=$validUrl",
        ).run { context ->
            assertThat(context).hasNotFailed()
            val webhook = context.getBean(OwnerAlertProperties::class.java).discord.webhook
            assertThat(webhook.isAnyConfigured()).isTrue()
            val webhooks = webhook.toDiscordWebhooks()
            assertThat(webhooks.describe(DiscordTarget.OPS)).isEqualTo("set")
            assertThat(webhooks.describe(DiscordTarget.PNL)).isEqualTo("fallback(OPS)")
        }
        runner.withPropertyValues("owner-alert.discord.webhook.pnl=", "owner-alert.discord.webhook.ops=  ")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(OwnerAlertProperties::class.java).discord.webhook.isAnyConfigured()).isFalse()
            }
    }

    @Test
    fun `대문자 scheme 허용`() {
        runner.withPropertyValues("owner-alert.discord.webhook.pnl=HTTPS://discord.example.test/api/webhooks/1/TOKENupperQ7")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(OwnerAlertProperties::class.java).discord.webhook.isAnyConfigured()).isTrue()
            }
    }

    @Test
    fun `잘못된 webhook 은 기동 실패이고 값이 어디에도 없다`() {
        listOf(
            "notaurlQ7",
            "http://discord.example.test/api/webhooks/1/TOKENhttpQ7",
            "https:///nohostQ7",
        ).forEach { bad ->
            assertFailsWithoutValue(arrayOf("owner-alert.discord.webhook.pnl=$bad"), "owner-alert.discord.webhook.pnl", bad)
            val token = bad.substringAfterLast('/')
            assertFailsWithoutValue(arrayOf("owner-alert.discord.webhook.pnl=$bad"), "owner-alert.discord.webhook.pnl", token)
        }
    }

    @Test
    fun `route override 가 종류별로 해석된다`() {
        runner.withPropertyValues(
            "owner-alert.routes.hourly-report.email=FALSE",
            "owner-alert.routes.profit.discord=ops",
            "owner-alert.routes.profit.push=Sound",
            "owner-alert.routes.monitoring-outage.discord=none",
        ).run { context ->
            assertThat(context).hasNotFailed()
            val overrides = context.getBean(OwnerAlertProperties::class.java).resolvedOverrides()
            assertThat(overrides).isEqualTo(
                mapOf(
                    OwnerAlertKind.HOURLY_REPORT to OwnerAlertRouteOverride(email = false),
                    OwnerAlertKind.PROFIT to OwnerAlertRouteOverride(discordTarget = DiscordTarget.OPS, push = DiscordPush.SOUND),
                    OwnerAlertKind.MONITORING_OUTAGE to OwnerAlertRouteOverride(discordDisabled = true),
                ),
            )
        }
    }

    @Test
    fun `잘못된 route 값은 key 만 담고 값은 없다`() {
        assertFailsWithoutValue(arrayOf("owner-alert.routes.profit.push=loudQ9"), "owner-alert.routes.profit.push", "loudQ9")
        assertFailsWithoutValue(arrayOf("owner-alert.routes.profit.discord=dmQ9"), "owner-alert.routes.profit.discord", "dmQ9")
        assertFailsWithoutValue(arrayOf("owner-alert.routes.profit.email=yesQ9"), "owner-alert.routes.profit.email", "yesQ9")
    }

    @Test
    fun `알 수 없는 종류 key 는 known 목록과 함께 실패`() {
        runner.withPropertyValues("owner-alert.routes.unknownkind.email=true").run { context ->
            assertThat(context).hasFailed()
            val messages = chain(context.startupFailure).mapNotNull { it.message }
            assertThat(messages).anyMatch {
                it.contains("owner-alert.routes.unknownkind") &&
                    it.contains("known: profit, hourly-report, monitoring-outage, monitoring-recovered")
            }
        }
    }

    @Test
    fun `toString 에 webhook 이 없다`() {
        runner.withPropertyValues(
            "owner-alert.discord.webhook.pnl=https://discord.example.test/api/webhooks/1/TOKENpnlQ7",
            "owner-alert.discord.webhook.market=https://discord.example.test/api/webhooks/2/TOKENmktQ7",
            "owner-alert.discord.webhook.ops=https://discord.example.test/api/webhooks/3/TOKENopsQ7",
        ).run { context ->
            val text = context.getBean(OwnerAlertProperties::class.java).toString()
            assertThat(text).doesNotContain("TOKEN").doesNotContain("/api/webhooks/").contains("pnl=set")
        }
        assertThat(OwnerAlertProperties().toString()).contains("<empty>")
    }

    @Test
    fun `toDiscordWebhooks 는 설정된 것만으로 fallback 을 만든다`() {
        runner.withPropertyValues("owner-alert.discord.webhook.market=$validUrl").run { context ->
            val webhooks = context.getBean(OwnerAlertProperties::class.java).discord.webhook.toDiscordWebhooks()
            assertThat(webhooks.describe(DiscordTarget.PNL)).isEqualTo("fallback(MARKET)")
        }
    }

    @Test
    fun `webhook 이 하나도 없으면 toDiscordWebhooks 는 실패한다`() {
        assertThatThrownBy { OwnerAlertProperties.Webhook().toDiscordWebhooks() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("owner-alert.discord.webhook requires at least one URL")
    }
}
