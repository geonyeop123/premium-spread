package io.premiumspread.infrastructure.batch.exchange

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.util.concurrent.TimeUnit

@Configuration(proxyBeanMethods = false)
class ExchangeWebClientConfig {
    @Bean
    fun exchangeRateWebClient(properties: ExchangeRateClientProperties): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeout.toMillis().toInt())
            .responseTimeout(properties.readTimeout)
            .doOnConnected { connection ->
                connection.addHandlerLast(ReadTimeoutHandler(properties.readTimeout.toMillis(), TimeUnit.MILLISECONDS))
            }

        return WebClient.builder()
            .baseUrl(properties.endpoint.toASCIIString().removeSuffix("/"))
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .defaultHeader("User-Agent", "PremiumSpread-Batch/1.0")
            .build()
    }
}
