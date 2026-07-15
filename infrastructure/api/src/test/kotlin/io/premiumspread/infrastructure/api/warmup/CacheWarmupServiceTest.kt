package io.premiumspread.infrastructure.api.warmup

import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.ticker.TickerService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class CacheWarmupServiceTest {
    @Test
    fun `비활성화된 warmup은 domain 조회를 실행하지 않는다`() {
        val premiumService = mock(PremiumService::class.java)
        val tickerService = mock(TickerService::class.java)
        val service = CacheWarmupService(
            premiumService,
            tickerService,
            WarmupProperties(enabled = false),
        )

        service.warmup()

        verifyNoInteractions(premiumService, tickerService)
    }
}
