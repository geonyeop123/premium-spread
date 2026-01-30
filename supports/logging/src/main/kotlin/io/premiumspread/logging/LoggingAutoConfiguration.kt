package io.premiumspread.logging

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 로깅 자동 설정
 */
@AutoConfiguration
class LoggingAutoConfiguration {

    /**
     * WebMVC 환경에서만 인터셉터 설정
     */
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = ["org.springframework.web.servlet.HandlerInterceptor"])
    @Bean
    fun loggingWebMvcConfigurer(): WebMvcConfigurer {
        return object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                registry.addInterceptor(RequestLoggingInterceptor())
                    .addPathPatterns("/api/**")
            }
        }
    }
}
