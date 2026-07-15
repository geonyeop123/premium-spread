package io.premiumspread.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

class Phase1BatchConfigurationContractTest {

    @Test
    fun `Batch는 Flyway를 실행하지 않는다`() {
        val yaml = ClassPathResource("application.yml").inputStream.bufferedReader().use { it.readText() }

        assertThat(yaml).contains("flyway:", "enabled: false")
    }

    @Test
    fun `공개 actuator는 health만 노출하고 상세 정보를 숨긴다`() {
        val yaml = ClassPathResource("application.yml").inputStream.bufferedReader().use { it.readText() }

        assertThat(yaml).contains("port: \${MANAGEMENT_PORT:9081}", "include: health", "show-details: never")
        assertThat(yaml).doesNotContain("include: health,info,prometheus,metrics")
    }
}
