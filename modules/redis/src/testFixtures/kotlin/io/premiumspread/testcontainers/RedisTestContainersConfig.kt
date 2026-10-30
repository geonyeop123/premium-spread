package io.premiumspread.testcontainers

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Redis Testcontainers 설정
 *
 * 테스트 JVM 하나가 Redis 컨테이너 하나를 공유한다. Spring context 가 여럿이어도 같은 인스턴스를 받는다 —
 * context 마다 새로 띄우면 `--parallel` 의 컨테이너 기동 경합에서 host port 대기 timeout 이 난다.
 * context 가 닫혀도 멈추지 않도록 `destroyMethod = ""` 로 둔다. 정리는 JVM 종료 시 Ryuk 가 맡는다.
 *
 * reuse 를 요청하지 않는다 — run 간·JVM 간 공유는 금지다. `~/.testcontainers.properties` 에 reuse 가 켜져
 * 있으면 api·batch 통합 테스트 JVM 이 같은 컨테이너를 공유해 한쪽의 `flushAll` 이 다른 쪽 데이터를 지운다.
 * JVM 안의 테스트 격리는 각 테스트의 `flushAll` 이 맡는다(같은 JVM 의 테스트 클래스는 순차 실행).
 */
@TestConfiguration(proxyBeanMethods = false)
class RedisTestContainersConfig {

    @Bean(destroyMethod = "")
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> = container

    companion object {
        private val container: GenericContainer<*> by lazy {
            GenericContainer(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .apply { start() }
        }
    }
}
