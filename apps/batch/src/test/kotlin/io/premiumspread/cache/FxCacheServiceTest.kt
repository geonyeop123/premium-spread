package io.premiumspread.cache

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.client.FxRateData
import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.ticker.Exchange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class FxCacheServiceTest {

    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var hashOps: HashOperations<String, String, String>
    private lateinit var fxCacheService: FxCacheService

    @BeforeEach
    fun setUp() {
        hashOps = mockk(relaxed = true)
        redisTemplate = mockk()
        every { redisTemplate.opsForHash<String, String>() } returns hashOps
        every { redisTemplate.expire(any(), any<Duration>()) } returns true
        fxCacheService = FxCacheService(redisTemplate)
    }

    private fun fxRateData(
        base: String = "USD",
        quote: String = "KRW",
        rate: String = "1432.60",
        epochMilli: Long = 1_706_500_000_000L,
        source: Exchange = Exchange.FX_PROVIDER,
    ) = FxRateData(
        baseCurrency = base,
        quoteCurrency = quote,
        rate = BigDecimal(rate),
        timestamp = Instant.ofEpochMilli(epochMilli),
        source = source,
    )

    @Nested
    @DisplayName("save")
    inner class Save {

        @Test
        fun `환율 데이터를 해시로 저장하고 TTL을 설정한다`() {
            // given
            val fxRate = fxRateData()

            // when
            fxCacheService.save(fxRate)

            // then
            verify {
                hashOps.putAll(
                    "fx:usd:krw",
                    match<Map<String, String>> { hash ->
                        hash["base"] == "USD" &&
                            hash["quote"] == "KRW" &&
                            hash["rate"] == "1432.60" &&
                            hash["timestamp"] == "1706500000000" &&
                            hash["source"] == "FX_PROVIDER"
                    },
                )
            }
            verify { redisTemplate.expire("fx:usd:krw", any<Duration>()) }
        }

        @Test
        fun `통화쌍 키는 소문자로 생성된다`() {
            // given
            val fxRate = fxRateData(base = "USD", quote = "KRW")

            // when
            fxCacheService.save(fxRate)

            // then
            verify { hashOps.putAll("fx:usd:krw", any()) }
        }
    }

    @Nested
    @DisplayName("get")
    inner class Get {

        @Test
        fun `해시가 존재하면 FxRateData를 반환한다`() {
            // given
            every { hashOps.entries("fx:usd:krw") } returns mapOf(
                "base" to "USD",
                "quote" to "KRW",
                "rate" to "1432.60",
                "timestamp" to "1706500000000",
            )

            // when
            val result = fxCacheService.get("usd", "krw")

            // then
            assertThat(result).isNotNull
            assertThat(result!!.baseCurrency).isEqualTo("USD")
            assertThat(result.quoteCurrency).isEqualTo("KRW")
            assertThat(result.rate).isEqualByComparingTo("1432.60")
            assertThat(result.timestamp).isEqualTo(Instant.ofEpochMilli(1_706_500_000_000L))
            assertThat(result.source).isEqualTo(Exchange.FX_PROVIDER)
        }

        @Test
        fun `해시가 없으면 null을 반환한다`() {
            // given
            every { hashOps.entries("fx:usd:krw") } returns emptyMap()

            // when & then
            assertThat(fxCacheService.get("usd", "krw")).isNull()
        }

        @Test
        fun `rate 필드가 숫자가 아니면 null을 반환한다`() {
            // given
            every { hashOps.entries("fx:usd:krw") } returns mapOf(
                "base" to "USD",
                "quote" to "KRW",
                "rate" to "invalid",
                "timestamp" to "1706500000000",
            )

            // when & then
            assertThat(fxCacheService.get("usd", "krw")).isNull()
        }

        @Test
        fun `필수 필드 base가 없으면 null을 반환한다`() {
            // given
            every { hashOps.entries("fx:usd:krw") } returns mapOf(
                "quote" to "KRW",
                "rate" to "1432.60",
                "timestamp" to "1706500000000",
            )

            // when & then
            assertThat(fxCacheService.get("usd", "krw")).isNull()
        }

        @Test
        fun `timestamp가 없으면 현재 시각을 합성하지 않고 null을 반환한다`() {
            every { hashOps.entries("fx:usd:krw") } returns mapOf(
                "base" to "USD",
                "quote" to "KRW",
                "rate" to "1432.60",
            )

            assertThat(fxCacheService.get("usd", "krw")).isNull()
        }

        @Test
        fun `source가 유효하지 않으면 null을 반환한다`() {
            every { hashOps.entries("fx:usd:krw") } returns mapOf(
                "base" to "USD",
                "quote" to "KRW",
                "rate" to "1432.60",
                "timestamp" to "1706500000000",
                "source" to "INVALID",
            )

            assertThat(fxCacheService.get("usd", "krw")).isNull()
        }

        @Test
        fun `요청 통화쌍과 저장된 base가 다르면 null을 반환한다`() {
            every { hashOps.entries("fx:usd:krw") } returns mapOf(
                "base" to "EUR",
                "quote" to "KRW",
                "rate" to "1432.60",
                "timestamp" to "1706500000000",
            )

            assertThat(fxCacheService.get("usd", "krw")).isNull()
        }

        @Test
        fun `요청 통화쌍과 저장된 quote가 다르면 null을 반환한다`() {
            every { hashOps.entries("fx:usd:krw") } returns mapOf(
                "base" to "USD",
                "quote" to "JPY",
                "rate" to "1432.60",
                "timestamp" to "1706500000000",
            )

            assertThat(fxCacheService.get("usd", "krw")).isNull()
        }
    }

    @Nested
    @DisplayName("getUsdKrw")
    inner class GetUsdKrw {

        @Test
        fun `USD-KRW 환율을 Domain snapshot으로 노출한다`() {
            every { hashOps.entries("fx:usd:krw") } returns mapOf(
                "base" to "USD",
                "quote" to "KRW",
                "rate" to "1432.60",
                "timestamp" to "1706500000000",
            )

            val result: ExchangeRateSnapshot? = fxCacheService.getUsdKrwSnapshot()

            assertThat(result).isEqualTo(
                ExchangeRateSnapshot(
                    baseCurrency = "USD",
                    quoteCurrency = "KRW",
                    rate = BigDecimal("1432.60"),
                    observedAt = Instant.ofEpochMilli(1_706_500_000_000L),
                    source = Exchange.FX_PROVIDER,
                ),
            )
        }

        @Test
        fun `비기본 source를 저장하고 조회하면 Domain snapshot까지 보존한다`() {
            val persisted = mutableMapOf<String, String>()
            val hashSlot = slot<Map<String, String>>()
            every { hashOps.putAll("fx:usd:krw", capture(hashSlot)) } answers {
                persisted.putAll(hashSlot.captured)
            }
            every { hashOps.entries("fx:usd:krw") } answers { persisted }

            fxCacheService.save(fxRateData(source = Exchange.BINANCE))
            val result = fxCacheService.getUsdKrwSnapshot()

            assertThat(result?.source).isEqualTo(Exchange.BINANCE)
        }

        @Test
        fun `USD-KRW 환율이 있으면 rate를 반환한다`() {
            // given
            every { hashOps.entries("fx:usd:krw") } returns mapOf(
                "base" to "USD",
                "quote" to "KRW",
                "rate" to "1432.60",
                "timestamp" to "1706500000000",
            )

            // when
            val result = fxCacheService.getUsdKrw()

            // then
            assertThat(result).isEqualByComparingTo("1432.60")
        }

        @Test
        fun `환율 캐시가 없으면 null을 반환한다`() {
            // given
            every { hashOps.entries("fx:usd:krw") } returns emptyMap()

            // when & then
            assertThat(fxCacheService.getUsdKrw()).isNull()
        }
    }
}
