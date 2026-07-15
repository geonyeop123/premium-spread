package io.premiumspread.infrastructure.batch.websocket

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.net.URI
import java.time.Duration

@Validated
@ConfigurationProperties(prefix = "market-streams")
data class WebSocketStreamProperties(
    @field:Valid val connection: Connection = Connection(),
    @field:Valid val binance: Stream = Stream(
        URI.create("wss://fstream.binance.com/public/ws/btcusdt@bookTicker"),
        "BTC",
        "USDT",
    ),
    @field:Valid val bithumb: Stream = Stream(
        URI.create("wss://pubwss.bithumb.com/pub/ws"),
        "BTC",
        "KRW",
    ),
) {
    data class Connection(
        val firstMessageTimeout: Duration = Duration.ofSeconds(5),
        val idleTimeout: Duration = Duration.ofSeconds(60),
        val initialBackoff: Duration = Duration.ofSeconds(1),
        val maxBackoff: Duration = Duration.ofSeconds(30),
        val watchdogInterval: Duration = Duration.ofSeconds(5),
    ) {
        init {
            require(firstMessageTimeout.isPositive()) { "market-streams.connection.first-message-timeout must be positive" }
            require(idleTimeout > firstMessageTimeout) {
                "market-streams.connection.idle-timeout must exceed first-message-timeout"
            }
            require(initialBackoff.isPositive()) { "market-streams.connection.initial-backoff must be positive" }
            require(maxBackoff >= initialBackoff) { "market-streams.connection.max-backoff must be >= initial-backoff" }
            require(watchdogInterval.isPositive()) { "market-streams.connection.watchdog-interval must be positive" }
        }
    }

    data class Stream(val endpoint: URI, @field:NotBlank val symbol: String, @field:NotBlank val quote: String) {
        init {
            require(endpoint.scheme == "ws" || endpoint.scheme == "wss") { "market stream endpoint must use ws or wss" }
            require(endpoint.host != null) { "market stream endpoint must be absolute" }
        }

        fun pairCode(): String = "${symbol.uppercase()}_${quote.uppercase()}"
    }
}
