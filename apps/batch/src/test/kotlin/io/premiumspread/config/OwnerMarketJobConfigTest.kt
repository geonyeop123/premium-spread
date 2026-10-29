package io.premiumspread.config

import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.job.ownerops.OwnerOpsSignalJob
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

/** owner 시장 신호 Job 의 lock·주기 설정을 고정한다 (dod AC13). */
class OwnerMarketJobConfigTest {

    @Test
    fun `시장 신호 Job 의 lock 은 설계 값이다`() {
        assertThat(DefaultJobConfigProvider.get(JobId.OWNER_MARKET_SIGNAL)).isEqualTo(MARKET_SIGNAL)
        assertThat(BatchJobProperties().get(JobId.OWNER_MARKET_SIGNAL)).isEqualTo(MARKET_SIGNAL)
    }

    @Test
    fun `yml 바인딩도 같은 값과 30초 주기`() {
        val sources = MutablePropertySources()
        YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
            .forEach(sources::addLast)
        val binder = Binder(ConfigurationPropertySources.from(sources), PropertySourcesPlaceholdersResolver(sources))

        val jobs = binder.bind("batch.jobs", Bindable.of(BatchJobProperties::class.java)).get()
        val scheduling = binder.bind("batch.scheduling", Bindable.of(BatchSchedulingProperties::class.java)).get()

        assertThat(jobs.get(JobId.OWNER_MARKET_SIGNAL)).isEqualTo(MARKET_SIGNAL)
        assertThat(scheduling.ownerMarketSignal.fixedRate).isEqualTo(Duration.ofSeconds(30))
    }

    @Test
    fun `운영 신호 Job 이 시장 신호 Job 을 감시한다`() {
        assertThat(OwnerOpsSignalJob.MONITORED_JOBS).contains(JobId.OWNER_MARKET_SIGNAL)
    }

    private companion object {
        val MARKET_SIGNAL = JobConfig(
            JobId.OWNER_MARKET_SIGNAL,
            "lock:owner-market:signal",
            Duration.ofSeconds(20),
            Duration.ofSeconds(10),
        )
    }
}
