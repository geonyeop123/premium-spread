package io.premiumspread.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

class Phase1ConfigurationContractTest {

    @Test
    fun `공통 JPA 설정은 schema를 자동 변경하지 않는다`() {
        val yaml = ClassPathResource("jpa.yml").inputStream.bufferedReader().use { it.readText() }

        assertThat(yaml).doesNotContain("ddl-auto: update")
        assertThat(yaml).contains("ddl-auto: validate")
    }

    @Test
    fun `공통 JDBC와 Hibernate 시간대는 UTC로 고정한다`() {
        val yaml = ClassPathResource("jpa.yml").inputStream.bufferedReader().use { it.readText() }

        assertThat(yaml).contains("jdbc.time_zone: UTC")
        assertThat(yaml).contains("connectionTimeZone=UTC")
        assertThat(yaml).contains("forceConnectionTimeZoneToSession=true")
    }

    @Test
    fun `datasource와 Hikari pool은 spring datasource 단일 설정을 사용한다`() {
        val yaml = ClassPathResource("jpa.yml").inputStream.bufferedReader().use { it.readText() }

        assertThat(yaml).contains("datasource:", "hikari:", "DB_POOL_MAX_SIZE", "DB_POOL_MIN_IDLE")
        assertThat(yaml).doesNotContain("datasource.mysql-jpa", "mysql-jpa:")
    }

    @Test
    fun `SQL 출력은 local과 test에서만 활성화한다`() {
        val yaml = ClassPathResource("jpa.yml").inputStream.bufferedReader().use { it.readText() }
        val common = yaml.substringBefore("---")
        val local = yaml.substringAfter("on-profile: local").substringBefore("---")
        val test = yaml.substringAfter("on-profile: test").substringBefore("---")
        val dev = yaml.substringAfter("on-profile: dev").substringBefore("---")

        assertThat(common).contains("show-sql: false")
        assertThat(local).contains("show-sql: true")
        assertThat(test).contains("show-sql: true")
        assertThat(dev).doesNotContain("show-sql: true")
    }

    @Test
    fun `API management endpoint는 application port와 분리되고 loopback에만 bind한다`() {
        val yaml = ClassPathResource("application.yml").inputStream.bufferedReader().use { it.readText() }

        assertThat(yaml).contains(
            "address: \${MANAGEMENT_ADDRESS:127.0.0.1}",
            "port: \${MANAGEMENT_PORT:9080}",
            "include: health,prometheus",
        )
    }

    @Test
    fun `API와 Batch는 동일한 aggregation zone 환경변수 계약을 사용한다`() {
        val yaml = ClassPathResource("application.yml").inputStream.bufferedReader().use { it.readText() }

        assertThat(yaml).contains("zone: \${AGGREGATION_ZONE:Asia/Seoul}")
    }

    @Test
    fun `prd에서는 API 문서와 상세 health 정보를 공개하지 않는다`() {
        val yaml = ClassPathResource("application-prd.yml").inputStream.bufferedReader().use { it.readText() }

        assertThat(yaml).contains("api-docs:", "swagger-ui:", "enabled: false", "show-details: never")
    }

    @Test
    fun `JWT 기본 secret과 token 정책은 local과 test 밖의 공통 설정에 존재하지 않는다`() {
        val yaml = ClassPathResource("application.yml").inputStream.bufferedReader().use { it.readText() }
        val common = yaml.substringBefore("---")

        assertThat(common).contains("secret-key: \${JWT_SECRET_KEY:}")
        assertThat(common).contains("access-token-expiry-ms: \${JWT_ACCESS_TOKEN_EXPIRY_MS:}")
        assertThat(common).contains("refresh-token-expiry-ms: \${JWT_REFRESH_TOKEN_EXPIRY_MS:}")
        assertThat(common).contains("clock-skew-seconds: \${JWT_CLOCK_SKEW_SECONDS:}")
        assertThat(common).doesNotContain("default-local-secret")
        assertThat(yaml).contains("on-profile: local", "default-local-secret")
    }
}
