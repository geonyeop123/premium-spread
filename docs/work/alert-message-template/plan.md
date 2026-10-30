# 알림 문구 템플릿화와 문구 정리 (issue #102) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** owner 알림 21종 + 회원 김프 메일 1종의 문구를 domain 템플릿 계층(`domain.alertmessage`)으로 모으고, message-spec.md 의 확정 문구로 바꾼다.
Discord 는 embed author·fields·footer·timestamp 로 보내고, 그 레이아웃을 outbox `account_pnl_mail.discord_layout`(V23)에 보존한다.

**Architecture:** 템플릿 = family 별 object(`AccountPnlAlertContent`·`AccountAlertContent`·`OwnerOpsAlertContent`·`OwnerMarketAlertContent`·`PremiumThresholdMailContent`)가
`MailDocument`·`EmbedDocument` 를 만들고, `alertmessage` 렌더러가 메일 본문 문자열과 `DiscordMessage(layout)` 로 바꾼다. Domain port `OwnerAlertDiscordSender` 시그니처는 그대로이고
`DiscordMessage` 가 선택 프로퍼티 `layout` 을 갖는다. infra:common 이 layout 을 JSON 으로 저장·복원하고, infra:batch adapter 가 embed payload 로 보낸다.

**Tech Stack:** Kotlin 2.x, Spring Boot 3.x, JDBC, Flyway·MySQL 8(Testcontainers), Jackson tree API(infra 만), JUnit5 + AssertJ + MockK, OkHttp MockWebServer(기존 batch IT 기반).

**Spec:** `docs/work/alert-message-template/design.md` · **DoD:** `docs/work/alert-message-template/dod.md` · **문구 정본:** `docs/work/alert-message-template/message-spec.md`

## Global Constraints

- base `3d179e7`(Task 0 확인). 과거 `docs/work/*` 산출물은 수정하지 않는다. message-spec.md 도 수정하지 않는다.
- **문구는 message-spec.md 가 정본이다. 이 plan 은 문구를 복제하지 않는다.** golden 기대 문자열은 구현자가 spec 의 해당 절에서 옮겨 적고, 숫자·시각만 테스트 입력에서 나온 값으로 바꾼다.
  칸 값의 줄 구성(⏎ 위치)도 spec 그대로다. 칸 값·라벨의 **데이터 출처**는 design §6 이 정한다. spec 과 design §6 이 다르면 멈추고 보고한다.
- 기존 테스트 수정은 dod R3 허용 목록 안에서만, 그 파일도 문구 단정 줄·기대 행 구성 줄·호출 줄·import 만(새 단정 추가 금지 — 새 단정은 신규 파일). 목록 밖을 고쳐야 하면 **멈추고 보고**.
- A단계(Task 1~7)는 동작 보존이다 — 기존 Content object 의 문구 코드를 바꾸지 않고, 기존 golden(`AccountPnlMailContentTest`·`AccountPnlDiscordContentTest`·`AccountAlertContentTest`·
  `OwnerOpsAlertContentTest`·`OwnerMarketAlertContentTest`·`OwnerAlertRowsTest`·`DiscordMessageTest`)을 **수정하지 않은 채** 통과시킨다(dod R14).
- Domain 에 새 framework·라이브러리 의존 금지(`java.time`·`java.text`·`java.math`·기존 domain 타입만). 앱 main 은 infrastructure 타입을 import 하지 않는다.
- eventKey 생성 함수 본문 무변경(dod R12). `OwnerAlertKind`·라우팅·발송 조건 무변경.
- webhook URL·수신 주소·예외 메시지·본문·칸 값을 로그에 남기지 않는다. 새 로그는 Task 6 의 WARN 하나뿐.
- 테스트: 고정 `Instant` + `ZoneId.of("Asia/Seoul")`. URL `https://discord.example.test/...`. 실 webhook·key 금지.
- 컴파일 실패를 RED 로 쓰지 않는다. RED 는 stub(빈 문자열·`TODO` 없는 고정 반환) 또는 변이에서의 **assertion 실패**다. RED·GREEN 로그를 dod `## 증거 로그` 에 append 한다.
- 각 태스크 커밋 시점에 `./gradlew test architectureTest --offline --no-daemon` 초록. integrationTest 소스도 컴파일되는 상태로 둔다.
- 커밋: `<type>: <subject>` + 한글 bullet, `Co-Authored-By` 금지. 모든 gradle 명령 `--offline --no-daemon`.
- 사용자 결정(design §13): D1 jackson-databind 선언 추가 승인, D2 평단 수정 표시 삭제 승인, A4 항목별 반올림·±1원 허용 승인, A8 철회(줄 구성 spec 그대로), N1 회원 zone 은 Configuration `@Bean` 주입.
- 보이지 않는 문자(U+200B 등)를 소스·문서에 직접 넣지 않는다. 소스에서는 `Char(0x200B)` 처럼 코드값으로 만든다.

## Review Focus

1. **spec 구조 일치** — 22종 각각 author·제목 형식·칸 이름·행별 칸 수·칸 값 줄 구성·footer·메일 섹션·라벨 순서·기준 시각 줄 유무가 spec 과 같은가(Task 8~13).
2. **동작 보존** — A단계에서 기존 golden 이 무수정 통과하는가, eventKey·라우팅·행 수가 그대로인가(Task 2~7, 8~11 Job 테스트).
3. **옛 행·손상 행** — `discord_layout` NULL·깨진 JSON·조립 실패 행이 claim 을 멈추지 않고 제목·설명 embed 로 가는가, 로그에 내용이 없는가(Task 6·7·14).
4. **계산 일치** — `AccountPnlBreakdown` 의 손익·정리 수수료·순손익이 `AccountNetPnl` 과 같은가(Task 4).
5. **시간·pair** — 모든 표시 시각이 주입 zone 의 KST 이고 UTC 원문이 없는가, 거래소 한글 표기가 알림 자신의 pair 에서 오는가, 회원 zone 이 Configuration 에서 오는가(Task 3·11·12).
6. **Discord 한도** — 칸 25·값 1024·합계 6000 경계와 2칸 행 채움 규칙(Task 2·3).

---

## File Structure

| 파일 | 책임 | 태스크 |
|---|---|---|
| `apps/batch/src/test/kotlin/io/premiumspread/application/notification/PremiumThresholdDeliveryServiceMailTest.kt` (신규) | 회원 메일 특성화 → 새 golden | 1·12 |
| `domain/src/main/kotlin/io/premiumspread/domain/owneralert/OwnerAlertDiscord.kt` | `DiscordField`·`DiscordLayout`·`DiscordMessage.layout` | 2 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlMailContent.kt` | `AccountPnlMail.discordLayout`(2), 파일 삭제·클래스 이동(8) | 2·8 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/OwnerAlertRows.kt` | 옛 overload 가 layout 복사(2), 새 overload(3), 옛 overload 삭제(13) | 2·3·13 |
| `domain/src/main/kotlin/io/premiumspread/domain/alertmessage/AlertFormat.kt` (신규) | 공용 서식 | 3 |
| `domain/src/main/kotlin/io/premiumspread/domain/alertmessage/MailDocument.kt` (신규) | `MailPair`·`MailBlock`·`MailDocument` + 렌더 | 3 |
| `domain/src/main/kotlin/io/premiumspread/domain/alertmessage/EmbedDocument.kt` (신규) | `EmbedCell`·`EmbedDocument` + 렌더 | 3 |
| `domain/src/main/kotlin/io/premiumspread/domain/alertmessage/OwnerAlertMessage.kt` (신규) | 알림 하나의 결과 | 3 |
| `domain/src/test/kotlin/io/premiumspread/domain/alertmessage/AlertFormatTest.kt` (신규) | 서식 + `AccountPnlFormat` 직접 비교(3) → 리터럴 고정(13) | 3·13 |
| `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/OwnerAlertRowsMessageTest.kt` (신규) | 새 overload + `AccountPnlMail` 불변식(삭제될 `OwnerAlertRowsTest:24,31` 이식) | 3 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlBreakdown.kt` (신규) | 수익·정시 새 값 | 4 |
| `infrastructure/common/src/main/resources/db/migration/V23__add_owner_alert_discord_layout.sql` (신규) | DDL | 5 |
| `apps/batch/src/integrationTest/resources/batch-schema.sql` | `account_pnl_mail.discord_layout` | 5 |
| `infrastructure/common/build.gradle.kts` | `jackson-databind` 선언 (design D1 — 승인됨) | 6 |
| `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/DiscordLayoutJson.kt` (신규) | 직렬화 | 6 |
| `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/OwnerAlertOutboxRows.kt` | validate·insert | 6 |
| `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcAccountPnlAlertStore.kt` | claim SELECT·매핑·조립 확인·WARN | 6 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/notification/DiscordWebhookSenderAdapter.kt` | `payload` | 7 |
| `apps/batch/src/main/kotlin/io/premiumspread/application/notification/owneralert/OwnerAlertDispatcher.kt` | `sendDiscord` 한 줄 | 7 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlAlertContent.kt` (신규), `AccountPnlDiscordContent.kt`(삭제), `OwnerAlertOutbox.kt`(`AccountPnlMail` 이동 대상), `NetPercentSwingPolicy.kt`(:12 KDoc) | 실계정 순손익 | 8 |
| `apps/batch/src/main/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJob.kt` | 호출 줄(8·9) | 8·9 |
| `apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJobTemplateRowsTest.kt` (신규) | dod AC15(b) — PnL Job | 8·9 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountAlertContent.kt` | 계정 알림 | 9 |
| `domain/src/main/kotlin/io/premiumspread/domain/ownerops/OwnerOpsAlertContent.kt`, `apps/batch/src/main/kotlin/io/premiumspread/application/job/ownerops/OwnerOpsSignalJob.kt` | 운영 | 10 |
| `apps/batch/src/test/kotlin/io/premiumspread/application/job/ownerops/OwnerOpsSignalJobTemplateRowsTest.kt` (신규) | dod AC15(b) — 운영 Job | 10 |
| `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/OwnerMarketAlertContent.kt`, `apps/batch/src/main/kotlin/io/premiumspread/application/job/ownermarket/OwnerMarketSignalJob.kt` | 시장 | 11 |
| `apps/batch/src/test/kotlin/io/premiumspread/application/job/ownermarket/OwnerMarketSignalJobTemplateRowsTest.kt` (신규) | dod AC15(b) — 시장 Job | 11 |
| `domain/src/main/kotlin/io/premiumspread/domain/notification/PremiumThresholdMailContent.kt` (신규), `apps/batch/src/main/kotlin/io/premiumspread/application/notification/PremiumThresholdDeliveryService.kt`, `apps/batch/src/main/kotlin/io/premiumspread/config/NotificationDeliveryConfiguration.kt` | 회원 | 12 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlFormat.kt` (삭제) | 정리 | 13 |
| `apps/batch/src/integrationTest/kotlin/io/premiumspread/application/job/OwnerAlertDiscordLayoutIntegrationTest.kt` (신규) | E2E | 14 |
| `docs/runbooks/{account-pnl-alert,owner-ops-alert,owner-market-alert}.md`, `.ai/PROJECT_STATUS.md`, `.ai/architecture/ARCHITECTURE_DESIGN.md` | 문서 | 15 |

---

### Task 0: Gate 1 기준선

**Files:** 없음 (dod `## 증거 로그` 에 append)

