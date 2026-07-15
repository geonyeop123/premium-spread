package io.premiumspread.infrastructure.batch

import io.micrometer.core.instrument.MeterRegistry
import io.premiumspread.domain.batch.BatchMarket
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.market.LatestMarketTickReadPort
import io.premiumspread.domain.market.TickerFlushObserver
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.domain.notification.NotificationDeliveryPort
import io.premiumspread.domain.notification.NotificationSender
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumThresholdEvaluator
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.email.EmailAutoConfiguration
import io.premiumspread.email.EmailSender
import io.premiumspread.email.SmtpConnectionProperties
import io.premiumspread.infrastructure.batch.alert.BoundedOperatorAlertAdapter
import io.premiumspread.infrastructure.batch.alert.OperatorAlertExecutorProperties
import io.premiumspread.infrastructure.batch.cache.FxCacheService
import io.premiumspread.infrastructure.batch.cache.FxRateReadAdapter
import io.premiumspread.infrastructure.batch.cache.PremiumAggregateAdapter
import io.premiumspread.infrastructure.batch.cache.PremiumAggregationCacheOperationsImpl
import io.premiumspread.infrastructure.batch.cache.PremiumCacheService
import io.premiumspread.infrastructure.batch.cache.PremiumSecondsCacheOperationsImpl
import io.premiumspread.infrastructure.batch.cache.PremiumSummaryCacheOperationsImpl
import io.premiumspread.infrastructure.batch.cache.TickerAggregateAdapter
import io.premiumspread.infrastructure.batch.cache.TickerCacheService
import io.premiumspread.infrastructure.batch.cache.TickerReadAdapter
import io.premiumspread.infrastructure.batch.exchange.ExchangeRateClient
import io.premiumspread.infrastructure.batch.exchange.ExchangeRateClientProperties
import io.premiumspread.infrastructure.batch.exchange.ExchangeRateWriteAdapter
import io.premiumspread.infrastructure.batch.exchange.ExchangeWebClientConfig
import io.premiumspread.infrastructure.batch.exchange.binance.BinanceWebSocketClient
import io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbWebSocketClient
import io.premiumspread.infrastructure.batch.ingestion.LatestMarketTickReaderAdapter
import io.premiumspread.infrastructure.batch.ingestion.binance.BinanceTickerIngestion
import io.premiumspread.infrastructure.batch.ingestion.bithumb.BithumbTickerIngestion
import io.premiumspread.infrastructure.batch.job.RedisJobRunRecorderAdapter
import io.premiumspread.infrastructure.batch.job.RedisJobLockAdapter
import io.premiumspread.infrastructure.batch.notification.DurableEmailNotificationSender
import io.premiumspread.infrastructure.batch.notification.NotificationDeliveryDeadlineProperties
import io.premiumspread.infrastructure.batch.notification.NotificationDeliveryMetrics
import io.premiumspread.infrastructure.batch.notification.NotificationDeliveryStartupValidator
import io.premiumspread.infrastructure.batch.notification.ObservedNotificationDeliveryPort
import io.premiumspread.infrastructure.batch.observability.AggregationZoneMetricProperties
import io.premiumspread.infrastructure.batch.observability.AggregationZoneMetrics
import io.premiumspread.infrastructure.batch.observability.BatchIngestionReadinessHealth
import io.premiumspread.infrastructure.batch.websocket.WebSocketMetrics
import io.premiumspread.infrastructure.batch.websocket.WebSocketTickerFlushObserver
import io.premiumspread.infrastructure.batch.websocket.WebSocketStreamProperties
import io.premiumspread.infrastructure.common.CommonInfrastructureAutoConfiguration
import io.premiumspread.infrastructure.common.persistence.jdbc.exchangerate.JdbcExchangeRateWriteRepository
import io.premiumspread.infrastructure.common.persistence.jdbc.notification.JdbcNotificationDeliveryRepository
import io.premiumspread.monitoring.AlertService
import io.premiumspread.monitoring.MonitoringAutoConfiguration
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.validation.annotation.Validated

