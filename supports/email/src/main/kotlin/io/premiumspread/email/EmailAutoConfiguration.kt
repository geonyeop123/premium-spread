package io.premiumspread.email

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.mail.javamail.JavaMailSender

@AutoConfiguration
class EmailAutoConfiguration {

    @Bean
    @ConditionalOnBean(JavaMailSender::class)
    @ConditionalOnProperty(name = ["alert.email.from"])
    @ConditionalOnMissingBean(EmailSender::class)
    fun emailSender(
        mailSender: JavaMailSender,
        @Value("\${alert.email.from}") from: String,
    ): EmailSender = JavaMailEmailSender(mailSender, from)
}
