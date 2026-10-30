package io.premiumspread.testcontainers

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Redis Testcontainers 설정
 *
 * 테스트 JVM 하나가 Redis 컨테이너 하나를 공유한다. Spring context 가 여럿이어도 같은 인스턴스에 붙는다 —
 * context 마다 새로 띄우면 `--parallel` 의 컨테이너 기동 경합에서 host port 대기 timeout 이 난다.
 *
 * 컨테이너를 bean 으로 노출하지 않는다. `Startable` bean 은 `destroyMethod` 와 무관하게
 * spring-boot-testcontainers 의 lifecycle post-processor 가 context close 때 `close()` 한다. 그러면 이후
 * `start()` 가 새 port 의 새 컨테이너를 띄우고, 캐시에 남은 다른 context 의 Lettuce/Redisson 연결은 옛
 * port 를 본다(context 로드 실패·context cache LRU 축출·`@DirtiesContext` 가 모두 이 경로를 탄다).
 * 그래서 컨테이너는 companion 에서 JVM 당 한 번 시작하고, context 에는 [DynamicPropertyRegistrar] 로
 * `spring.data.redis.host`/`port` 만 넘긴다. 직접 client 를 만드는 테스트 설정은 [host]·[port] 를 쓴다.
 * 정리는 JVM 종료 시 Ryuk 가 맡는다.
 *
 * reuse 를 요청하지 않는다 — run 간·JVM 간 공유는 금지다. `~/.testcontainers.properties` 에 reuse 가 켜져
 * 있으면 api·batch 통합 테스트 JVM 이 같은 컨테이너를 공유해 한쪽의 `flushAll` 이 다른 쪽 데이터를 지운다.
 * JVM 안의 테스트 격리는 각 테스트의 `flushAll` 이 맡는다(같은 JVM 의 테스트 클래스는 순차 실행).
 */
@TestConfiguration(proxyBeanMethods = false)
class RedisTestContainersConfig {

    @Bean
    fun redisContainerProperties(): DynamicPropertyRegistrar = DynamicPropertyRegistrar { registry ->
        registry.add("spring.data.redis.host") { host() }
        registry.add("spring.data.redis.port") { port() }
    }

    companion object {
        private val container: GenericContainer<*> by lazy {
            GenericContainer(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .apply { start() }
        }

        /** JVM 공용 Redis 컨테이너 host. 첫 호출이 컨테이너를 시작한다. */
        fun host(): String = container.host

        /** JVM 공용 Redis 컨테이너의 6379 mapped port. 첫 호출이 컨테이너를 시작한다. */
        fun port(): Int = container.getMappedPort(6379)
    }
}
