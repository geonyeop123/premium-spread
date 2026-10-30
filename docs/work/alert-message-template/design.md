# 알림 문구 템플릿화와 문구 정리 (issue #102) 설계

- slug: `alert-message-template` · base `origin/dev` `3d179e7` · branch `refactor/issue-102-alert-message-template`
- 입력: issue #102 · ③ 사용자 합의(§0.1) · **확정 문구 정본 `docs/work/alert-message-template/message-spec.md`(22종)** ·
  `.ai/rules/{architecture,testing,naming}.md` · 직전 단위 `docs/work/owner-market-alerts/*`(동결 기록, 수정 대상 아님)
- 상태: DRAFT (⑥ 스펙 리뷰 1차 반영 — 변경 내역은 §15)

## 0. 요약

owner 알림 21종과 회원 김프 메일 1종의 문구가 Content object 5개와 batch 서비스 1곳에 문자열로 흩어져 있다
(`AccountPnlMailContent.kt`·`AccountPnlDiscordContent.kt`·`AccountAlertContent.kt`·`OwnerOpsAlertContent.kt`·`OwnerMarketAlertContent.kt`·
`PremiumThresholdDeliveryService.kt:51-69`). 이번 작업은

1. domain 에 **채널별 문서 모델 2개**(메일 `MailDocument`, Discord `EmbedDocument`)와 렌더러, 공용 서식 `AlertFormat` 을 두고,
2. 모든 알림이 그 모델로 문구를 만들게 바꾸고(문구 정본 = message-spec.md),
3. Discord 는 embed **author·fields(inline 칸)·footer·timestamp** 로 보내기 위해 outbox DISCORD 행에 레이아웃을 보존한다.

새 라이브러리 없음. 발송 조건·라우팅·eventKey·재시도·채널 선택은 바꾸지 않는다.

### 0.1 사용자 합의 (③ — 이 문서가 바꾸지 않는다)

