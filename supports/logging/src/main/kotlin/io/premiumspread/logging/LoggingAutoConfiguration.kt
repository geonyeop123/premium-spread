package io.premiumspread.logging

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 로깅 자동 설정
 *
 * HTTP 요청 로깅 인터셉터와 MVC 등록을 로깅 모듈이 함께 소유한다.
 */
@AutoConfiguration(after = [WebMvcAutoConfiguration::class])
@Import(LoggingAutoConfiguration.LoggingWebMvcConfiguration::class)
class LoggingAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebMvcConfigurer::class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    class LoggingWebMvcConfiguration {
        @Bean
        @ConditionalOnMissingBean
        fun requestLoggingInterceptor(): RequestLoggingInterceptor = RequestLoggingInterceptor()

        @Bean
        fun requestLoggingWebMvcConfigurer(
            requestLoggingInterceptor: RequestLoggingInterceptor,
        ): WebMvcConfigurer =
            object : WebMvcConfigurer {
                override fun addInterceptors(registry: InterceptorRegistry) {
                    registry
                        .addInterceptor(requestLoggingInterceptor)
                        .addPathPatterns("/api/**")
                }
            }
    }
}