- [ ] **Step 1:** 기준선 실행·기록.
  ```bash
  git rev-parse --short HEAD && git merge-base HEAD origin/dev | cut -c1-7                                   # base 3d179e7
  ./gradlew test architectureTest --offline --no-daemon                                                    # R1·R2 — 테스트 수 기록
  ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon                                  # R4
  ./gradlew :infrastructure:common:integrationTest --offline --no-daemon                                   # R5 (Docker)
  ./gradlew :apps:batch:integrationTest --offline --no-daemon                                              # R6 (Docker)
  ./gradlew :apps:api:integrationTest --offline --no-daemon                                                # R7 (Docker)
  ls infrastructure/common/src/main/resources/db/migration | sort -V | tail -1                             # (g1) V23 번호 근거
  ./gradlew :infrastructure:common:dependencies --configuration compileClasspath --offline --no-daemon | grep -m1 'jackson-databind'   # (g2) D1 전이 존재
  grep -rn 'columns()).containsExactly\|columns("account_pnl_mail")' --include=*.kt infrastructure/common/src/integrationTest apps/batch/src/integrationTest   # (g3) 열 고정 단언
  grep -rnE '\.(subject|body|title|description)\b' --include=*.kt apps/batch/src/test apps/batch/src/integrationTest infrastructure/batch/src/test   # (g4) R3 허용 예외 대상 줄
  grep -n 'MockWebServer()\|DiscordWebhooks(\|DiscordWebhookSenderAdapter(\|OwnerAlertRouting(' apps/batch/src/integrationTest/kotlin/io/premiumspread/application/job/OwnerAlertDeliveryIntegrationTest.kt   # (g5) AC16 mock Discord 기반
  grep -n 'fun newSignalJob\|fun newDeliveryJob\|OwnerAlertRouting(' apps/batch/src/integrationTest/kotlin/io/premiumspread/application/job/OwnerMarketAlertIntegrationTest.kt   # (g6) AC16 시장 회차 기반
  grep -rlE 'AccountPnlFormat\.|OwnerOpsAlertContent\.threshold' --include=*.kt domain/src/main | sort   # (g7) Task 13 삭제 전 사용 파일
  grep -n 'account_pnl_mail (\|discord_color  INT\|ck_account_pnl_mail_channel' apps/batch/src/integrationTest/resources/batch-schema.sql   # (g8) fixture 정의
  grep -rn 'aggregation.zone' --include=*.kt apps/batch/src/main                                          # (g9) zone 키
  grep -rnE 'OwnerOpsAlertContent\.(RUNBOOK|elapsed)|OwnerMarketAlertContent\.RUNBOOK' --include=*.kt domain apps infrastructure   # (g10) Task 10·11 삭제 심볼의 외부 사용처
  grep -n 'PremiumThresholdDeliveryService' apps/batch/src/test/kotlin/io/premiumspread/application/notification/NotificationDisabledContextTest.kt   # (g11) Task 12 A14 근거
  ```
- [ ] **Step 2: 예상 결과.** gradle 6건 exit 0(Docker 부재면 integration 3건 "미실행", green 금지).
  - (g1) `V22__create_owner_market_alert_state.sql`.
  - (g2) `com.fasterxml.jackson.core:jackson-databind` 한 줄.
  - (g3) `V19OwnerAlertChannelMigrationIntegrationTest.kt:34`, `AccountPnlFixtureSchemaIntegrationTest.kt:45`.
  - (g4) dod R3 수정 허용 파일들과 `OwnerMarketSignalJobTest.kt:235`·`OwnerAccountAlertIntegrationTest.kt:111`(둘 다 무수정 통과 예정)의 줄만. 그 밖 파일이면 R3 목록을 Gate 1 에서 다시 정한다.
  - (g5) `:63` `MockWebServer()`, `:79` `DiscordWebhooks(`, `:84` `DiscordWebhookSenderAdapter(`, `:94` `OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true))`.
  - (g6) `:105` `fun newSignalJob`, `:119` `fun newDeliveryJob`, `:298` `OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true))`.
  - (g7) 5개 파일 — `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/{AccountAlertContent,AccountPnlDiscordContent,AccountPnlMailContent}.kt`, `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/OwnerMarketAlertContent.kt`, `domain/src/main/kotlin/io/premiumspread/domain/ownerops/OwnerOpsAlertContent.kt`. 모두 Task 8~11 이 재작성·삭제한다.
  - (g8) `:225` `CREATE TABLE IF NOT EXISTS account_pnl_mail (`, `:240` `discord_color  INT          NULL,`, `:242` `CONSTRAINT ck_account_pnl_mail_channel CHECK (`.
  - (g9) `OwnerAlertDeliveryConfiguration.kt:153`·`OwnerMarketAlertConfiguration.kt:67`.
  - (g10) `domain/src/test/kotlin/io/premiumspread/domain/ownerops/OwnerOpsAlertContentTest.kt:264-266`(`elapsed`) 3줄만 — Task 10 이 교체하는 파일이다. main 사용처 없음.
  - (g11) `:33` `doesNotHaveBean(PremiumThresholdDeliveryService::class.java)`, `:52` `PremiumThresholdDeliveryService::class,`(`@Import`).
  **다르면 멈추고 보고한다** — (g1) 이 V22 가 아니면 V23 번호, (g2) 가 비면 D1 전제(전이 존재)가 깨진 것이므로 변경 요청, (g10) 에 main 사용처가 있으면 Task 10·11 의 삭제 범위.
- [ ] **Step 3: Commit** — `docs: 알림 문구 템플릿화 Gate 1 기준선 기록` (dod 증거 로그만)

---

## A단계 — 동작 보존

### Task 1: 회원 김프 메일 특성화 테스트 (AC9-a)

**Files:** Create `apps/batch/src/test/kotlin/io/premiumspread/application/notification/PremiumThresholdDeliveryServiceMailTest.kt`. main 무변경.

**테스트가 재는 것:** 기존 `PremiumThresholdDeliveryServiceTest` 와 같은 mockk 구성(`subscriptions`·`transactions` slot, `Clock.fixed(2026-07-15T00:30:00Z, UTC)`, `MarketPair.default(Symbol("BTC"))`, 닉네임 `member`)으로
`NewNotificationDelivery.subject`·`payload` 를 `isEqualTo`.

| 경우 | 입력 | 기대 subject | 기대 payload 의 바뀌는 줄 |
|---|---|---|---|
| ABOVE | threshold `5.00`, rate `6`, observedAt `2026-07-15T00:30:00Z` | `[premium-spread] BTC 프리미엄 6% 도달` | `조건: 5% 이상 (ABOVE)` · `현재 프리미엄: 6%` · `관측 시각: 2026-07-15T00:30:00Z` |
| BELOW | threshold `-1`, rate `-1.5` | `[premium-spread] BTC 프리미엄 -1.5% 도달` | `조건: -1% 이하 (BELOW)` · `현재 프리미엄: -1.5%` |

payload 전문은 현재 `PremiumThresholdDeliveryService.kt:56-69` 의 `trimIndent()` 결과(인사·빈 줄·`설정하신 알림 조건이 충족되었습니다.`·`심볼: BTC`·`거래소 페어: BTC:BITHUMB:BINANCE`·위 3줄·빈 줄·`--`·`premium-spread`)를 그대로 적는다.

- [ ] **Step 1:** 테스트 작성 → `./gradlew :apps:batch:test --tests 'io.premiumspread.application.notification.PremiumThresholdDeliveryServiceMailTest' --offline --no-daemon` → PASS (특성화).
- [ ] **Step 2: 변이 확인** — 기대 subject 의 `6%` 를 `7%` 로 바꿔 FAIL 을 보고 되돌린다. 로그를 증거 로그에.
- [ ] **Step 3: Commit** — `test: 회원 김프 메일 문구 특성화 테스트`

### Task 2: Discord 레이아웃 domain 타입 (AC1)

**Files:** Modify `domain/src/main/kotlin/io/premiumspread/domain/owneralert/OwnerAlertDiscord.kt`, `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlMailContent.kt`(클래스 `AccountPnlMail` 만),
`domain/src/main/kotlin/io/premiumspread/domain/accountpnl/OwnerAlertRows.kt`(옛 overload 의 DISCORD 행에 `discordLayout = message.layout` 한 줄);
Test Create `domain/src/test/kotlin/io/premiumspread/domain/owneralert/DiscordLayoutTest.kt`.

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.domain.owneralert

/** embed 칸 하나. 이름 1~256자·값 1~1024자(UTF-16), 둘 다 공백만이면 안 된다. 빈 칸은 U+200B 한 글자(zero-width space — isBlank 가 false). */
data class DiscordField(val name: String, val value: String, val inline: Boolean) {
    init {
        require(name.isNotBlank() && name.length <= DiscordMessage.FIELD_NAME_MAX) { "field name must be 1..256 characters." }
        require(value.isNotBlank() && value.length <= DiscordMessage.FIELD_VALUE_MAX) { "field value must be 1..1024 characters." }
    }
}

/** title·color·description 밖의 embed 요소. null 이면 그 요소를 보내지 않는다(fields 는 항상 보낸다). */
data class DiscordLayout(
    val author: String?,
    val fields: List<DiscordField>,
    val footer: String?,
    val timestamp: Instant?,
) {
    init {
        require(author == null || (author.isNotBlank() && author.length <= DiscordMessage.AUTHOR_MAX)) { "author must be 1..256 characters." }
        require(footer == null || (footer.isNotBlank() && footer.length <= DiscordMessage.FOOTER_MAX)) { "footer must be 1..2048 characters." }
        require(fields.size <= DiscordMessage.FIELDS_MAX) { "fields must be at most 25." }
    }
}

/**
 * Discord embed 1개. [layout] 이 있으면 adapter 는 description 을 보내지 않는다(description = 롤백용 대체 평문).
 * 기본 생성자는 상한을 검증만 하고, [of] 가 title·description 을 절단한다.
 */
data class DiscordMessage(
    val title: String,
    val description: String,
    val color: Int,
    val layout: DiscordLayout? = null,
) {
    init {
        // 기존 4개 require 그대로 + 아래
        require(layout == null || layout.totalLength(title) <= EMBED_TOTAL_MAX) { "embed must be at most $EMBED_TOTAL_MAX characters in total." }
    }

    companion object {
        const val TITLE_MAX = 255
        const val DESCRIPTION_MAX = 4096
        const val CONTENT_MAX = 2000
        const val EMBED_TOTAL_MAX = 6000
        const val COLOR_MAX = 0xFFFFFF
        const val FIELD_NAME_MAX = 256
        const val FIELD_VALUE_MAX = 1024
        const val FIELDS_MAX = 25
        const val FOOTER_MAX = 2048
        const val AUTHOR_MAX = 256
        const val ELLIPSIS = "…"
        fun of(title: String, description: String, color: Int, layout: DiscordLayout? = null): DiscordMessage
        fun truncate(text: String, max: Int): String   // 기존 그대로
    }
}

