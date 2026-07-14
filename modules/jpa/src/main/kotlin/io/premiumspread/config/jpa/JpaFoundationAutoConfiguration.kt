package io.premiumspread.config.jpa

import jakarta.persistence.EntityManagerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.context.annotation.Import

/**
 * Registers the shared datasource without relying on an application's component scan.
 */
@AutoConfiguration(before = [DataSourceAutoConfiguration::class, HibernateJpaAutoConfiguration::class])
@Import(DataSourceConfig::class)
class JpaFoundationAutoConfiguration

/**
 * Enables auditing only after Hibernate has supplied an EntityManagerFactory.
 */
@AutoConfiguration(after = [HibernateJpaAutoConfiguration::class, JpaFoundationAutoConfiguration::class])
@ConditionalOnBean(EntityManagerFactory::class)
@Import(JpaAuditingConfiguration::class)
class JpaAuditingAutoConfiguration
