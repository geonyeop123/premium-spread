package io.premiumspread.application.job.aggregation

import io.premiumspread.config.AggregationProperties
import io.premiumspread.domain.aggregation.AggregationWindow
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Component
class AggregationWindowPolicy(
    private val properties: AggregationProperties,
) {
    val zoneId: ZoneId
        get() = properties.aggregationZone.zoneId

    fun previous(now: Instant, unit: ChronoUnit): AggregationWindow {
        val zone = properties.aggregationZone
        if (unit == ChronoUnit.DAYS) {
            val currentDay = now.atZone(zone.zoneId).toLocalDate()
            val from = currentDay.minusDays(1).atStartOfDay(zone.zoneId).toInstant()
            val to = currentDay.atStartOfDay(zone.zoneId).toInstant()
            return AggregationWindow(from, to, zone)
        }

        val from = now.minus(1, unit).truncatedTo(unit)
        return AggregationWindow(from, from.plus(1, unit), zone)
    }
}
