package io.premiumspread.config

import io.premiumspread.logging.RequestLoggingInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(RequestLoggingInterceptor())
            .addPathPatterns("/api/**")
    }
}