/** title + author + footer + 칸 이름·값 길이 합(description 제외). DiscordMessage 검증용. */
internal fun DiscordLayout.totalLength(title: String): Int
```

```kotlin
// domain/.../accountpnl/AccountPnlMailContent.kt — AccountPnlMail 만 변경
data class AccountPnlMail(
    val eventKey: String,
    val subject: String,
    val body: String,
    val channel: OwnerAlertChannel = OwnerAlertChannel.EMAIL,
    val discordRoute: DiscordRoute? = null,
    val discordColor: Int? = null,
    /** DISCORD 행의 embed 레이아웃. null = 제목·설명만(V23 이전 행 포함). */
    val discordLayout: DiscordLayout? = null,
) {
    init {
        // 기존 2개 require 그대로 + 아래
        require(channel == OwnerAlertChannel.DISCORD || discordLayout == null) { "discordLayout must be set only for DISCORD rows." }
    }
}
```

`toString` 노출(자동): `DiscordField`(name·value·inline), `DiscordLayout`(author·fields·footer·timestamp), `DiscordMessage`(title·description·color·layout), `AccountPnlMail`(+discordLayout) —
실계정 금액을 담으므로 로그 인자로 쓰지 않는다(현재 사용처 없음).

**테스트가 재는 것:** dod AC1 (a)~(d) 의 경계값 전부(U+200B 한 글자 name·value 통과 포함 — 테스트도 `Char(0x200B).toString()` 으로 만든다). 합계 6000/6001 은 title 255 + author 256 + footer 2048 + 칸들로 맞춘다. description 4096 + layout 합계 6000 이 통과(description 제외 확인).

- [ ] **Step 1:** `DiscordLayout`·`DiscordField` 를 검증 없는 stub 으로 두고 테스트 작성 → FAIL(거부 기대가 통과하지 않음) 로그.
- [ ] **Step 2:** 구현 → `./gradlew :domain:test --tests 'io.premiumspread.domain.owneralert.*' --tests 'io.premiumspread.domain.accountpnl.*' --offline --no-daemon` → PASS. 기존 golden·`DiscordMessageTest`·`OwnerAlertRowsTest` 무수정.
- [ ] **Step 3:** `./gradlew test architectureTest --offline --no-daemon` → exit 0.
- [ ] **Step 4: Commit** — `feat: Discord embed 레이아웃 domain 타입 추가`

### Task 3: 템플릿 계층 `domain.alertmessage` (AC2·AC3)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/alertmessage/{AlertFormat,MailDocument,EmbedDocument,OwnerAlertMessage}.kt`;
Modify `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/OwnerAlertRows.kt`(새 overload 추가, 옛 overload 유지);
Test Create `domain/src/test/kotlin/io/premiumspread/domain/alertmessage/{AlertFormatTest,MailDocumentTest,EmbedDocumentTest}.kt`, `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/OwnerAlertRowsMessageTest.kt`.
`AccountPnlFormat.kt` 와 기존 Content object 는 **건드리지 않는다**(A단계 — 기존 숫자 함수는 `AlertFormat` 에 같은 동작으로 둔다. `AccountPnlFormat` 은 Task 13 에서 삭제).

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.domain.alertmessage

/** 알림 문구 공용 서식. locale 무관(Locale.ROOT), 반올림 HALF_UP. zone 없는 시각 표기 함수는 두지 않는다. */
internal object AlertFormat {
    /** `yyyy-MM-dd HH:mm z` — Asia/Seoul → `2026-10-20 09:00 KST`. 메일 모든 시각. */
    fun stamp(instant: Instant, zone: ZoneId): String
    /** [instant] 와 [basis] 의 zone 날짜가 같으면 `HH:mm`, 다르면 `MM-dd HH:mm`. */
    fun clock(instant: Instant, basis: Instant, zone: ZoneId): String
    /** `MM-dd HH:mm`. */
    fun monthDayClock(instant: Instant, zone: ZoneId): String
    /** [at] 시점 zone 약어(`z`) — Asia/Seoul → `KST`. */
    fun zoneAbbreviation(at: Instant, zone: ZoneId): String
    /** 정시간 → `N시간`, 정분 → `N분`, 그 밖 `N초` (기존 OwnerOpsAlertContent.threshold 와 같은 동작). */
    fun threshold(duration: Duration): String
    /** 분 단위 버림. 음수 0. `<60분` → `N분`, 정시간 → `N시간`, 그 밖 `N시간 M분`. */
    fun duration(duration: Duration): String
    /** UPBIT `업비트`, BITHUMB `빗썸`, BINANCE `바이낸스`, FX_PROVIDER `환율 제공자` (enum 전수 when). */
    fun exchange(exchange: Exchange): String
    /** `{exchange(korea)}{separator}{exchange(foreign)}` — separator `/` 또는 ` / `. */
    fun exchangePair(pair: MarketPair, separator: String): String
    fun plain(value: BigDecimal): String                // stripTrailingZeros().toPlainString()
    fun format(pattern: String, value: BigDecimal): String
    fun sign(value: BigDecimal): String                 // 음수 "-", 그 밖 "+"
    fun percent(value: BigDecimal): String              // "+1.11%" / "-0.10%"
    fun percentPoint(value: BigDecimal): String         // "+1.25%p"
    fun premium(value: BigDecimal): String              // "1.78%" / "-0.50%" (양수 부호 없음)
    fun krw(value: BigDecimal): String                  // 원 단위 반올림 후 부호 — "+469,353원", 0 이면 "+0원"
    fun krwOrZero(value: BigDecimal): String            // 반올림 0 이면 "0원", 아니면 krw
    fun cost(value: BigDecimal): String                 // 반올림 0 이면 "0원", 아니면 "-16,173원" (절댓값 앞에 -)
    fun krwPrice(value: BigDecimal): String             // "158,500,000원" (부호 없음, #,##0)
    fun usdtPrice(value: BigDecimal): String            // "111,200 USDT" (#,##0.####)
    fun usdt2(value: BigDecimal): String                // "14.79 USDT" (부호 없음, #,##0.00, 절댓값)
    fun signedUsdt(value: BigDecimal): String           // "+3.2 USDT" (plain), 0 이면 "0 USDT"
    fun signedUsdt2(value: BigDecimal): String          // "+4.20 USDT" / "-851.20 USDT", 0 이면 "0.00 USDT"
    fun rate(value: BigDecimal): String                 // "1,400.00"
    /** 한글 음절(AC00–D7A3)·자모(1100–11FF)·호환 자모(3130–318F)·CJK 통합(4E00–9FFF)·전각(FF01–FF60) 2, 그 밖 1. */
    fun displayWidth(text: String): Int
}
```

```kotlin
package io.premiumspread.domain.alertmessage

/** 메일 한 항목. [children] 은 부모 아래 "  - " 하위 항목(예: 수수료 내역). */
data class MailPair(val label: String, val value: String, val children: List<MailPair> = emptyList())

sealed interface MailBlock {
    /** 줄 그대로. 빈 목록 금지. */
    data class Text(val lines: List<String>) : MailBlock
    /** 라벨 정렬 블록. 빈 목록 금지. */
    data class Pairs(val pairs: List<MailPair>) : MailBlock
    /** "- 항목". 빈 목록 금지. */
    data class Bullets(val items: List<String>) : MailBlock
    /** "■ {title}" + 안쪽 블록(사이 빈 줄 없음). Section 중첩 금지. */
    data class Section(val title: String, val blocks: List<MailBlock>) : MailBlock
}

/** plain text 메일 한 통. [basis] null = "기준 시각" 줄 없음. [signature] = 본문 끝 "--" 아래 줄. */
data class MailDocument(
    val subject: String,
    val blocks: List<MailBlock>,
    val basis: Instant?,
    val zone: ZoneId,
    val signature: String? = null,
) {
    /** design §3.2 렌더 규칙 1~5. */
    fun body(): String
}
```

```kotlin
package io.premiumspread.domain.alertmessage

data class EmbedCell(val name: String, val value: String)

/** Discord embed 문서. [rows] 한 행 = 나란히 놓이는 칸 1~3개. 빈 rows 금지. */
data class EmbedDocument(
    val author: String,
    val title: String,
    val color: Int,
    val rows: List<List<EmbedCell>>,
    val footer: String?,
    val timestamp: Instant,
) {
    /** design §3.3 규칙 1~5. 결과 layout 의 fields 는 채움 칸 포함. */
    fun toDiscordMessage(): DiscordMessage

    companion object {
        /** U+200B(zero-width space) 한 글자. 보이지 않는 문자를 소스에 직접 넣지 않고 코드값으로 만든다. */
        private val ZWSP: String = Char(0x200B).toString()

        /** 2칸 행 뒤 채움 칸(design §3.3 규칙 2). */
        val SPACER: DiscordField = DiscordField(ZWSP, ZWSP, inline = true)
    }
}
```

```kotlin
package io.premiumspread.domain.alertmessage

