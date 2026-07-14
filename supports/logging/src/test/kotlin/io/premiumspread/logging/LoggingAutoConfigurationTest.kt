package io.premiumspread.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration
import org.springframework.boot.logging.LoggingInitializationContext
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    fun `unsafe request id is replaced with a bounded generated correlation id`() {
        val request = MockHttpServletRequest().apply {
            addHeader(RequestLoggingInterceptor.REQUEST_ID_HEADER, "invalid request id forged")
        }
        val response = MockHttpServletResponse()

        CorrelationIdFilter().doFilter(request, response, MockFilterChain())

        assertThat(response.getHeader(RequestLoggingInterceptor.REQUEST_ID_HEADER)).matches("[a-f0-9]{32}")
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty()
    }

    @Test
    fun `task decorator propagates and then clears submitter MDC`() {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        try {
            MDC.put(RequestLoggingInterceptor.MDC_REQUEST_ID, "correlation-1")
            val propagated = executor.submit(MdcContext.wrap(Runnable {
                assertThat(MDC.get(RequestLoggingInterceptor.MDC_REQUEST_ID)).isEqualTo("correlation-1")
            }))
            propagated.get()

            assertThat(executor.submit<String?> { MDC.get(RequestLoggingInterceptor.MDC_REQUEST_ID) }.get()).isNull()
        } finally {
            MDC.clear()
            executor.shutdownNow()
        }
    }

    @Test
    fun `email token password and cookie values are masked`() {
        val source =
            """email=user@example.com token=token-value password=secret cookie=session-id authorization=Bearer abc.def"""

        val masked = LogMaskingFilter.mask(source)

        assertThat(masked).doesNotContain("user@example.com", "token-value", "secret", "session-id", "abc.def")
        assertThat(masked).contains("email=***MASKED***", "token=***MASKED***", "password=***MASKED***", "cookie=***MASKED***")
    }

    @Test
    fun `Hibernate SQL logger는 local과 test에서만 DEBUG다`() {
        val xml = ClassPathResource("logback-spring.xml").inputStream.bufferedReader().use { it.readText() }
        val common = xml.substringBefore("<springProfile name=\"local\">")
        val local = xml.substringAfter("<springProfile name=\"local\">").substringBefore("</springProfile>")
        val test = xml.substringAfter("<springProfile name=\"test\">").substringBefore("</springProfile>")
        val production = xml.substringAfter("<springProfile name=\"prd\">").substringBefore("</springProfile>")

        assertThat(common).contains("<logger name=\"org.hibernate.SQL\" level=\"WARN\"/>")
        assertThat(local).contains("<logger name=\"org.hibernate.SQL\" level=\"DEBUG\"/>")
        assertThat(test).contains("<logger name=\"org.hibernate.SQL\" level=\"DEBUG\"/>")
        assertThat(production).doesNotContain("org.hibernate.SQL")
    }

    @Test
    fun `prd JSON appender는 실제 출력의 message MDC stacktrace 민감정보를 마스킹한다`() {
        val loggerContext = LoggerContext()
        try {
            configureProductionLogging(loggerContext)
            val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
            val appender =
                rootLogger.getAppender("CONSOLE_JSON") as OutputStreamAppender<ILoggingEvent>
            val event =
                LoggingEvent().apply {
                    loggerName = "io.premiumspread.logging.production-probe"
                    level = Level.ERROR
                    message =
                        "email=user@example.com authorization=Bearer access-secret password=password-secret cookie=session-secret"
                    threadName = "production-logging-test"
                    timeStamp = System.currentTimeMillis()
                    mdcPropertyMap =
                        mapOf(
                            "token" to "mdc-token-secret",
                            "requestId" to "safe-request-id",
                        )
                    setThrowableProxy(
                        ch.qos.logback.classic.spi.ThrowableProxy(
                            IllegalStateException("refresh_token=stacktrace-secret"),
                        ),
                    )
                }

            val json = String(checkNotNull(appender.encoder).encode(event), StandardCharsets.UTF_8)

            assertThat(json).contains("***MASKED***", "safe-request-id")
            assertThat(json).doesNotContain(
                "user@example.com",
                "access-secret",
                "password-secret",
                "session-secret",
                "mdc-token-secret",
                "stacktrace-secret",
            )
        } finally {
            loggerContext.stop()
        }
    }

    private fun configureProductionLogging(loggerContext: LoggerContext) {
        val environment =
            MockEnvironment()
                .withProperty("spring.profiles.active", "prd")
                .withProperty("LOG_PATH", "build/test-logs")
                .withProperty("APP_NAME", "logging-test")
        environment.setActiveProfiles("prd")
        val constructor =
            Class.forName("org.springframework.boot.logging.logback.SpringBootJoranConfigurator")
                .getDeclaredConstructor(LoggingInitializationContext::class.java)
                .apply { isAccessible = true }
        val configurator =
            constructor.newInstance(LoggingInitializationContext(environment)) as JoranConfigurator
        configurator.context = loggerContext
        ClassPathResource("logback-spring.xml").inputStream.use(configurator::doConfigure)
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
