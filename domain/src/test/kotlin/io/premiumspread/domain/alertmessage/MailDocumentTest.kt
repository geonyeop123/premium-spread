package io.premiumspread.domain.alertmessage

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class MailDocumentTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val basis = Instant.parse("2026-10-20T00:00:00Z")

    private fun doc(blocks: List<MailBlock>, basis: Instant? = null, signature: String? = null) =
        MailDocument("s", blocks, basis, seoul, signature)

    @Test
    fun `Text 는 줄 그대로이고 최상위 블록 사이에 빈 줄 하나다`() {
        val body = doc(listOf(MailBlock.Text(listOf("a", "b")), MailBlock.Text(listOf("c")))).body()
        assertThat(body).isEqualTo("a\nb\n\nc")
    }

    @Test
    fun `Pairs 는 표시 폭 최댓값 더하기 2 로 정렬한다`() {
        val body = doc(
            listOf(MailBlock.Pairs(listOf(MailPair("평단", "100원"), MailPair("현재가", "110원")))),
        ).body()
        assertThat(body).isEqualTo("평단    100원\n현재가  110원")
    }

    @Test
    fun `children 은 두 칸 들여 하위 항목으로 형제끼리 정렬한다`() {
        val body = doc(
            listOf(
                MailBlock.Pairs(
                    listOf(
                        MailPair(
                            "진입 수수료",
                            "-16,173원",
                            listOf(MailPair("빗썸 진입", "-10,000원"), MailPair("바이낸스 진입", "-6,173원")),
                        ),
                        MailPair("순손익", "+1원"),
                    ),
                ),
            ),
        ).body()
        assertThat(body).isEqualTo(
            listOf(
                "진입 수수료  -16,173원",
                "  - 빗썸 진입      -10,000원",
                "  - 바이낸스 진입  -6,173원",
                "순손익       +1원",
            ).joinToString("\n"),
        )
    }

    @Test
    fun `Bullets 는 하이픈 접두이다`() {
        assertThat(doc(listOf(MailBlock.Bullets(listOf("하나", "둘")))).body()).isEqualTo("- 하나\n- 둘")
    }

    @Test
    fun `전문 - Section 안에는 빈 줄이 없고 기준 시각과 서명이 붙는다`() {
        val body = doc(
            listOf(
                MailBlock.Text(listOf("안내 문장")),
                MailBlock.Section(
                    "포지션",
                    listOf(
                        MailBlock.Pairs(listOf(MailPair("평단", "100원"), MailPair("현재가", "110원"))),
                        MailBlock.Bullets(listOf("하나", "둘")),
                    ),
                ),
                MailBlock.Section("비용", listOf(MailBlock.Text(listOf("수수료 없음")))),
                MailBlock.Text(listOf("끝")),
            ),
            basis = basis,
            signature = "premium-spread",
        ).body()

        assertThat(body).isEqualTo(
            listOf(
                "안내 문장",
                "",
                "■ 포지션",
                "평단    100원",
                "현재가  110원",
                "- 하나",
                "- 둘",
                "",
                "■ 비용",
                "수수료 없음",
                "",
                "끝",
                "",
                "기준 시각 2026-10-20 09:00 KST",
                "",
                "--",
                "premium-spread",
            ).joinToString("\n"),
        )
    }

    @Test
    fun `basis 만 있으면 기준 시각 줄로 끝난다`() {
        assertThat(doc(listOf(MailBlock.Text(listOf("a"))), basis = basis).body())
            .isEqualTo("a\n\n기준 시각 2026-10-20 09:00 KST")
    }

    @Test
    fun `basis 와 signature 가 모두 null 이면 마지막 블록으로 끝나고 개행이 없다`() {
        val body = doc(listOf(MailBlock.Text(listOf("a")), MailBlock.Bullets(listOf("b")))).body()
        assertThat(body).isEqualTo("a\n\n- b").doesNotEndWith("\n")
    }

    @Test
    fun `줄 끝 공백이 없다`() {
        val body = doc(listOf(MailBlock.Pairs(listOf(MailPair("가", ""), MailPair("나나", "x"))))).body()
        assertThat(body.lines()).allSatisfy { assertThat(it).isEqualTo(it.trimEnd()) }
    }

    @Test
    fun `빈 목록은 거부한다`() {
        assertThatThrownBy { MailBlock.Text(emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MailBlock.Pairs(emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MailBlock.Bullets(emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MailBlock.Section("t", emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Section 중첩은 거부한다`() {
        val inner = MailBlock.Section("안", listOf(MailBlock.Text(listOf("a"))))
        assertThatThrownBy { MailBlock.Section("밖", listOf(inner)) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