/** 알림 하나의 채널별 문구. eventKey 는 outbox UNIQUE·SMTP Message-ID 형식(`[A-Za-z0-9._-]{1,128}`)을 지킨다. */
data class OwnerAlertMessage(val eventKey: String, val mail: MailDocument, val discord: EmbedDocument)
```

```kotlin
// domain/.../accountpnl/OwnerAlertRows.kt — 추가 (옛 expand(mail, route, discord) 는 Task 13 까지 유지)
fun expand(message: OwnerAlertMessage, route: OwnerAlertRoute): List<AccountPnlMail>
```

`toString` 노출(자동): `MailPair`·`MailBlock.*`·`MailDocument`(subject·blocks·basis·zone·signature), `EmbedCell`·`EmbedDocument`(author·title·color·rows·footer·timestamp),
`OwnerAlertMessage` — 실계정 금액·회원 닉네임을 담으므로 로그 인자 금지.

**테스트가 재는 것:**

- `AlertFormatTest`: dod AC2 의 값 전부(`displayWidth("마지막 성공")` = 11, `displayWidth("10분 저점")` = 9) + 복제한 숫자 함수의 동등성 —
  `AccountPnlFormat` 은 domain 모듈 `internal` 이라 같은 모듈 테스트에서 직접 부를 수 있다. 따라서 `percent`·`percentPoint`·`krw`·`krwOrZero`·`cost`·`plain`·`signedUsdt` 각각을
  `0`·`-0.4`·`0.5`·`-0.5`·`1234567.5` 로 불러 `assertThat(AlertFormat.f(x)).isEqualTo(AccountPnlFormat.f(x))` 로 **기존 함수 출력과 직접 비교**한다(기대값을 손으로 옮기지 않는다).
  이 비교 블록은 Task 13 에서 `AccountPnlFormat` 삭제 직전 리터럴로 바꾼다.
  `clock(2026-10-19T15:30:00Z, basis 2026-10-20T00:00:00Z, Asia/Seoul)` = `00:30`(UTC 날짜는 다르고 KST 날짜는 같다), `clock(2026-10-17T00:30:00Z, …)` = `10-17 09:30`.
- `MailDocumentTest`: Text·Pairs(라벨 `평단`/`현재가` → 각각 공백 4/2 뒤 값)·children(`  - 빗썸 진입` 정렬)·Bullets·Section 2개 + 최상위 Text 순서의 전문 `isEqualTo`; basis 줄; signature; basis·signature 둘 다 null 이면 마지막 블록으로 끝남; 빈 Text 목록은 `IllegalArgumentException`.
- `EmbedDocumentTest`: 행 [3,2,3,2,1] → fields **12개**(칸 11개 + 2번째 행 뒤 SPACER 1개; 4번째 행 뒤는 다음 행이 1칸이라 없음 — design §3.3 규칙 2), inline 값; SPACER 의 name·value 가 U+200B 한 글자(`Char(0x200B).toString()` 과 같음); 행 [1] → inline false; 값 1025자 → 1024자 + `…`; 대체 description 전문(채움 칸 줄 없음); 4칸 행·빈 rows 거부; `layout.timestamp == timestamp`.
- `OwnerAlertRowsMessageTest`: route 4조합(`(true,null)`·`(false,PNL/HERE)`·`(true,MARKET/SILENT)`·`(false,null)`) 별 행 수·순서·필드; `route.discord == null` 이면 `EmbedDocument` 의 렌더가 불리지 않음(6000 초과 문서를 넣어도 예외 없음으로 확인).
  **이식(dod AC3(d))** — Task 13 에서 삭제되는 `OwnerAlertRowsTest.kt` 의 비문구 계약을 같은 내용으로 이 파일에 둔다: `:24` 3인자 `AccountPnlMail` 은 EMAIL·`discordRoute`·`discordColor` null, `:31` 채널과 route·color 가 어긋난 4조합(`DISCORD`+route null, `DISCORD`+color null, `EMAIL`+route, `EMAIL`+color) 거부.
  (`:79` 의 "DISCORD 행을 mail 로 주면 거부" 는 옛 overload 전용 계약이라 overload 와 함께 사라진다.)

- [ ] **Step 1:** 렌더 함수를 `""`·빈 fields 를 돌려주는 stub 으로 두고 테스트 작성 → FAIL 로그.
- [ ] **Step 2:** 구현 → `./gradlew :domain:test --offline --no-daemon` → PASS. 기존 golden 무수정(R14).
- [ ] **Step 3:** `./gradlew test architectureTest --offline --no-daemon` → exit 0.
- [ ] **Step 4: Commit** — `feat: 알림 문구 템플릿 계층(메일·Discord 문서와 공용 서식) 추가`

### Task 4: `AccountPnlBreakdown` (AC4)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlBreakdown.kt`; Test Create `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountPnlBreakdownTest.kt`. `AccountNetPnl` 무변경.

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.domain.accountpnl

/** 빗썸 현물 leg 표시값. 금액은 전체 정밀도(표시 반올림은 템플릿). */
data class KoreaLegBreakdown(
    val quantity: BigDecimal,
    val averagePrice: BigDecimal,
    val currentPrice: BigDecimal,     // = AccountNetPnl.koreaExitPrice
    val pnlKrw: BigDecimal,
    val valueKrw: BigDecimal,
    val entryFeeKrw: BigDecimal?,     // null = 미반영
    val exitFeeKrw: BigDecimal,
) { override fun toString(): String = "KoreaLegBreakdown" }

/**
 * 바이낸스 선물 숏 leg 표시값. quantity = |signedQuantity|.
 * 방향 필드는 없다 — AccountHedgePosition.resolve 가 signedQuantity < 0(숏)일 때만 포지션을 만든다(design §6.1).
 */
data class ForeignLegBreakdown(
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val currentPrice: BigDecimal,     // = AccountNetPnl.foreignExitPrice
    val pnlUsdt: BigDecimal,
    val pnlKrw: BigDecimal,
    val valueUsdt: BigDecimal,
    val valueKrw: BigDecimal,
    val entryFeeUsdt: BigDecimal?,    // null = 미반영
    val entryFeeKrw: BigDecimal?,
    val exitFeeUsdt: BigDecimal,
    val exitFeeKrw: BigDecimal,
    val source: ForeignPerpPositionSource,
) { override fun toString(): String = "ForeignLegBreakdown(source=$source)" }

/** 수익·정시 알림의 값 (design §6.1). 실계정 금액이므로 toString 은 calculatedAt 만. */
data class AccountPnlBreakdown(
    val currentPremiumPercent: BigDecimal,   // scale 2
    val entryPremiumPercent: BigDecimal,     // scale 2
    val premiumGapPercentPoint: BigDecimal,  // scale 2 = current − entry (둘 다 반올림 뒤)
    val korea: KoreaLegBreakdown,
    val foreign: ForeignLegBreakdown,
    val grossKrw: BigDecimal,
    val feeTotalKrw: BigDecimal,             // 전체 정밀도 — 표시는 이 값을 반올림(A4, 항목 합이 아님)
    val fundingUsdt: BigDecimal?,            // null = 미반영
    val fundingKrw: BigDecimal,
    val netKrw: BigDecimal,                  // 전체 정밀도 — 표시는 이 값을 반올림(A4)
    val netPercent: BigDecimal,
    val fxRate: BigDecimal,
    val calculatedAt: Instant,
) {
    override fun toString(): String = "AccountPnlBreakdown(calculatedAt=$calculatedAt)"

    companion object {
        fun of(pnl: AccountNetPnl): AccountPnlBreakdown
    }
}
```

**테스트가 재는 것:** dod AC4 — `AccountNetPnl.calculate(position, fx 1400, fxObservedAt 2026-10-19T23:59:00Z, calculatedAt 2026-10-20T00:00:00Z)` 로 만든 pnl 에서 표의 값 전부(`compareTo`),
불변식 3개, 진입 수수료·펀딩 null 경우, 음수 김프(매수호가 150,000,000 → 현김프 음수), `toString` 세 개에 금액·수량 문자열(`0.266`·`152000000`·`469`) 없음.

- [ ] **Step 1:** `of` 를 모든 값 0 인 stub 으로 두고 테스트 작성 → FAIL 로그.
- [ ] **Step 2:** 구현 → `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.*' --offline --no-daemon` → PASS.
- [ ] **Step 3: Commit** — `feat: 실계정 수익 알림 표시값 계산(AccountPnlBreakdown) 추가`

### Task 5: V23 migration 과 fixture (AC11)

**Files:** Create `infrastructure/common/src/main/resources/db/migration/V23__add_owner_alert_discord_layout.sql`;
Modify `apps/batch/src/integrationTest/resources/batch-schema.sql`(account_pnl_mail 정의);
허용 예외 Modify `infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/migration/V19OwnerAlertChannelMigrationIntegrationTest.kt`(:34-50 목록 끝에 `"discord_layout:text:YES",` 1줄),
`apps/batch/src/integrationTest/kotlin/io/premiumspread/config/AccountPnlFixtureSchemaIntegrationTest.kt`(:45-61 목록 끝 같은 1줄);
Test Create `infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/migration/V23OwnerAlertDiscordLayoutMigrationIntegrationTest.kt`.

**DDL (전문):**

```sql
-- #102 owner 알림 Discord embed 레이아웃(author·fields·footer·timestamp)을 DISCORD 행에 보존한다.
-- JSON 텍스트이며 직렬화는 infrastructure:common 이 한다. NULL = 레이아웃 없는 DISCORD 행(배포 전 행 포함 — 제목·설명으로 발송) 또는 EMAIL 행.
-- 비밀값(webhook URL·수신 주소)은 담지 않는다.
ALTER TABLE account_pnl_mail
    ADD COLUMN discord_layout TEXT NULL,
    ADD CONSTRAINT ck_account_pnl_mail_discord_layout CHECK (discord_layout IS NULL OR channel = 'DISCORD');
```

batch fixture(Task 0 (g8) 위치): `discord_color  INT          NULL,`(:240) 다음 줄에 `discord_layout TEXT         NULL,`, `ck_account_pnl_mail_channel`(:242) 제약 다음에
`CONSTRAINT ck_account_pnl_mail_discord_layout CHECK (discord_layout IS NULL OR channel = 'DISCORD'),` (주석 `-- V23 와 같은 스키마`).

**테스트가 재는 것:** dod AC11 (a)~(c) — 기존 `V22OwnerMarketAlertStateMigrationIntegrationTest` 의 `flyway(target)`·`clean`·`appliedVersions` 패턴. (b) 는 V22 target 에 EMAIL 1행·DISCORD 1행 → latest → 두 행 `discord_layout` NULL·subject/body/discord_* 동일.

- [ ] **Step 1:** 테스트 먼저 → `./gradlew :infrastructure:common:integrationTest --tests 'io.premiumspread.infrastructure.common.migration.V23OwnerAlertDiscordLayoutMigrationIntegrationTest' --offline --no-daemon` → FAIL(열 없음) 로그.
- [ ] **Step 2:** DDL·fixture·허용 예외 2줄 → 같은 명령 + `:infrastructure:common:verifyMigrations` → PASS. `./gradlew :infrastructure:common:integrationTest --offline --no-daemon` → exit 0(V19 테스트 포함).
- [ ] **Step 3:** `./gradlew test architectureTest --offline --no-daemon` → exit 0. `git diff --name-only --diff-filter=MDR 3d179e7 -- infrastructure/common/src/main/resources/db/migration` → 빈 출력.
- [ ] **Step 4: Commit** — `feat: owner 알림 outbox 에 Discord 레이아웃 열 추가(V23)`

### Task 6: outbox 레이아웃 직렬화와 claim (AC12)

**Files:** Modify `infrastructure/common/build.gradle.kts`(`implementation("com.fasterxml.jackson.core:jackson-databind")` — 버전 없음, BOM. D1 승인됨), `OwnerAlertOutboxRows.kt`, `JdbcAccountPnlAlertStore.kt`;
Create `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/DiscordLayoutJson.kt`;
Test Create `infrastructure/common/src/test/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/DiscordLayoutJsonTest.kt`,
`infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/OwnerAlertDiscordLayoutStoreIntegrationTest.kt`.

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

internal enum class DiscordLayoutUnreadableReason { JSON, VERSION, SHAPE }

/** 메시지 = "discord layout unreadable: {json|version|shape}". 입력 내용·cause 를 담지 않는다. */
internal class DiscordLayoutUnreadableException(val reason: DiscordLayoutUnreadableReason) :
    RuntimeException("discord layout unreadable: ${reason.name.lowercase()}")

