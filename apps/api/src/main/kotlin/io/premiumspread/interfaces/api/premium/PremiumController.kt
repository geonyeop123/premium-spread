package io.premiumspread.interfaces.api.premium

import io.premiumspread.application.premium.PremiumCriteria
import io.premiumspread.application.premium.PremiumFacade
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/premiums")
class PremiumController(
    private val premiumFacade: PremiumFacade,
) {

    @PostMapping("/calculate/{symbol}")
    fun calculate(@PathVariable symbol: String): ResponseEntity<PremiumResponse.Detail> {
        val result = premiumFacade.calculateAndSave(PremiumCriteria.Create(symbol))
        return ResponseEntity.ok(PremiumResponse.Detail.from(result))
    }

    @GetMapping("/current/{symbol}")
    fun getCurrent(@PathVariable symbol: String): ResponseEntity<PremiumResponse.Current> {
        val result = premiumFacade.findLatestSnapshot(symbol)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(PremiumResponse.Current.from(result))
    }

    @GetMapping("/history/{symbol}")
    fun getHistory(
        @PathVariable symbol: String,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
    ): ResponseEntity<List<PremiumResponse.Detail>> {
        val results = premiumFacade.findByPeriod(symbol, from, to)
        return ResponseEntity.ok(results.map { PremiumResponse.Detail.from(it) })
    }
}
