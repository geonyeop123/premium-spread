package io.premiumspread.config

import io.premiumspread.interfaces.scheduling.BatchSchedulingProperties
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BatchZoneConsistencyValidatorTest {
    @Test
    fun `cron and aggregation bucket zones must match`() {
        assertThatThrownBy {
            BatchZoneConsistencyValidator(
                BatchSchedulingProperties(zone = "UTC"),
                AggregationProperties(zone = "Asia/Seoul"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
