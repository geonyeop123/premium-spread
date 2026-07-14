package io.premiumspread.redis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RedisKeyGeneratorTest {

    @Test
    fun `premium v2 key는 거래소 쌍과 symbol을 포함한다`() {
        assertThat(RedisKeyGenerator.premiumV2Key("BITHUMB", "BINANCE", "BTC"))
            .isEqualTo("premium:bithumb:binance:btc")
        assertThat(RedisKeyGenerator.premiumV2HistoryKey("BITHUMB", "BINANCE", "BTC"))
            .isEqualTo("premium:bithumb:binance:btc:history")
        assertThat(RedisKeyGenerator.premiumV2SecondsKey("BITHUMB", "BINANCE", "BTC"))
            .isEqualTo("premium:bithumb:binance:btc:seconds")
    }

    @Test
    fun `premium v2 집계 key도 거래소 쌍을 포함한다`() {
        assertThat(RedisKeyGenerator.premiumV2AggregationKey("BITHUMB", "BINANCE", "BTC", "minutes"))
            .isEqualTo("premium:bithumb:binance:btc:minutes")
        assertThat(RedisKeyGenerator.premiumV2SummaryKey("BITHUMB", "BINANCE", "BTC", "1h"))
            .isEqualTo("premium:bithumb:binance:btc:summary:1h")
    }
}
