package io.premiumspread.logging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

class LoggingAutoConfigurationTest {
    private val contextRunner =
        WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    WebMvcAutoConfiguration::class.java,
                    LoggingAutoConfiguration::class.java,
                ),
            ).withUserConfiguration(LoggingProbeConfiguration::class.java)

    @Test
    fun `logging auto configuration registers the request interceptor`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(RequestLoggingInterceptor::class.java)

            val mockMvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).build()
            mockMvc
                .perform(
                    get("/api/logging-probe")
                        .header(RequestLoggingInterceptor.REQUEST_ID_HEADER, "request-id"),
                ).andExpect(status().isOk)
                .andExpect(header().string(RequestLoggingInterceptor.REQUEST_ID_HEADER, "request-id"))
        }
    }

    @Test
    fun `logging auto configuration backs off without Spring MVC`() {
        ApplicationContextRunner()
            .withClassLoader(FilteredClassLoader(WebMvcConfigurer::class.java))
            .withConfiguration(AutoConfigurations.of(LoggingAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(RequestLoggingInterceptor::class.java)
            }
    }

    @Test
    fun `auto configuration imports metadata exposes logging configuration`() {
        val resource =
            checkNotNull(
                javaClass.classLoader.getResource(
                    "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                ),
            )

        assertThat(resource.readText().lineSequence().filter(String::isNotBlank).toList())
            .contains("io.premiumspread.logging.LoggingAutoConfiguration")
    }

    @Configuration(proxyBeanMethods = false)
    class LoggingProbeConfiguration {
        @RestController
        class LoggingProbeController {
            @GetMapping("/api/logging-probe")
            fun probe(): String = "ok"
        }
    }
}
