package io.premiumspread.domain.market

import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MarketPairTest {
    @Test
    fun `canonical key는 symbol과 두 거래소를 모두 포함한다`() {
        val pair = MarketPair(Symbol("btc"), Exchange.BITHUMB, Exchange.BINANCE)

        assertThat(pair.canonicalKey).isEqualTo("BTC:BITHUMB:BINANCE")
    }

    @Test
    fun `한국과 해외 거래소의 방향을 검증한다`() {
        assertThatThrownBy { MarketPair(Symbol("BTC"), Exchange.BINANCE, Exchange.BITHUMB) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `기본 페어는 BITHUMB와 BINANCE다`() {
        val pair = MarketPair.default(Symbol("BTC"))

        assertThat(pair.koreaExchange).isEqualTo(Exchange.BITHUMB)
        assertThat(pair.foreignExchange).isEqualTo(Exchange.BINANCE)
    }

    @Test
    fun `같은 심볼도 거래소 조합이 다르면 canonical key와 identity가 다르다`() {
        val bithumb = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE)
        val upbit = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)

        assertThat(bithumb.canonicalKey).isNotEqualTo(upbit.canonicalKey)
        assertThat(bithumb).isNotEqualTo(upbit)
    }
}
