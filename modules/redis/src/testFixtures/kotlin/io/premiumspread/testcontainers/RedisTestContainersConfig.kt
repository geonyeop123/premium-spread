package io.premiumspread.testcontainers

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Redis Testcontainers 설정
 *
 * 테스트에서 실제 Redis 컨테이너 사용. Spring context 마다 새 컨테이너를 띄운다.
 * reuse 를 요청하지 않는다 — `~/.testcontainers.properties` 에 reuse 가 켜져 있으면 api·batch 통합 테스트
 * JVM 이 같은 컨테이너를 공유해, `--parallel` 에서 한쪽의 `flushAll` 이 다른 쪽 데이터를 지운다.
 */
@TestConfiguration(proxyBeanMethods = false)
class RedisTestContainersConfig {

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
}
