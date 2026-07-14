package io.premiumspread.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.DateTimeException
import java.time.ZoneId

class AggregationPropertiesTest {
    @Test
    fun `기본 집계 timezone은 Asia Seoul이다`() {
        assertThat(AggregationProperties().aggregationZone.zoneId).isEqualTo(ZoneId.of("Asia/Seoul"))
    }

    @Test
    fun `UTC timezone도 명시적으로 바인딩할 수 있다`() {
        assertThat(AggregationProperties("UTC").aggregationZone.zoneId).isEqualTo(ZoneId.of("UTC"))
    }

    @Test
    fun `유효하지 않은 timezone은 거부한다`() {
        assertThatThrownBy { AggregationProperties("not-a-zone") }
            .isInstanceOf(DateTimeException::class.java)
    }
}