@AutoConfiguration(
    after = [
        CommonInfrastructureAutoConfiguration::class,
        MonitoringAutoConfiguration::class,
        EmailAutoConfiguration::class,
    ],
)
@EnableConfigurationProperties(
    BatchMarketProperties::class,
    WebSocketStreamProperties::class,
    AggregationZoneMetricProperties::class,
)
@Import(
    BatchCacheAdapterConfiguration::class,
    BatchExchangeAdapterConfiguration::class,
    BatchJobSupportConfiguration::class,
    BatchNotificationAdapterConfiguration::class,
    BatchNotificationDisabledConfiguration::class,
    BatchStreamingAdapterConfiguration::class,
    BatchTestStreamingFallbackConfiguration::class,
)
class BatchInfrastructureAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(BatchMarketProvider::class)
    fun batchMarketProvider(properties: BatchMarketProperties): BatchMarketProvider =
        BatchMarketProvider {
            val symbol = Symbol(properties.symbol)
            BatchMarket(
                pair = MarketPair(symbol, properties.koreaExchange, properties.foreignExchange),
                koreaQuote = Quote.coin(symbol, properties.koreaQuote),
                foreignQuote = Quote.coin(symbol, properties.foreignQuote),
                fxBase = properties.fxBase,
                fxQuote = properties.fxQuote,
            )
        }

    @Bean
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean(AggregationZoneMetrics::class)
    fun aggregationZoneMetrics(properties: AggregationZoneMetricProperties): AggregationZoneMetrics =
        AggregationZoneMetrics(properties)

    @Bean
    @ConditionalOnBean(WebSocketMetrics::class)
    @ConditionalOnMissingBean(TickerFlushObserver::class)
    fun tickerFlushObserver(metrics: WebSocketMetrics): TickerFlushObserver = WebSocketTickerFlushObserver(metrics)

    @Bean
    fun batchMarketStreamConsistencyValidator(
        market: BatchMarketProperties,
        streams: WebSocketStreamProperties,
    ) = BatchMarketStreamConsistencyValidator(market, streams)
}

class BatchMarketStreamConsistencyValidator(market: BatchMarketProperties, streams: WebSocketStreamProperties) {
    init {
        require(streams.binance.symbol.equals(market.symbol, ignoreCase = true)) {
            "market-streams.binance.symbol must match batch.market.symbol"
        }
        require(streams.bithumb.symbol.equals(market.symbol, ignoreCase = true)) {
            "market-streams.bithumb.symbol must match batch.market.symbol"
        }
        require(streams.bithumb.quote.equals(market.koreaQuote.code, ignoreCase = true)) {
            "market-streams.bithumb.quote must match batch.market.korea-quote"
        }
        val supportedForeignQuote = streams.binance.quote.equals(market.foreignQuote.code, ignoreCase = true) ||
            (market.foreignQuote == Currency.USD && streams.binance.quote.equals("USDT", ignoreCase = true))
        require(supportedForeignQuote) {
            "market-streams.binance.quote must match batch.market.foreign-quote or its USDT/USD normalization"
        }
        val rawPair = "${streams.binance.symbol}${streams.binance.quote}".lowercase()
        require(streams.binance.endpoint.path.lowercase().contains(rawPair)) {
            "market-streams.binance.endpoint must contain configured raw pair $rawPair"
        }
    }
}

