package io.premiumspread.infrastructure.exchangerate

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate

@DisplayName("FxCacheReader")
class FxCacheReaderTest {

    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var hashOps: HashOperations<String, String, String>
    private lateinit var fxCacheReader: FxCacheReader

    @BeforeEach
    fun setUp() {
        hashOps = mockk(relaxed = true)
        redisTemplate = mockk()
        every { redisTemplate.opsForHash<String, String>() } returns hashOps
        fxCacheReader = FxCacheReader(redisTemplate)
    }

    @Nested
    @DisplayName("get")
    inner class Get {

        @Test
        fun `모든 필드가 있으면 CachedFxRate를 반환한다`() {
            every { hashOps.entries("fx:usd:krw") } returns mapOf(
                "base" to "USD",
                "quote" to "KRW",
                "rate" to "1432.60",
                "timestamp" to "1704067200000",
            )

            val result = fxCacheReader.get("usd", "krw")

            assertThat(result).isNotNull
            assertThat(result!!.baseCurrency).isEqualTo("USD")
            assertThat(result.quoteCurrency).isEqualTo("KRW")
            assertThat(result.rate).isEqualByComparingTo("1432.60")
        }

        @Test
        fun `캐시가 비어 있으면 null을 반환한다`() {
            every { hashOps.entries("fx:usd:krw") } returns emptyMap()

            val result = fxCacheReader.get("usd", "krw")

            assertThat(result).isNull()
        }

        @Test
        fun `base 필드가 없으면 null을 반환한다`() {
            every { hashOps.entries("fx:usd:krw") } returns mapOf(
                "quote" to "KRW",
                "rate" to "1432.60",
                "timestamp" to "1704067200000",
            )

            val result = fxCacheReader.get("usd", "krw")

            assertThat(result).isNull()
        }

        @Test
        fun `rate 값이 숫자가 아니면 null을 반환한다`() {
            every { hashOps.entries("fx:usd:krw") } returns mapOf(
                "base" to "USD",
                "quote" to "KRW",
                "rate" to "invalid",
                "timestamp" to "1704067200000",
            )

            val result = fxCacheReader.get("usd", "krw")

            assertThat(result).isNull()
        }

        @Test
        fun `timestamp가 없으면 현재 시각으로 대체하여 반환한다`() {
            every { hashOps.entries("fx:usd:krw") } returns mapOf(
                "base" to "USD",
                "quote" to "KRW",
                "rate" to "1432.60",
            )

            val result = fxCacheReader.get("usd", "krw")

            assertThat(result).isNotNull
            assertThat(result!!.rate).isEqualByComparingTo("1432.60")
        }

        @Test
        fun `대소문자 구분 없이 key를 소문자로 생성한다`() {
            every { hashOps.entries("fx:usd:krw") } returns mapOf(
                "base" to "USD",
                "quote" to "KRW",
                "rate" to "1432.60",
                "timestamp" to "1704067200000",
            )

            // 대문자 입력 → 소문자 key
            val result = fxCacheReader.get("USD", "KRW")

            assertThat(result).isNotNull
            verify { hashOps.entries("fx:usd:krw") }
        }
    }

    @Nested
    @DisplayName("getUsdKrw")
    inner class GetUsdKrw {

        @Test
        fun `USD-KRW 환율을 조회한다`() {
            every { hashOps.entries("fx:usd:krw") } returns mapOf(
                "base" to "USD",
                "quote" to "KRW",
                "rate" to "1432.60",
                "timestamp" to "1704067200000",
            )

            val result = fxCacheReader.getUsdKrw()

            assertThat(result).isNotNull
            assertThat(result!!.baseCurrency).isEqualTo("USD")
        }

        @Test
        fun `캐시가 없으면 null을 반환한다`() {
            every { hashOps.entries("fx:usd:krw") } returns emptyMap()

            assertThat(fxCacheReader.getUsdKrw()).isNull()
        }
    }

    @Nested
    @DisplayName("getUsdKrwRate")
    inner class GetUsdKrwRate {

        @Test
        fun `USD-KRW 환율 값을 반환한다`() {
            every { hashOps.entries("fx:usd:krw") } returns mapOf(
                "base" to "USD",
                "quote" to "KRW",
                "rate" to "1432.60",
                "timestamp" to "1704067200000",
            )

            val result = fxCacheReader.getUsdKrwRate()

            assertThat(result).isEqualByComparingTo("1432.60")
        }

        @Test
        fun `캐시가 없으면 null을 반환한다`() {
            every { hashOps.entries("fx:usd:krw") } returns emptyMap()

            assertThat(fxCacheReader.getUsdKrwRate()).isNull()
        }
    }

    @Nested
    @DisplayName("exists")
    inner class Exists {

        @Test
        fun `키가 존재하면 true를 반환한다`() {
            every { redisTemplate.hasKey("fx:usd:krw") } returns true

            assertThat(fxCacheReader.exists("usd", "krw")).isTrue()
        }

        @Test
        fun `키가 없으면 false를 반환한다`() {
            every { redisTemplate.hasKey("fx:usd:krw") } returns false

            assertThat(fxCacheReader.exists("usd", "krw")).isFalse()
        }
    }
}
