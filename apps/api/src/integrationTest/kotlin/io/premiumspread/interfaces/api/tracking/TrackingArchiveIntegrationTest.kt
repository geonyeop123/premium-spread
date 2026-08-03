package io.premiumspread.interfaces.api.tracking

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * design.md §5.3.2 "요청·응답 계약" 표를 그대로 옮긴다 (dod.md AC5).
 *
 * 표가 단일 출처이고 이 테스트가 그 표를 검증한다 — 세 문서가 409 의 위치를 다르게 서술하던
 * 문제의 재발 차단선이다.
 */
class TrackingArchiveIntegrationTest : TrackingContractTestBase() {

    private fun archive(id: Long, tk: String = token) =
        mockMvc.post("/api/v1/trackings/$id/archive") { header("Authorization", "Bearer $tk") }

    private fun grossPnl(id: Long, tk: String = token) =
        mockMvc.get("/api/v1/trackings/$id/gross-pnl") { header("Authorization", "Bearer $tk") }

    @Test
    fun `케이스1 - snapshot 이 신선하면 MARKET_SNAPSHOT 으로 확정한다`() {
        val id = saveTracking().id
        savePremium()
        archive(id).andExpect {
            status { isOk() }
            jsonPath("$.closePriceSource") { value("MARKET_SNAPSHOT") }
            jsonPath("$.hasConfirmedClose") { value(true) }
            jsonPath("$.status") { value("ARCHIVED") }
        }
    }

    @Test
    fun `케이스2 - snapshot 이 없어도 archive 는 200 이고 SNAPSHOT_UNAVAILABLE 로 기록한다`() {
        val id = saveTracking().id
        archive(id).andExpect {
            status { isOk() }
            jsonPath("$.closePriceSource") { value("SNAPSHOT_UNAVAILABLE") }
            jsonPath("$.hasConfirmedClose") { value(false) }
        }
    }

    @Test
    fun `케이스3 - 재호출은 INVALID_TRACKING`() {
        val id = saveTracking().id
        savePremium()
        archive(id).andExpect { status { isOk() } }
        archive(id).andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    fun `케이스4 - 확정 뒤 시세가 바뀌어도 손익이 동일하다`() {
        val id = saveTracking().id
        savePremium(koreaPrice = BigDecimal("129555000"))
        archive(id).andExpect { status { isOk() } }

        val before = grossPnl(id).andReturn().response.contentAsString
        savePremium(koreaPrice = BigDecimal("150000000"))
        val after = grossPnl(id).andReturn().response.contentAsString

        val b = objectMapper.readTree(before)
        val a = objectMapper.readTree(after)
        assertThat(a.get("totalGrossPnlKrw")).isEqualTo(b.get("totalGrossPnlKrw"))
        assertThat(a.get("priceBasis").asText()).isEqualTo("ARCHIVED_SNAPSHOT")
    }

    @Test
    fun `케이스5 - 확정하지 못한 종료는 gross-pnl 에서 409`() {
        val id = saveTracking().id
        archive(id).andExpect { status { isOk() } }
        grossPnl(id).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("TRACKING_CLOSE_SNAPSHOT_UNAVAILABLE") }
        }
    }

    @Test
    fun `케이스5b - observedAt 이 미래면 확정하지 않는다`() {
        val id = saveTracking().id
        savePremium(observedAt = Instant.now().plus(Duration.ofMinutes(10)))
        archive(id).andExpect {
            status { isOk() }
            jsonPath("$.closePriceSource") { value("SNAPSHOT_UNAVAILABLE") }
        }
    }

    @Test
    fun `케이스5d - premium 은 신선해도 FX 가 낡으면 확정하지 않는다`() {
        val id = saveTracking().id
        val now = Instant.now()
        savePremium(observedAt = now, fxObservedAt = now.minus(Duration.ofMinutes(45)))
        archive(id).andExpect {
            status { isOk() }
            jsonPath("$.closePriceSource") { value("SNAPSHOT_UNAVAILABLE") }
        }
    }

    @Test
    fun `케이스6 - 동시 archive 중 정확히 하나만 확정한다`() {
        val id = saveTracking().id
        savePremium()
        val n = 6
        val start = CountDownLatch(1)
        val done = CountDownLatch(n)
        val ok = AtomicInteger(0)
        val conflict = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(n)
        repeat(n) {
            pool.submit {
                start.await()
                val status = archive(id).andReturn().response.status
                if (status == 200) ok.incrementAndGet() else conflict.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        pool.shutdownNow()

        assertThat(ok.get()).isEqualTo(1)
        assertThat(conflict.get()).isEqualTo(n - 1)
        assertThat(trackingRepository.findById(id)!!.hasConfirmedClose).isTrue()
    }

    @Test
    fun `케이스8 - 타인 소유는 TRACKING_NOT_FOUND 이고 DB 가 변하지 않는다`() {
        val other = newMember("other@example.com")
        val id = saveTracking(owner = other).id
        savePremium()

        archive(id).andExpect { status { isNotFound() } }

        val row = trackingRepository.findById(id)!!
        assertThat(row.status.name).isEqualTo("ACTIVE")
        assertThat(row.closedAt).isNull()
        assertThat(row.closePriceSource).isNull()
    }
}
