package io.premiumspread.infrastructure.batch.exchange.binance

import com.fasterxml.jackson.databind.JsonNode
import io.premiumspread.domain.accountpnl.ForeignPerpBalance
import io.premiumspread.domain.accountpnl.ForeignPerpMarginType
import io.premiumspread.domain.accountpnl.ForeignPerpPositionRisk
import io.premiumspread.domain.accountpnl.ForeignPerpSymbolConfig
import io.premiumspread.infrastructure.batch.exchange.BalanceUnavailableReason
import java.math.BigDecimal

/** 부분 실패 허용 항목의 해석 결과. */
internal sealed interface Parsed<out T : Any> {
    class Ok<T : Any>(val value: T) : Parsed<T>

    class Unavailable(val reason: BalanceUnavailableReason) : Parsed<Nothing>
}

/** 바이낸스 계정 응답 해석. 순수 함수이며 예외를 던지지 않고 사유를 돌려준다. 네트워크 호출은 하지 않는다. */
internal object BinanceAccountResponseParser {
    private val RISK_FIELDS = listOf(
        "liquidationPrice",
        "markPrice",
        "isolatedMargin",
        "isolatedWallet",
        "maintMargin",
        "positionInitialMargin",
        "notional",
    )
    private val MARGIN_FIELDS = listOf("isolatedMargin", "isolatedWallet", "maintMargin", "positionInitialMargin")

    fun parseBalance(root: JsonNode): Parsed<ForeignPerpBalance> {
        val assets = root.get("assets")?.takeIf { it.isArray }
        val usdt = assets?.firstOrNull { it.path("asset").asText() == "USDT" }
            ?: return Parsed.Unavailable(BalanceUnavailableReason.MISSING)
        val margin = usdt.get("marginBalance")?.let { decimalOrNull(it) }
        val available = usdt.get("availableBalance")?.let { decimalOrNull(it) }
        return when {
            margin == null || available == null -> Parsed.Unavailable(BalanceUnavailableReason.NOT_NUMBER)
            margin.signum() < 0 || available.signum() < 0 -> Parsed.Unavailable(BalanceUnavailableReason.NEGATIVE)
            else -> Parsed.Ok(ForeignPerpBalance(margin, available))
        }
    }

    /** symbolConfig 응답 배열에서 [symbol] 행을 해석한다. */
    fun parseSymbolConfig(root: JsonNode, symbol: String): Parsed<ForeignPerpSymbolConfig> {
        val row = root.takeIf { it.isArray }?.firstOrNull { it.path("symbol").asText() == symbol }
            ?: return Parsed.Unavailable(BalanceUnavailableReason.MISSING)
        val leverageNode = row.get("leverage") ?: return Parsed.Unavailable(BalanceUnavailableReason.MISSING)
        val leverage = intOrNull(leverageNode) ?: return Parsed.Unavailable(BalanceUnavailableReason.NOT_NUMBER)
        if (leverage < 1) return Parsed.Unavailable(BalanceUnavailableReason.NOT_POSITIVE)
        val marginType = when (row.path("marginType").asText()) {
            "CROSSED" -> ForeignPerpMarginType.CROSSED
            "ISOLATED" -> ForeignPerpMarginType.ISOLATED
            else -> return Parsed.Unavailable(BalanceUnavailableReason.UNKNOWN)
        }
        return Parsed.Ok(ForeignPerpSymbolConfig(leverage, marginType))
    }

    /**
     * positionRisk 행의 위험 필드 7개. 하나라도 없거나 숫자가 아니거나 범위를 벗어나면 묶음 전체를 버린다 — 필수 필드와 달리
     * 회차를 실패시키지 않는다. `liquidationPrice` 0 은 청산가 없음(null)이다.
     */
    fun parsePositionRisk(row: JsonNode): Parsed<ForeignPerpPositionRisk> {
        val values = when (val read = readRiskFields(row)) {
            is Parsed.Unavailable -> return read
            is Parsed.Ok -> read.value
        }
        val liquidation = values.getValue("liquidationPrice")
        val mark = values.getValue("markPrice")
        val negative = liquidation.signum() < 0 || MARGIN_FIELDS.any { values.getValue(it).signum() < 0 }
        return when {
            mark.signum() <= 0 -> Parsed.Unavailable(BalanceUnavailableReason.NOT_POSITIVE)
            negative -> Parsed.Unavailable(BalanceUnavailableReason.NEGATIVE)
            else -> buildRisk(values, liquidation, mark)
        }
    }

    private fun readRiskFields(row: JsonNode): Parsed<Map<String, BigDecimal>> {
        val values = HashMap<String, BigDecimal>()
        for (name in RISK_FIELDS) {
            val node = row.get(name) ?: return Parsed.Unavailable(BalanceUnavailableReason.MISSING)
            values[name] = decimalOrNull(node) ?: return Parsed.Unavailable(BalanceUnavailableReason.NOT_NUMBER)
        }
        return Parsed.Ok(values)
    }

    private fun buildRisk(
        values: Map<String, BigDecimal>,
        liquidation: BigDecimal,
        mark: BigDecimal,
    ): Parsed<ForeignPerpPositionRisk> = try {
        Parsed.Ok(
            ForeignPerpPositionRisk(
                liquidationPrice = liquidation.takeIf { it.signum() != 0 },
                markPrice = mark,
                isolatedMargin = values.getValue("isolatedMargin"),
                isolatedWallet = values.getValue("isolatedWallet"),
                maintMargin = values.getValue("maintMargin"),
                positionInitialMargin = values.getValue("positionInitialMargin"),
                notional = values.getValue("notional"),
            ),
        )
    } catch (_: IllegalArgumentException) {
        // 위 사전 검사가 도메인 불변식과 갈라져도 회차를 깨지 않는다
        Parsed.Unavailable(BalanceUnavailableReason.NEGATIVE)
    }

    private fun intOrNull(node: JsonNode): Int? = when {
        node.isIntegralNumber && node.canConvertToInt() -> node.intValue()
        node.isTextual -> node.asText().toIntOrNull()
        else -> null
    }

    private fun decimalOrNull(node: JsonNode): BigDecimal? = try {
        when {
            node.isNumber -> node.decimalValue()
            node.isTextual -> BigDecimal(node.asText())
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }
}
