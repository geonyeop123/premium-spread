package io.premiumspread.infrastructure.batch.exchange.bithumb

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object BithumbJwtSigner {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private const val HEADER = """{"alg":"HS256","typ":"JWT"}"""

    fun sign(accessKey: String, secretKey: String, query: String?, nonce: String, timestampMillis: Long): String {
        val payload = buildString {
            append("{\"access_key\":").append(quote(accessKey))
            append(",\"nonce\":").append(quote(nonce))
            append(",\"timestamp\":").append(timestampMillis)
            if (!query.isNullOrEmpty()) {
                append(",\"query_hash\":").append(quote(sha512Hex(query)))
                append(",\"query_hash_alg\":\"SHA512\"")
            }
            append('}')
        }
        val signingInput = encode(HEADER) + "." + encode(payload)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = encoder.encodeToString(mac.doFinal(signingInput.toByteArray(Charsets.UTF_8)))
        return "$signingInput.$signature"
    }

    fun sha512Hex(value: String): String =
        MessageDigest.getInstance("SHA-512").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun encode(value: String): String = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
