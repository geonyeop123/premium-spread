package io.premiumspread.scheduler

import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant

@DisplayName("PremiumScheduler E2E")
class PremiumSchedulerE2ETest : BatchIntegrationTestBase() {

    @Autowired
    lateinit var premiumScheduler: PremiumScheduler

    private val nowMillis = Instant.now().toEpochMilli()

    /**
     * 선행 캐시 seed
     * - ticker:bithumb:btc = 129,555,000 KRW
     * - ticker:binance:btc = 89,277.10 USDT
     * - fx:usd:krw = 1,432.60
     */
    @BeforeEach
    fun seedCacheData() {
        // 빗썸 티커
        redisTemplate.opsForHash<String, String>().putAll(
            "ticker:bithumb:btc",
            mapOf(
                "exchange" to "BITHUMB",
                "symbol" to "BTC",
                "currency" to "KRW",
                "price" to "129555000",
                "volume" to "2345.6789",
                "timestamp" to nowMillis.toString(),
            ),
        )

        // 바이낸스 티커
        redisTemplate.opsForHash<String, String>().putAll(
            "ticker:binance:btc",
            mapOf(
                "exchange" to "BINANCE",
                "symbol" to "BTC",
                "currency" to "USDT",
                "price" to "89277.10",
                "volume" to "",
                "timestamp" to nowMillis.toString(),
            ),
        )

        // 환율
        redisTemplate.opsForHash<String, String>().putAll(
            "fx:usd:krw",
            mapOf(
                "base" to "USD",
                "quote" to "KRW",
                "rate" to "1432.60",
                "timestamp" to nowMillis.toString(),
            ),
        )
    }

    @Nested
    @DisplayName("calculatePremium")
    inner class CalculatePremium {

        @Test
        fun `실행 후 프리미엄 캐시가 저장된다`() {
            // when
            premiumScheduler.calculatePremium()

            // then
            val hash = redisTemplate.opsForHash<String, String>().entries("premium:btc")
            assertThat(hash).isNotEmpty
            assertThat(hash["symbol"]).isEqualTo("BTC")
            assertThat(BigDecimal(hash["korea_price"])).isEqualByComparingTo(BigDecimal("129555000"))
            assertThat(BigDecimal(hash["foreign_price"])).isEqualByComparingTo(BigDecimal("89277.10"))
            assertThat(BigDecimal(hash["fx_rate"])).isEqualByComparingTo(BigDecimal("1432.60"))
            assertThat(BigDecimal(hash["rate"])).isPositive()
            assertThat(hash["observed_at"]).isNotNull()
        }

        @Test
        fun `실행 후 프리미엄 초당 ZSet에 데이터가 저장된다`() {
            // when
            premiumScheduler.calculatePremium()

            // then
            val zsetSize = redisTemplate.opsForZSet().size("premium:seconds:btc")
            assertThat(zsetSize).isGreaterThanOrEqualTo(1)

            val members = redisTemplate.opsForZSet().rangeWithScores("premium:seconds:btc", 0, -1)
            assertThat(members).isNotEmpty
            // value format: "rate:koreaPrice:foreignPrice:fxRate"
            val entry = members!!.first()
            val parts = entry.value!!.split(":")
            assertThat(parts).hasSize(4)
            assertThat(BigDecimal(parts[0])).isPositive() // premiumRate
        }

        @Test
        fun `포지션이 열려있으면 히스토리 ZSet에 데이터가 저장된다`() {
            // given - position open seed
            redisTemplate.opsForValue().set("position:open:exists", "true")

            // when
            premiumScheduler.calculatePremium()

            // then
            val zsetSize = redisTemplate.opsForZSet().size("premium:btc:history")
            assertThat(zsetSize).isGreaterThanOrEqualTo(1)

            val members = redisTemplate.opsForZSet().rangeWithScores("premium:btc:history", 0, -1)
            assertThat(members).isNotEmpty
            // value format: "premiumRate:koreaPrice:foreignPrice"
            val parts = members!!.first().value!!.split(":")
            assertThat(parts).hasSize(3)
        }
    }
}
