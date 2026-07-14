package io.premiumspread.domain.notification

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class NotificationEventKeyTest {

    @Test
    fun `threshold scale만 다른 동일 이벤트는 같은 key를 만든다`() {
        val arguments = Arguments()

        val first = arguments.create(BigDecimal("5.0"))
        val second = arguments.create(BigDecimal("5.0000"))

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `revision pair direction cooldown window 변경은 서로 다른 key를 만든다`() {
        val arguments = Arguments()
        val base = arguments.create()

        assertThat(arguments.create(revision = 2)).isNotEqualTo(base)
        assertThat(arguments.create(pair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)))
            .isNotEqualTo(base)
        assertThat(arguments.create(direction = ThresholdDirection.BELOW)).isNotEqualTo(base)
        assertThat(arguments.create(cooldown = Duration.ofHours(2))).isNotEqualTo(base)
        assertThat(arguments.create(window = Instant.parse("2026-07-15T01:01:00Z"))).isNotEqualTo(base)
    }

    @Test
    fun `key schema는 필드명과 cooldown millisecond 단위가 명시된 안정적인 형식이다`() {
        assertThat(Arguments().create()).isEqualTo(
            "v2|subscriptionId=10|subscriptionRevision=1" +
                "|marketPair=BTC:BITHUMB:BINANCE|direction=ABOVE|threshold=5" +
                "|cooldownMillis=3600000|windowStart=2026-07-15T01:00:00Z",
        )
    }

    private data class Arguments(
        val revision: Long = 1,
        val pair: MarketPair = MarketPair.default(Symbol("BTC")),
        val direction: ThresholdDirection = ThresholdDirection.ABOVE,
        val threshold: BigDecimal = BigDecimal("5.0"),
        val cooldown: Duration = Duration.ofHours(1),
        val window: Instant = Instant.parse("2026-07-15T01:00:00Z"),
    ) {
        fun create(
            threshold: BigDecimal = this.threshold,
            revision: Long = this.revision,
            pair: MarketPair = this.pair,
            direction: ThresholdDirection = this.direction,
            cooldown: Duration = this.cooldown,
            window: Instant = this.window,
        ): String = NotificationEventKey.create(
            subscriptionId = 10,
            subscriptionRevision = revision,
            pair = pair,
            direction = direction,
            threshold = threshold,
            cooldownWindow = cooldown,
            cooldownWindowStart = window,
        )
    }
}
