package io.premiumspread.interfaces.api.premium

import io.premiumspread.application.premium.PremiumCreateCriteria
import io.premiumspread.application.premium.PremiumFacade
import io.premiumspread.application.premium.PremiumResult
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant

@RestController
@RequestMapping("/api/v1/premiums")
class PremiumController(
    private val premiumFacade: PremiumFacade,
) {

    @PostMapping("/calculate/{symbol}")
    fun calculate(@PathVariable symbol: String): ResponseEntity<PremiumResponse> {
        val result = premiumFacade.calculateAndSave(PremiumCreateCriteria(symbol))
        return ResponseEntity.ok(PremiumResponse.from(result))
    }

    @GetMapping("/current/{symbol}")
    fun getCurrent(@PathVariable symbol: String): ResponseEntity<PremiumResponse> {
        val result = premiumFacade.findLatest(symbol)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(PremiumResponse.from(result))
    }

    @GetMapping("/history/{symbol}")
    fun getHistory(
        @PathVariable symbol: String,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
    ): ResponseEntity<List<PremiumResponse>> {
        val results = premiumFacade.findByPeriod(symbol, from, to)
        return ResponseEntity.ok(results.map { PremiumResponse.from(it) })
    }
}

data class PremiumResponse(
    val id: Long,
    val symbol: String,
    val koreaTickerId: Long,
    val foreignTickerId: Long,
    val fxTickerId: Long,
    val premiumRate: BigDecimal,
    val observedAt: Instant,
) {
    companion object {
        fun from(result: PremiumResult): PremiumResponse = PremiumResponse(
            id = result.id,
            symbol = result.symbol,
            koreaTickerId = result.koreaTickerId,
            foreignTickerId = result.foreignTickerId,
            fxTickerId = result.fxTickerId,
            premiumRate = result.premiumRate,
            observedAt = result.observedAt,
        )
    }
}