| # | 합의 |
|---|---|
| U1 | 범위: owner 알림 전부(실계정 순손익 수익·정시·중단·복구 / 계정 알림 포지션 변경·순수익률 급변·미반영 리마인더 / 운영 알림 / #90 시장 알림) + 회원 김프 임계값 도달 메일 |
| U2 | 확정 문구 정본 = `message-spec.md` 22종. 숫자는 예시, 구조(제목 형식·author·칸 이름·칸 배치·footer·메일 섹션·항목 순서·접두어)가 계약 |
| U3 | Discord 는 embed fields(inline 칸) + author + footer + timestamp. `**` 굵게 허용. Markdown 표 금지 |
| U4 | 메일 plain text 유지. 제목 접두어 owner `[실계정]`·`[운영]`·`[시장]`, 회원 `[김프 알림]`. 본문 끝 `기준 시각 yyyy-MM-dd HH:mm KST`. UTC 병기 제거 |
| U5 | 공통 삭제: 런북 경로 줄, `BITHUMB`/`BINANCE` 영문 거래소 표기, "청산", `gross`, 체결가 기준(대조용) 수익률, "바이낸스 포지션: 거래소 API · 날짜" 줄, 호가 VWAP 표기. 조치 안내(빗썸 허용 IP, `BINANCE_MANUAL_*` env)는 "확인할 것"으로 유지 |
| U6 | 수익·정시의 새 값: 현김프·매수김프·차이, leg 별 현재가·손익·평가액, 진입·정리(예상) 수수료 거래소별, 수수료 합계, 펀딩비, 순손익. 제목은 퍼센트+금액. 계산 본체 `AccountNetPnl.kt`(`Leg.of`), 새 값은 domain 순수 함수 |
| U7 | 범위 밖: 총 자산·잔여 금액·증거금+손익·USDT 원화가(새 수집 필요), HTML 메일, 리소스 파일 외부화, 발송 조건·라우팅 변경 |
| U8 | 색은 기존 유지(message-spec 의 color) |

### 0.2 핵심 결정

| # | 결정 | 절 |
|---|---|---|
| K1 | 템플릿 계층은 **domain 새 패키지 `io.premiumspread.domain.alertmessage`** 에 둔다. 순수 Kotlin + `java.time`·`java.text`·`java.math` 만. 기존 `AccountPnlFormat`(internal)의 숫자 서식은 같은 동작으로 `AlertFormat` 에 두고, 모든 family 전환 뒤 `AccountPnlFormat` 을 삭제한다 | §3 |
| K2 | 메일과 Discord 는 **별개 문서**(`MailDocument`·`EmbedDocument`)다. 한 모델에서 자동 변환하지 않는다 — message-spec 은 같은 알림이라도 메일 섹션 구성과 Discord 칸 배치가 다르다(예: 수익 알림 수수료는 메일 하위 항목 4줄, Discord 칸 2개). 각 알림 템플릿이 두 문서를 같은 값으로 만든다 | §3.1 |
| K3 | Domain port `OwnerAlertDiscordSender.send(route, message: DiscordMessage)` **시그니처 무변경**. `DiscordMessage` 에 선택 프로퍼티 `layout: DiscordLayout?`(author·fields·footer·timestamp)를 더한다. 구현은 기존 `infrastructure:batch` `DiscordWebhookSenderAdapter` | §4 |
| K4 | outbox 보존: **Flyway V23 으로 `account_pnl_mail.discord_layout TEXT NULL` 을 append-only 추가**하고 JSON 직렬화는 `infrastructure:common` 이 한다. body 구조 인코딩(후보 b)은 쓰지 않는다 | §5 |
| K5 | 옛 행 호환: `discord_layout IS NULL` 인 DISCORD 행은 지금과 같은 title/description embed 로 보낸다. 새 행의 `body` 에는 레이아웃을 평문으로 펼친 대체 설명을 넣어 **구 binary 로 롤백해도 읽을 수 있는 메시지**가 간다 | §5.3 |
| K6 | 수익·정시 새 값은 domain 순수 함수 `AccountPnlBreakdown.of(pnl)` 가 `AccountNetPnl` 의 결과(현재가·환율·포지션)에서 다시 계산한다. `AccountNetPnl` 은 바꾸지 않는다. 같은 공식임을 테스트가 `grossKrw`·`exitFeeEstimateKrw`·`netKrw` 일치로 고정한다 | §6.1 |
| K7 | 각 family 의 공개 진입점은 `OwnerAlertMessage(eventKey, mail, discord)` 하나를 돌려준다. `OwnerAlertRows.expand(message, route)` 가 행으로 펼친다. eventKey 형식 무변경 | §3.4 |
| K8 | 거래소 한글 표기(`빗썸`·`바이낸스`)는 `AlertFormat.exchange(Exchange)` 한 곳이 한다. 표기 재료는 **알림 자신의 `MarketPair`·`Exchange`** 뿐이다 | §8 |
| K9 | 커밋은 **A단계(동작 보존 — 기존 golden 무수정 통과)** 와 **B단계(family 별 문구 전환 커밋)** 로 나눈다 | §12 |

## 1. 범위

### 1.1 포함

- domain `alertmessage`(신규): `AlertFormat`, `MailDocument`·`MailBlock`·`MailPair`, `EmbedDocument`·`EmbedCell`, `OwnerAlertMessage`, 렌더러
- domain `owneralert`: `DiscordField`·`DiscordLayout`, `DiscordMessage.layout`
- domain `accountpnl`: `AccountPnlMail.discordLayout`, `OwnerAlertRows.expand(message, route)`, `AccountPnlBreakdown`(신규), `AccountPnlAlertContent`(신규 — `AccountPnlMailContent`·`AccountPnlDiscordContent` 대체), `AccountAlertContent` 재작성, `AccountPnlFormat` 삭제, `NetPercentSwingPolicy` KDoc 참조 갱신
- domain `ownerops`·`ownermarket`: `OwnerOpsAlertContent`·`OwnerMarketAlertContent` 재작성
- domain `notification`: `PremiumThresholdMailContent`(신규)
- apps:batch: 세 Job 의 호출 줄, `OwnerAlertDispatcher.sendDiscord`, `PremiumThresholdDeliveryService`(zone 생성자 인자·템플릿 호출), `config/NotificationDeliveryConfiguration`(서비스 빈 생성·zone 주입)
- infrastructure:common: V23, `OwnerAlertOutboxRows`(insert·validate), `JdbcAccountPnlAlertStore`(claim 읽기), `DiscordLayoutJson`(신규), batch fixture 스키마, `build.gradle.kts`(jackson-databind 선언 — D1 승인)
- infrastructure:batch: `DiscordWebhookSenderAdapter.payload`
- 문서: `docs/runbooks/account-pnl-alert.md`·`owner-ops-alert.md`·`owner-market-alert.md` 예시, `.ai/PROJECT_STATUS.md`(V23), `.ai/architecture/ARCHITECTURE_DESIGN.md`(템플릿 계층 한 단락)

### 1.2 제외 (scope creep 차단선)

- U7 전부. 발송 조건·쿨다운·라우팅·`OwnerAlertKind`·eventKey·재시도·Message-ID 규칙
- 회원 알림의 Discord 발송, 회원 메일 HTML
- `AccountNetPnl` 의 필드·공식 변경, 관측·정책·상태 저장 변경
- outbox 테이블·행 타입 rename(`account_pnl_mail`·`AccountPnlMail` 이름 부채 유지)
- Redis key·TTL·payload, 공개 endpoint, 새 메트릭
- 과거 `docs/work/*` 산출물과 `message-spec.md` 수정

## 2. 현재 구조 (조사 결과)

| 위치 | 하는 일 |
|---|---|
| `domain/.../accountpnl/AccountPnlMailContent.kt:27-40` | outbox 행 타입 `AccountPnlMail`(eventKey·subject·body·channel·discordRoute·discordColor) |
| 같은 파일 `:43-146` | 수익·정시·중단·복구 메일 문구, eventKey `account-pnl-{kind}-{ms}` |
| `AccountPnlDiscordContent.kt` | 같은 4종 Discord title/description |
| `AccountAlertContent.kt` | 포지션 변경·급변·리마인더 메일·Discord, eventKey |
| `AccountPnlFormat.kt` | internal 공용 서식(`time` 은 `yyyy-MM-dd HH:mm:ss (UTC instant)`, `shortTime` 은 `yyyy-MM-dd HH:mm z`), `RUNBOOK`, 실패 범주 label |
| `ownerops/OwnerOpsAlertContent.kt`, `ownermarket/OwnerMarketAlertContent.kt` | 운영·시장 메일·Discord, `threshold`/`elapsed` 서식(`threshold` 는 시장이 빌려 씀) |
| `owneralert/OwnerAlertDiscord.kt` | `DiscordMessage(title≤255, description≤4096, color)`, `of()` 절단, port `OwnerAlertDiscordSender` |
| `accountpnl/OwnerAlertRows.kt` | 메일 행 + `DiscordMessage` → DISCORD 행(subject=title, body=description) |
| `accountpnl/NetPercentSwingPolicy.kt:12` | KDoc 이 `AccountPnlMailContent.hourly` 를 참조(Task 8 에서 `AccountPnlAlertContent.hourly` 로) |
| `apps/batch/.../owneralert/OwnerAlertDispatcher.kt:96-102` | DISCORD 행을 `DiscordMessage(subject, body, color)` 로 재조립 |
| `infrastructure/batch/.../DiscordWebhookSenderAdapter.kt:142-150` | embed `{title, description, color}` |
| `infrastructure/common/.../OwnerAlertOutboxRows.kt` | 행 검증(subject≤255, body≤65,535 B)·INSERT (세 store 공용) |
| `JdbcAccountPnlAlertStore.kt:93-104,359-381` | claim SELECT 와 행 매핑 |
| `apps/batch/.../PremiumThresholdDeliveryService.kt:51-69` | 회원 메일 subject/payload 하드코딩, 관측 시각 `Instant.toString()`(UTC). 빈 등록은 클래스의 `@Service` + `@ConditionalOnProperty(notification.email.enabled=true)` |
| `apps/batch/.../config/NotificationDeliveryConfiguration.kt` | 같은 `@ConditionalOnProperty` 를 가진 빈 없는 Configuration(`NotificationDeliveryProperties` 활성화만) |

zone 출처: `OwnerOpsSignalJob`·`OwnerMarketSignalJob` 은 `aggregation.zone` — 각 `config/*Configuration` 의 `@Bean` 메서드가 `@Value` 로 읽어 `ZoneId.of(zone)` 을 Job 생성자에 넘긴다
(`OwnerAlertDeliveryConfiguration.kt:153,165`, `OwnerMarketAlertConfiguration.kt:67`). `AccountPnlMonitorJob` 은 생성자 기본값 `Asia/Seoul`(`AccountPnlMonitorJob.kt:83`, 배선이 넘기지 않음).
회원 서비스는 zone 이 없다.

## 3. 템플릿 계층 (K1·K2·K7)

### 3.1 배치와 의존 방향

```text
domain.alertmessage   (AlertFormat · MailDocument · EmbedDocument · OwnerAlertMessage · 렌더러)
   ↑                    → domain.owneralert (DiscordMessage · DiscordLayout · DiscordField), domain.ticker, domain.market
   ├ domain.accountpnl  (AccountPnlAlertContent · AccountAlertContent · AccountPnlBreakdown · OwnerAlertRows)
   ├ domain.ownerops    (OwnerOpsAlertContent)
   ├ domain.ownermarket (OwnerMarketAlertContent)
   └ domain.notification(PremiumThresholdMailContent)
```

- `alertmessage` 는 `owneralert`(출력 타입)와 `ticker`(`Exchange`)·`market`(`MarketPair`)만 본다. family 패키지를 보지 않는다 — 문구(무엇을 쓸지)는 family 템플릿, 형태(어떻게 찍을지)는 `alertmessage`.
- 실패 범주 label·안내 문장 같은 **문구**는 `AlertFormat` 에 두지 않는다(family 템플릿).
- apps:batch 는 compile 시 `:domain` 만 의존하므로 회원 템플릿도 domain 에 둔다. `AlertFormat` 은 internal 이지만 apps 는 `MailDocument.body()` 만 부른다.

### 3.2 메일 문서

```kotlin
data class MailPair(val label: String, val value: String, val children: List<MailPair> = emptyList())

sealed interface MailBlock {
    data class Text(val lines: List<String>) : MailBlock          // 줄 그대로
    data class Pairs(val pairs: List<MailPair>) : MailBlock       // "라벨<정렬 공백>값", children 은 "  - 라벨<정렬>값"
    data class Bullets(val items: List<String>) : MailBlock       // "- 항목"
    data class Section(val title: String, val blocks: List<MailBlock>) : MailBlock  // "■ 제목" + 안쪽 블록(빈 줄 없이)
}

data class MailDocument(
    val subject: String,
    val blocks: List<MailBlock>,
    val basis: Instant?,          // null 이면 "기준 시각" 줄 없음
    val zone: ZoneId,
    val signature: String? = null, // 회원 메일 "premium-spread"
) { fun body(): String }
```

렌더 규칙(`body()`):

1. 최상위 블록 사이에 빈 줄 1개. `Section` 안 블록 사이에는 빈 줄 없음.
2. `Pairs` 정렬: 같은 블록 안 라벨의 **표시 폭** 최댓값 + 2 까지 공백으로 채운다. 표시 폭은 한글(음절·자모·호환 자모)과 CJK·전각 문자 2, 그 밖 1.
   `children` 은 부모 아래 `"  - "` 접두로, 형제끼리 같은 규칙으로 정렬한다.
3. `basis != null` 이면 빈 줄 + `기준 시각 {AlertFormat.stamp(basis, zone)}`.
4. `signature != null` 이면 빈 줄 + `--` + 줄바꿈 + signature.
5. 줄 끝 공백 없음, 마지막 줄 뒤 개행 없음.

`[ASSUMPTION] A1` message-spec 의 공백 개수는 손으로 맞춘 예시라 블록마다 일관되지 않다(예: 중단 메일 `원인` 뒤 7칸, `마지막 성공` 뒤 2칸).
계약은 위 정렬 규칙이고 golden 은 규칙의 출력이다.

### 3.3 Discord 문서와 렌더 규칙

```kotlin
data class EmbedCell(val name: String, val value: String)

data class EmbedDocument(
    val author: String,
    val title: String,
    val color: Int,
    val rows: List<List<EmbedCell>>,   // 한 행 = 나란히 놓이는 칸 1~3개
    val footer: String?,
    val timestamp: Instant,
) { fun toDiscordMessage(): DiscordMessage }
```

`toDiscordMessage()` 규칙:

1. 칸 → `DiscordField`. 칸이 1개인 행은 `inline=false`(전체 폭), 2~3개인 행은 `inline=true`.
2. 칸이 2개인 inline 행 **다음에 칸 2개 이상인 행이 오면** 빈 inline 칸 `EmbedDocument.SPACER` = `DiscordField(ZWSP, ZWSP, inline = true)` 하나를 덧붙여 행을 3칸으로 채운다
   (ZWSP = 유니코드 U+200B zero-width space 한 글자. 문서·소스에 보이지 않는 문자를 직접 넣지 않고 소스에서는 `Char(0x200B).toString()` 으로 만든다 — plan Task 3).
   (Discord 는 inline 칸을 한 줄에 3개까지 흘려 넣으므로, 채우지 않으면 다음 행 첫 칸이 위 행으로 올라간다).
   예: 수익 알림 행 [3,2,3,2,1] = 칸 11개 + 2번째 행 뒤 채움 1개 = **fields 12개**(4번째 행 뒤는 다음 행이 1칸이라 채우지 않는다).
3. 절단: title 255, author 256, footer 2048, 칸 이름 256, 칸 값 1024 (`DiscordMessage.truncate`, UTF-16 기준·surrogate 보존).
4. `description` = 대체 평문(§5.3): 칸마다 한 줄 `"{name}: {value 의 \n 을 ' / ' 로, ** 제거}"`(채움 칸 제외) + footer 가 있으면 마지막 줄. 4096 절단.
5. `layout = DiscordLayout(author, fields, footer, timestamp)`.

### 3.4 알림 하나의 결과와 행 펼치기

```kotlin
data class OwnerAlertMessage(val eventKey: String, val mail: MailDocument, val discord: EmbedDocument)

object OwnerAlertRows {
    /** route.email 이면 EMAIL 행(subject=mail.subject, body=mail.body()), route.discord 면 DISCORD 행(메일 행 먼저). */
    fun expand(message: OwnerAlertMessage, route: OwnerAlertRoute): List<AccountPnlMail>
}
```

DISCORD 행: `subject = m.title`, `body = m.description`(대체 평문), `discordColor = m.color`, `discordLayout = m.layout` (`m = discord.toDiscordMessage()`).
Discord 렌더링은 `route.discord != null` 일 때만 한다(기존 lazy 규칙 유지). 옛 `expand(mail, route, discord)` 는 모든 family 전환 뒤 삭제한다.

### 3.5 공용 서식 `AlertFormat` (internal object)

기존 `AccountPnlFormat` 의 숫자 함수(`plain`·`format`·`sign`·`percent`·`krw`·`krwOrZero`·`cost`·`signedUsdt`·`percentPoint`)와 같은 동작을 두고 아래를 더한다.
A단계에서는 기존 Content object 를 건드리지 않으므로 두 object 가 잠시 공존하고, B단계 정리 태스크가 `AccountPnlFormat` 과 UTC 병기 `time` 을 삭제한다. 시그니처는 plan Task 3 이 전문으로 둔다.
두 object 모두 domain 모듈 internal 이라 domain 테스트에서 직접 호출해 출력을 비교할 수 있다(plan Task 3 `AlertFormatTest`).

| 함수 | 출력 예 | 용도 |
|---|---|---|
| `stamp(instant, zone)` | `2026-10-20 09:00 KST` (`yyyy-MM-dd HH:mm z`, `Locale.ROOT`) | 메일 모든 시각 |
| `clock(instant, basis, zone)` | 같은 zone 날짜면 `09:30`, 아니면 `10-17 09:30` | Discord 칸·메일 `(08:59 관측)` |
| `monthDayClock(instant, zone)` | `10-27 21:00` | 운영 해소 Discord |
| `zoneAbbreviation(at, zone)` | `KST` | 일일 요약 대상 구간 |
| `threshold(duration)` | `30분`·`6시간`·`90초` | 기존 `OwnerOpsAlertContent.threshold` 와 같은 동작 |
| `duration(duration)` | `45분`·`6시간`·`6시간 5분` (음수 → `0분`) | 지속 시간 |
| `exchange(Exchange)` / `exchangePair(pair, sep)` | `빗썸`·`바이낸스`·`업비트`·`환율 제공자` / `빗썸/바이낸스` | K8 |
| `premium(value)` | `1.78%`, `-0.50%` (양수 부호 없음, 0.00) | 실계정 김프·회원 메일 현재 김프 |
| `thresholdPercent(value)` | `1.23%`, `1.2345%` (최소 소수 2자리, 설정 정밀도 보존, 뒤 0 제거) | 회원 메일 조건 줄 임계값 |
| `krwPrice(value)` | `158,500,000원` | 가격·평가액 |
| `usdtPrice(value)` | `111,200 USDT`, `70,000.5 USDT` (`#,##0.####`) | 바이낸스 가격 |
| `usdt2(value)` | `14.36 USDT` (부호 없음, `#,##0.00`) | 수수료·평가액 USDT |
| `signedUsdt2(value)` | `+4.20 USDT`, `-851.20 USDT` | 손익·펀딩 USDT |
| `rate(value)` | `1,400.00` | 환율 |
| `displayWidth(text)` | 한글 2·그 밖 1 (`마지막 성공` = 11, `10분 저점` = 9) | 메일 정렬 |

`[ASSUMPTION] A2` Discord 칸의 시각은 `clock(instant, basis, zone)` 이다 — 기준 시각과 같은 날이면 `HH:mm`, 아니면 `MM-dd HH:mm`(리마인더 `10-17 09:30 변경` 이 이 규칙의 예).
운영 해소 알림만 message-spec 대로 항상 `MM-dd HH:mm` 이다.

## 4. Discord 전달 계약 (K3)

```kotlin
data class DiscordField(val name: String, val value: String, val inline: Boolean)
data class DiscordLayout(val author: String?, val fields: List<DiscordField>, val footer: String?, val timestamp: Instant?)
data class DiscordMessage(val title: String, val description: String, val color: Int, val layout: DiscordLayout? = null)
fun interface OwnerAlertDiscordSender { fun send(route: DiscordRoute, message: DiscordMessage) }   // 무변경
```

- 한도(Discord API): 칸 이름 1~256, 칸 값 1~1024(공백만 불가), 칸 ≤25, footer 1~2048, author 1~256, title 1~255(outbox `subject` VARCHAR(255) 와 맞춤),
  `layout != null` 이면 title+author+footer+칸 이름·값 합계 ≤ 6000. 검증은 생성자, 절단은 렌더러(§3.3).
- adapter payload: `layout == null` → 지금과 같은 `{title, description, color}`(기존 `DiscordWebhookSenderAdapterTest` 무수정 통과).
  `layout != null` → `{author:{name}?, title, color, fields:[{name,value,inline}], footer:{text}?, timestamp?}` — **description 은 보내지 않는다**(대체 평문은 롤백용).
  `fields` 키는 **항상** 싣는다(빈 목록이면 `[]` — Discord 가 허용한다. 템플릿이 만든 layout 은 rows 가 비지 않으므로 실제로는 늘 1개 이상). `author`·`footer`·`timestamp` 만 null 이면 키를 뺀다.
  `content`(@here)·`allowed_mentions`·`flags`(SILENT 4096)는 push 규칙 그대로. timestamp 는 `Instant.toString()`(ISO-8601 UTC) — Discord 가 보는 사람 현지 시각으로 그린다.
- `OwnerAlertDispatcher.sendDiscord` 는 `DiscordMessage(mail.subject, mail.body, color, mail.discordLayout)` 로 바뀌는 한 줄뿐이다.

## 5. outbox 보존 (K4·K5)

### 5.1 후보 비교

| 후보 | 장점 | 단점 | 판정 |
|---|---|---|---|
| (a) 새 nullable 컬럼 `discord_layout`(JSON 텍스트), 직렬화 infra | NULL = 옛 행으로 판별이 명확, 운영자가 SQL 로 읽을 수 있음, domain 에 직렬화 없음, 구 binary 는 컬럼을 무시 | Flyway 1개, 배포 순서(API 먼저) | **채택** |
| (b) `body` 에 구조 인코딩 | migration 없음 | 옛 행과 새 행을 내용으로 추측해야 함, 구 binary 롤백 시 인코딩된 원문이 그대로 발송, body 의미가 채널마다 달라짐 | 기각 |
| (c) 자식 테이블(칸 행) | 순수 관계형 | `ON DUPLICATE KEY` 무시 행의 id 회수·중복 칸 방지가 복잡, claim 질의 2배 | 기각 |

### 5.2 V23 (번호 = `ls infrastructure/common/src/main/resources/db/migration | sort -V | tail -1` 결과 V22 + 1, plan Task 0 이 재확인)

`ALTER TABLE account_pnl_mail ADD COLUMN discord_layout TEXT NULL` + CHECK(`discord_layout IS NULL OR channel = 'DISCORD'`). DDL 전문은 plan Task 5.
순수 추가 — `verifyMigrations` 금지 목록(TRUNCATE·DROP TABLE·WHERE 없는 DELETE)과 무관, 기존 행은 NULL, 롤백 SQL 없음. API 만 Flyway 를 실행한다(batch 비활성).

JSON 스키마(v1, infra 소유, 키 순서 고정, `fields` 는 빈 목록이어도 `[]` 로 항상 쓴다):

```json
{"v":1,"author":"실계정 · BTC","fields":[{"name":"현김프","value":"1.78%","inline":true}],"footer":"환율 1,400.00원/USD","timestamp":"2026-10-20T00:00:00Z"}
```

`author`·`footer`·`timestamp` 는 null 가능. 직렬화는 `infrastructure:common` 의 internal `DiscordLayoutJson`(Jackson tree API, Kotlin module·reflection 불필요).
TEXT(최대 65,535 바이트) 안에 든다 — 레이아웃 합계 6000자 × UTF-8 최대 4바이트 + JSON 구조. 검증은 `OwnerAlertOutboxRows.validate`.
의존 선언은 D1(§13, **승인됨**)대로 `infrastructure:common` 에 `jackson-databind` 를 추가한다.

### 5.3 옛 행·롤백·배포

| 상황 | 동작 |
|---|---|
| 배포 전에 쌓인 미발송 DISCORD 행 (`discord_layout` NULL) | 새 batch 가 `layout = null` 로 읽고 **옛 title/description embed** 로 보낸다(옛 문구). 재렌더하지 않는다 — outbox 는 렌더 결과의 정본이다 |
| `discord_layout` 을 읽을 수 없음(JSON 오류·`v ≠ 1`·필수 키 누락·domain 불변식 위반) | 그 행만 `layout = null` 로 보고 대체 평문 description 으로 보낸다. WARN `Owner alert discord layout unreadable; sending plain description: id={}, reason={}` (reason ∈ `json`·`version`·`shape`, 내용·예외 메시지 없음). claim 전체를 실패시키지 않는다(독 행 방지). **shape 판정은 `DiscordLayout` 복원뿐 아니라 그 행의 `subject`·`body`·color 와 합쳐 `DiscordMessage(subject, body, color, layout)` 를 실제로 조립해 본 결과까지 포함한다** — 조립이 `IllegalArgumentException`(예: title+layout 합계 6000 초과)이면 `shape` 로 같은 처리. 그래야 dispatcher 의 `DiscordMessage` 생성이 그 행에서 예외를 내지 않는다 |
| 구 batch 로 롤백 (V23 은 남음) | 구 INSERT 는 컬럼을 모름 → NULL. 구 claim 은 컬럼을 무시하고 `subject`/`body` 로 보낸다 → 새 행은 대체 평문으로 읽힌다 |
| 배포 순서 | **API 먼저**(Flyway 는 API 만 실행) → batch. 새 batch 가 V23 전에 뜨면 `account_pnl_mail` 의 INSERT(`OwnerAlertOutboxRows.kt:33-36` — 세 store 공용)와 claim SELECT(`JdbcAccountPnlAlertStore.kt:96-97`)가 `Unknown column 'discord_layout'` 로 실패한다 → 해당 batch Job 이 Failure(`OwnerOpsSignalJob.kt:156-158` 같은 store 실패 분기)로 끝나고 OperatorAlert(Slack)가 난다. **owner 실계정·운영·시장 알림 enqueue 와, 기존 대기 행(EMAIL 포함) 전달이 V23 적용 전까지 모두 멈춘다**(데이터 손상 없음 — 실패한 INSERT 는 상태 갱신과 같은 트랜잭션이라 롤백되고, 대기 행은 그대로 남아 V23 뒤 전달된다). 그래서 API(Flyway) 먼저 배포한다. runbook 에 적는다 |

## 6. 알림별 값 출처

문구 형태(칸 이름·배치·제목 형식·footer·메일 섹션·칸 값의 줄 구성)는 message-spec 이 정본이다. 아래 표는 **각 자리에 무엇을 넣는가**만 정한다.
`basis` = 기준 시각(메일 끝 줄·embed timestamp).

### 6.1 `AccountPnlBreakdown` (K6, `io.premiumspread.domain.accountpnl`)

`AccountPnlBreakdown.of(pnl: AccountNetPnl)` — 반올림 없음(나눗셈 `DECIMAL128`), 퍼센트만 scale 2 HALF_UP. 입력은 `pnl.position`·`koreaExitPrice`·`foreignExitPrice`·`fxRate`.
"현재가" = 지금 정리 시 가격 = 순수익률 계산과 같은 값(빗썸 매수호가 VWAP·바이낸스 매도호가 VWAP). 문구에는 VWAP 라는 말을 쓰지 않는다(U5).
바이낸스 leg 는 항상 숏이다 — `AccountHedgePosition.resolve`(`AccountHedgePosition.kt:19-20`)가 `foreign.signedQuantity < 0` 이 아니면 포지션을 만들지 않으므로 `AccountNetPnl` 에 롱이 오지 않는다. 그래서 breakdown 에 방향 필드를 두지 않는다.

| 값 | 식 |
|---|---|
| `currentPremiumPercent` | `(koreaExit − foreignExit × fx) / koreaExit × 100` |
| `entryPremiumPercent` | `(korea.averageBuyPrice − foreign.entryPrice × fx) / korea.averageBuyPrice × 100` (환율은 현재 환율) |
| `premiumGapPercentPoint` | `currentPremiumPercent − entryPremiumPercent` (둘 다 scale 2 로 반올림한 뒤 뺀다 — `[ASSUMPTION] A3` 표시값끼리 맞게) |
| korea `pnlKrw` / `valueKrw` | `(koreaExit − avg) × qty` / `koreaExit × qty` |
| foreign `pnlUsdt` / `pnlKrw` | `(entry − foreignExit) × qty` / `pnlUsdt × fx` |
| foreign `valueUsdt` / `valueKrw` | `foreignExit × qty` / `valueUsdt × fx` |
| korea `entryFeeKrw` | `korea.entryFeeKrw` (null = 미반영) |
| foreign `entryFeeUsdt` / `entryFeeKrw` | `entryCommissionUsdt` (null = 미반영) / `× fx` |
| korea `exitFeeKrw` | `korea.sellFeeRate × koreaExit × qty` |
| foreign `exitFeeUsdt` / `exitFeeKrw` | `takerFeeRate × foreignExit × qty` / `× fx` |
| `feeTotalKrw` | 진입(미반영은 0) + 정리 예상 |
| `fundingUsdt` / `fundingKrw` | `foreign.fundingUsdt` (null = 미반영) / `pnl.fundingKrw` |
| `grossKrw`·`netKrw`·`netPercent` | `pnl` 그대로 |

불변식 테스트: `korea.pnlKrw + foreign.pnlKrw == pnl.grossKrw`, `korea.exitFeeKrw + foreign.exitFeeKrw == pnl.exitFeeEstimateKrw`,
`gross − feeTotal + fundingKrw == pnl.netKrw` (모두 `compareTo`).

`A4`(**사용자 승인**) 금액은 각 항목을 전체 정밀도 값에서 원 단위 HALF_UP 으로 표시한다. **합계(`수수료 합계`)·순손익도 반올림한 항목을 더하지 않고 각자의 전체 정밀도 값(`feeTotalKrw`·`netKrw`)을 반올림한다.**
그래서 표시된 항목의 합이 표시된 합계·순손익과 ±1원 다를 수 있고, 이 차이는 허용한다
(message-spec 예시의 `수수료 -73,846원`·`순손익 +469,354원` 은 반올림한 항목을 더한 값이고, 같은 입력의 실제 계산은 `-73,847원`·`+469,353원`).

### 6.2 실계정 순손익 (`AccountPnlAlertContent`, author `실계정 · BTC`)

| 알림 | 제목 핵심 | basis | 비고 |
|---|---|---|---|
| 수익 | `수익 알림 {percent(net%)} {krw(net)}` | `pnl.calculatedAt` | footer `환율 {rate(fx)}원/USD` |
| 정시 | `정시 보고 {HH}:00 {percent} {krw}` (HH = calculatedAt 의 zone 정시) | `pnl.calculatedAt` | footer `매시 정각 발송 · 환율 …` |
| 중단 | `모니터링 중단 — {label}` | `now`(렌더 인자) | footer `{threshold(MonitoringOutagePolicy.THRESHOLD)} 넘게 계산하지 못하면 알림`. `[확인할 것]` 칸·섹션은 `BITHUMB_AUTH` 만 |
| 복구 | `모니터링 복구` | 메일 basis 없음(spec), embed timestamp = `now`(렌더 인자) | footer 없음 |

중단·복구 칸·메일 항목의 시각 출처:

| 자리 | 값 | Discord 표기 | 메일 표기 |
|---|---|---|---|
| 중단 `[마지막 성공]` / `마지막 성공` | `OutageNotice.Outage.lastSuccessAt` (null 이면 `없음`) | `clock(lastSuccessAt, now, zone)` | `stamp(lastSuccessAt, zone)` |
| 중단 `[중단 감지]` / `중단 감지` | 렌더 인자 `now` (`AccountPnlAlertContent.outage(notice, now, zone)`) | `clock(now, now, zone)` | `stamp(now, zone)` |
| 복구 `[복구 시각]` / `복구 시각` | 렌더 인자 `now` (`AccountPnlAlertContent.recovered(now, zone)`) | `clock(now, now, zone)` | `stamp(now, zone)` |

수익·정시 칸 값: §6.1 값을 `premium`·`percentPoint`·`krwPrice`·`krw`·`usdtPrice`·`usdt2`·`signedUsdt2`·`cost` 로 찍는다. 순손익 칸은 `**{krw} ({percent})**`.
- `[ASSUMPTION] A5` 미반영 항목(진입 수수료·펀딩비 null)은 금액 자리에 `미반영 (이력 없음)` 또는 `미반영 (수동 미입력)`(바이낸스 수동 입력 모드)을 쓴다. 합계는 0 으로 계산된 값 그대로.
- `[ASSUMPTION] A6` 실패 범주 label 은 `BITHUMB_AUTH` 만 `빗썸 인증 실패` 로 줄이고(허용 IP 안내는 "확인할 것"으로 이동), 나머지 6개는 그대로다.
- 바이낸스 포지션 출처(거래소 API/수동 env)는 수익·정시 본문에 쓰지 않는다(U5).
- D2(**승인됨 — 삭제**): 빗썸 평단 사용자 수정 표시(현재 `빗썸 평단 사용자 수정됨`)는 메일·Discord 어디에도 쓰지 않는다. 평단 값은 수정 여부와 무관하게 `평단 {krwPrice(avg)}` 로 같다.

### 6.3 계정 알림 (`AccountAlertContent`, author `실계정 · BTC`)

| 알림 | 제목 핵심 | basis | 값 |
|---|---|---|---|
| 포지션 변경 | `포지션 변경 — {빗썸·바이낸스\|빗썸\|바이낸스}` (+ ` · 헤지 불일치`) | `detectedAt` | 바뀐 leg = `{변경 전} ⏎ → {변경 후}`, 안 바뀐 leg = 칸 이름에 ` (변경 없음)` |
| 순수익률 급변 | `순수익률 {급등\|급락} {pp(delta)} → {percent(net%)}` | `pnl.calculatedAt` | 기준선 칸 이름 `기준선 ({bucketStart 의 HH}:00)` |
| 미반영 리마인더 | `미반영 항목 {items.size}건` | `detectedAt` | 빠진 항목 3종은 `[순손익에 빠진 항목]`, `MANUAL_FUNDING_STALE` 은 `[오래된 항목]`. 해당 항목이 없는 칸·섹션은 만들지 않는다 |

- leg 표기 재료: 빗썸 `{plain(qty)} BTC`·`평단 {krwPrice(avg)}`, 비었으면 `포지션 없음`. 바이낸스 `{plain(|qty|)} BTC`·`평단 {usdtPrice(entry)}`, flat 이면 `포지션 없음`.
- **칸 값·메일 줄의 줄 구성은 message-spec 그대로다**(design 이 바꾸지 않는다):
  - 바뀐 leg — Discord 칸 `{수량} BTC · 평단 {가격} ⏎ → {변경 후}`(spec 201·231행), 메일 `변경 전  …`·`변경 후  …` Pairs(spec 214-215·242-243행).
  - 안 바뀐 **빗썸** leg — Discord 칸 값 `{plain(qty)} BTC ⏎ 평단 {krwPrice(avg)}` 두 줄(spec 201행), 메일 `{plain(qty)} BTC · 평단 {krwPrice(avg)}` 한 줄(spec 211행).
  - 안 바뀐 **바이낸스** leg — Discord 칸 값 `{plain(|qty|)} BTC · 평단 {usdtPrice(entry)}` 한 줄(spec 231행), 메일 같은 한 줄(spec 246행).
  - `[ASSUMPTION] A13` 안 바뀐 leg 가 비었거나 flat 이면(spec 예시 없음) Discord·메일 모두 `포지션 없음` 한 줄.
- `[ASSUMPTION] A7` 포지션 변경 알림의 바이낸스 leg 는 계정 원본 스냅샷(`PositionLegs.foreign` = `ForeignLeg`)이라 롱일 수 있다(수익·정시와 달리 `AccountHedgePosition.resolve` 를 거치지 않는다). 롱이면 값 앞에 `롱 ` 을 붙인다. 수익·정시 알림은 §6.1 대로 항상 숏이라 이 규칙이 없다.
- 급변 footer `기준 ±{format("0.00", threshold)}%p · 같은 시간대 같은 방향은 1번만 알림`.
- 리마인더 footer `하루 1번, {HH}:00 이후 발송`, 메일 안내 문장 `하루 1번, {HH}:00 이후에 보냅니다.` 의 `{HH}` 는 **`AccountAlert.UnrecoveredReminder.reminderHour`**(`AccountAlert.kt:56`, 설정 `account-pnl-monitor.account-alerts.reminder-hour` → `AccountAlertSettings.reminderHour`)를 `%02d`(`Locale.ROOT`)로 찍는다. 기존 `AccountAlertContent.kt:200` 과 같은 출처다. spec 의 `09:00` 을 **하드코딩하지 않는다**(golden 은 `reminderHour = 9` 입력의 결과 + `reminderHour = 7` 변형 1건).

### 6.4 운영 알림 (`OwnerOpsAlertContent`, author `운영`)

| 신호 | headline | 칸 (Alert) | 메일 첫 줄 |
|---|---|---|---|
| `FxIngestion` | `환율 수집 지연 {base}/{quote}` | `[마지막 성공] clock(since)\|없음` · `[장애 감지] clock(incidentStartedAt)` · `[기준] threshold(FxIngestionFreshness.MAX_INGESTION_GAP)` | `환율({base}/{quote})을 {기준} 넘게 가져오지 못했습니다.` |
| `MarketStream` | `시세 끊김 {exchange(e)} {baseCode}/{currency}` | `[마지막 시세]` · `[장애 감지]` · `[기준] threshold(settings.marketStaleAfter)` | `{exchange} {baseCode}/{currency} 시세가 {기준} 넘게 들어오지 않았습니다.` |
| `JobFailures` | `Job 연속 실패 {jobId.tag}` | `[연속 실패] {count}회` · `[첫 실패] clock(since)` · `[지속] duration(at − incidentStartedAt)` | `{tag} Job이 {count}회 연속 실패했습니다.` |

- 제목: Alert `⚠️ {headline}` + (sequence ≥ 2 이면 ` · {sequence}번째 알림`), Recovered `✅ 복구 — {headline}`. 메일은 `[운영] ` + 이모지 뺀 같은 핵심.
- footer: Fx·Market `해소될 때까지 {threshold(realertInterval)}마다 다시 알리고, 해소되면 1번 알림`, Job `기준 {jobFailureCount}회 연속이고 {threshold(jobFailureDuration)} 이상 · {interval}마다 다시 알림`, Recovered 없음.
- Recovered 칸: `[장애 감지] monthDayClock(incidentStartedAt)` · `[복구 확인] monthDayClock(at)` · `[지속] duration(at − incidentStartedAt)` / `[보낸 알림] {notifyCount}회`.
- basis = `notice.at`.
- `[ASSUMPTION] A9` 순번 접미사와 안내 문장은 신호 종류로만 정해진다(spec 은 Fx·Market 첫 알림, Job 재알림 예시만 있다). `since == null` 이면 `없음`.
- `[ASSUMPTION] A10` 해소 메일 첫 줄: Market `{exchange} {pair} 시세 끊김이 해소되었습니다.`(spec), Fx `환율({base}/{quote}) 수집 지연이 해소되었습니다.`, Job `{tag} Job 연속 실패가 해소되었습니다.`

### 6.5 시장 알림 (`OwnerMarketAlertContent`, author `시장 · {symbol} {exchange(korea)}/{exchange(foreign)}`)

| 알림 | Discord 제목 | 메일 제목 | 칸 |
|---|---|---|---|
| 급변 | `{📈\|📉} 김프 {급등\|급락} {pp(delta)} → {percent(current)}` | `[시장] 김프 … ({symbol})` | `[현재 김프]` · `[{threshold(window)} {저점\|고점}] percent(reference)` · `[변화] pp(delta)` |
| 기준선 | `{🔺\|🔻} 김프 {상단 기준선 돌파\|하단 기준선 이탈} {percent(current)}` | 같은 규칙 | `[현재 김프]` · `[{상단\|하단} 기준선]` |
| 신고·신저 | `{⬆️\|⬇️} 김프 {1시간\|24시간} {신고가\|신저가} {percent(current)}` | 같은 규칙 | `[현재 김프]` · `[직전 {기간} {고점\|저점}]` · `[차이] pp(current − previous)` |
| 일일 요약 | `🗓️ 김프 일일 요약 {MM/dd}` | 같은 규칙 | 시가·고가·저가 / 종가·평균 / 환율 전일 마감·환율 현재 / 실계정 순수익률 |

- footer 의 수치는 `MarketAlertSettings`(`swingThreshold`·`swingCooldown`·`thresholdHysteresis`(notice.hysteresis)·`extremeMinDelta`·`extreme{Hour,Day}Cooldown`)에서 `plain`·`threshold` 로 찍는다.
- 메일 `현재 김프` 값 = `{percent(current)} ({clock(currentObservedAt, at)} 관측)`. basis = `notice.at`.
- 요약 footer `대상 {MM-dd} 00:00 ~ 24:00 {zone 약어}`, 메일 첫 줄 `{yyyy-MM-dd} {symbol} 김프 요약입니다. ({거래소쌍}, 00:00 ~ 24:00 {약어})`.
  환율 값: quote 가 KRW 면 `{rate}원/{base}`(Discord)·`{rate}원`(메일), 아니면 `{rate} {quote}/{base}`·`{rate} {quote}`. 메일 섹션 제목 `환율 ({base}/{quote})`.
- `[ASSUMPTION] A11` 요약 결측(spec 에 없음): 일 집계 없음 → Discord 칸 1개 `[김프] 전일 집계 없음`, 메일 `■ 김프` 아래 `전일 집계 없음`.
  환율 값 없음 → `없음`. 실계정 `NoPosition` → `포지션 없음`, `Unavailable` → `없음`.

### 6.6 회원 김프 메일 (`PremiumThresholdMailContent`, Discord 없음)

- 제목 `[김프 알림] {SYMBOL} 김프 {premium(rate)} 도달`.
- 본문: `Text("안녕하세요, {nickname}님.", "설정하신 김프 알림 조건에 도달했습니다.")` → `Pairs(종목 "{SYMBOL} ({exchange(korea)} / {exchange(foreign)})", 조건 "김프 {thresholdPercent(threshold)} {이상|이하}", 현재 김프 premium(rate), 관측 시각 stamp(observedAt))` → signature `premium-spread`. basis 없음.
- 재료는 `ActiveNotificationSubscription`(pair·nickname·direction·threshold)과 `PremiumSnapshot`(premiumRate·observedAt). zone 은 서비스 생성자 인자로 받는다 — 값은 `NotificationDeliveryConfiguration` 이 `aggregation.zone` 을 읽어 넘긴다(§7, N1).
- subject 는 V14 `VARCHAR(500)` 안이다(가장 긴 경우도 60자 미만).

## 7. 시간 계약

- 모든 시각은 `Instant`. 표시 zone 은 호출자가 넘긴 `ZoneId` — owner 알림은 기존 그대로(운영·시장 = `aggregation.zone`, PnL 모니터 = 생성자 기본값 `Asia/Seoul`;
  `[ASSUMPTION] A12` PnL 배선은 바꾸지 않는다).
- 회원 서비스(N1 — 기존 관례 `OwnerAlertDeliveryConfiguration.kt:153,165`·`OwnerMarketAlertConfiguration.kt:67` 를 따른다): 서비스 클래스에는 `@Value` 를 두지 않는다.
  `PremiumThresholdDeliveryService` 는 생성자 끝에 `private val zone: ZoneId = ZoneId.of("Asia/Seoul")` 를 받고(기존 4인자 테스트 무수정 컴파일),
  `apps/batch/src/main/kotlin/io/premiumspread/config/NotificationDeliveryConfiguration.kt` 의 새 `@Bean` 메서드가 `@Value("\${aggregation.zone:Asia/Seoul}") zone: String` 을 받아 `ZoneId.of(zone)` 으로 생성한다
  (batch 의 `BatchZoneConsistencyValidator` 가 `batch.scheduling.zone` 과 같음을 보장하는 키). 이 Configuration 은 이미 서비스와 같은 `@ConditionalOnProperty(notification.email.enabled=true)` 를 갖는다.
  서비스 클래스는 `@Service` 를 빼고 `@ConditionalOnProperty` 는 남긴다 — `[ASSUMPTION] A14` 기존 `NotificationDisabledContextTest.kt:49-52` 가 이 클래스를 `@Import` 하고 `:33` 에서 `doesNotHaveBean` 을 단정하므로,
  조건까지 빼면 disabled 컨텍스트에 빈이 생겨 R3 밖 테스트가 깨진다. 조건을 남기면 그 테스트는 무수정 통과하고, 운영에서는 component scan 대상이 아니므로 Configuration 의 `@Bean` 하나만 등록된다.
- 메일 표기 `yyyy-MM-dd HH:mm z`(`Locale.ROOT`, Asia/Seoul → `KST`). 초 단위·UTC 원문 병기 없음. Discord 칸은 §3.5 `clock`, embed `timestamp` 는 Instant.
- 이 작업에는 **범위(`[from,to)`) 계산이 없다**. 정시 `{HH}:00`·요약 대상일은 기존 정책이 만든 값(`bucketStart`·`date`)을 표시만 한다.
- 테스트는 고정 Instant + 명시 zone(`Asia/Seoul`)만 쓴다. 렌더러는 zone 인자 없이 시각을 찍지 않는다(시스템 default zone 무관).

## 8. MarketPair identity (K8)

- 시장 알림의 author·메일 첫 줄의 거래소쌍·심볼은 **`notice.pair`** 에서만 만든다(`AlertFormat.exchangePair(notice.pair, "/")`). 설정·기본 pair 로 대체하지 않는다.
- 운영 `MarketStream` 은 `signal.exchange`·`signal.quote`, 회원 메일은 `subscription.pair`(= `findActiveByPair(snapshot.pair)` 로 고른 구독의 pair).
- `exchange(Exchange)` 는 enum 전수 `when` 이라 새 거래소가 생기면 컴파일이 표기 추가를 강제한다.
- eventKey 는 그대로 pair key(`btc-bithumb-binance`)를 담는다 — 영문 소문자 식별자이고 표시 문구가 아니다(U5 의 영문 표기 삭제 대상 아님).

## 9. 영향 없음 확인

| 항목 | 판정 | 근거 |
|---|---|---|
| DB·캐시 순서 | 변경 없음 | 캐시를 쓰지 않는다. outbox INSERT 는 기존 트랜잭션(상태 + 행 한 트랜잭션, DB-first) 안에서 열 하나가 늘 뿐이다 |
| 배치 lock·lease·timeout·`JobResult` | 변경 없음 | Job 은 렌더 함수 호출 줄만 바뀐다. 렌더 예외(Discord 합계 6000 초과 등 — 템플릿상 불가)는 지금과 같은 경로(회차 action 예외 → 기존 분기)다. 배포 순서 위반 시 동작은 §5.3 |
| 공개 endpoint | 영향 없음 | API 코드 변경 없음(`PublicEndpointPolicy`·`docs/runbooks/auth-security.md` 무변경). API 는 V23 을 적용만 한다 |
| Redis | 변경 없음 | key·TTL·payload 무관(`modules:redis`·`docs/runbooks/redis-contract.md` 무변경) |
| 발송 조건·라우팅·eventKey | 변경 없음 | U7 |
| 회원 서비스 빈 조건 | 변경 없음 | `notification.email.enabled=true` 일 때만 생성(§7). 빈 정의 위치만 클래스 → `NotificationDeliveryConfiguration` |

## 10. 비노출 (로그·toString)

- 새 로그는 §5.3 WARN 하나(키 `id`·`reason`, 값 enum 문자열). 본문·칸 값·URL·주소·예외 메시지를 싣지 않는다.
- 자동 `toString` 을 갖는 새/변경 data class 와 노출 프로퍼티:

| 타입 | 노출 | 주의 |
|---|---|---|
| `DiscordField`·`DiscordLayout`·`DiscordMessage` | 칸 이름·값·author·footer·timestamp·title·description·color | 실계정 금액을 담는다. 로그에 넘기지 않는다(현재 로그 호출 없음 — 기존과 같음) |
| `AccountPnlMail` | 기존 + `discordLayout` | 동일 |
| `MailDocument`·`MailBlock`·`MailPair`·`EmbedDocument`·`EmbedCell`·`OwnerAlertMessage` | 제목·본문 블록·칸 | 회원 메일은 닉네임을 담는다. 로그 금지 |
| `AccountPnlBreakdown`·`KoreaLegBreakdown`·`ForeignLegBreakdown` | **toString 재정의** — `calculatedAt`·`source` 만 | 실계정 금액·수량·가격 |

## 11. 테스트 전략

| 계층 | 무엇을 | 위치 |
|---|---|---|
| Domain 불변식 | `DiscordField`·`DiscordLayout`·`DiscordMessage` 한도 경계(255/256/1024/2048/25/6000), `AccountPnlMail` 의 layout 은 DISCORD 만, 채널·route·color 불변식(삭제되는 `OwnerAlertRowsTest` 에서 이식) | `domain` unit |
| 서식·렌더러 | `AlertFormat` 경계값과 `AccountPnlFormat` 직접 비교(A단계), 메일 정렬·섹션·basis·signature, embed 칸 inline·채움 칸·절단·대체 평문 | `domain` unit |
| 순수 계산 | `AccountPnlBreakdown` — message-spec 수익 예시 입력으로 각 값, `AccountNetPnl` 과의 일치 | `domain` unit |
| golden | 22종 각각 메일 subject·body `isEqualTo`, Discord author·title·color·fields(name·value·inline)·footer·timestamp `isEqualTo` + 결측·변형 시나리오. 삭제되는 옛 테스트의 비문구 계약(Message-ID 형식·비밀 부재·정시 버킷 eventKey)도 여기로 이식 | `domain` unit, family 별 파일 |
| 전수 적합성 | 22종 시나리오를 한 목록으로 — 금지어, 접두어, basis 줄 규칙, author 집합, 행별 칸 수, Discord 한도 | `domain` unit `AlertMessageSpecConformanceTest` |
| Job 행 | 세 Job 이 만든 DISCORD 행 `discordLayout != null`, EMAIL 행 body == 템플릿 `mail.body()` (dod AC15(b)) | `apps:batch` unit, Job 별 신규 `*TemplateRowsTest` |
| 회원 메일 | A단계 특성화(현재 문구 고정) → B단계 새 golden | `apps:batch` unit + `domain` unit |
| 직렬화 | `DiscordLayoutJson` 왕복·null 키·읽기 실패 reason | `infrastructure:common` unit |
| adapter | payload(layout 유무 × push 3종), 기존 테스트 무수정 | `infrastructure:batch` unit |
| persistence | V23 경로(빈 DB·V22→V23·CHECK), insert→claim 왕복, NULL 옛 행, 손상 JSON·조립 실패(shape) | `infrastructure:common` integrationTest |
| E2E | Job → MySQL outbox → 전달 Job → mock Discord 서버 payload 에 fields, 옛 NULL 행은 title/description | `apps:batch` integrationTest |
| 경계 | `architectureTest` (domain 새 의존 없음·앱 main infra import 없음) | root |

## 12. 커밋 전략 (K9)

이슈는 "템플릿 이전"과 "문구 변경"을 따로 리뷰할 수 있기를 요구한다. 순수한 2단계(① 지금 문구를 새 템플릿으로 그대로 재현 → ② 문구 변경)는
①에서 만든 템플릿을 ②가 거의 다 다시 쓴다(섹션·칸 구조가 바뀌므로 지금 문구를 새 모델로 재현한 코드가 남지 않는다). 그래서 다음처럼 자른다.

- **A단계 — 동작 보존** (plan Task 1~7): 회원 메일 특성화, Discord 레이아웃 타입, 템플릿 계층·공용 서식, `AccountPnlBreakdown`, V23, persistence, adapter·dispatcher.
  기존 Content object 의 문구 코드는 바뀌지 않는다. **기존 golden 테스트 무수정 통과**가 이 단계의 증거다(dod R14).
- **B단계 — family 별 문구 전환** (plan Task 8~12): 커밋 하나 = family 하나(실계정 순손익 / 계정 알림 / 운영 / 시장 / 회원). 각 커밋의 diff 는 그 family 의
  템플릿·golden·Job 호출 줄·문구 단정 줄·Job 행 신규 테스트뿐이라 message-spec 의 해당 절과 나란히 놓고 리뷰한다.
- 정리·22종 적합성(Task 13), E2E(Task 14), 문서(Task 15), 최종 gate(Task 16).

## 13. 가정과 결정

| # | 내용 | 상태 · 기본값(plan 이 따르는 것) |
|---|---|---|
| A1 | 메일 정렬은 표시 폭 규칙(§3.2), spec 공백 개수는 예시 | 규칙 |
| A2 | Discord 칸 시각 `HH:mm`/`MM-dd HH:mm` 규칙(§3.5), 운영 해소만 항상 날짜 포함 | 규칙 |
| A3 | 김프 차이 = 반올림한 두 값의 차 | §6.1 |
| A4 | 금액은 항목별 반올림, 합계·순손익은 전체 정밀도 값을 반올림, 항목 합과 ±1원 차이 허용 | **사용자 승인** · §6.1 |
| A5 | 미반영 표기 `미반영 (이력 없음\|수동 미입력)` | §6.2 |
| A6 | 중단 label 은 빗썸 인증만 변경 | §6.2 |
| A7 | 포지션 변경 알림의 바이낸스 leg 롱이면 `롱 ` 접두(수익·정시는 항상 숏 — 방향 필드 없음) | §6.1·§6.3 |
| A8 | ~~안 바뀐 leg 한 줄 통일~~ | **철회** — 줄 구성은 message-spec 그대로(§6.3) |
| A9·A10 | 운영 순번 접미사·안내 문장 신호 종류별, 해소 첫 줄 | §6.4 |
| A11 | 요약 결측 표기 | §6.5 |
| A12 | PnL 모니터 zone 배선 무변경 | §7 |
| A13 | 안 바뀐 leg 가 비었거나 flat 이면 `포지션 없음` 한 줄 (spec 예시 없음) | §6.3 |
| A14 | 회원 서비스는 `@Service` 만 빼고 `@ConditionalOnProperty` 유지 (기존 `NotificationDisabledContextTest` 무수정) | §7 |
| **D1** | `infrastructure:common` 에 `implementation("com.fasterxml.jackson.core:jackson-databind")` **선언 추가**. 버전은 Spring Boot BOM, 아티팩트는 이미 그 모듈 compile classpath(`modules:redis` 의 redisson `api` 전이)와 batch·api 런타임에 있다 — 해석된 의존 그래프는 바뀌지 않는다 | **결정됨(사용자 승인)** — 선언 추가. plan Task 0 (g2) 가 전이 존재를 확인 |
| **D2** | 빗썸 평단 사용자 수정 표시(현재 `빗썸 평단 사용자 수정됨`)는 spec 에 자리가 없다 | **결정됨(사용자 승인)** — 삭제(§6.2) |
| N1 | 회원 서비스 zone 주입 방식 | **결정됨** — `NotificationDeliveryConfiguration` 의 `@Bean` 이 `aggregation.zone` 을 읽어 생성자로 넘긴다(§7). 서비스에 `@Value` 없음 |
| N2 | 최상위 data class(`MailDocument` 등) 구조 | **결정됨** — 현행 유지(변경 없음) |

## 14. 문서 갱신

| 문서 | 갱신 |
|---|---|
| `docs/runbooks/account-pnl-alert.md` | 메일·Discord 예시(:149 부근 수익, :262·:281 계정 알림, :313 부근), 대조 체크리스트(:457·:483)의 "청산가(호가 VWAP)·체결가 기준" 문구를 "현재가·순수익률"로, V23 배포 순서(API 먼저)와 순서 위반 시 장애 범위(§5.3 배포 순서 행 그대로), `discord_layout` 설명(:514 부근 테이블 설명), 옛 행·손상 행 동작 |
| `docs/runbooks/owner-ops-alert.md` | :123-158 예시 3개를 새 형식으로, :135·:146 `조치 안내` 줄 삭제, :184 부근 문구 |
| `docs/runbooks/owner-market-alert.md` | :131-155 예시와 :133 의 "마지막 줄은 항상 조치 안내" 문장, :179 부근 |
| `.ai/PROJECT_STATUS.md` | `Flyway V1~V23`(:13), V23 행(:128 다음) |
| `.ai/architecture/ARCHITECTURE_DESIGN.md` | owner 알림 단락(:143 부근)에 "문구는 `domain.alertmessage` 템플릿 계층, Discord 는 embed fields, 레이아웃은 outbox `discord_layout`(V23)" 한 단락 |

runbook 예시는 message-spec 을 복제하지 않고 **각 runbook 이 다루는 알림 1~2개 예시 + message-spec 링크**로 둔다.
로그 예시(`account-pnl-alert.md:397` `Account PnL observed: …`)는 로그 계약이라 바꾸지 않는다.

## 15. 변경 내역

| 라운드 | 바꾼 것 | 사유 |
|---|---|---|
| ⑥-1 | §13 D1·D2·A4 결정됨, N1·N2 추가, A8 철회 → §6.3 줄 구성 spec 기준·A13 신설 | 사용자 결정 |
| ⑥-1 | §3.3 수익 알림 fields 12개 예시, SPACER 를 `U+200B`·`Char(0x200B)` 로 표기(보이지 않는 문자 직접 삽입 금지) | 스펙 리뷰 MAJOR 1·MINOR 13(e) |
| ⑥-1 | §5.3 배포 순서 위반 장애 범위 정정(enqueue·기존 대기 행 전달 전부 정지) | MAJOR 5 |
| ⑥-1 | §5.3 shape 판정에 `DiscordMessage` 조립 실패 포함 | MINOR 13(c) |
| ⑥-1 | §4·§5.2 `fields` 키 항상 포함으로 통일 | MINOR 13(d) |
| ⑥-1 | §6.1 바이낸스 leg 항상 숏(방향 필드 없음), §6.3 A7 범위 정리 | MINOR 13(b) |
| ⑥-1 | §6.2 중단·복구 시각 출처 표, §6.3 리마인더 `reminderHour` 출처 | MINOR 6·7 |
| ⑥-1 | §7 회원 zone 주입을 Configuration `@Bean` 으로(A14) | N1 |
| ⑥-1 | §2 `NetPercentSwingPolicy.kt:12` KDoc, §11 Job 행 신규 테스트·이식 계약 | MINOR 10, MAJOR 4, MINOR 8 |
| 구현 2026-10-30 | `PremiumThresholdDeliveryService` 의 `@Transactional` 을 클래스 수준으로 이동(public 진입점은 `evaluate` 하나라 동작 동일) | `@Service` 제거로 kotlin-spring allopen 대상에서 빠져 final 이 되자 메서드 수준 트랜잭션 프록시가 실패. 클래스 수준 `@Transactional` 은 allopen 대상이라 open |
| 구현 2026-10-30 | 회원 메일 조건 줄 임계값을 `AlertFormat.thresholdPercent`(최소 소수 2자리, 설정 정밀도 보존, 뒤 0 제거)로 표기, §3.5 표·§6.6 정정 | DECIMAL(10,4) 설정값 1.2345 가 1.23% 로 보이던 문제(⑨ 리뷰) |
| 구현 2026-10-30 | `JdbcAccountPnlAlertStore` claim 의 조립 실패 분류: layout 없이 조립이 성공하고 layout 포함 조립만 실패할 때만 `reason=shape` | ⑨ 리뷰 |
| 구현 2026-10-30 | 저장 JSON 의 author·footer·timestamp 키 누락은 shape 처리 | 쓰기는 항상 키를 쓰므로 누락은 손상 |
| 구현 2026-10-30 | 일일 요약 날짜 `String.format` 에 `Locale.ROOT` | ⑨ 리뷰 |
