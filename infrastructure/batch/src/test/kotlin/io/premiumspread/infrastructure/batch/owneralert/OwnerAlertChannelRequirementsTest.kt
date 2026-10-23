package io.premiumspread.infrastructure.batch.owneralert

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class OwnerAlertChannelRequirementsTest {

    private val webhook = "https://discord.example.test/api/webhooks/101/TOKENpnlQ7"

    private fun env(vararg props: Pair<String, String>): MockEnvironment =
        MockEnvironment().apply { props.forEach { (key, value) -> setProperty(key, value) } }

    @Test
    fun `메일 채널이 실제로 쓰일 때만 수신 주소가 필수다`() {
        // (owner-alert.email.enabled, notification.email.enabled) → emailEffective = recipientRequired
        val table = listOf(
            Triple(null, "true", true),
            Triple(null, "false", false),
            Triple("true", "true", true),
            Triple("true", "false", false),
            Triple("false", "true", false),
            Triple("false", "false", false),
        )
        table.forEach { (emailEnabled, smtp, expected) ->
            val environment = env("notification.email.enabled" to smtp)
            emailEnabled?.let { environment.setProperty("owner-alert.email.enabled", it) }
            val description = "email=$emailEnabled, smtp=$smtp"
            assertThat(OwnerAlertChannelRequirements.emailEffective(environment)).`as`(description).isEqualTo(expected)
            assertThat(OwnerAlertChannelRequirements.recipientRequired(environment)).`as`(description).isEqualTo(expected)
        }
    }

    @Test
    fun `채널 필수 키는 수신 주소 하나뿐이고 메일 채널이 꺼지면 비어 있다`() {
        listOf("", "  ").forEach { blank ->
            val on = env("notification.email.enabled" to "true", "account-pnl-monitor.recipient" to blank)
            assertThat(OwnerAlertChannelRequirements.missingKeys(on)).`as`("blank=[$blank]")
                .containsExactly("account-pnl-monitor.recipient")
        }
        val withoutRecipient = env("notification.email.enabled" to "true")
        assertThat(OwnerAlertChannelRequirements.missingKeys(withoutRecipient)).containsExactly("account-pnl-monitor.recipient")

        val present = env("notification.email.enabled" to "true", "account-pnl-monitor.recipient" to "owner@example.test")
        assertThat(OwnerAlertChannelRequirements.missingKeys(present)).isEmpty()

        listOf(
            env("notification.email.enabled" to "false", "account-pnl-monitor.recipient" to ""),
            env(
                "notification.email.enabled" to "true",
                "owner-alert.email.enabled" to "false",
                "account-pnl-monitor.recipient" to "  ",
            ),
        ).forEach { off ->
            assertThat(OwnerAlertChannelRequirements.missingKeys(off)).isEmpty()
        }
    }

    @Test
    fun `ready 는 채널 준비식과 같다`() {
        val cases = listOf(
            Triple(
                "메일만",
                env("notification.email.enabled" to "true", "account-pnl-monitor.recipient" to "owner@example.test"),
                true,
            ),
            Triple(
                "Discord 만",
                env(
                    "notification.email.enabled" to "false",
                    "owner-alert.discord.enabled" to "true",
                    "owner-alert.discord.webhook.market" to webhook,
                ),
                true,
            ),
            Triple(
                "Discord 켜짐 webhook 없음",
                env(
                    "notification.email.enabled" to "true",
                    "account-pnl-monitor.recipient" to "owner@example.test",
                    "owner-alert.discord.enabled" to "true",
                    "owner-alert.discord.webhook.pnl" to " ",
                ),
                false,
            ),
            Triple("둘 다 꺼짐", env("notification.email.enabled" to "false"), false),
            Triple(
                "메일 켜짐 recipient 없음",
                env("notification.email.enabled" to "true", "account-pnl-monitor.recipient" to ""),
                false,
            ),
        )
        cases.forEach { (label, environment, expected) ->
            val formula = OwnerAlertChannelRequirements.missingKeys(environment).isEmpty() &&
                !OwnerAlertChannelRequirements.discordMisconfigured(environment) &&
                OwnerAlertChannelRequirements.anyChannel(environment)
            assertThat(OwnerAlertChannelRequirements.ready(environment)).`as`(label).isEqualTo(expected).isEqualTo(formula)
        }
    }

    @Test
    fun `recipient 는 값을 그대로 돌려주고 비면 값 없이 실패한다`() {
        assertThat(OwnerAlertChannelRequirements.recipient(env("account-pnl-monitor.recipient" to "owner@example.test")))
            .isEqualTo("owner@example.test")

        listOf(env(), env("account-pnl-monitor.recipient" to "   ")).forEach { environment ->
            assertThatThrownBy { OwnerAlertChannelRequirements.recipient(environment) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("account-pnl-monitor.recipient")
                .hasMessageNotContaining("@example.test")
                .hasNoCause()
        }
    }
}
