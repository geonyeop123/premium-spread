package io.premiumspread.config

import io.premiumspread.interfaces.scheduling.BatchSchedulingProperties
import org.springframework.stereotype.Component
import java.time.ZoneId

@Component
class BatchZoneConsistencyValidator(
    scheduling: BatchSchedulingProperties,
    aggregation: AggregationProperties,
) {
    init {
        val schedulingZone = ZoneId.of(scheduling.zone)
        require(schedulingZone == aggregation.aggregationZone.zoneId) {
            "batch.scheduling.zone and aggregation.zone must be identical"
        }
    }
}
