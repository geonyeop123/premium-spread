package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.owneralert.OwnerAlertKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class OwnerAlertSignalStateTest {

    private val kind = OwnerAlertKind.FX_INGESTION_DELAYED
    private val start = Instant.parse("2026-10-28T00:00:00Z")

    private fun state(key: String, version: Long = 0) = OwnerAlertSignalState(key, kind, null, version)

    @Test
    fun `signalKey 정규식은 허용 문자와 64자까지 받는다`() {
        listOf("fx-ingestion.usd-krw", "job-failures.fx_ingestion", "a".repeat(64)).forEach {
            assertThat(state(it).signalKey).isEqualTo(it)
        }
    }

    @Test
    fun `signalKey 정규식을 어기면 거부한다`() {
        listOf("Bad Key", "a:b", "a".repeat(65), "").forEach {
            assertThatThrownBy { state(it) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("signalKey must match [a-z0-9._-]{1,64}.")
        }
    }

    @Test
    fun `version 이 음수면 거부한다`() {
        assertThatThrownBy { state("fx.usd-krw", -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("version must not be negative.")
    }

    @Test
    fun `initial 은 incident null 과 version 0 이고 active 가 아니다`() {
        val initial = OwnerAlertSignalState.initial("fx.usd-krw", kind)

        assertThat(initial.incident).isNull()
        assertThat(initial.version).isZero()
        assertThat(initial.active).isFalse()
        assertThat(initial.alertKind).isEqualTo(kind)
    }

    @Test
    fun `incident 가 있으면 active 이다`() {
        val active = state("fx.usd-krw").copy(incident = OwnerAlertIncident(start, start, 1))

        assertThat(active.active).isTrue()
    }

    @Test
    fun `notifyCount 가 1 미만이면 거부한다`() {
        assertThatThrownBy { OwnerAlertIncident(start, start, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("notifyCount must be at least 1.")
    }

    @Test
    fun `lastNotifiedAt 이 startedAt 보다 이르면 거부하고 같으면 허용한다`() {
        assertThatThrownBy { OwnerAlertIncident(start, start.minusMillis(1), 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("lastNotifiedAt must not be before startedAt.")

        assertThat(OwnerAlertIncident(start, start, 1).notifyCount).isEqualTo(1)
    }
}
