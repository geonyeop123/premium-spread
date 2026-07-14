package io.premiumspread.email

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl

@AutoConfiguration
@ConditionalOnProperty(
    prefix = "notification.email",
    name = ["enabled"],
    havingValue = "true",
)
@EnableConfigurationProperties(
    NotificationEmailProperties::class,
    SmtpConnectionProperties::class,
)
class EmailAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(JavaMailSender::class)
    fun javaMailSender(properties: SmtpConnectionProperties): JavaMailSender =
        JavaMailSenderImpl().apply {
            host = properties.host
            port = properties.port
            username = properties.username
            password = properties.password
            javaMailProperties["mail.smtp.auth"] = "true"
            javaMailProperties["mail.smtp.starttls.enable"] = "true"
            javaMailProperties["mail.smtp.connectiontimeout"] = properties.connectTimeout.toMillis().toString()
            javaMailProperties["mail.smtp.timeout"] = properties.readTimeout.toMillis().toString()
            javaMailProperties["mail.smtp.writetimeout"] = properties.writeTimeout.toMillis().toString()
        }

    @Bean
    @ConditionalOnMissingBean(EmailSender::class)
    fun emailSender(
        mailSender: JavaMailSender,
        properties: NotificationEmailProperties,
    ): EmailSender = JavaMailEmailSender(mailSender, properties.from)
}
