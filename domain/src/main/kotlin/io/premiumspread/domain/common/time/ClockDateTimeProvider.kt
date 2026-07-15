package io.premiumspread.domain.common.time

import java.time.Clock
import java.time.temporal.TemporalAccessor
import java.util.Optional
import org.springframework.data.auditing.DateTimeProvider

/** Spring Data auditing이 애플리케이션의 단일 [Clock]을 사용하도록 하는 계약 구현. */
class ClockDateTimeProvider(private val clock: Clock) : DateTimeProvider {
    override fun getNow(): Optional<TemporalAccessor> = Optional.of(clock.instant())
}