/**
 * account_pnl_mail.discord_layout 의 v1 JSON. 키 순서 v·author·fields(name·value·inline)·footer·timestamp, null 은 JSON null.
 * fields 는 빈 목록이어도 `[]` 로 항상 쓴다(design §4·§5.2). Jackson tree API 만 쓴다(Kotlin module·reflection 없음).
 */
internal object DiscordLayoutJson {
    const val VERSION = 1
    fun write(layout: DiscordLayout): String
    /** JSON 오류 → JSON, v 가 1 이 아님 → VERSION, 키 누락·타입 불일치·domain 불변식 위반 → SHAPE. */
    fun read(json: String): DiscordLayout
}
```

```kotlin
// OwnerAlertOutboxRows — 추가·변경
const val MAX_LAYOUT_BYTES = 65_535 // MySQL TEXT
fun validate(mail: AccountPnlMail)  // 기존 + discordLayout 이 있으면 write 결과 UTF-8 바이트 ≤ 65535,
                                    // 아니면 IllegalArgumentException("discordLayout must be at most 65535 bytes in UTF-8.")
fun insert(jdbcTemplate: JdbcTemplate, mail: AccountPnlMail, createdAt: Instant)
    // INSERT 열 목록에 discord_layout 추가(discord_color 뒤), 값 = mail.discordLayout?.let(DiscordLayoutJson::write)
```

`JdbcAccountPnlAlertStore`: claim SELECT 열 목록 끝에 `discord_layout`. `toPendingMail` 의 DISCORD 행 처리(값이 non-null 일 때):

1. `DiscordLayoutJson.read(value)` 로 layout 을 복원한다.
2. **조립 확인** — 같은 행의 `subject`·`body`·`discord_color` 와 합쳐 `DiscordMessage(subject, body, color, layout)` 를 실제로 만들어 본다(결과는 버린다). `IllegalArgumentException` 이면
   `DiscordLayoutUnreadableException(SHAPE)` 와 같은 처리로 간다 — 그래야 `OwnerAlertDispatcher.sendDiscord` 의 `DiscordMessage` 생성이 이 행에서 예외를 내지 않는다(design §5.3).
3. 1·2 에서 실패하면 `log.warn("Owner alert discord layout unreadable; sending plain description: id={}, reason={}", id, reason.name.lowercase())` 후 `discordLayout = null`(레이아웃 없이 대체 평문으로 발송).
   로그 키는 `id`·`reason` 뿐 — 컬럼 값·예외 메시지 원문 금지. claim 의 다른 행에는 영향이 없다.

EMAIL 행은 값이 있어도 읽지 않는다(CHECK 가 막는다).

**테스트가 재는 것:** dod AC12 (a)~(g). (a) write 기대 문자열은 design §5.2 예시 형식의 전문 `isEqualTo` 1건 + 빈 fields `"fields":[]` 1건 + 왕복 property 6건. (b)(c)(d)(e)(f) 는 실제 MySQL, `JdbcOwnerAlertOutboxIntegrationTest` 의 컨텍스트 구성 재사용,
(d)(e) 는 `OutputCaptureExtension` 으로 WARN 문구·`reason=json`/`reason=shape` 와 컬럼 값 부재. (e) 는 subject 255자 DISCORD 행에 칸 값 합계가 6000 − 255 를 넘는 유효 JSON layout 을 SQL 로 넣는다.

- [ ] **Step 1:** 테스트 먼저(코덱 stub 은 `"{}"`·빈 layout 반환) → unit FAIL, integration FAIL 로그.
- [ ] **Step 2:** 구현 → `./gradlew :infrastructure:common:test --tests 'io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.*' :infrastructure:common:integrationTest --offline --no-daemon` → PASS(기존 store IT 무수정).
- [ ] **Step 3:** `./gradlew test architectureTest --offline --no-daemon` → exit 0.
- [ ] **Step 4: Commit** — `feat: owner 알림 outbox 에 Discord 레이아웃 저장·복원`

### Task 7: Discord adapter payload 와 dispatcher (AC13·AC14)

**Files:** Modify `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/notification/DiscordWebhookSenderAdapter.kt`(`payload` 만),
`apps/batch/src/main/kotlin/io/premiumspread/application/notification/owneralert/OwnerAlertDispatcher.kt`(`sendDiscord` 마지막 줄만);
Test Create `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/notification/DiscordWebhookSenderAdapterLayoutTest.kt`,
`apps/batch/src/test/kotlin/io/premiumspread/application/notification/owneralert/OwnerAlertDispatcherLayoutTest.kt`.

**계약:**

```kotlin
// OwnerAlertDispatcher.sendDiscord — 마지막 줄
sender.send(route, DiscordMessage(mail.subject, mail.body, color, mail.discordLayout))
```

adapter `payload(push, message)` 의 embed 객체(키 순서 고정):

| `message.layout` | embed |
|---|---|
| null | `title`, `description`, `color` (현재와 같음) |
| non-null | `author: {name}`(author null 이면 키 없음), `title`, `color`, `fields: [{name, value, inline}]`(**항상** — 빈 목록이면 `[]`, design §4), `footer: {text}`(null 이면 키 없음), `timestamp: Instant.toString()`(null 이면 키 없음) — `description` 없음 |

root 의 `content`·`embeds`·`allowed_mentions`·`flags` 규칙 무변경.

**테스트가 재는 것:** AC13 — layout 있는 메시지 × push 3종의 요청 본문 JSON 전문 비교(기존 테스트의 `json(...)` 비교 방식), author·footer·timestamp null + 빈 fields 조합 1건(`"fields":[]`), 칸 3개 순서·inline.
AC14 — fake store 의 DISCORD 행(layout 있음/없음)을 dispatch 하면 fake sender 가 받은 `DiscordMessage.layout` 이 행의 값과 같다.

- [ ] **Step 1:** 테스트 먼저 → FAIL(layout 무시) 로그.
- [ ] **Step 2:** 구현 → `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.notification.*' :apps:batch:test --tests 'io.premiumspread.application.notification.owneralert.*' --offline --no-daemon` → PASS. `DiscordWebhookSenderAdapterTest`·`OwnerAlertDispatcherTest` 무수정.
- [ ] **Step 3:** **A단계 종료 확인(R14)** — `git diff --name-status 3d179e7 -- domain/src/test infrastructure/batch/src/test apps/batch/src/test` 에 M·D 행이 없다. `./gradlew test architectureTest --offline --no-daemon` → exit 0.
- [ ] **Step 4: Commit** — `feat: Discord webhook 으로 embed 레이아웃 발송`

---

## B단계 — family 별 문구 전환

공통: 각 태스크는 (1) 새 golden 테스트를 먼저 쓰고(기대 문자열 = message-spec 해당 절 + 테스트 입력의 숫자) FAIL 을 본 뒤, (2) 템플릿을 구현하고, (3) Job 호출 줄과 허용 예외 테스트 줄을 고친다.
golden 은 dod 의 정의(메일 subject·`body()`, Discord author·title·color·fields 전부·footer·timestamp) 그대로 `isEqualTo`. 각 family 템플릿은 `AlertFormat`·`MailDocument`·`EmbedDocument` 만 쓴다(`AccountPnlFormat` 금지).

**Job 행 신규 테스트(dod AC15(b)) 공통 규칙:** Job 별 신규 파일 `*TemplateRowsTest.kt` 는 같은 디렉터리의 기존 Job 테스트가 쓰는 fake·fixture 구성을 **파일 안 private 으로 복제**한다(기존 테스트 파일에 의존·수정 금지 — R3).
라우팅은 `OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true), overrides)` 로 직접 만들고, `overrides` 는 그 시나리오의 `OwnerAlertKind` 에 `OwnerAlertRouteOverride(email = true)` 를 준다(기본 경로가 메일을 끈 종류도 메일 행이 생기게).
단정: 행이 EMAIL·DISCORD 순 2행, DISCORD 행 `discordLayout != null`, EMAIL 행 `body` == 같은 입력(같은 clock·zone·settings)으로 부른 family 템플릿의 `message.mail.body()`, DISCORD 행 `discordLayout` == `message.discord.toDiscordMessage().layout`.

### Task 8: 실계정 순손익 5종 (AC5)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlAlertContent.kt`;
Move `AccountPnlMail` 클래스를 `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/OwnerAlertOutbox.kt` 끝으로(내용 무변경) 하고 `AccountPnlMailContent.kt` 삭제, `AccountPnlDiscordContent.kt` 삭제;
Modify `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/NetPercentSwingPolicy.kt`(:12 KDoc 의 `AccountPnlMailContent.hourly` → `AccountPnlAlertContent.hourly`, 그 밖 무변경);
Modify `apps/batch/src/main/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJob.kt`(`buildMails` 의 4개 호출과 새 `route` overload);
Test Create `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountPnlAlertContentTest.kt`, `apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJobTemplateRowsTest.kt`;
Delete `AccountPnlMailContentTest.kt`·`AccountPnlDiscordContentTest.kt`(아래 이식을 먼저 끝낸 뒤);
허용 예외 Modify `AccountPnlMonitorJobTest.kt`·`AccountPnlMonitorJobHourlyReportTest.kt`·`AccountPnlMonitorJobOwnerAlertTest.kt`·`infrastructure/batch/src/test/.../AccountPnlEmailSenderAdapterTest.kt`(호출을 `OwnerAlertRows.expand(AccountPnlAlertContent.x(...), OwnerAlertRoute(email = true, discord = null)).single()` 로).

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.domain.accountpnl

/** 실계정 순손익 알림 4종의 메일·Discord 문구. 문구 정본 message-spec.md "실계정 순손익", 값 출처 design §6.2. */
object AccountPnlAlertContent {
    const val COLOR_PROFIT: Int = 0x2ECC71
    const val COLOR_HOURLY: Int = 0x3498DB
    const val COLOR_OUTAGE: Int = 0xE74C3C
    const val COLOR_RECOVERED: Int = 0x95A5A6
    const val AUTHOR: String = "실계정 · BTC"

    /** eventKey `account-pnl-profit-{calculatedAt ms}`. basis = pnl.calculatedAt. */
    fun profit(pnl: AccountNetPnl, zone: ZoneId): OwnerAlertMessage
    /** eventKey `account-pnl-hourly-{calculatedAt 의 zone 정시 ms}`. basis = pnl.calculatedAt. */
    fun hourly(pnl: AccountNetPnl, zone: ZoneId): OwnerAlertMessage
    /** eventKey `account-pnl-outage-{now ms}`. basis = now. [마지막 성공] = notice.lastSuccessAt(null → "없음"), [중단 감지] = now. */
    fun outage(notice: OutageNotice.Outage, now: Instant, zone: ZoneId): OwnerAlertMessage
    /** eventKey `account-pnl-recovered-{now ms}`. 메일 basis 없음, embed timestamp = now. [복구 시각] = now. */
    fun recovered(now: Instant, zone: ZoneId): OwnerAlertMessage

