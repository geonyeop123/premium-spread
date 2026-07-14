package io.premiumspread.config.jpa

import jakarta.persistence.EntityManagerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import javax.sql.DataSource

/**
 * Registers the shared datasource without relying on an application's component scan.
 */
@AutoConfiguration(before = [DataSourceAutoConfiguration::class, HibernateJpaAutoConfiguration::class])
@Import(DatabaseSettingsConfiguration::class)
class JpaFoundationAutoConfiguration

@Configuration(proxyBeanMethods = false)
@ConditionalOnMissingBean(DataSource::class)
@ConditionalOnProperty(prefix = "spring.datasource", name = ["url"])
@EnableConfigurationProperties(DatabaseProperties::class)
@Import(ProductionDatabaseSettingsValidator::class)
class DatabaseSettingsConfiguration

/**
 * Enables auditing only after Hibernate has supplied an EntityManagerFactory.
 */
@AutoConfiguration(after = [HibernateJpaAutoConfiguration::class, JpaFoundationAutoConfiguration::class])
@ConditionalOnBean(EntityManagerFactory::class)
@Import(JpaAuditingConfiguration::class)
class JpaAuditingAutoConfiguration
