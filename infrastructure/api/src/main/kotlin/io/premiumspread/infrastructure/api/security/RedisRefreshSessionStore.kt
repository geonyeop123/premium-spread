package io.premiumspread.infrastructure.api.security

import io.premiumspread.domain.auth.RefreshRotationResult
import io.premiumspread.domain.auth.RefreshSession
import io.premiumspread.domain.auth.RefreshSessionProof
import io.premiumspread.domain.auth.RefreshSessionStore
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration
import java.time.Instant

class RedisRefreshSessionStore(
    private val redisTemplate: StringRedisTemplate,
    private val properties: RefreshProperties,
) : RefreshSessionStore {
    override fun replace(session: RefreshSession, replacedAt: Instant) {
        val ttl = remainingTtl(session.expiresAt, replacedAt)
        redisTemplate.execute(
            REPLACE_SCRIPT,
            listOf(key(session.memberId)),
            session.memberId.toString(),
            session.tokenHash,
            session.tokenId,
            session.familyId,
            session.generation.toString(),
            session.expiresAt.toEpochMilli().toString(),
            ttl.toMillis().toString(),
        ) ?: error("Redis refresh session replace returned no result")
    }

    override fun rotate(
        proof: RefreshSessionProof,
        replacement: RefreshSession,
        rotatedAt: Instant,
    ): RefreshRotationResult {
        require(proof.memberId == replacement.memberId) { "replacement memberId mismatch" }
        require(proof.familyId == replacement.familyId) { "replacement familyId mismatch" }
        require(replacement.generation == proof.generation + 1) { "replacement generation must increment by one" }
        val ttl = remainingTtl(replacement.expiresAt, rotatedAt)
        val code = redisTemplate.execute(
            ROTATE_SCRIPT,
            listOf(key(proof.memberId)),
            proof.memberId.toString(),
            proof.tokenHash,
            proof.tokenId,
            proof.familyId,
            proof.generation.toString(),
            replacement.tokenHash,
            replacement.tokenId,
            replacement.generation.toString(),
            replacement.expiresAt.toEpochMilli().toString(),
            ttl.toMillis().toString(),
            properties.concurrentGraceMs.toString(),
        ) ?: return RefreshRotationResult.INVALID_SESSION
        return when (code) {
            1L -> RefreshRotationResult.ROTATED
            2L -> RefreshRotationResult.CONCURRENT_LOSER
            3L -> RefreshRotationResult.REUSED_AND_FAMILY_REVOKED
            4L -> RefreshRotationResult.STALE_LOGIN_FAMILY
            5L -> RefreshRotationResult.SESSION_NOT_FOUND
            else -> RefreshRotationResult.INVALID_SESSION
        }
    }

    override fun revoke(proof: RefreshSessionProof, revokedAt: Instant): Boolean {
        val code = redisTemplate.execute(
            REVOKE_SCRIPT,
            listOf(key(proof.memberId)),
            proof.familyId,
            proof.generation.toString(),
            proof.tokenHash,
            proof.tokenId,
            proof.memberId.toString(),
        )
        return code == 1L
    }

    private fun key(memberId: Long): String = "${properties.keyPrefix}:{$memberId}"

    private fun remainingTtl(expiresAt: Instant, now: Instant): Duration =
        Duration.between(now, expiresAt).also { require(!it.isNegative && !it.isZero) { "refresh session already expired" } }

    private companion object {
        val REPLACE_SCRIPT = script("refresh-session-replace.lua")
        val ROTATE_SCRIPT = script("refresh-session-rotate.lua")
        val REVOKE_SCRIPT = script("refresh-session-revoke.lua")

        fun script(name: String): DefaultRedisScript<Long> = DefaultRedisScript<Long>().apply {
            setLocation(ClassPathResource("io/premiumspread/infrastructure/api/security/$name"))
            resultType = Long::class.javaObjectType
        }
    }
}
