package io.premiumspread.application.job.aggregation

import io.premiumspread.application.common.JobResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class AggregationJobTest {

    @Nested
    @DisplayName("실행")
    inner class Run {

        @Test
        fun `데이터가 존재하면 writer를 호출하고 Success를 반환한다`() {
            // given
            var writerCalled = false
            val job = AggregationJob(
                reader = { _, _ -> "some-data" },
                writer = { _, _, _ -> writerCalled = true },
            )

            // when
            val result = job.run()

            // then
            assertThat(result).isEqualTo(JobResult.Success)
            assertThat(writerCalled).isTrue()
        }

        @Test
        fun `reader가 null을 반환하면 Skipped를 반환한다`() {
            // given
            val job = AggregationJob(
                reader = { _, _ -> null },
                writer = { _, _, _ -> },
            )

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Skipped::class.java)
            assertThat((result as JobResult.Skipped).reason).isEqualTo("no_data")
        }

        @Test
        fun `reader에서 예외 발생 시 Failure를 반환한다`() {
            // given
            val job = AggregationJob(
                reader = { _, _ -> throw RuntimeException("read error") },
                writer = { _, _, _ -> },
            )

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            assertThat((result as JobResult.Failure).exception.message).isEqualTo("read error")
        }

        @Test
        fun `writer에서 예외 발생 시 Failure를 반환한다`() {
            // given
            val job = AggregationJob(
                reader = { _, _ -> "data" },
                writer = { _, _, _ -> throw RuntimeException("write error") },
            )

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            assertThat((result as JobResult.Failure).exception.message).isEqualTo("write error")
        }

        @Test
        fun `reader에 올바른 시간 윈도우를 전달한다`() {
            // given
            var capturedFrom: Instant? = null
            var capturedTo: Instant? = null
            val job = AggregationJob(
                reader = { from, to ->
                    capturedFrom = from
                    capturedTo = to
                    null
                },
                writer = { _, _, _ -> },
            )

            // when
            job.run()

            // then
            assertThat(capturedFrom).isNotNull()
            assertThat(capturedTo).isNotNull()
            assertThat(capturedTo!!).isAfter(capturedFrom!!)
        }

        @Test
        fun `writer에 데이터와 시간 윈도우를 전달한다`() {
            // given
            var capturedData: Any? = null
            val job = AggregationJob(
                reader = { _, _ -> "aggregated-data" },
                writer = { data, _, _ ->
                    capturedData = data
                },
            )

            // when
            job.run()

            // then
            assertThat(capturedData).isEqualTo("aggregated-data")
        }
    }
}
