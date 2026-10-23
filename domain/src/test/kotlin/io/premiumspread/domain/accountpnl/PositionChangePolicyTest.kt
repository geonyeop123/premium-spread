package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PositionChangePolicyTest {
    private val now = Instant.parse("2026-10-20T03:00:00Z")
    private val api = ForeignPerpPositionSource.EXCHANGE_API
    private val manual = ForeignPerpPositionSource.MANUAL_ENV

    private fun korea(quantity: String, price: String = "100000000") =
        KoreaLeg.of(BigDecimal(quantity), BigDecimal(price))

    private fun foreign(quantity: String, price: String = "70000") =
        ForeignLeg.of(BigDecimal(quantity), BigDecimal(price))

    private fun assertEquals(expected: Any?, actual: Any?) = assertThat(actual).isEqualTo(expected)

    private fun assertTrue(actual: Boolean) = assertThat(actual).isTrue()

    private fun assertFalse(actual: Boolean) = assertThat(actual).isFalse()

    private fun assertNull(actual: Any?) = assertThat(actual).isNull()

    private fun assertNotNull(actual: AccountAlert.PositionChanged?): AccountAlert.PositionChanged {
        assertThat(actual).isNotNull
        return actual!!
    }

    private inline fun <reified T : Throwable> assertThrows(noinline block: () -> Unit) {
        assertThatThrownBy(block).isInstanceOf(T::class.java)
    }

    private val before = PositionLegs(korea("0.5"), foreign("-0.5"))

    private fun watching(warned: Boolean) = PositionWatchState(before, warned)

    @Test
    fun `P1 첫 기록은 알림 없이 legs 를 저장한다`() {
        val decision = PositionChangePolicy.decide(PositionWatchState.INITIAL, before, manual, now)

        assertNull(decision.alert)
        assertEquals(PositionWatchState(before, false), decision.next)
    }

    @Test
    fun `P2 같은 legs 의 API 출처는 경고 상태를 false 로 만든다`() {
        val same = PositionLegs(korea("0.50"), foreign("-0.5"))
        val decision = PositionChangePolicy.decide(watching(true), same, api, now)

        assertNull(decision.alert)
        assertEquals(PositionWatchState(same, false), decision.next)
    }

    @Test
    fun `P3 같은 legs 의 manual 출처는 경고 상태를 유지한다`() {
        val same = PositionLegs(korea("0.50"), foreign("-0.5"))

        for (warned in listOf(true, false)) {
            val decision = PositionChangePolicy.decide(watching(warned), same, manual, now)
            assertNull(decision.alert)
            assertEquals(PositionWatchState(same, warned), decision.next)
        }
    }

    @Test
    fun `P4 빗썸만 바뀐 manual 은 헤지 불일치 경고를 낸다`() {
        val after = PositionLegs(korea("0.6"), foreign("-0.5"))
        val decision = PositionChangePolicy.decide(watching(false), after, manual, now)

        val alert = assertNotNull(decision.alert)
        assertEquals(before, alert.before)
        assertEquals(after, alert.after)
        assertTrue(alert.hedgeMismatchWarning)
        assertEquals(manual, alert.source)
        assertEquals(now, alert.detectedAt)
        assertTrue(alert.koreaChanged)
        assertFalse(alert.foreignChanged)
        assertEquals(PositionWatchState(after, true), decision.next)
    }

    @Test
    fun `P5 이미 경고한 manual 빗썸 변경은 경고 없이 알린다`() {
        val after = PositionLegs(korea("0.6"), foreign("-0.5"))
        val decision = PositionChangePolicy.decide(watching(true), after, manual, now)

        val alert = assertNotNull(decision.alert)
        assertFalse(alert.hedgeMismatchWarning)
        assertEquals(PositionWatchState(after, true), decision.next)
    }

    @Test
    fun `빗썸이 원래 값으로 돌아와도 경고는 해소되지 않는다`() {
        val drifted = PositionLegs(korea("0.6"), foreign("-0.5"))
        val back = PositionLegs(korea("0.5"), foreign("-0.5"))

        val decision = PositionChangePolicy.decide(PositionWatchState(drifted, true), back, manual, now)

        val alert = assertNotNull(decision.alert)
        assertFalse(alert.hedgeMismatchWarning)
        assertEquals(PositionWatchState(back, true), decision.next)
    }

    @Test
    fun `P6 API 출처의 빗썸 변경은 경고 없이 알리고 경고 상태를 false 로 둔다`() {
        val after = PositionLegs(korea("0.6"), foreign("-0.5"))
        val decision = PositionChangePolicy.decide(watching(true), after, api, now)

        val alert = assertNotNull(decision.alert)
        assertFalse(alert.hedgeMismatchWarning)
        assertEquals(PositionWatchState(after, false), decision.next)
    }

    @Test
    fun `P7 바이낸스만 바뀌면 경고 상태가 해소된다`() {
        val after = PositionLegs(korea("0.5"), foreign("-0.6"))
        val decision = PositionChangePolicy.decide(watching(true), after, manual, now)

        val alert = assertNotNull(decision.alert)
        assertFalse(alert.koreaChanged)
        assertTrue(alert.foreignChanged)
        assertFalse(alert.hedgeMismatchWarning)
        assertEquals(PositionWatchState(after, false), decision.next)
    }

    @Test
    fun `P8 두 leg 가 모두 바뀌면 경고 없이 알리고 경고 상태를 false 로 둔다`() {
        val after = PositionLegs(korea("0.6"), foreign("-0.6"))
        val decision = PositionChangePolicy.decide(watching(true), after, manual, now)

        val alert = assertNotNull(decision.alert)
        assertTrue(alert.koreaChanged && alert.foreignChanged)
        assertFalse(alert.hedgeMismatchWarning)
        assertEquals(PositionWatchState(after, false), decision.next)
    }

    @Test
    fun `전량 청산도 변경으로 알린다`() {
        val koreaClosed = PositionLegs(KoreaLeg.EMPTY, foreign("-0.5"))
        val koreaAlert = assertNotNull(PositionChangePolicy.decide(watching(false), koreaClosed, api, now).alert)
        assertTrue(koreaAlert.koreaChanged)

        val foreignClosed = PositionLegs(korea("0.5"), ForeignLeg.FLAT)
        val foreignAlert = assertNotNull(PositionChangePolicy.decide(watching(false), foreignClosed, api, now).alert)
        assertTrue(foreignAlert.foreignChanged)
    }

    @Test
    fun `알림 불변식은 변경 없는 생성을 거부한다`() {
        assertThrows<IllegalArgumentException> {
            AccountAlert.PositionChanged(before, before, false, manual, now)
        }
    }

    @Test
    fun `알림 불변식은 API 출처나 조건 밖의 경고를 거부한다`() {
        val koreaOnly = PositionLegs(korea("0.6"), foreign("-0.5"))
        val both = PositionLegs(korea("0.6"), foreign("-0.6"))

        assertThrows<IllegalArgumentException> {
            AccountAlert.PositionChanged(before, koreaOnly, true, api, now)
        }
        assertThrows<IllegalArgumentException> {
            AccountAlert.PositionChanged(before, both, true, manual, now)
        }
    }
}
