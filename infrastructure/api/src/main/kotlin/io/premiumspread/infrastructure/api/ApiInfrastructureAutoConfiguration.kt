package io.premiumspread.infrastructure.api

import io.premiumspread.domain.auth.RefreshCookiePolicy
import io.premiumspread.domain.auth.RefreshSessionStore
import io.premiumspread.domain.auth.RefreshTokenHasher
import io.premiumspread.domain.auth.TokenIssuer
import io.premiumspread.domain.member.PasswordEncoder
import io.premiumspread.infrastructure.api.security.ApiSecurityConfiguration
import io.premiumspread.infrastructure.api.security.BcryptPasswordEncoderAdapter
import io.premiumspread.infrastructure.api.security.CookieProperties
import io.premiumspread.infrastructure.api.security.CorsProperties
import io.premiumspread.infrastructure.api.security.HmacRefreshTokenHasher
import io.premiumspread.infrastructure.api.security.JwtProperties
import io.premiumspread.infrastructure.api.security.JwtTokenIssuer
import io.premiumspread.infrastructure.api.security.ProductionSecurityPolicyValidator
import io.premiumspread.infrastructure.api.security.RedisRefreshSessionStore
import io.premiumspread.infrastructure.api.security.RefreshCookiePolicyAdapter
import io.premiumspread.infrastructure.api.security.RefreshProperties
import io.premiumspread.infrastructure.api.warmup.CacheWarmupService
import io.premiumspread.infrastructure.api.warmup.WarmupProperties
import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.ticker.TickerService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.Clock

@AutoConfiguration(after = [io.premiumspread.infrastructure.common.CommonInfrastructureAutoConfiguration::class])
@EnableConfigurationProperties(
    JwtProperties::class,
    CookieProperties::class,
    CorsProperties::class,
    RefreshProperties::class,
    WarmupProperties::class,
)
@Import(ApiSecurityConfiguration::class)
class ApiInfrastructureAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun apiClock(): Clock = Clock.systemUTC()

    @Bean
    @ConditionalOnMissingBean(TokenIssuer::class)
    fun tokenIssuer(properties: JwtProperties): TokenIssuer = JwtTokenIssuer(properties)

    @Bean
    @ConditionalOnMissingBean(RefreshTokenHasher::class)
    fun refreshTokenHasher(properties: RefreshProperties): RefreshTokenHasher = HmacRefreshTokenHasher(properties)

    @Bean
    @ConditionalOnMissingBean(RefreshCookiePolicy::class)
    fun refreshCookiePolicy(properties: CookieProperties): RefreshCookiePolicy = RefreshCookiePolicyAdapter(properties)

    @Bean
    @ConditionalOnBean(StringRedisTemplate::class)
    @ConditionalOnMissingBean(RefreshSessionStore::class)
    fun refreshSessionStore(
        redisTemplate: StringRedisTemplate,
        properties: RefreshProperties,
    ): RefreshSessionStore = RedisRefreshSessionStore(redisTemplate, properties)

    @Bean
    @ConditionalOnMissingBean(org.springframework.security.crypto.password.PasswordEncoder::class)
    fun springPasswordEncoder(): org.springframework.security.crypto.password.PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    @ConditionalOnMissingBean(PasswordEncoder::class)
    fun passwordEncoder(delegate: org.springframework.security.crypto.password.PasswordEncoder): PasswordEncoder =
        BcryptPasswordEncoderAdapter(delegate)

    @Bean
    @ConditionalOnBean(PremiumService::class, TickerService::class)
    @ConditionalOnMissingBean(CacheWarmupService::class)
    fun cacheWarmupService(
        premiumService: PremiumService,
        tickerService: TickerService,
        properties: WarmupProperties,
    ): CacheWarmupService = CacheWarmupService(premiumService, tickerService, properties)

    @Bean
    @Profile("prd")
    @ConditionalOnMissingBean(ProductionSecurityPolicyValidator::class)
    fun productionSecurityPolicyValidator(
        jwt: JwtProperties,
        cookie: CookieProperties,
        cors: CorsProperties,
        refresh: RefreshProperties,
    ): ProductionSecurityPolicyValidator = ProductionSecurityPolicyValidator(jwt, cookie, cors, refresh)
}
