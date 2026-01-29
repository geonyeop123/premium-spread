package io.premiumspread.monitoring

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory

/**
 * 애플리케이션 기본 헬스 인디케이터
 *
 * Redis 헬스체크는 Spring Boot Actuator의 자동 설정으로 처리됨
 * (spring-boot-starter-data-redis 의존성 시 자동 등록)
 */
@Component
class ApplicationHealthIndicator : HealthIndicator {

    override fun health(): Health {
        val runtime = Runtime.getRuntime()
        val memoryBean = ManagementFactory.getMemoryMXBean()

        return Health.up()
            .withDetail("freeMemory", formatBytes(runtime.freeMemory()))
            .withDetail("totalMemory", formatBytes(runtime.totalMemory()))
            .withDetail("maxMemory", formatBytes(runtime.maxMemory()))
            .withDetail("heapUsage", memoryBean.heapMemoryUsage.toString())
            .withDetail("processors", runtime.availableProcessors())
            .build()
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        return "${mb}MB"
    }
}