    /** 실패 범주 표기 (값 전부). */
    internal fun label(category: AccountReadFailureCategory): String = when (category) {
        AccountReadFailureCategory.BITHUMB_AUTH -> "빗썸 인증 실패"
        AccountReadFailureCategory.BINANCE_AUTH -> "바이낸스 인증 실패"
        AccountReadFailureCategory.NETWORK -> "네트워크·요청 한도 오류"
        AccountReadFailureCategory.STALE_MARKET_DATA -> "시세 지연"
        AccountReadFailureCategory.STALE_FX -> "환율 수집 지연"
        AccountReadFailureCategory.INSUFFICIENT_DEPTH -> "호가 깊이 부족"
        AccountReadFailureCategory.UNEXPECTED -> "기타 오류(로그 확인)"
    }
}
```

```kotlin
// AccountPnlMonitorJob — 추가 (Task 9 까지 옛 route(kind, mail, discord) 공존)
private fun route(kind: OwnerAlertKind, message: OwnerAlertMessage): List<AccountPnlMail> =
    OwnerAlertRows.expand(message, routing.resolve(kind))
```

**요건(구현부):** 칸 값 출처 design §6.1·§6.2, 미반영 표기 A5(`ForeignPerpPositionSource.MANUAL_ENV` 면 바이낸스·펀딩 `수동 미입력`, 빗썸은 항상 `이력 없음`), 중단 `[확인할 것]` 칸·섹션은 `BITHUMB_AUTH` 만,
중단 footer·메일 첫 줄의 `5분` 은 `AlertFormat.threshold(MonitoringOutagePolicy.THRESHOLD)`.
금액 반올림은 A4(승인) — 각 항목·`feeTotalKrw`·`netKrw` 를 각자의 전체 정밀도 값에서 반올림한다(항목 반올림값을 더해 합계를 만들지 않는다).
빗썸 평단 사용자 수정 표시는 쓰지 않는다(D2 승인 — 삭제). 중단·복구 시각 출처는 design §6.2 표(`lastSuccessAt`·`now`).

**테스트가 재는 것:** AC5 — 수익(AC4 입력, calculatedAt `2026-10-20T00:00:00Z`): 제목 `💰 수익 알림 +1.11% +469,353원`, fields **12개**(칸 11개 + 2번째 행 뒤 채움 1개 — design §3.3 규칙 2), footer `환율 1,400.00원/USD`, timestamp;
메일 전문. 정시: calculatedAt `2026-10-20T00:00:30Z` → `📊 정시 보고 09:00 …`, eventKey 정시 ms. 중단(BITHUMB_AUTH, lastSuccess null, now `2026-10-20T03:10:00Z`) 칸 [2,1]·`[마지막 성공] 없음`·`[중단 감지] 12:10` + 메일 `■ 확인할 것`;
중단(STALE_FX, lastSuccess `03:00Z`) 칸 [2]·`[마지막 성공] 12:00`·확인할 것 없음; 복구 메일 전문(기준 시각 줄 없음, `복구 시각` = now). 변형: 진입 수수료·펀딩 null(EXCHANGE_API·MANUAL_ENV 각 1건), label 7개 전부, `modified = true` 입력에서도 `수정` 문자열 없음(D2).
**이식(dod AC5) — 삭제되는 테스트의 비문구 계약을 이 파일로 옮긴다:**

| 삭제되는 테스트 | 계약 | `AccountPnlAlertContentTest` 의 새 테스트 |
|---|---|---|
| `AccountPnlMailContentTest.kt:204` | SMTP Message-ID 형식 `[A-Za-z0-9._-]{1,128}`(출처 `JavaMailEmailSender.DELIVERY_ID_PATTERN`)·중복 없음 | profit·hourly·outage·recovered 4종 eventKey 전부 |
| `AccountPnlMailContentTest.kt:219` | 제목·본문에 비밀 문자열 없음(`fakeSecrets` fixture 를 이 파일에 복제) | 4종의 메일 subject·`body()` + Discord title·author·footer·모든 칸 name·value |
| `AccountPnlMailContentTest.kt:407` | 정시 eventKey 가 같은 시간대면 같고 버킷 시작 시각 | calculatedAt `00:00:00Z`·`00:59:59Z` → `account-pnl-hourly-1792454400000`, `01:00:00Z` → `account-pnl-hourly-1792458000000` |
| `AccountPnlDiscordContentTest.kt:186-189` | 색 상수 값 | `COLOR_PROFIT`=3066993·`COLOR_HOURLY`=3447003·`COLOR_OUTAGE`=15158332·`COLOR_RECOVERED`=9807270 |

**Job 행(dod AC15(b)):** `AccountPnlMonitorJobTemplateRowsTest` — 같은 디렉터리 `AccountPnlMonitorJobOwnerAlertTest` 의 fake 구성을 복제. 시나리오 2개: 수익 회차(`PROFIT`), 중단 회차(`MONITORING_OUTAGE`). 단정은 B단계 공통 규칙.

- [ ] **Step 1:** golden·이식 테스트·Job 행 테스트 먼저(템플릿은 빈 문서를 돌려주는 stub) → FAIL 로그.
- [ ] **Step 2:** 구현·Job·허용 예외 → `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.*' :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.*' :infrastructure:batch:test --offline --no-daemon` → PASS.
  Job 테스트의 행 수·채널·eventKey·`JobResult` 단정 줄은 바꾸지 않았는지 diff 로 확인. `grep -rn 'AccountPnlMailContent\|AccountPnlDiscordContent' --include=*.kt domain apps infrastructure` → 출력 없음(KDoc 포함).
- [ ] **Step 3:** `./gradlew test architectureTest --offline --no-daemon` → exit 0.
- [ ] **Step 4: Commit** — `refactor: 실계정 순손익 알림 문구를 템플릿으로 전환`

### Task 9: 계정 알림 (AC6)

**Files:** Modify(재작성) `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountAlertContent.kt`, `AccountPnlMonitorJob.kt`(`accountAlertRows` 한 줄 + 옛 `route(kind, mail, discord)` 삭제);
Test 교체 `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountAlertContentTest.kt`; Modify `AccountPnlMonitorJobTemplateRowsTest.kt`(Task 8 신규 파일 — 시나리오 추가).

**Interfaces — Produces (전문):**

```kotlin
object AccountAlertContent {
    const val COLOR_POSITION_CHANGED: Int = 0xF1C40F
    const val COLOR_HEDGE_MISMATCH: Int = 0xE67E22
    const val COLOR_SWING_UP: Int = 0x1ABC9C
    const val COLOR_SWING_DOWN: Int = 0xE91E63
    const val COLOR_REMINDER: Int = 0x7F8C8D

    /** 무변경 — `account-pnl-position-{ms}` / `account-pnl-swing-{up|down}-{bucketStart ms}` / `account-pnl-reminder-{dayStart ms}`. */
    fun eventKey(alert: AccountAlert): String

    /** basis: 포지션·리마인더 = detectedAt, 급변 = pnl.calculatedAt. author = AccountPnlAlertContent.AUTHOR. */
    fun message(alert: AccountAlert, zone: ZoneId): OwnerAlertMessage
}
```

```kotlin
// AccountPnlMonitorJob
private fun accountAlertRows(alert: AccountAlert): List<AccountPnlMail> = route(alert.kind, AccountAlertContent.message(alert, zone))
```

**요건:** design §6.3, A7·A13. **칸 값·메일 줄의 줄 구성은 message-spec 그대로**(A8 철회):
안 바뀐 빗썸 칸 값 = `{plain(qty)} BTC\n평단 {krwPrice(avg)}`(spec 201행), 안 바뀐 바이낸스 칸 값 = `{plain(|qty|)} BTC · 평단 {usdtPrice(entry)}`(spec 231행), 메일의 안 바뀐 leg 는 둘 다 `… · 평단 …` 한 줄(spec 211·246행).
안 바뀐 leg 가 비었거나 flat 이면 `포지션 없음` 한 줄(A13). 바이낸스 leg 롱이면 값 앞 `롱 `(A7 — `ForeignLeg` 은 원본 스냅샷이라 롱 가능).
리마인더 항목 표기(값 전부): `KOREA_ENTRY_FEE` → `빗썸 진입 수수료 — 이력 없음`, `FOREIGN_ENTRY_FEE` → `바이낸스 진입 수수료 — {이력 없음|수동 미입력}`,
`FUNDING` → `펀딩비 — {이력 없음|수동 미입력}`, `MANUAL_FUNDING_STALE` → `수동 펀딩비 {days}일째 그대로 ({signedUsdt}, {Discord: clock / 메일: stamp} 변경)`.
리마인더 footer `하루 1번, {HH}:00 이후 발송`·메일 문장 `하루 1번, {HH}:00 이후에 보냅니다.` 의 `{HH}` = `"%02d".format(Locale.ROOT, alert.reminderHour)`
(`AccountAlert.UnrecoveredReminder.reminderHour` — `AccountAlert.kt:56`, 기존 `AccountAlertContent.kt:200` 과 같은 출처). **`09:00` 리터럴을 템플릿 코드에 쓰지 않는다.**

**테스트가 재는 것:** AC6 — spec 5종 + 변형 5건(둘 다 변경, 바이낸스 롱, 안 바뀐 빗썸 leg 비어 있음, 리마인더 오래된 항목만, 빠진 항목만) + `reminderHour = 7` 리마인더 1건(footer·메일 문장 `07:00`).
입력은 spec 예시 수치(빗썸 0.5→0.6 BTC·평단 100,000,000→101,000,000, 바이낸스 0.5 BTC·70,000 USDT, 급변 기준선 +0.40%·임계 0.5, 리마인더 펀딩 +3.2 USDT·changedAt `2026-10-17T00:30:00Z`·`reminderHour = 9`).
기존 `AccountPnlMonitorJobAccountAlertTest`(색 상수만 참조)는 무수정 통과.
**Job 행(dod AC15(b)):** `AccountPnlMonitorJobTemplateRowsTest` 에 포지션 변경 회차(`POSITION_CHANGED`) 시나리오 추가.

- [ ] **Step 1:** golden 먼저 → FAIL 로그. **Step 2:** 구현 → `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.*' :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.*' --offline --no-daemon` → PASS.
- [ ] **Step 3:** `./gradlew test architectureTest --offline --no-daemon` → exit 0.
- [ ] **Step 4: Commit** — `refactor: 계정 알림 문구를 템플릿으로 전환`

### Task 10: 운영 알림 (AC7)

**Files:** Modify(재작성) `domain/src/main/kotlin/io/premiumspread/domain/ownerops/OwnerOpsAlertContent.kt`, `apps/batch/src/main/kotlin/io/premiumspread/application/job/ownerops/OwnerOpsSignalJob.kt`(:80-81 한 표현식);
Test 교체 `domain/src/test/kotlin/io/premiumspread/domain/ownerops/OwnerOpsAlertContentTest.kt`; Test Create `apps/batch/src/test/kotlin/io/premiumspread/application/job/ownerops/OwnerOpsSignalJobTemplateRowsTest.kt`.

**Interfaces — Produces (전문):**

```kotlin
object OwnerOpsAlertContent {
    const val ALERT_COLOR = 0xE67E22
    const val RECOVERED_COLOR = 0x95A5A6
    const val AUTHOR = "운영"

