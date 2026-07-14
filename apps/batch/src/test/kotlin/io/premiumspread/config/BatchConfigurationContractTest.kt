package io.premiumspread.config

import io.premiumspread.interfaces.scheduling.BatchSchedulingProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

class BatchConfigurationContractTest {
    @Test
    fun `Batch management endpoint는 application port와 분리되고 loopback에만 bind한다`() {
        val yaml = ClassPathResource("application.yml").inputStream.bufferedReader().use { it.readText() }

        assertThat(yaml).contains(
            "address: \${MANAGEMENT_ADDRESS:127.0.0.1}",
            "port: \${MANAGEMENT_PORT:9081}",
            "include: health,prometheus",
        )
    }

    @Test
    fun `scheduler와 aggregation은 동일한 명시적 zone 환경변수 계약을 사용한다`() {
        val yaml = ClassPathResource("application.yml").inputStream.bufferedReader().use { it.readText() }

        assertThat(yaml).contains(
            "zone: \${AGGREGATION_ZONE:Asia/Seoul}",
        )
        assertThat(yaml).doesNotContain("BATCH_SCHEDULING_ZONE")
    }

    @Test
    fun `잘못된 scheduler cron은 설정 binding 단계에서 거부한다`() {
        assertThatThrownBy {
            BatchSchedulingProperties.PremiumAggregation(minuteCron = "not-a-cron")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("minuteCron")
    }
}
