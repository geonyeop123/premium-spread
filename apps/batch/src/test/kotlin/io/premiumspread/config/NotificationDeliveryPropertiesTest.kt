package io.premiumspread.config

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class NotificationDeliveryPropertiesTest {
    @Test
    fun `default timing relation is valid`() {
        NotificationDeliveryProperties()
    }

    @Test
    fun `maximum claim lifetime must be below stale threshold`() {
        assertThatThrownBy {
            NotificationDeliveryProperties(
                batchSize = 10,
                concurrency = 1,
                hardSendDeadline = Duration.ofSeconds(10),
                dbQueueSafetyMargin = Duration.ofSeconds(1),
                staleThreshold = Duration.ofSeconds(100),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("claim lifetime")
    }

    @Test
    fun `cooldown window must be representable in milliseconds`() {
        assertThatThrownBy {
            NotificationDeliveryProperties(cooldownWindow = Duration.ofNanos(1))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least 1ms")
    }
}
