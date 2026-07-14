package io.premiumspread.config

import io.premiumspread.cache.TickerCacheService
import io.premiumspread.domain.exchangerate.ExchangeRateService
import io.premiumspread.domain.member.MemberService
import io.premiumspread.domain.notification.NotificationSubscriptionService
import io.premiumspread.domain.position.PositionService
import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.ticker.TickerService
import io.premiumspread.scheduler.PremiumScheduler
import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext

class BatchComponentScanBoundaryTest : BatchIntegrationTestBase() {

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun `Batch context는 API용 Domain Service를 스캔하지 않고 Batch bean은 유지한다`() {
        val domainServiceTypes = listOf(
            ExchangeRateService::class.java,
            MemberService::class.java,
            NotificationSubscriptionService::class.java,
            PositionService::class.java,
            PremiumService::class.java,
            TickerService::class.java,
        )

        domainServiceTypes.forEach { type ->
            assertThat(context.getBeansOfType(type)).describedAs(type.name).isEmpty()
        }
        assertThat(context.getBeansOfType(TickerCacheService::class.java)).hasSize(1)
        assertThat(context.getBeansOfType(PremiumScheduler::class.java)).hasSize(1)
    }
}
