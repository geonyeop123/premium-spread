package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

class AccountPnlMailContentTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val bidObservedAt = Instant.parse("2026-10-20T00:00:00Z")
    private val fxObservedAt = Instant.parse("2026-10-19T23:30:00Z")
    private val calculatedAt = Instant.parse("2026-10-20T00:00:05Z")
    private val fakeSecrets = listOf("test-access-key", "test-secret", "@example.com")

    private fun position(
        modified: Boolean = false,
        koreaFee: BigDecimal? = BigDecimal("12000"),
        commission: BigDecimal? = BigDecimal("8.9"),
        funding: BigDecimal? = BigDecimal("3.2"),
    ) = AccountHedgePosition(
        KoreaSpotAccountSnapshot(
            quantity = BigDecimal("0.266"),
            averageBuyPrice = BigDecimal("113016278"),
            averageBuyPriceModified = modified,
            entryFeeKrw = koreaFee,
            sellFeeRate = BigDecimal("0.0004"),
            bidLevels = listOf(PriceLevel(BigDecimal("109500000"), BigDecimal("1.0"))),
            lastPrice = BigDecimal("109575000"),
            bidObservedAt = bidObservedAt,
        ),
        ForeignPerpAccountSnapshot(
            signedQuantity = BigDecimal("-0.266"),
            entryPrice = BigDecimal("83549.4"),
            entryCommissionUsdt = commission,
            fundingUsdt = funding,
            takerFeeRate = BigDecimal("0.0005"),
            askLevels = listOf(PriceLevel(BigDecimal("81580"), BigDecimal("5.0"))),
            lastPrice = BigDecimal("81577"),
            askObservedAt = Instant.parse("2026-10-20T00:00:01Z"),
        ),
    )

    private fun pnl(
        netKrw: String = "456789.4",
        netPercent: String = "1.23",
        modified: Boolean = false,
        koreaFeeUnrecovered: Boolean = false,
        foreignFeeUnrecovered: Boolean = false,
        fundingUnrecovered: Boolean = false,
    ) = AccountNetPnl(
        grossKrw = BigDecimal("500000.5"),
        entryFeeKrw = BigDecimal("23823.65"),
        fundingKrw = BigDecimal("4251.2"),
        exitFeeEstimateKrw = BigDecimal("15000.1"),
        netKrw = BigDecimal(netKrw),
        netPercent = BigDecimal(netPercent),
        grossKrwAtLast = BigDecimal("490000"),
        exitFeeEstimateKrwAtLast = BigDecimal("15000"),
        netKrwAtLast = BigDecimal("455000"),
        netPercentAtLast = BigDecimal("1.20"),
        koreaExitPrice = BigDecimal("109500000"),
        foreignExitPrice = BigDecimal("81580"),
        koreaEntryFeeUnrecovered = koreaFeeUnrecovered,
        foreignEntryFeeUnrecovered = foreignFeeUnrecovered,
        fundingUnrecovered = fundingUnrecovered,
        position = position(
            modified,
            koreaFee = if (koreaFeeUnrecovered) null else BigDecimal("12000"),
            commission = if (foreignFeeUnrecovered) null else BigDecimal("8.9"),
            funding = if (fundingUnrecovered) null else BigDecimal("3.2"),
        ),
        fxRate = BigDecimal("1328.5"),
        fxObservedAt = fxObservedAt,
        calculatedAt = calculatedAt,
    )

    @Test
    fun `제목에 순수익률과 순수익금이 있다`() {
        val mail = AccountPnlMailContent.profit(pnl(), zone)

        assertThat(mail.subject).isEqualTo("[김프 알림] 순수익률 +1.23% · +456,789원")
        assertThat(mail.eventKey).isEqualTo("account-pnl-profit-1792454405000")
    }

    @Test
    fun `음수는 마이너스 부호를 쓴다`() {
        val mail = AccountPnlMailContent.profit(pnl(netKrw = "-285018.5", netPercent = "-0.98"), zone)

        assertThat(mail.subject).isEqualTo("[김프 알림] 순수익률 -0.98% · -285,019원")
    }

    @Test
    fun `본문에 계산 근거가 모두 있다`() {
        val body = AccountPnlMailContent.profit(pnl(), zone).body

        assertThat(body).contains(
            "순수익률", "순수익금", "gross", "진입 수수료", "누적 펀딩비", "예상 청산 수수료",
            "체결가 기준 순수익률", "수량", "평단", "진입가", "청산가", "최근 체결가", "환율",
            "빗썸 호가 관측", "바이낸스 호가 관측", "환율 관측", "계산 시각",
            "+1.23%", "+456,789원", "+1.20%", "113,016,278", "0.266", "83549.4", "1328.5",
            "2026-10-20 09:00:00 (2026-10-20T00:00:00Z)",
            "2026-10-20 08:30:00 (2026-10-19T23:30:00Z)",
            "2026-10-20 09:00:05 (2026-10-20T00:00:05Z)",
        )
        assertThat(body).doesNotContain("미반영", "평단 사용자 수정됨")
    }

    @Test
    fun `진입 수수료를 leg 별로 따로 보여준다`() {
        val body = AccountPnlMailContent.profit(pnl(), zone).body

        assertThat(body).contains(
            "진입 수수료: -23,824원",
            "  - 빗썸: -12,000원",
            "  - 바이낸스: -8.9 USDT (-11,824원)",
            "누적 펀딩비: +4,251원 (+3.2 USDT)",
            "청산가(호가 VWAP): 109,500,000.00원",
            "청산가(호가 VWAP): 81580.0000 USDT",
            "※ 금액은 표시 시 원 단위 반올림(계산은 전체 정밀도)",
        )
    }

    @Test
    fun `0원인 수수료와 펀딩은 부호 없이 0원으로 표시한다`() {
        val zero = pnl().copy(
            entryFeeKrw = BigDecimal("0.3"),
            fundingKrw = BigDecimal("-0.2"),
            exitFeeEstimateKrw = BigDecimal.ZERO,
            position = position().let {
                it.copy(korea = it.korea.copy(entryFeeKrw = BigDecimal.ZERO))
            },
        )

        val body = AccountPnlMailContent.profit(zero, zone).body

        assertThat(body).contains("진입 수수료: 0원", "  - 빗썸: 0원", "누적 펀딩비: 0원", "예상 청산 수수료: 0원")
        assertThat(body).doesNotContain("-0원", "+0원")
    }

    @Test
    fun `미반영 항목을 명시한다`() {
        val body = AccountPnlMailContent.profit(
            pnl(koreaFeeUnrecovered = true, foreignFeeUnrecovered = true, fundingUnrecovered = true),
            zone,
        ).body

        assertThat(body).contains(
            "진입 수수료(빗썸) 미반영(이력 없음)",
            "진입 수수료(바이낸스) 미반영(이력 없음)",
            "펀딩비 미반영(이력 없음)",
        )
    }

    @Test
    fun `평단 수정 표시를 넣는다`() {
        assertThat(AccountPnlMailContent.profit(pnl(modified = true), zone).body).contains("빗썸 평단 사용자 수정됨")
    }

    @Test
    fun `중단 메일 제목에 범주가 있다`() {
        val now = Instant.parse("2026-10-20T01:00:00Z")
        val expected = mapOf(
            AccountReadFailureCategory.BITHUMB_AUTH to "빗썸 인증 실패(허용 IP 확인)",
            AccountReadFailureCategory.BINANCE_AUTH to "바이낸스 인증 실패",
            AccountReadFailureCategory.NETWORK to "네트워크·요청 한도 오류",
            AccountReadFailureCategory.STALE_MARKET_DATA to "시세 지연",
            AccountReadFailureCategory.STALE_FX to "환율 수집 지연",
            AccountReadFailureCategory.INSUFFICIENT_DEPTH to "호가 깊이 부족",
            AccountReadFailureCategory.UNEXPECTED to "기타 오류(로그 확인)",
        )
        assertThat(expected.keys).containsExactlyInAnyOrder(*AccountReadFailureCategory.entries.toTypedArray())

        expected.forEach { (category, label) ->
            val mail = AccountPnlMailContent.outage(OutageNotice.Outage(category, bidObservedAt), now, zone)
            assertThat(mail.subject).isEqualTo("[김프 알림] 모니터링 중단 — $label")
            assertThat(mail.eventKey).isEqualTo("account-pnl-outage-1792458000000")
            assertThat(mail.body).contains(label, "2026-10-20 09:00:00 (2026-10-20T00:00:00Z)", "docs/runbooks/account-pnl-alert.md")
        }
    }

    @Test
    fun `중단 본문은 인증 힌트와 마지막 성공 없음을 표시한다`() {
        val now = Instant.parse("2026-10-20T01:00:00Z")

        val auth = AccountPnlMailContent.outage(OutageNotice.Outage(AccountReadFailureCategory.BITHUMB_AUTH, null), now, zone)
        val other = AccountPnlMailContent.outage(OutageNotice.Outage(AccountReadFailureCategory.NETWORK, null), now, zone)

        assertThat(auth.body).contains("허용 IP", "없음")
        assertThat(other.body).doesNotContain("허용 IP")
    }

    @Test
    fun `복구 메일에 복구 시각이 있다`() {
        val mail = AccountPnlMailContent.recovered(Instant.parse("2026-10-20T01:00:00Z"), zone)

        assertThat(mail.subject).isEqualTo("[김프 알림] 모니터링 복구")
        assertThat(mail.eventKey).isEqualTo("account-pnl-recovered-1792458000000")
        assertThat(mail.body).contains("2026-10-20 10:00:00 (2026-10-20T01:00:00Z)")
    }

    @Test
    fun `세 종류 eventKey 는 SMTP deliveryId 형식을 만족한다`() {
        // 출처: supports/email JavaMailEmailSender.DELIVERY_ID_PATTERN — 어긋나면 발송이 매번 예외로 끝난다
        val deliveryIdPattern = Regex("[A-Za-z0-9._-]{1,128}")
        val now = Instant.parse("2026-10-20T01:00:00Z")
        val keys = listOf(
            AccountPnlMailContent.profit(pnl(), zone).eventKey,
            AccountPnlMailContent.outage(OutageNotice.Outage(AccountReadFailureCategory.NETWORK, null), now, zone).eventKey,
            AccountPnlMailContent.recovered(now, zone).eventKey,
        )

        keys.forEach { key -> assertThat(deliveryIdPattern.matches(key)).describedAs(key).isTrue() }
        assertThat(keys).doesNotHaveDuplicates()
    }

    @Test
    fun `제목과 본문에 비밀 문자열이 없다`() {
        val now = Instant.parse("2026-10-20T01:00:00Z")
        val mails = listOf(
            AccountPnlMailContent.profit(pnl(), zone),
            AccountPnlMailContent.outage(OutageNotice.Outage(AccountReadFailureCategory.BINANCE_AUTH, null), now, zone),
            AccountPnlMailContent.recovered(now, zone),
        )
        mails.forEach { mail ->
            fakeSecrets.forEach { secret ->
                assertThat(mail.subject).doesNotContain(secret)
                assertThat(mail.body).doesNotContain(secret)
            }
        }
    }

    // api 출처 수익 메일 기준 golden. Task 1 시점(base 코드) 실제 출력이며 이후 태스크에서 고치지 않는다.
    private val apiGoldenFullEventKey = "account-pnl-profit-1792454405000"
    private val apiGoldenFullSubject = "[김프 알림] 순수익률 +1.23% · +456,789원"
    private val apiGoldenFullBody = listOf(
        "순수익률: +1.23%",
        "순수익금: +456,789원",
        "체결가 기준 순수익률(대조용): +1.20% / 순수익금 +455,000원",
        "",
        "gross: +500,001원",
        "진입 수수료: -23,824원",
        "  - 빗썸: -12,000원",
        "  - 바이낸스: -8.9 USDT (-11,824원)",
        "누적 펀딩비: +4,251원 (+3.2 USDT)",
        "예상 청산 수수료: -15,000원",
        "",
        "[빗썸 현물]",
        "수량: 0.266",
        "평단: 113,016,278원",
        "청산가(호가 VWAP): 109,500,000.00원",
        "최근 체결가: 109,575,000원",
        "",
        "[바이낸스 선물 숏]",
        "수량: 0.266",
        "진입가: 83549.4 USDT",
        "청산가(호가 VWAP): 81580.0000 USDT",
        "최근 체결가: 81577 USDT",
        "",
        "환율: 1328.5 KRW/USDT",
        "빗썸 호가 관측: 2026-10-20 09:00:00 (2026-10-20T00:00:00Z)",
        "바이낸스 호가 관측: 2026-10-20 09:00:01 (2026-10-20T00:00:01Z)",
        "환율 관측: 2026-10-20 08:30:00 (2026-10-19T23:30:00Z)",
        "계산 시각: 2026-10-20 09:00:05 (2026-10-20T00:00:05Z)",
        "",
        "※ 금액은 표시 시 원 단위 반올림(계산은 전체 정밀도)",
    ).joinToString("\n")

    private val apiGoldenUnrecoveredEventKey = "account-pnl-profit-1792454405000"
    private val apiGoldenUnrecoveredSubject = "[김프 알림] 순수익률 +1.23% · +456,789원"
    private val apiGoldenUnrecoveredBody = listOf(
        "순수익률: +1.23%",
        "순수익금: +456,789원",
        "체결가 기준 순수익률(대조용): +1.20% / 순수익금 +455,000원",
        "",
        "gross: +500,001원",
        "진입 수수료: -23,824원",
        "  - 빗썸: 진입 수수료(빗썸) 미반영(이력 없음)",
        "  - 바이낸스: 진입 수수료(바이낸스) 미반영(이력 없음)",
        "누적 펀딩비: +4,251원  펀딩비 미반영(이력 없음)",
        "예상 청산 수수료: -15,000원",
        "",
        "[빗썸 현물]",
        "수량: 0.266",
        "평단: 113,016,278원",
        "청산가(호가 VWAP): 109,500,000.00원",
        "최근 체결가: 109,575,000원",
        "",
        "[바이낸스 선물 숏]",
        "수량: 0.266",
        "진입가: 83549.4 USDT",
        "청산가(호가 VWAP): 81580.0000 USDT",
        "최근 체결가: 81577 USDT",
        "",
        "환율: 1328.5 KRW/USDT",
        "빗썸 호가 관측: 2026-10-20 09:00:00 (2026-10-20T00:00:00Z)",
        "바이낸스 호가 관측: 2026-10-20 09:00:01 (2026-10-20T00:00:01Z)",
        "환율 관측: 2026-10-20 08:30:00 (2026-10-19T23:30:00Z)",
        "계산 시각: 2026-10-20 09:00:05 (2026-10-20T00:00:05Z)",
        "",
        "※ 금액은 표시 시 원 단위 반올림(계산은 전체 정밀도)",
    ).joinToString("\n")

    @Test
    fun `api 출처 수익 메일은 기준 golden 과 같다 - 모든 항목 확보`() {
        val mail = AccountPnlMailContent.profit(pnl(), zone)

        assertThat(mail.eventKey).isEqualTo(apiGoldenFullEventKey)
        assertThat(mail.subject).isEqualTo(apiGoldenFullSubject)
        assertThat(mail.body).isEqualTo(apiGoldenFullBody)
    }

    @Test
    fun `api 출처 수익 메일은 기준 golden 과 같다 - 진입 수수료와 펀딩 미확보`() {
        val mail = AccountPnlMailContent.profit(
            pnl(koreaFeeUnrecovered = true, foreignFeeUnrecovered = true, fundingUnrecovered = true),
            zone,
        )

        assertThat(mail.eventKey).isEqualTo(apiGoldenUnrecoveredEventKey)
        assertThat(mail.subject).isEqualTo(apiGoldenUnrecoveredSubject)
        assertThat(mail.body).isEqualTo(apiGoldenUnrecoveredBody)
    }

    private fun manual(pnl: AccountNetPnl): AccountNetPnl = pnl.copy(
        position = pnl.position.copy(
            foreign = pnl.position.foreign.copy(positionSource = ForeignPerpPositionSource.MANUAL_ENV),
        ),
    )

    @Test
    fun `수동 출처면 바이낸스 선물 숏 다음 줄에 수동 입력 표기`() {
        val lines = AccountPnlMailContent.profit(manual(pnl()), zone).body.split("\n")

        val index = lines.indexOf("[바이낸스 선물 숏]")
        assertThat(index).isGreaterThanOrEqualTo(0)
        assertThat(lines[index + 1]).isEqualTo("바이낸스 포지션: 수동 입력(env)")
    }

    @Test
    fun `수동 출처 진입 수수료와 펀딩 입력값에 수동 입력 표기`() {
        val lines = AccountPnlMailContent.profit(manual(pnl()), zone).body.split("\n")

        assertThat(lines).contains(
            "  - 바이낸스: -8.9 USDT (-11,824원) 수동 입력",
            "누적 펀딩비: +4,251원 (+3.2 USDT) 수동 입력",
        )
    }

    @Test
    fun `수동 출처 미입력은 수동 미입력으로 표기하고 이력 없음을 쓰지 않는다`() {
        val lines = AccountPnlMailContent.profit(
            manual(pnl(foreignFeeUnrecovered = true, fundingUnrecovered = true)),
            zone,
        ).body.split("\n")

        val binance = "  - 바이낸스: 진입 수수료(바이낸스) 미반영(수동 미입력)"
        val funding = "누적 펀딩비: +4,251원  펀딩비 미반영(수동 미입력)"
        assertThat(lines).contains(binance, funding)
        assertThat(binance).doesNotContain("이력 없음")
        assertThat(funding).doesNotContain("이력 없음")
        assertThat(lines.filter { it.contains("바이낸스: ") || it.startsWith("누적 펀딩비") })
            .noneMatch { it.contains("이력 없음") }
    }

    @Test
    fun `수동 출처여도 빗썸 미반영 문구는 이력 없음 그대로`() {
        val lines = AccountPnlMailContent.profit(manual(pnl(koreaFeeUnrecovered = true)), zone).body.split("\n")

        assertThat(lines).contains("  - 빗썸: 진입 수수료(빗썸) 미반영(이력 없음)")
    }

    @Test
    fun `수동 출처여도 제목은 같다`() {
        val original = pnl()

        assertThat(AccountPnlMailContent.profit(manual(original), zone).subject)
            .isEqualTo(AccountPnlMailContent.profit(original, zone).subject)
    }

    @Test
    fun `정시 보고 제목은 버킷 시각과 순수익률 순수익금을 쓴다`() {
        val mail = AccountPnlMailContent.hourly(pnl(), zone)

        assertThat(mail.subject).isEqualTo("[김프 알림] 정시 보고 09:00 · 순수익률 +1.23% · +456,789원")
    }

    @Test
    fun `정시 보고 제목의 시각은 지정한 zone 의 정시다`() {
        val utc = AccountPnlMailContent.hourly(pnl(), ZoneId.of("UTC"))

        assertThat(utc.subject).startsWith("[김프 알림] 정시 보고 00:00 · ")
    }

    @Test
    fun `정시 보고 본문은 안내 한 줄과 빈 줄 뒤에 수익 메일 본문이 그대로 온다`() {
        val hourly = AccountPnlMailContent.hourly(pnl(), zone).body
        val profit = AccountPnlMailContent.profit(pnl(), zone).body

        assertThat(hourly.lines().first()).isEqualTo("정시 보고 — 1% 알림 기준과 무관하게 매시간 발송된다.")
        assertThat(hourly.lines()[1]).isEmpty()
        assertThat(hourly.lines().drop(2).joinToString("\n")).isEqualTo(profit)
    }

    @Test
    fun `정시 보고 eventKey 는 같은 시간대면 같고 버킷 시작 시각이다`() {
        val first = AccountPnlMailContent.hourly(pnl(), zone)
        val sameHour = pnl().copy(calculatedAt = Instant.parse("2026-10-20T00:59:59Z"))
            .let { AccountPnlMailContent.hourly(it, zone) }
        val nextHour = pnl().copy(calculatedAt = Instant.parse("2026-10-20T01:00:00Z"))
            .let { AccountPnlMailContent.hourly(it, zone) }

        assertThat(first.eventKey).isEqualTo("account-pnl-hourly-1792454400000")
        assertThat(sameHour.eventKey).isEqualTo(first.eventKey)
        assertThat(nextHour.eventKey).isEqualTo("account-pnl-hourly-1792458000000")
    }

    // 정시 보고·중단·복구 메일 기준 golden. Task 1 시점(base 1bce3dd) 실제 출력이며 이후 태스크에서 고치지 않는다.
    private val goldenHourlyEventKey = "account-pnl-hourly-1792454400000"
    private val goldenHourlySubject = "[김프 알림] 정시 보고 09:00 · 순수익률 +1.23% · +456,789원"
    private val goldenHourlyBody = """
        |정시 보고 — 1% 알림 기준과 무관하게 매시간 발송된다.
        |
        |순수익률: +1.23%
        |순수익금: +456,789원
        |체결가 기준 순수익률(대조용): +1.20% / 순수익금 +455,000원
        |
        |gross: +500,001원
        |진입 수수료: -23,824원
        |  - 빗썸: -12,000원
        |  - 바이낸스: -8.9 USDT (-11,824원)
        |누적 펀딩비: +4,251원 (+3.2 USDT)
        |예상 청산 수수료: -15,000원
        |
        |[빗썸 현물]
        |수량: 0.266
        |평단: 113,016,278원
        |청산가(호가 VWAP): 109,500,000.00원
        |최근 체결가: 109,575,000원
        |
        |[바이낸스 선물 숏]
        |수량: 0.266
        |진입가: 83549.4 USDT
        |청산가(호가 VWAP): 81580.0000 USDT
        |최근 체결가: 81577 USDT
        |
        |환율: 1328.5 KRW/USDT
        |빗썸 호가 관측: 2026-10-20 09:00:00 (2026-10-20T00:00:00Z)
        |바이낸스 호가 관측: 2026-10-20 09:00:01 (2026-10-20T00:00:01Z)
        |환율 관측: 2026-10-20 08:30:00 (2026-10-19T23:30:00Z)
        |계산 시각: 2026-10-20 09:00:05 (2026-10-20T00:00:05Z)
        |
        |※ 금액은 표시 시 원 단위 반올림(계산은 전체 정밀도)
    """.trimMargin()

    private val goldenBithumbAuthOutageEventKey = "account-pnl-outage-1792465800000"
    private val goldenBithumbAuthOutageSubject = "[김프 알림] 모니터링 중단 — 빗썸 인증 실패(허용 IP 확인)"
    private val goldenBithumbAuthOutageBody = """
        |실계정 순손익 모니터링이 5분 넘게 중단되었습니다.
        |원인 범주: 빗썸 인증 실패(허용 IP 확인)
        |마지막 성공: 없음
        |알림 시각: 2026-10-20 12:10:00 (2026-10-20T03:10:00Z)
        |빗썸 Open API key 의 허용 IP 가 현재 공인 IP 와 같은지 확인하세요.
        |조치 안내: docs/runbooks/account-pnl-alert.md
    """.trimMargin()

    private val goldenStaleFxOutageEventKey = "account-pnl-outage-1792465800000"
    private val goldenStaleFxOutageSubject = "[김프 알림] 모니터링 중단 — 환율 수집 지연"
    private val goldenStaleFxOutageBody = """
        |실계정 순손익 모니터링이 5분 넘게 중단되었습니다.
        |원인 범주: 환율 수집 지연
        |마지막 성공: 2026-10-20 12:00:00 (2026-10-20T03:00:00Z)
        |알림 시각: 2026-10-20 12:10:00 (2026-10-20T03:10:00Z)
        |조치 안내: docs/runbooks/account-pnl-alert.md
    """.trimMargin()

    private val goldenRecoveredEventKey = "account-pnl-recovered-1792466100000"
    private val goldenRecoveredSubject = "[김프 알림] 모니터링 복구"
    private val goldenRecoveredBody = """
        |실계정 순손익 모니터링이 복구되었습니다.
        |복구 시각: 2026-10-20 12:15:00 (2026-10-20T03:15:00Z)
    """.trimMargin()

    @Test
    fun `정시 보고 메일은 기준 golden 과 같다`() {
        val mail = AccountPnlMailContent.hourly(pnl(), zone)

        assertThat(mail.eventKey).isEqualTo(goldenHourlyEventKey)
        assertThat(mail.subject).isEqualTo(goldenHourlySubject)
        assertThat(mail.body).isEqualTo(goldenHourlyBody)
    }

    @Test
    fun `빗썸 인증 중단 메일은 기준 golden 과 같다`() {
        val now = Instant.parse("2026-10-20T03:10:00Z")

        val mail = AccountPnlMailContent.outage(OutageNotice.Outage(AccountReadFailureCategory.BITHUMB_AUTH, null), now, zone)

        assertThat(mail.eventKey).isEqualTo(goldenBithumbAuthOutageEventKey)
        assertThat(mail.subject).isEqualTo(goldenBithumbAuthOutageSubject)
        assertThat(mail.body).isEqualTo(goldenBithumbAuthOutageBody)
    }

    @Test
    fun `환율 지연 중단 메일은 기준 golden 과 같다`() {
        val now = Instant.parse("2026-10-20T03:10:00Z")
        val notice = OutageNotice.Outage(AccountReadFailureCategory.STALE_FX, Instant.parse("2026-10-20T03:00:00Z"))

        val mail = AccountPnlMailContent.outage(notice, now, zone)

        assertThat(mail.eventKey).isEqualTo(goldenStaleFxOutageEventKey)
        assertThat(mail.subject).isEqualTo(goldenStaleFxOutageSubject)
        assertThat(mail.body).isEqualTo(goldenStaleFxOutageBody)
    }

    @Test
    fun `복구 메일은 기준 golden 과 같다`() {
        val mail = AccountPnlMailContent.recovered(Instant.parse("2026-10-20T03:15:00Z"), zone)

        assertThat(mail.eventKey).isEqualTo(goldenRecoveredEventKey)
        assertThat(mail.subject).isEqualTo(goldenRecoveredSubject)
        assertThat(mail.body).isEqualTo(goldenRecoveredBody)
    }
}
