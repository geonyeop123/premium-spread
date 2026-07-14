package io.premiumspread.config.jpa

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
@ConditionalOnMissingBean(DataSource::class)
@ConditionalOnProperty(prefix = "datasource.mysql-jpa", name = ["enabled"], havingValue = "true")
class DataSourceConfig {
    @Bean
    @ConfigurationProperties(prefix = "datasource.mysql-jpa.main")
    fun mySqlMainHikariConfig(): HikariConfig =
        HikariConfig()

    @Primary
    @Bean
    fun mySqlMainDataSource(@Qualifier("mySqlMainHikariConfig") hikariConfig: HikariConfig) =
        HikariDataSource(hikariConfig)
}
