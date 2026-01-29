package io.premiumspread.client.config

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit

@Configuration
class WebClientConfig {

    @Bean
    fun bithumbWebClient(): WebClient = createWebClient(
        baseUrl = "https://api.bithumb.com",
        name = "bithumb",
    )

    @Bean
    fun binanceWebClient(): WebClient = createWebClient(
        baseUrl = "https://fapi.binance.com",
        name = "binance",
    )

    @Bean
    fun exchangeRateWebClient(): WebClient = createWebClient(
        baseUrl = "https://v6.exchangerate-api.com",
        name = "exchangerate",
    )

    private fun createWebClient(
        baseUrl: String,
        name: String,
        connectTimeoutMs: Int = 5000,
        readTimeoutSeconds: Long = 10,
        writeTimeoutSeconds: Long = 10,
    ): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
            .responseTimeout(Duration.ofSeconds(readTimeoutSeconds))
            .doOnConnected { conn ->
                conn.addHandlerLast(ReadTimeoutHandler(readTimeoutSeconds, TimeUnit.SECONDS))
                conn.addHandlerLast(WriteTimeoutHandler(writeTimeoutSeconds, TimeUnit.SECONDS))
            }

        return WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .defaultHeader("User-Agent", "PremiumSpread-Batch/1.0")
            .build()
    }
}