@Validated
@ConfigurationProperties(prefix = "batch.market")
data class BatchMarketProperties(
    @field:NotBlank val symbol: String = "BTC",
    val koreaExchange: Exchange = Exchange.BITHUMB,
    val koreaQuote: Currency = Currency.KRW,
    val foreignExchange: Exchange = Exchange.BINANCE,
    val foreignQuote: Currency = Currency.USD,
    val fxBase: Currency = Currency.USD,
    val fxQuote: Currency = Currency.KRW,
)

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(StringRedisTemplate::class)
@Import(
    FxCacheService::class,
    PremiumAggregateAdapter::class,
    PremiumAggregationCacheOperationsImpl::class,
    PremiumCacheService::class,
    PremiumSecondsCacheOperationsImpl::class,
    PremiumSummaryCacheOperationsImpl::class,
    TickerAggregateAdapter::class,
    TickerCacheService::class,
    FxRateReadAdapter::class,
    TickerReadAdapter::class,
)
class BatchCacheAdapterConfiguration

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(MeterRegistry::class, JdbcExchangeRateWriteRepository::class)
@EnableConfigurationProperties(ExchangeRateClientProperties::class)
@Import(ExchangeWebClientConfig::class, ExchangeRateClient::class, ExchangeRateWriteAdapter::class)
class BatchExchangeAdapterConfiguration

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(StringRedisTemplate::class, MeterRegistry::class, AlertService::class)
@EnableConfigurationProperties(OperatorAlertExecutorProperties::class)
@Import(BoundedOperatorAlertAdapter::class, RedisJobRunRecorderAdapter::class, RedisJobLockAdapter::class)
class BatchJobSupportConfiguration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "notification.email", name = ["enabled"], havingValue = "true")
@ConditionalOnBean(JdbcNotificationDeliveryRepository::class, EmailSender::class)
@EnableConfigurationProperties(NotificationDeliveryDeadlineProperties::class)
class BatchNotificationAdapterConfiguration {
    @Bean
    fun notificationDeliveryMetrics(
        meterRegistry: ObjectProvider<MeterRegistry>,
    ): NotificationDeliveryMetrics = NotificationDeliveryMetrics(meterRegistry.getIfAvailable())

    @Bean
    @Primary
    fun notificationDeliveryPort(
        repository: JdbcNotificationDeliveryRepository,
        metrics: NotificationDeliveryMetrics,
    ): NotificationDeliveryPort = ObservedNotificationDeliveryPort(repository, metrics)

    @Bean
    @ConditionalOnMissingBean(NotificationSender::class)
    fun notificationSender(emailSender: EmailSender): NotificationSender =
        DurableEmailNotificationSender(emailSender)

    @Bean
    fun notificationDeliveryStartupValidator(
        smtp: SmtpConnectionProperties,
        delivery: NotificationDeliveryDeadlineProperties,
    ): NotificationDeliveryStartupValidator = NotificationDeliveryStartupValidator(smtp, delivery.hardSendDeadline)

    @Bean
    @ConditionalOnMissingBean(PremiumThresholdEvaluator::class)
    fun noOpPremiumThresholdEvaluator(): PremiumThresholdEvaluator = PremiumThresholdEvaluator { }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "notification.email", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class BatchNotificationDisabledConfiguration {
    @Bean
    @ConditionalOnMissingBean(PremiumThresholdEvaluator::class)
    fun noOpPremiumThresholdEvaluator(): PremiumThresholdEvaluator = PremiumThresholdEvaluator { }
}

@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnBean(StringRedisTemplate::class, MeterRegistry::class, OperatorAlert::class)
@Import(
    WebSocketMetrics::class,
    BatchIngestionReadinessHealth::class,
    BinanceTickerIngestion::class,
    BithumbTickerIngestion::class,
    BinanceWebSocketClient::class,
    BithumbWebSocketClient::class,
    LatestMarketTickReaderAdapter::class,
)
class BatchStreamingAdapterConfiguration

@Configuration(proxyBeanMethods = false)
@Profile("test")
class BatchTestStreamingFallbackConfiguration {
    @Bean
    @ConditionalOnMissingBean(LatestMarketTickReadPort::class)
    fun latestMarketTickReadPort(): LatestMarketTickReadPort = LatestMarketTickReadPort { _, _ -> null }
}
