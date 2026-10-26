package io.premiumspread.infrastructure.common.cache.exchangerate

import io.premiumspread.domain.exchangerate.FxIngestionFreshness
import io.premiumspread.redis.RedisTtl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class FxCacheTtlContractTest {
    @Test
    fun `FX 캐시 TTL 은 40분이다`() {
        assertThat(RedisTtl.FX).isEqualTo(Duration.ofMinutes(40))
    }

    @Test
    fun `캐시가 freshness 판정보다 먼저 만료되지 않는다`() {
        val margin = RedisTtl.FX.minus(FxIngestionFreshness.MAX_INGESTION_GAP)

        assertThat(margin).isGreaterThanOrEqualTo(Duration.ofMinutes(5))
    }
}
