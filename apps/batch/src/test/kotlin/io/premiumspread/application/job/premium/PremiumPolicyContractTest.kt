package io.premiumspread.application.job.premium

import io.premiumspread.domain.premium.PremiumPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PremiumPolicyContractTest {

    @Test
    fun `Batch 저장 정밀도는 Domain PremiumPolicy 결과를 그대로 사용한다`() {
        val result = PremiumPolicy.calculate(
            koreaPrice = BigDecimal("129555000"),
            foreignPriceUsd = BigDecimal("89277"),
            fxRate = BigDecimal("1432.6"),
        )

        assertThat(result.storagePremiumRate).isEqualByComparingTo("1.2954")
        assertThat(result.foreignPriceInKrw).isEqualByComparingTo("127898230.2")
    }
}
