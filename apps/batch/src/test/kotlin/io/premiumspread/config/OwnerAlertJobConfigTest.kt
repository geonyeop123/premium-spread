package io.premiumspread.config

import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfig
import io.premiumspread.domain.job.JobId
import io.premiumspread.interfaces.scheduling.BatchSchedulingProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.io.ClassPathResource
import java.time.Duration

/** owner 운영 알림 Job 두 개의 lock·주기 설정을 고정한다 (dod AC10). */
class OwnerAlertJobConfigTest {

    @Test
    fun `새 Job 두 개의 기본 lock 설정`() {
        assertThat(DefaultJobConfigProvider.get(JobId.OWNER_ALERT_DELIVERY)).isEqualTo(DELIVERY)
        assertThat(DefaultJobConfigProvider.get(JobId.OWNER_OPS_SIGNAL)).isEqualTo(SIGNAL)
    }

    @Test
    fun `두 provider 는 모든 JobId 에서 같은 값`() {
        val properties = BatchJobProperties()

        JobId.entries.forEach {
            assertThat(properties.get(it)).`as`(it.name).isEqualTo(DefaultJobConfigProvider.get(it))
        }
        assertThat(properties.get(JobId.OWNER_ALERT_DELIVERY)).isEqualTo(DELIVERY)
        assertThat(properties.get(JobId.OWNER_OPS_SIGNAL)).isEqualTo(SIGNAL)
    }

    @Test
    fun `yml 의 jobs 와 scheduling 값`() {
        val sources = MutablePropertySources()
        YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
            .forEach(sources::addLast)
        val binder = Binder(ConfigurationPropertySources.from(sources), PropertySourcesPlaceholdersResolver(sources))

        val jobs = binder.bind("batch.jobs", Bindable.of(BatchJobProperties::class.java)).get()
        val scheduling = binder.bind("batch.scheduling", Bindable.of(BatchSchedulingProperties::class.java)).get()

        assertThat(jobs.get(JobId.OWNER_ALERT_DELIVERY)).isEqualTo(DELIVERY)
        assertThat(jobs.get(JobId.OWNER_OPS_SIGNAL)).isEqualTo(SIGNAL)
        assertThat(scheduling.ownerAlertDelivery.fixedRate).isEqualTo(Duration.ofSeconds(15))
        assertThat(scheduling.ownerOpsSignal.fixedRate).isEqualTo(Duration.ofSeconds(30))
    }

    private companion object {
        val DELIVERY = JobConfig(
            JobId.OWNER_ALERT_DELIVERY,
            "lock:owner-alert:delivery",
            Duration.ofSeconds(50),
            Duration.ofSeconds(40),
        )
        val SIGNAL = JobConfig(
            JobId.OWNER_OPS_SIGNAL,
            "lock:owner-ops:signal",
            Duration.ofSeconds(20),
            Duration.ofSeconds(10),
        )
    }
}
