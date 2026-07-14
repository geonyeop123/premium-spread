package io.premiumspread.config

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class BatchJobPropertiesTest {
    @Test
    fun `lease must exceed the declared execution timeout`() {
        assertThatThrownBy {
            BatchJobProperties.LockSpec(
                lockKey = "lock:test",
                lease = Duration.ofSeconds(5),
                executionTimeout = Duration.ofSeconds(5),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