    /** 무변경. */
    fun eventKey(notice: OpsNotice): String

    /** basis = notice.at. */
    fun message(notice: OpsNotice, settings: OpsAlertSettings, zone: ZoneId): OwnerAlertMessage

    /** Task 11 까지 OwnerMarketAlertContent 가 쓴다. Task 11 에서 삭제. */
    internal fun threshold(duration: Duration): String
}
```

`RUNBOOK`·`elapsed` 는 삭제한다. 근거: Task 0 (g10) `grep -rnE 'OwnerOpsAlertContent\.(RUNBOOK|elapsed)|OwnerMarketAlertContent\.RUNBOOK' --include=*.kt domain apps infrastructure` 의 결과가
`OwnerOpsAlertContentTest.kt:264-266`(`elapsed` — 이 태스크가 교체하는 파일)뿐이고 main 사용처가 없다. 파일 안 비한정 사용은 재작성으로 함께 사라진다. 삭제 후 같은 grep → 출력 없음.

```kotlin
// OwnerOpsSignalJob :80-81
OwnerAlertRows.expand(OwnerOpsAlertContent.message(notice, settings, zone), routing.resolve(notice.kind))
```

**요건:** design §6.4, A9·A10.

**테스트가 재는 것:** AC7 — spec 4종(환율: since `2026-10-27T23:29:00Z`·incident `2026-10-28T00:00:00Z`·at 같음, 시세 빗썸 BTC/KRW, Job `premium_realtime` sequence 2·count 4·first `2026-10-27T17:55:00Z`·incident `18:00Z`·at `2026-10-28T00:00:00Z`,
해소 바이낸스 BTC/USD incident `2026-10-27T12:00:00Z`·at `2026-10-28T00:00:00Z`·notifyCount 2) + 변형 4건. `OwnerOpsSignalJobTest` 무수정 통과.
**Job 행(dod AC15(b)):** `OwnerOpsSignalJobTemplateRowsTest` — 같은 디렉터리 `OwnerOpsSignalJobTest` 의 fake 구성을 복제. 시나리오: 환율 수집 지연 첫 알림(`FX_INGESTION_DELAYED`).

- [ ] **Step 1:** golden·Job 행 테스트 먼저 → FAIL. **Step 2:** 구현 → `./gradlew :domain:test --tests 'io.premiumspread.domain.ownerops.*' --tests 'io.premiumspread.domain.ownermarket.*' :apps:batch:test --tests 'io.premiumspread.application.job.ownerops.*' --offline --no-daemon` → PASS.
- [ ] **Step 3:** `./gradlew test architectureTest --offline --no-daemon` → exit 0.
- [ ] **Step 4: Commit** — `refactor: 운영 알림 문구를 템플릿으로 전환`

### Task 11: 시장 알림 (AC8)

**Files:** Modify(재작성) `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/OwnerMarketAlertContent.kt`, `apps/batch/src/main/kotlin/io/premiumspread/application/job/ownermarket/OwnerMarketSignalJob.kt`(:139-140 한 표현식),
`OwnerOpsAlertContent.kt`(`threshold` 삭제); Test 교체 `domain/src/test/kotlin/io/premiumspread/domain/ownermarket/OwnerMarketAlertContentTest.kt`;
Test Create `apps/batch/src/test/kotlin/io/premiumspread/application/job/ownermarket/OwnerMarketSignalJobTemplateRowsTest.kt`;
허용 예외 Modify `apps/batch/src/integrationTest/kotlin/io/premiumspread/application/job/OwnerMarketAlertIntegrationTest.kt`(:189-200).
`apps/batch/src/test/kotlin/io/premiumspread/application/job/ownermarket/OwnerMarketSignalJobTest.kt` 는 **수정하지 않는다** — `:235` 의 `contains("KRW/USD")` 는 새 메일 섹션 제목 `■ 환율 (KRW/USD)`(base KRW·quote USD, design §6.5)도 만족한다(R3 diff 최소화).

**Interfaces — Produces (전문):**

```kotlin
object OwnerMarketAlertContent {
    const val UP_COLOR = 0xE74C3C
    const val DOWN_COLOR = 0x3498DB
    const val SUMMARY_COLOR = 0x95A5A6

    /** 무변경. */
    fun eventKey(notice: MarketNotice): String

    /** author = "시장 · {pair.symbol.code} {AlertFormat.exchangePair(pair, "/")}" (notice.pair 에서만). basis = notice.at. */
    fun message(notice: MarketNotice, settings: MarketAlertSettings, zone: ZoneId): OwnerAlertMessage
}
```

`RUNBOOK` 삭제(Task 0 (g10) — 외부 사용처 없음). 이모지 상수 7개는 유지(private).

```kotlin
// OwnerMarketSignalJob :139-140
OwnerAlertRows.expand(OwnerMarketAlertContent.message(notice, settings, zone), routing.resolve(notice.kind))
```

**요건:** design §6.5, A11.

**테스트가 재는 것:** AC8 — spec 7종(at `2026-10-29T00:00:00Z`, currentObservedAt `2026-10-28T23:59:00Z`, 기본 `MarketAlertSettings()`, 요약 date 2026-10-28·day open/high/low/close/avg 2.10/2.80/1.90/2.50/2.35·fxRate 1385.20·현재 fx 1390.50·pnl Value(1.23, 123457)) + 결측 2건 +
`MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)` author 1건. 허용 예외: `OwnerMarketAlertIntegrationTest:189-200` 은 메일 body 에 `■ 환율 (USD/KRW)`·`순수익률  +`·`+2.20%`·`1,390.25` 포함, Discord 는 `layout.fields` 값에 `9.99` 가 없음으로.
**Job 행(dod AC15(b)):** `OwnerMarketSignalJobTemplateRowsTest` — 같은 디렉터리 `OwnerMarketSignalJobTest` 의 fake 구성을 복제. 시나리오: 김프 급등 회차(`PREMIUM_SWING`), 일일 요약 회차(`DAILY_SUMMARY`).

- [ ] **Step 1:** golden·Job 행 테스트 먼저 → FAIL. **Step 2:** 구현 → `./gradlew :domain:test --tests 'io.premiumspread.domain.ownermarket.*' :apps:batch:test --tests 'io.premiumspread.application.job.ownermarket.*' --offline --no-daemon` → PASS(`OwnerMarketSignalJobTest` 무수정).
- [ ] **Step 3:** `./gradlew test architectureTest --offline --no-daemon` → exit 0. `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.application.job.OwnerMarketAlertIntegrationTest' --offline --no-daemon` → exit 0(Docker).
- [ ] **Step 4: Commit** — `refactor: 시장 알림 문구를 템플릿으로 전환`

### Task 12: 회원 김프 메일 (AC9-b·c·d·e)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/notification/PremiumThresholdMailContent.kt`;
Modify `apps/batch/src/main/kotlin/io/premiumspread/application/notification/PremiumThresholdDeliveryService.kt`, `apps/batch/src/main/kotlin/io/premiumspread/config/NotificationDeliveryConfiguration.kt`;
Test Create `domain/src/test/kotlin/io/premiumspread/domain/notification/PremiumThresholdMailContentTest.kt`; Modify `PremiumThresholdDeliveryServiceMailTest.kt`(Task 1 에서 만든 파일 — 기대값을 새 문구로, zone 사례 추가).

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.domain.notification

/** 회원 김프 임계값 도달 메일. 문구 정본 message-spec.md "회원 알림", 값 출처 design §6.6. basis 없음, signature "premium-spread". */
object PremiumThresholdMailContent {
    fun render(subscription: ActiveNotificationSubscription, snapshot: PremiumSnapshot, zone: ZoneId): MailDocument
}
```

```kotlin
// PremiumThresholdDeliveryService — @Service 제거, @ConditionalOnProperty 유지(design §7 A14), 생성자 끝에 zone 추가. @Value 없음(N1).
@ConditionalOnProperty(prefix = "notification.email", name = ["enabled"], havingValue = "true")
class PremiumThresholdDeliveryService(
    private val subscriptions: ActiveNotificationSubscriptionPort,
    private val transactions: NotificationDeliveryTransactionService,
    private val properties: NotificationDeliveryProperties,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) : PremiumThresholdEvaluator {
    // toDelivery: subject = mail.subject, payload = mail.body()  (mail = PremiumThresholdMailContent.render(this, snapshot, zone))
}
```

```kotlin
// apps/batch/src/main/kotlin/io/premiumspread/config/NotificationDeliveryConfiguration.kt — 클래스 본문에 추가.
// 기존 애너테이션(@Configuration(proxyBeanMethods = false), @ConditionalOnProperty(notification.email.enabled=true), @EnableConfigurationProperties) 무변경.
// 관례: OwnerAlertDeliveryConfiguration.kt:153,165 · OwnerMarketAlertConfiguration.kt:67 (Configuration 이 aggregation.zone 을 읽어 ZoneId.of 로 넘긴다).
@Bean
fun premiumThresholdDeliveryService(
    subscriptions: ActiveNotificationSubscriptionPort,
    transactions: NotificationDeliveryTransactionService,
    properties: NotificationDeliveryProperties,
    clock: Clock,
    @Value("\${aggregation.zone:Asia/Seoul}") zone: String,
): PremiumThresholdDeliveryService = PremiumThresholdDeliveryService(subscriptions, transactions, properties, clock, ZoneId.of(zone))
```

`@Service` 를 빼는 이유: 빈 정의를 Configuration 한 곳에 두어 component scan 과 `@Bean` 이 같은 타입을 두 번 등록하지 않게 한다.
`@ConditionalOnProperty` 를 남기는 이유(A14): `NotificationDisabledContextTest.kt:49-52` 가 이 클래스를 `@Import` 하고 `:33` 에서 `doesNotHaveBean` 을 단정한다(Task 0 (g11)). 조건이 남아 있으면 disabled 에서 import 가 건너뛰어져 그 테스트가 무수정 통과한다.
`NewNotificationDelivery` 필드·eventKey·`recipientEmail` 무변경. 기존 `PremiumThresholdDeliveryServiceTest`(4인자 생성) 무수정 컴파일.

**테스트가 재는 것:** AC9(b) 두 경우 전문(제목·본문), (c) `zone = ZoneId.of("UTC")` 인스턴스는 `관측 시각  2026-07-15 00:30 UTC`. domain 테스트는 같은 두 경우 + 닉네임이 본문 첫 줄에만 있음.
(d) 기존 `PremiumThresholdDeliveryServiceTest`·`NotificationDisabledContextTest` 무수정 통과. (e) 서비스 소스에 `@Value`·`@Service` 없음.

- [ ] **Step 1:** 특성화 테스트의 기대값을 새 문구로 바꾸고 → FAIL(현재 구현) 로그.
- [ ] **Step 2:** 구현 → `./gradlew :domain:test --tests 'io.premiumspread.domain.notification.*' :apps:batch:test --tests 'io.premiumspread.application.notification.*' --offline --no-daemon` → PASS.
  `grep -nE '@Value|@Service' apps/batch/src/main/kotlin/io/premiumspread/application/notification/PremiumThresholdDeliveryService.kt` → 출력 없음.
- [ ] **Step 3:** `./gradlew test architectureTest --offline --no-daemon` → exit 0. `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.application.notification.*' --offline --no-daemon` → exit 0(Docker — enabled 컨텍스트의 빈 생성과 disabled 부재 확인).
- [ ] **Step 4: Commit** — `refactor: 회원 김프 알림 메일 문구를 템플릿으로 전환`

