package io.premiumspread.infrastructure.batch.exchange.binance

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object BinanceRequestSigner {
    /** HMAC-SHA256(secretKey, query) 를 소문자 hex 로 낸다. */
    fun sign(secretKey: String, query: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(query.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
