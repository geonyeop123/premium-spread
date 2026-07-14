package io.premiumspread.infrastructure.api.security

import io.premiumspread.domain.auth.RefreshRotationResult
import io.premiumspread.domain.auth.RefreshSession
import io.premiumspread.domain.auth.RefreshSessionProof
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@Testcontainers
class RedisRefreshSessionStoreIntegrationTest {
    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var store: RedisRefreshSessionStore
    private val hasher = HmacRefreshTokenHasher(PROPERTIES)

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(
            RedisStandaloneConfiguration(REDIS.host, REDIS.getMappedPort(6379)),
        ).apply { afterPropertiesSet() }
        redisTemplate = StringRedisTemplate(connectionFactory).apply { afterPropertiesSet() }
        redisTemplate.execute { it.serverCommands().flushAll() }
        store = RedisRefreshSessionStore(redisTemplate, PROPERTIES)
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `replace는 원문을 저장하지 않고 새 로그인 family로 기존 session을 교체한다`() {
        val first = session(raw = "first-raw-refresh", family = "family-a", generation = 0)
        val second = session(raw = "second-raw-refresh", family = "family-b", generation = 0)

        store.replace(first, NOW)
        store.replace(second, NOW.plusSeconds(1))

        val entries = redisTemplate.opsForHash<String, String>().entries(KEY)
        assertThat(entries["familyId"]).isEqualTo("family-b")
        assertThat(entries["memberId"]).isEqualTo(MEMBER_ID.toString())
        assertThat(entries["currentHash"]).isEqualTo(second.tokenHash)
        assertThat(entries.values).noneMatch { it.contains("first-raw-refresh") || it.contains("second-raw-refresh") }
    }

    @Test
    fun `동일 refresh의 동시 회전은 하나만 성공하고 승자 session은 다음 회전에 성공한다`() {
        val original = session(raw = "original", family = "family", generation = 0)
        val candidates = listOf(
            session(raw = "candidate-a", family = "family", generation = 1, tokenId = "jti-a"),
            session(raw = "candidate-b", family = "family", generation = 1, tokenId = "jti-b"),
        )
        store.replace(original, NOW)
        val proof = original.toProof()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val futures = candidates.map { candidate ->
            executor.submit<Pair<RefreshSession, RefreshRotationResult>> {
                start.await()
                candidate to store.rotate(proof, candidate, NOW.plusSeconds(1))
            }
        }
        start.countDown()
        val results = futures.map { it.get() }
        executor.shutdownNow()

        assertThat(results.map { it.second }).containsExactlyInAnyOrder(
            RefreshRotationResult.ROTATED,
            RefreshRotationResult.CONCURRENT_LOSER,
        )
        val winner = results.single { it.second == RefreshRotationResult.ROTATED }.first
        val loser = results.single { it.second == RefreshRotationResult.CONCURRENT_LOSER }.first
        assertThat(store.revoke(loser.toProof(), NOW.plusSeconds(1))).isFalse()
        val next = session(raw = "winner-next", family = "family", generation = 2, tokenId = "jti-next")
        assertThat(store.rotate(winner.toProof(), next, NOW.plusSeconds(2)))
            .isEqualTo(RefreshRotationResult.ROTATED)
    }

    @Test
    fun `grace 이후 같은 family의 이전 token 재사용은 현재 family를 폐기한다`() {
        val original = session(raw = "original", family = "family", generation = 0)
        val rotated = session(raw = "rotated", family = "family", generation = 1)
        store.replace(original, NOW)
        assertThat(store.rotate(original.toProof(), rotated, NOW.plusSeconds(1)))
            .isEqualTo(RefreshRotationResult.ROTATED)
        redisTemplate.opsForHash<String, String>().put(KEY, "rotatedAt", "0")

        assertThat(store.rotate(original.toProof(), rotated.copy(tokenId = "unused"), NOW.plusSeconds(4)))
            .isEqualTo(RefreshRotationResult.REUSED_AND_FAMILY_REVOKED)
        assertThat(redisTemplate.hasKey(KEY)).isFalse()
    }

    @Test
    fun `서로 다른 API node 시계는 Redis 기준 grace 판정을 바꾸지 않는다`() {
        val original = session(raw = "original", family = "family", generation = 0)
        val winner = session(raw = "winner", family = "family", generation = 1, tokenId = "winner-jti")
        val loser = session(raw = "loser", family = "family", generation = 1, tokenId = "loser-jti")
        store.replace(original, NOW)

        assertThat(store.rotate(original.toProof(), winner, NOW.minusSeconds(30)))
            .isEqualTo(RefreshRotationResult.ROTATED)
        assertThat(store.rotate(original.toProof(), loser, NOW.plusSeconds(30)))
            .isEqualTo(RefreshRotationResult.CONCURRENT_LOSER)
        val next = session(raw = "next", family = "family", generation = 2, tokenId = "next-jti")
        assertThat(store.rotate(winner.toProof(), next, NOW.plusSeconds(30)))
            .isEqualTo(RefreshRotationResult.ROTATED)
    }

