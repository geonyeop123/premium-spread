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
import org.springframework.data.redis.core.ZSetOperations
import java.time.Instant

@DisplayName("PremiumCacheReader")
class PremiumCacheReaderTest {

    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var hashOps: HashOperations<String, String, String>
    private lateinit var zSetOps: ZSetOperations<String, String>
    private lateinit var premiumCacheReader: PremiumCacheReader

    @BeforeEach
    fun setUp() {
        hashOps = mockk(relaxed = true)
        zSetOps = mockk(relaxed = true)
        redisTemplate = mockk()
        every { redisTemplate.opsForHash<String, String>() } returns hashOps
        every { redisTemplate.opsForZSet() } returns zSetOps
        premiumCacheReader = PremiumCacheReader(redisTemplate)
    }

    private fun hashEntry() = mapOf(
        "symbol" to "BTC",
        "rate" to "1.28",
        "korea_price" to "129555000",
        "foreign_price" to "89277",
        "foreign_price_krw" to "127916893.66",
        "fx_rate" to "1432.6",
        "observed_at" to "1704067200000",
    )

    private fun tuple(value: String, score: Double): ZSetOperations.TypedTuple<String> =
        mockk<ZSetOperations.TypedTuple<String>>().also {
            every { it.value } returns value
            every { it.score } returns score
        }

    @Nested
    @DisplayName("get")
    inner class Get {

        @Test
        fun `모든 필드가 있으면 CachedPremium을 반환한다`() {
            every { hashOps.entries("premium:btc") } returns hashEntry()

            val result = premiumCacheReader.get("btc")

            assertThat(result).isNotNull
            assertThat(result!!.symbol).isEqualTo("BTC")
            assertThat(result.premiumRate).isEqualByComparingTo("1.28")
            assertThat(result.koreaPrice).isEqualByComparingTo("129555000")
            assertThat(result.foreignPrice).isEqualByComparingTo("89277")
            assertThat(result.foreignPriceInKrw).isEqualByComparingTo("127916893.66")
            assertThat(result.fxRate).isEqualByComparingTo("1432.6")
        }

        @Test
        fun `캐시가 비어 있으면 null을 반환한다`() {
            every { hashOps.entries("premium:btc") } returns emptyMap()

            assertThat(premiumCacheReader.get("btc")).isNull()
        }

        @Test
        fun `rate 필드가 없으면 null을 반환한다`() {
            val hash = hashEntry().toMutableMap().also { it.remove("rate") }
            every { hashOps.entries("premium:btc") } returns hash

            assertThat(premiumCacheReader.get("btc")).isNull()
        }

        @Test
        fun `korea_price 필드가 없으면 null을 반환한다`() {
            val hash = hashEntry().toMutableMap().also { it.remove("korea_price") }
            every { hashOps.entries("premium:btc") } returns hash

            assertThat(premiumCacheReader.get("btc")).isNull()
        }

        @Test
        fun `대소문자 구분 없이 key를 소문자로 생성한다`() {
            every { hashOps.entries("premium:btc") } returns hashEntry()

            premiumCacheReader.get("BTC")

            verify { hashOps.entries("premium:btc") }
        }
    }

    @Nested
    @DisplayName("getBtc")
    inner class GetBtc {

        @Test
        fun `BTC 프리미엄을 조회한다`() {
            every { hashOps.entries("premium:btc") } returns hashEntry()

            val result = premiumCacheReader.getBtc()

            assertThat(result).isNotNull
            verify { hashOps.entries("premium:btc") }
        }

        @Test
        fun `캐시가 없으면 null을 반환한다`() {
            every { hashOps.entries("premium:btc") } returns emptyMap()

            assertThat(premiumCacheReader.getBtc()).isNull()
        }
    }

    @Nested
    @DisplayName("getHistory")
    inner class GetHistory {

        @Test
        fun `최근 N개의 히스토리를 ZSet에서 조회한다`() {
            val score1 = 1_704_067_200_000.0
            val score2 = 1_704_067_260_000.0
            every {
                zSetOps.reverseRangeWithScores("premium:btc:history", 0, 99)
            } returns linkedSetOf(
                tuple("1.28:129555000:89277", score2),
                tuple("1.30:130000000:89000", score1),
            )

            val result = premiumCacheReader.getHistory("btc")

            assertThat(result).hasSize(2)
            assertThat(result[0].premiumRate).isEqualByComparingTo("1.28")
            assertThat(result[0].koreaPrice).isEqualByComparingTo("129555000")
            assertThat(result[0].foreignPrice).isEqualByComparingTo("89277")
            assertThat(result[0].timestamp).isEqualTo(Instant.ofEpochMilli(score2.toLong()))
            assertThat(result[1].premiumRate).isEqualByComparingTo("1.30")
        }

        @Test
        fun `ZSet이 비어 있으면 빈 리스트를 반환한다`() {
            every {
                zSetOps.reverseRangeWithScores("premium:btc:history", 0, 99)
            } returns emptySet()

            val result = premiumCacheReader.getHistory("btc")

            assertThat(result).isEmpty()
        }

        @Test
        fun `파싱 불가한 엔트리는 스킵한다`() {
            every {
                zSetOps.reverseRangeWithScores("premium:btc:history", 0, 99)
            } returns linkedSetOf(
                tuple("invalid-format", 1_704_067_260_000.0),  // parts < 3 → skip
                tuple("1.28:129555000:89277", 1_704_067_200_000.0),  // valid
            )

            val result = premiumCacheReader.getHistory("btc")

            assertThat(result).hasSize(1)
            assertThat(result[0].premiumRate).isEqualByComparingTo("1.28")
        }

        @Test
        fun `limit 파라미터가 결과 개수를 제한한다`() {
            every {
                zSetOps.reverseRangeWithScores("premium:btc:history", 0, 4)
            } returns linkedSetOf(
                tuple("1.28:129555000:89277", 1_704_067_200_000.0),
            )

            premiumCacheReader.getHistory("btc", limit = 5)

            verify { zSetOps.reverseRangeWithScores("premium:btc:history", 0, 4) }
        }
    }

    @Nested
    @DisplayName("getBtcHistory")
    inner class GetBtcHistory {

        @Test
        fun `BTC 히스토리를 조회한다`() {
            every {
                zSetOps.reverseRangeWithScores("premium:btc:history", 0, 99)
            } returns emptySet()

            premiumCacheReader.getBtcHistory()

            verify { zSetOps.reverseRangeWithScores("premium:btc:history", 0, 99) }
        }
    }

    @Nested
    @DisplayName("exists")
    inner class Exists {

        @Test
        fun `키가 존재하면 true를 반환한다`() {
            every { redisTemplate.hasKey("premium:btc") } returns true

            assertThat(premiumCacheReader.exists("btc")).isTrue()
        }

        @Test
        fun `키가 없으면 false를 반환한다`() {
            every { redisTemplate.hasKey("premium:btc") } returns false

            assertThat(premiumCacheReader.exists("btc")).isFalse()
        }
    }
}
