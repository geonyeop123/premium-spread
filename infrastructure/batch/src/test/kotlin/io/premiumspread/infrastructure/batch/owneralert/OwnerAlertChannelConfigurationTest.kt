package io.premiumspread.infrastructure.batch.owneralert

import io.mockk.mockk
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.owneralert.OwnerAlertChannels
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.email.EmailSender
import io.premiumspread.infrastructure.batch.notification.DiscordWebhookSenderAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class OwnerAlertChannelConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    class Stubs {
        @Bean
        fun emailSender(): EmailSender = mockk(relaxed = true)
    }

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(Stubs::class.java, OwnerAlertChannelConfiguration::class.java)

    private val webhook = "owner-alert.discord.webhook.ops=https://discord.example.test/api/webhooks/1/x"
    private val mail = arrayOf("notification.email.enabled=true", "account-pnl-monitor.recipient=owner@example.test")

    private fun ApplicationContextRunner.assertNoChannelBeans() = run { ctx ->
        assertThat(ctx).hasNotFailed()
        assertThat(ctx).doesNotHaveBean(OwnerAlertRouting::class.java)
        assertThat(ctx).doesNotHaveBean(DiscordWebhookSenderAdapter::class.java)
        assertThat(ctx).doesNotHaveBean(AccountPnlMailSender::class.java)
    }

    @Test
    fun `PnL 모니터가 꺼져 있어도 Discord 채널이면 라우팅과 Discord sender 가 뜬다`() {
        runner.withPropertyValues("account-pnl-monitor.enabled=false", "owner-alert.discord.enabled=true", webhook).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(DiscordWebhookSenderAdapter::class.java)
            assertThat(ctx).doesNotHaveBean(AccountPnlMailSender::class.java)
            assertThat(ctx.getBean(OwnerAlertRouting::class.java).channels)
                .isEqualTo(OwnerAlertChannels(email = false, discord = true))
        }
    }

    @Test
    fun `메일 채널만이면 메일 sender 와 라우팅`() {
        runner.withPropertyValues(*mail).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(AccountPnlMailSender::class.java)
            assertThat(ctx).doesNotHaveBean(DiscordWebhookSenderAdapter::class.java)
            assertThat(ctx.getBean(OwnerAlertRouting::class.java).channels)
                .isEqualTo(OwnerAlertChannels(email = true, discord = false))
        }
    }

    @Test
    fun `채널이 준비되지 않으면 bean 이 없다`() {
        runner.assertNoChannelBeans()
        runner.withPropertyValues("owner-alert.discord.enabled=true").assertNoChannelBeans()
        runner.withPropertyValues("notification.email.enabled=true").assertNoChannelBeans()
    }

    @Test
    fun `scheduling 이 꺼지면 bean 이 없다`() {
        runner.withPropertyValues(*mail, "batch.scheduling.enabled=false").assertNoChannelBeans()
        runner.withPropertyValues(*mail, "scheduling.enabled=false").assertNoChannelBeans()
    }

    @Test
    fun `채널 ready 면 Discord 켜짐과 무관하게 webhook 과 override 를 검증한다`() {
        val badWebhook = "owner-alert.discord.webhook.ops=http://discord.example.test/x"
        val cases = listOf(
            arrayOf("owner-alert.discord.enabled=true", badWebhook),
            arrayOf(*mail, badWebhook),
            arrayOf(*mail, "owner-alert.routes.ops-recovered.push=loud"),
        )
        cases.forEach { props ->
            runner.withPropertyValues(*props).run { ctx ->
                assertThat(ctx).hasFailed()
                val messages = generateSequence(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.toList()
                assertThat(messages).noneMatch { it.contains("discord.example.test/x") || it.contains("loud") }
            }
        }
    }
}