### Task 13: 정리와 22종 전수 적합성 (AC10)

**Files:** Modify `domain/src/test/kotlin/io/premiumspread/domain/alertmessage/AlertFormatTest.kt`(삭제 전 동등성 블록을 리터럴로), Delete `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlFormat.kt`, `OwnerAlertRows.expand(mail, route, discord)` overload, `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/OwnerAlertRowsTest.kt`(계약은 Task 3 에서 `OwnerAlertRowsMessageTest` 로 이식됨);
Test Create `domain/src/test/kotlin/io/premiumspread/domain/alertmessage/AlertMessageSpecConformanceTest.kt`.

**테스트가 재는 것:** dod AC10 — `@TestFactory` 로 dod "AC10 시나리오 표" 22행을 각각 dynamic test 로. 입력은 Task 8~12 golden 과 같은 fixture(테스트 안 private builder, 다른 테스트 파일에 의존하지 않음).
행마다 (a)~(e). 금지어 정규식은 dod AC10 의 것 그대로.

- [ ] **Step 1:** `AlertFormatTest` 의 `AccountPnlFormat` 직접 비교 블록을 실행해 얻은 출력값을 리터럴 기대값으로 바꾼다(PASS 확인) → 그 뒤 삭제.
  `grep -rn 'AccountPnlFormat\|AccountPnlMailContent\|AccountPnlDiscordContent\|expand(.*) {' --include=*.kt domain apps infrastructure` → 출력 없음(KDoc·주석 포함).
- [ ] **Step 2:** 적합성 테스트 → 변이 확인(한 템플릿의 접두어를 `[실계정 ]` 으로 바꿔 FAIL 을 보고 되돌림) → PASS. `./gradlew :domain:test --offline --no-daemon`.
- [ ] **Step 3:** `./gradlew test architectureTest --offline --no-daemon` → exit 0.
- [ ] **Step 4: Commit** — `refactor: 옛 알림 서식 제거와 22종 문구 적합성 테스트`

### Task 14: E2E — 실제 outbox 와 mock Discord (AC16)

**Files:** Create `apps/batch/src/integrationTest/kotlin/io/premiumspread/application/job/OwnerAlertDiscordLayoutIntegrationTest.kt`. main 무변경.

**테스트가 재는 것:** 두 기존 IT 의 기반을 이 파일 안에서 조합한다(기존 파일 무수정) —
mock Discord 는 `OwnerAlertDeliveryIntegrationTest` 방식(Task 0 (g5): `MockWebServer()`, `DiscordWebhooks(pnl, market, ops)` 의 **market** URL 을 `server.url(...)` 로, 실제 `DiscordWebhookSenderAdapter`, payload `ObjectMapper().readTree`),
시장 회차는 `OwnerMarketAlertIntegrationTest` 방식(Task 0 (g6): 분봉 적재 → `OwnerMarketSignalJob` → `OwnerAlertDeliveryJob(store, mailSender, discordSender, executor, clock)`, 실제 `JdbcAccountPnlAlertStore`).
라우팅(케이스 c 의 "메일 켬" 수단): 프로퍼티가 아니라 테스트가 직접 만든 `OwnerAlertRouting` 을 Job 생성자에 넘긴다 —
`OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true), mapOf(OwnerAlertKind.PREMIUM_SWING to OwnerAlertRouteOverride(email = true)))`
(`PREMIUM_SWING` 기본 경로는 `email = false` — `OwnerAlertRouting.kt:32`. 같은 생성 방식이 `OwnerAlertDeliveryIntegrationTest.kt:94`·`OwnerMarketAlertIntegrationTest.kt:298` 에 있다).

| # | 준비 | 단정 |
|---|---|---|
| a | 급등 회차 → `OwnerAlertDeliveryJob` 1회 | 요청 1건, `embeds[0].author.name` = `시장 · BTC 빗썸/바이낸스`, `fields` 3개 이름 `현재 김프`·`10분 저점`·`변화`, `footer.text` 존재, `timestamp` ISO, `description` 키 없음, 행 `SENT`, `discord_layout` NOT NULL |
| b | SQL 로 `discord_layout` NULL DISCORD 행 1개(subject `t`, body `d`, color 1) | 요청 embed 키가 정확히 `title`·`description`·`color` |
| c | (a) 와 같은 회차의 EMAIL 행(위 override 로 생성) | 메일 행 subject 가 `[시장] ` 접두, body 에 `기준 시각 ` 줄 |

- [ ] **Step 1:** 작성 → `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.application.job.OwnerAlertDiscordLayoutIntegrationTest' --offline --no-daemon` → PASS(이미 구현됨 — 변이 확인: adapter 의 `description` 생략 조건을 뒤집어 (a) FAIL 을 보고 되돌림).
- [ ] **Step 2:** `./gradlew :apps:batch:integrationTest --offline --no-daemon` → exit 0.
- [ ] **Step 3: Commit** — `test: Discord embed 레이아웃 발송 E2E`

### Task 15: 문서 (AC17)

**Files:** Modify `docs/runbooks/account-pnl-alert.md`, `docs/runbooks/owner-ops-alert.md`, `docs/runbooks/owner-market-alert.md`, `.ai/PROJECT_STATUS.md`, `.ai/architecture/ARCHITECTURE_DESIGN.md`.

**요건:** design §14 표 전부. runbook 예시는 각 문서가 다루는 알림 1~2개만, `[message-spec](../work/alert-message-template/message-spec.md)` 링크.
`account-pnl-alert.md` 에 (1) V23 배포 순서와 위반 시 장애 범위 — design §5.3 "배포 순서" 행 그대로:
"API(Flyway) 먼저 → batch. 새 batch 가 V23 전에 뜨면 `account_pnl_mail` INSERT·claim SELECT 가 `Unknown column 'discord_layout'` 로 실패 → 해당 batch Job Failure → OperatorAlert(Slack).
owner 실계정·운영·시장 알림 enqueue 와 기존 대기 행(EMAIL 포함) 전달이 V23 적용 전까지 모두 멈춘다(데이터 손상 없음 — 대기 행은 남아 V23 뒤 전달)."
(2) `discord_layout` NULL 행·손상 행(json·version·shape — 조립 실패 포함) 동작과 WARN 문구, (3) 대조 체크리스트의 `청산가(호가 VWAP)`·`체결가 기준` 을 `현재가`·`순수익률` 로.
`docs/runbooks/account-pnl-alert.md:397` 의 로그 예시(`Account PnL observed: …`)는 로그 계약이므로 그대로 둔다.

- [ ] **Step 1:** 수정 → `bash docs/check-documentation.sh` → exit 0. `grep -n '조치 안내: docs/runbooks\|BITHUMB/BINANCE\|(대조용)' docs/runbooks/account-pnl-alert.md docs/runbooks/owner-ops-alert.md docs/runbooks/owner-market-alert.md` → 출력 없음.
  `grep -n 'Unknown column' docs/runbooks/account-pnl-alert.md` → 1줄 이상.
- [ ] **Step 2: Commit** — `docs: 알림 문구 템플릿화 runbook·상태 문서 갱신`

### Task 16: 최종 gate

- [ ] **Step 1:**
  ```bash
  ./gradlew test architectureTest --offline --no-daemon
  ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon
  ./gradlew :infrastructure:common:integrationTest --offline --no-daemon
  ./gradlew :apps:batch:integrationTest --offline --no-daemon
  ./gradlew :apps:api:integrationTest --offline --no-daemon
  ./gradlew harnessCheck harnessCheckTest --offline --no-daemon
  ./gradlew jacocoTestReport jacocoTestCoverageVerification --offline --no-daemon
  bash docs/check-documentation.sh
  git diff --name-status 3d179e7 -- '*/src/test/*' '*/src/integrationTest/*'        # R3 — M·D·R 행이 허용 목록 안
  git diff --name-only --diff-filter=MDR 3d179e7 -- infrastructure/common/src/main/resources/db/migration   # R4 — 빈 출력
  ```
- [ ] **Step 2: 예상 결과.** 전부 exit 0(Docker 부재 시 "미실행" 기록, green 금지). R3 diff 의 M·D 행은 dod R3 목록과 이번 작업 신규 파일(Task 1·12 의 `PremiumThresholdDeliveryServiceMailTest.kt`, Task 3·13 의 `AlertFormatTest.kt`, Task 8·9 의 `AccountPnlMonitorJobTemplateRowsTest.kt`)의 수정뿐.
  `OwnerMarketSignalJobTest.kt`·`NotificationDisabledContextTest.kt` 는 diff 에 없다.
- [ ] **Step 3:** 결과를 dod 증거 로그에 append. AC18 은 PR 에서 owner 에게 요청.

---

## 변경 내역

| 라운드 | 바꾼 것 | 사유 |
|---|---|---|
| ⑥-1 | Task 3·8 fields 13→12, SPACER 를 U+200B·`Char(0x200B)` 로(보이지 않는 문자 직접 삽입 금지) | MAJOR 1, MINOR 13(e) |
| ⑥-1 | Task 9 줄 구성 spec 그대로(A8 철회), A13 | 사용자 결정 |
| ⑥-1 | Task 8~11 Job 별 `*TemplateRowsTest` 신규 | MAJOR 4 |
| ⑥-1 | Task 15 배포 순서 위반 장애 범위 | MAJOR 5 |
| ⑥-1 | Task 9 `reminderHour`, Task 8 중단·복구 시각 출처 | MINOR 6·7 |
| ⑥-1 | Task 3·8 삭제 테스트 계약 이식 표 | MINOR 8 |
| ⑥-1 | Task 3 `AccountPnlFormat` 직접 비교, Task 13 리터럴 고정 | MINOR 9 |
| ⑥-1 | Task 8 `NetPercentSwingPolicy` KDoc, Task 8·13 grep 패턴 | MINOR 10 |
| ⑥-1 | Task 0 (g5)~(g11) 명령·예상 결과, Task 10 삭제 근거 grep | MINOR 11 |
| ⑥-1 | Task 11 `OwnerMarketSignalJobTest` 수정 대상 제외 | MINOR 12 |
| ⑥-1 | Task 14 라우팅 수단 확정, Task 4 `long` 제거, Task 6 조립 확인, Task 6·7 `fields` 항상 포함 | MINOR 13(a)~(d) |
| ⑥-1 | Task 12 zone 을 `NotificationDeliveryConfiguration` `@Bean` 으로(서비스 `@Value` 없음) | N1 |
