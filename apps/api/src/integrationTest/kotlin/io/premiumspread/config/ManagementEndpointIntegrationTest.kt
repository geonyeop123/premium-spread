package io.premiumspread.config

import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.server.address=127.0.0.1",
        "management.server.port=0",
    ],
)
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, TestConfig::class)
class ManagementEndpointIntegrationTest {
    @LocalServerPort
    private var applicationPort: Int = 0

    @LocalManagementPort
    private var managementPort: Int = 0

    private val client = HttpClient.newHttpClient()

    @Test
    fun `readiness와 Prometheus는 별도 management port에서 실제 응답한다`() {
        assertThat(managementPort).isNotEqualTo(applicationPort)

        val readiness = get(managementPort, "/actuator/health/readiness")
        val prometheus = get(managementPort, "/actuator/prometheus")
        val applicationPrometheus = get(applicationPort, "/actuator/prometheus")

        assertThat(readiness.statusCode()).isEqualTo(200)
        assertThat(readiness.body()).contains("\"status\":\"UP\"")
        assertThat(prometheus.statusCode()).isEqualTo(200)
        assertThat(prometheus.body()).contains("# HELP")
        assertThat(applicationPrometheus.statusCode()).isEqualTo(404)
    }

    private fun get(port: Int, path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
}
