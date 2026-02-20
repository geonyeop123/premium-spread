package io.premiumspread.infrastructure.cache

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
import java.math.BigDecimal

@DisplayName("TickerCacheReader")
class TickerCacheReaderTest {

    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var hashOps: HashOperations<String, String, String>
    private lateinit var tickerCacheReader: TickerCacheReader

    @BeforeEach
    fun setUp() {
        hashOps = mockk(relaxed = true)
        redisTemplate = mockk()
        every { redisTemplate.opsForHash<String, String>() } returns hashOps
        tickerCacheReader = TickerCacheReader(redisTemplate)
    }

    @Nested
    @DisplayName("get")
    inner class Get {

        @Test
        fun `모든 필드가 있으면 CachedTicker를 반환한다`() {
            every { hashOps.entries("ticker:bithumb:btc") } returns mapOf(
                "exchange" to "BITHUMB",
                "symbol" to "BTC",
                "currency" to "KRW",
                "price" to "129555000",
                "volume" to "10.5",
                "timestamp" to "1704067200000",
            )

            val result = tickerCacheReader.get("bithumb", "btc")

            assertThat(result).isNotNull
            assertThat(result!!.exchange).isEqualTo("BITHUMB")
            assertThat(result.symbol).isEqualTo("BTC")
            assertThat(result.currency).isEqualTo("KRW")
            assertThat(result.price).isEqualByComparingTo("129555000")
            assertThat(result.volume).isEqualByComparingTo("10.5")
        }

        @Test
        fun `volume이 빈 문자열이면 null로 처리한다`() {
            every { hashOps.entries("ticker:bithumb:btc") } returns mapOf(
                "exchange" to "BITHUMB",
                "symbol" to "BTC",
                "currency" to "KRW",
                "price" to "129555000",
                "volume" to "",
                "timestamp" to "1704067200000",
            )

            val result = tickerCacheReader.get("bithumb", "btc")

            assertThat(result).isNotNull
            assertThat(result!!.volume).isNull()
        }

        @Test
        fun `캐시가 비어 있으면 null을 반환한다`() {
            every { hashOps.entries("ticker:bithumb:btc") } returns emptyMap()

            val result = tickerCacheReader.get("bithumb", "btc")

            assertThat(result).isNull()
        }

        @Test
        fun `price 필드가 없으면 null을 반환한다`() {
            every { hashOps.entries("ticker:bithumb:btc") } returns mapOf(
                "exchange" to "BITHUMB",
                "symbol" to "BTC",
                "currency" to "KRW",
                "timestamp" to "1704067200000",
            )

            val result = tickerCacheReader.get("bithumb", "btc")

            assertThat(result).isNull()
        }

        @Test
        fun `price 값이 숫자가 아니면 null을 반환한다`() {
            every { hashOps.entries("ticker:bithumb:btc") } returns mapOf(
                "exchange" to "BITHUMB",
                "symbol" to "BTC",
                "currency" to "KRW",
                "price" to "not-a-number",
                "timestamp" to "1704067200000",
            )

            val result = tickerCacheReader.get("bithumb", "btc")

            assertThat(result).isNull()
        }

        @Test
        fun `대소문자 구분 없이 key를 소문자로 생성한다`() {
            every { hashOps.entries("ticker:bithumb:btc") } returns mapOf(
                "exchange" to "BITHUMB",
                "symbol" to "BTC",
                "currency" to "KRW",
                "price" to "129555000",
                "timestamp" to "1704067200000",
            )

            tickerCacheReader.get("BITHUMB", "BTC")

            verify { hashOps.entries("ticker:bithumb:btc") }
        }
    }

    @Nested
    @DisplayName("getBithumbBtc / getBinanceBtc")
    inner class ShortcutMethods {

        @Test
        fun `getBithumbBtc는 bithumb-btc 티커를 조회한다`() {
            every { hashOps.entries("ticker:bithumb:btc") } returns mapOf(
                "exchange" to "BITHUMB",
                "symbol" to "BTC",
                "currency" to "KRW",
                "price" to "129555000",
                "timestamp" to "1704067200000",
            )

            val result = tickerCacheReader.getBithumbBtc()

            assertThat(result).isNotNull
            assertThat(result!!.exchange).isEqualTo("BITHUMB")
            verify { hashOps.entries("ticker:bithumb:btc") }
        }

        @Test
        fun `getBinanceBtc는 binance-btc 티커를 조회한다`() {
            every { hashOps.entries("ticker:binance:btc") } returns mapOf(
                "exchange" to "BINANCE",
                "symbol" to "BTC",
                "currency" to "USDT",
                "price" to "89277",
                "timestamp" to "1704067200000",
            )

            val result = tickerCacheReader.getBinanceBtc()

            assertThat(result).isNotNull
            assertThat(result!!.price).isEqualByComparingTo(BigDecimal("89277"))
            verify { hashOps.entries("ticker:binance:btc") }
        }

        @Test
        fun `캐시가 없으면 null을 반환한다`() {
            every { hashOps.entries(any()) } returns emptyMap()

            assertThat(tickerCacheReader.getBithumbBtc()).isNull()
            assertThat(tickerCacheReader.getBinanceBtc()).isNull()
        }
    }

    @Nested
    @DisplayName("exists")
    inner class Exists {

        @Test
        fun `키가 존재하면 true를 반환한다`() {
            every { redisTemplate.hasKey("ticker:bithumb:btc") } returns true

            assertThat(tickerCacheReader.exists("bithumb", "btc")).isTrue()
        }

        @Test
        fun `키가 없으면 false를 반환한다`() {
            every { redisTemplate.hasKey("ticker:bithumb:btc") } returns false

            assertThat(tickerCacheReader.exists("bithumb", "btc")).isFalse()
        }
    }
}
