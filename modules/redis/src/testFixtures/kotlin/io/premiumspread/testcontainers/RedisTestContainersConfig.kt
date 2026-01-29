package io.premiumspread.testcontainers

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Redis Testcontainers 설정
 *
 * 테스트에서 실제 Redis 컨테이너 사용
 */
@TestConfiguration(proxyBeanMethods = false)
class RedisTestContainersConfig {

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true)
}
