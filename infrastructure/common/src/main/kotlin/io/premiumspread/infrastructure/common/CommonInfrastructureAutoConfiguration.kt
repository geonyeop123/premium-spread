package io.premiumspread.infrastructure.common

import io.premiumspread.config.jpa.JpaAuditingAutoConfiguration
import io.premiumspread.config.jpa.JpaFoundationAutoConfiguration
import io.premiumspread.domain.BaseEntity
import io.premiumspread.infrastructure.common.cache.AfterCommitCacheExecutor
import io.premiumspread.infrastructure.common.cache.MicrometerCacheReadMetrics
import io.premiumspread.infrastructure.common.cache.exchangerate.FxCacheReader
import io.premiumspread.infrastructure.common.cache.exchangerate.FxCacheWriter
import io.premiumspread.infrastructure.common.cache.premium.PremiumAggregationCacheReader
import io.premiumspread.infrastructure.common.cache.premium.PremiumCacheReader
import io.premiumspread.infrastructure.common.cache.premium.PremiumCacheWriter
import io.premiumspread.infrastructure.common.cache.ticker.TickerCacheReader
import io.premiumspread.infrastructure.common.cache.ticker.TickerCacheWriter
import io.premiumspread.infrastructure.common.config.AggregationInfrastructureProperties
import io.premiumspread.infrastructure.common.persistence.CommonPersistenceMarker
import io.premiumspread.infrastructure.common.persistence.jdbc.exchangerate.ExchangeRateQueryRepository
import io.premiumspread.infrastructure.common.persistence.jdbc.exchangerate.JdbcExchangeRateRepositoryAdapter
import io.premiumspread.infrastructure.common.persistence.jdbc.exchangerate.JdbcExchangeRateWriteRepository
import io.premiumspread.infrastructure.common.persistence.jdbc.notification.ActiveSubscriptionReadRepository
import io.premiumspread.infrastructure.common.persistence.jdbc.notification.JdbcNotificationDeliveryRepository
import io.premiumspread.infrastructure.common.persistence.jdbc.premium.PremiumAggregationQueryRepository
import io.premiumspread.infrastructure.common.persistence.jdbc.premium.PremiumAggregationRepository
import io.premiumspread.infrastructure.common.persistence.jdbc.ticker.TickerAggregationQueryRepository
import io.premiumspread.infrastructure.common.persistence.jdbc.ticker.TickerAggregationRepository
import io.premiumspread.infrastructure.common.persistence.jpa.member.JpaMemberRepositoryAdapter
import io.premiumspread.infrastructure.common.persistence.jpa.notification.JpaNotificationSubscriptionRepositoryAdapter
import io.premiumspread.infrastructure.common.persistence.jpa.position.JpaPositionRepositoryAdapter
import io.premiumspread.infrastructure.common.persistence.jpa.premium.JpaPremiumRepositoryAdapter
import io.premiumspread.infrastructure.common.persistence.jpa.ticker.JpaTickerRepositoryAdapter
import io.premiumspread.redis.RedisFoundationAutoConfiguration
import io.premiumspread.redis.RedisTimeSeriesAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.EnableTransactionManagement

@AutoConfiguration(
    after = [
        DataSourceAutoConfiguration::class,
        JdbcTemplateAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        RedisAutoConfiguration::class,
        JpaFoundationAutoConfiguration::class,
        JpaAuditingAutoConfiguration::class,
        RedisFoundationAutoConfiguration::class,
        RedisTimeSeriesAutoConfiguration::class,
    ],
)
@EnableConfigurationProperties(AggregationInfrastructureProperties::class)
@Import(CommonJdbcConfiguration::class, CommonJpaConfiguration::class, CommonCacheConfiguration::class)
class CommonInfrastructureAutoConfiguration

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(JdbcTemplate::class)
@Import(
    ExchangeRateQueryRepository::class,
    JdbcExchangeRateRepositoryAdapter::class,
    JdbcExchangeRateWriteRepository::class,
    ActiveSubscriptionReadRepository::class,
    JdbcNotificationDeliveryRepository::class,
    PremiumAggregationQueryRepository::class,
    PremiumAggregationRepository::class,
    TickerAggregationQueryRepository::class,
    TickerAggregationRepository::class,
)
class CommonJdbcConfiguration

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(jakarta.persistence.EntityManagerFactory::class)
@EnableTransactionManagement
@EntityScan(basePackageClasses = [BaseEntity::class])
@EnableJpaRepositories(basePackageClasses = [CommonPersistenceMarker::class])
@Import(
    JpaMemberRepositoryAdapter::class,
    JpaNotificationSubscriptionRepositoryAdapter::class,
    JpaPositionRepositoryAdapter::class,
    JpaPremiumRepositoryAdapter::class,
    JpaTickerRepositoryAdapter::class,
)
class CommonJpaConfiguration

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(StringRedisTemplate::class)
@ConditionalOnProperty(name = ["redis.enabled"], havingValue = "true", matchIfMissing = true)
@Import(
    AfterCommitCacheExecutor::class,
    MicrometerCacheReadMetrics::class,
    FxCacheReader::class,
    FxCacheWriter::class,
    PremiumAggregationCacheReader::class,
    PremiumCacheReader::class,
    PremiumCacheWriter::class,
    TickerCacheReader::class,
    TickerCacheWriter::class,
)
class CommonCacheConfiguration