    @Test
    fun `Redis 시간이 역행한 흔적은 grace로 허용하지 않고 fail closed한다`() {
        val original = session(raw = "original", family = "family", generation = 0)
        val rotated = session(raw = "rotated", family = "family", generation = 1)
        store.replace(original, NOW)
        assertThat(store.rotate(original.toProof(), rotated, NOW))
            .isEqualTo(RefreshRotationResult.ROTATED)
        redisTemplate.opsForHash<String, String>().put(KEY, "rotatedAt", Long.MAX_VALUE.toString())

        assertThat(store.rotate(original.toProof(), rotated.copy(tokenId = "unused"), NOW))
            .isEqualTo(RefreshRotationResult.REUSED_AND_FAMILY_REVOKED)
        assertThat(redisTemplate.hasKey(KEY)).isFalse()
    }

    @Test
    fun `이전 로그인 family는 현재 family를 회전하거나 logout으로 revoke하지 못한다`() {
        val old = session(raw = "old-login", family = "old-family", generation = 0)
        val current = session(raw = "current-login", family = "current-family", generation = 0)
        store.replace(old, NOW)
        store.replace(current, NOW.plusSeconds(1))

        assertThat(
            store.rotate(
                old.toProof(),
                session(raw = "old-next", family = "old-family", generation = 1),
                NOW.plusSeconds(2),
            ),
        ).isEqualTo(RefreshRotationResult.STALE_LOGIN_FAMILY)
        assertThat(store.revoke(old.toProof(), NOW.plusSeconds(2))).isFalse()
        assertThat(redisTemplate.opsForHash<String, String>().get(KEY, "familyId")).isEqualTo("current-family")

        assertThat(store.revoke(current.toProof(), NOW.plusSeconds(3))).isTrue()
        assertThat(redisTemplate.hasKey(KEY)).isFalse()
    }

    @Test
    fun `회전과 경합한 logout은 같은 family의 직전 token으로 현재 session을 revoke한다`() {
        val original = session(raw = "original", family = "family", generation = 0)
        val rotated = session(raw = "rotated", family = "family", generation = 1)
        store.replace(original, NOW)
        assertThat(store.rotate(original.toProof(), rotated, NOW.plusSeconds(1)))
            .isEqualTo(RefreshRotationResult.ROTATED)

        assertThat(store.revoke(original.toProof(), NOW.plusSeconds(1))).isTrue()
        assertThat(redisTemplate.hasKey(KEY)).isFalse()
    }

    @Test
    fun `key의 memberId field가 proof와 다르면 손상 session을 fail-closed로 제거한다`() {
        val current = session(raw = "current", family = "family", generation = 0)
        store.replace(current, NOW)
        redisTemplate.opsForHash<String, String>().put(KEY, "memberId", "999")

        val result = store.rotate(
            current.toProof(),
            session(raw = "next", family = "family", generation = 1),
            NOW.plusSeconds(1),
        )

        assertThat(result).isEqualTo(RefreshRotationResult.INVALID_SESSION)
        assertThat(redisTemplate.hasKey(KEY)).isFalse()
    }

    private fun session(
        raw: String,
        family: String,
        generation: Long,
        tokenId: String = "jti-$family-$generation",
    ): RefreshSession = RefreshSession(
        tokenHash = hasher.hash(raw),
        tokenId = tokenId,
        memberId = MEMBER_ID,
        expiresAt = NOW.plusSeconds(3_600),
        familyId = family,
        generation = generation,
    )

    private fun RefreshSession.toProof(): RefreshSessionProof = RefreshSessionProof(
        tokenHash = tokenHash,
        tokenId = tokenId,
        memberId = memberId,
        familyId = familyId,
        generation = generation,
    )

    companion object {
        private const val MEMBER_ID = 1L
        private const val KEY = "auth:refresh:{1}"
        private val NOW = Instant.parse("2026-07-14T03:00:00Z")
        private val PROPERTIES = RefreshProperties(
            hmacKey = "test-refresh-hmac-key-must-be-at-least-32-bytes!!",
            concurrentGraceMs = 2_000,
            keyPrefix = "auth:refresh",
        )

        @Container
        @JvmField
        val REDIS: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
    }
}
