package io.premiumspread.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration

@Configuration
class IngestionModeConfig(
    @Value("\${premium.ingestion.binance.mode:rest}") private val binanceMode: String,
    @Value("\${premium.ingestion.bithumb.mode:rest}") private val bithumbMode: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun validate() {
        require(binanceMode in VALID_MODES) {
            "Invalid premium.ingestion.binance.mode: '$binanceMode' (must be one of $VALID_MODES)"
        }
        require(bithumbMode in VALID_MODES) {
            "Invalid premium.ingestion.bithumb.mode: '$bithumbMode' (must be one of $VALID_MODES)"
        }
        log.info("Ingestion modes — binance: {}, bithumb: {}", binanceMode, bithumbMode)
    }

    companion object {
        val VALID_MODES = setOf("rest", "websocket")
    }
}
