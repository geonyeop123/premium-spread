---
feature: 알림 문구 템플릿화와 문구 정리 (issue #102)
slug: alert-message-template
status: FROZEN
frozen_at: 2026-10-30
source: docs/work/alert-message-template/design.md (DRAFT)
---

## 범위

**포함**

- domain `alertmessage` 템플릿 계층(`AlertFormat`·`MailDocument`·`EmbedDocument`·`OwnerAlertMessage`)과 렌더 규칙 (design §3)
- `DiscordField`·`DiscordLayout`·`DiscordMessage.layout`, `AccountPnlMail.discordLayout`, `OwnerAlertRows.expand(message, route)` (§3.4, §4)
- `AccountPnlBreakdown` 순수 계산 (§6.1)
- 22종 문구 전환 — 실계정 순손익 5·계정 알림 5·운영 4·시장 7·회원 1 (message-spec.md, design §6)
- V23 `account_pnl_mail.discord_layout`, `DiscordLayoutJson`, outbox insert·claim, 옛 행·손상 행 처리 (§5)
- `DiscordWebhookSenderAdapter` payload, `OwnerAlertDispatcher` 한 줄, 세 Job 호출 줄, `PremiumThresholdDeliveryService` zone 주입(`NotificationDeliveryConfiguration` 빈) (§4, §6.6, §7)
- runbook 3개·`.ai/PROJECT_STATUS.md`·`.ai/architecture/ARCHITECTURE_DESIGN.md` (§14)

**제외** *(scope creep 차단선)*

- 총 자산·잔여 금액·증거금+손익·USDT 원화가, HTML 메일, 리소스 파일 외부화
- 발송 조건·쿨다운·라우팅·`OwnerAlertKind`·eventKey·재시도·Message-ID, 회원 Discord
- `AccountNetPnl` 필드·공식, 관측·정책·상태 저장, Redis, 공개 endpoint, 새 메트릭
- outbox rename, 과거 `docs/work/*` 산출물, `message-spec.md`

## 사용자 합의 (③)

design §0.1 U1~U8 그대로. 문구 정본은 `docs/work/alert-message-template/message-spec.md` — **숫자·시각은 예시, 구조가 계약**이다.
"구조" = 제목 형식(이모지·접두어·핵심 순서), Discord author·칸 이름·칸 배치(행별 칸 수와 순서)·칸 값의 줄 구성·footer, 메일 섹션 제목·항목 라벨과 순서·안내 문장·기준 시각 줄 유무.
공백 개수는 design §3.2 정렬 규칙(A1)이 정한다. D1(jackson-databind 선언 추가)·D2(평단 수정 표시 삭제)·A4(항목별 반올림, ±1원 허용)는 **사용자 승인으로 결정됨**, A8 은 철회(줄 구성 spec 그대로) — design §13.

## 기존 gate 조사

> 작성 시점은 소스만 읽었다. 실행 기준선은 plan Task 0 이 증거 로그에 남긴다.

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있다 — root `architectureTest`(domain 외부 의존 allowlist, 앱 main infra import 금지, `gradle-dependency-graph.snapshot`) | R1. 새 규칙 없음. domain 에 의존 추가 없음 |
| lint | ktlint·detekt (CI `quality-gate.yml`), 로컬 gradle task 없음 | R11 CI 확인만 |
| golden 테스트 | 있다 — `AccountPnlMailContentTest`·`AccountPnlDiscordContentTest`·`AccountAlertContentTest`·`OwnerOpsAlertContentTest`·`OwnerMarketAlertContentTest`·`DiscordMessageTest`·`OwnerAlertRowsTest` | A단계 무수정 통과가 동작 보존 증거(R14). B단계에서 family 별로 교체. 삭제되는 파일의 비문구 계약은 plan Task 3·8 이 신규 테스트로 이식 |
| 회원 메일 문구 단정 | **없다** — `PremiumThresholdDeliveryServiceTest` 는 eventKey·수신자만 | AC9(a) 특성화 테스트를 먼저 만든다 |
| 회원 서비스 빈 조건 단정 | 있다 — `NotificationDisabledContextTest:33`(`@Import` :49-52), `NotificationDisabledIntegrationTest:47` | 무수정 통과(design §7 A14) |
| Discord payload 테스트 | 있다 — `DiscordWebhookSenderAdapterTest:72-96`(title/description/color, push 3종) | layout 없는 행의 회귀 방어선(R13) — 무수정 |
| outbox 컬럼 고정 단언 | 있다 — `V19OwnerAlertChannelMigrationIntegrationTest:34-50`(latest 까지 migrate 후 `containsExactly`), `AccountPnlFixtureSchemaIntegrationTest:44-61` | 허용 예외 — 목록 끝에 `discord_layout:text:YES` 추가만 |
| migration 버전 단언 | `V22…IntegrationTest:28` 은 `containsAll((1..22))` | V23 추가로 깨지지 않음 |
| Job 문구 단정 | 있다 — `AccountPnlMonitorJobTest`(:87,:198,:247,:344,:404,:543,:623-633), `AccountPnlMonitorJobHourlyReportTest:52`, `AccountPnlMonitorJobOwnerAlertTest:115-132,:145-172`. `OwnerMarketSignalJobTest:235` 의 `contains("KRW/USD")` 는 새 메일(`■ 환율 (KRW/USD)`)도 만족 | 앞의 3개만 허용 예외 — 문구 단정 줄과 기대 행 구성 줄만. `OwnerMarketSignalJobTest` 는 무수정 |
| E2E 문구 단정 | `OwnerMarketAlertIntegrationTest:189-200`, `OwnerAccountAlertIntegrationTest:111`(제목 `헤지 불일치` — 새 제목도 포함) | 앞의 것만 허용 예외 |
| mock Discord 서버 E2E | 있다 — `OwnerAlertDeliveryIntegrationTest`(MockWebServer, payload `readTree`) | AC16 이 같은 기반 |
| migration 검사 | `:infrastructure:common:verifyMigrations` | R4 |
| 문서 검사 | `bash docs/check-documentation.sh` (TBD/TODO 금지, 링크 실재) | AC17 |

**결론.** 새 스크립트·gradle task 를 만들지 않는다. 검증 명령은 아래 5개 task 와 그 `--tests` 필터 변형뿐이다:
`./gradlew test architectureTest --offline --no-daemon`, `./gradlew :infrastructure:common:integrationTest --offline --no-daemon`,
`./gradlew :apps:batch:integrationTest --offline --no-daemon`, `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon`, `bash docs/check-documentation.sh`.
회귀 방어선은 여기에 기존 gate(`:apps:api:integrationTest`·`harnessCheck`·jacoco)를 더한다.

## 수용기준

공통 고정값: zone `Asia/Seoul`, 고정 `Instant`(실계정 `2026-10-20T00:00:00Z` = 09:00 KST, 운영 `2026-10-28T00:00:00Z`, 시장 `2026-10-29T00:00:00Z`, 회원 `2026-07-15T00:30:00Z`),
기본 pair `BTC/BITHUMB/BINANCE`, fx `USD/KRW`. 테스트 URL `https://discord.example.test/...`, 주소 `owner@example.test`·`member@example.com`(기존 fixture). 실 webhook·key 금지.
**golden** = 메일 `subject`·`body()` 와 Discord `author`·`title`·`color`·`fields`(name·value·inline 전부)·`footer`·`timestamp` 를 `isEqualTo` 로 단정한다.
기대 문자열은 message-spec 해당 절에서 옮기고 숫자·시각만 테스트 입력의 값으로 바꾼다(칸 값의 줄 구성 포함).

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | (a) `DiscordField` — name 0자·공백만·257자, value 0자·1025자 거부, 256·1024 통과, U+200B(zero-width space) 한 글자 name·value 통과. (b) `DiscordLayout` — 칸 26개 거부·25 통과, footer 2049 거부, author 257 거부, author/footer null 통과. (c) `DiscordMessage(layout != null)` — title+author+footer+칸 합계 6001 거부·6000 통과, description 길이는 합계에 넣지 않는다. layout null 이면 기존 검증 그대로(`DiscordMessageTest` 무수정 통과). (d) `AccountPnlMail` — EMAIL 행에 `discordLayout` 이 있으면 `IllegalArgumentException("discordLayout must be set only for DISCORD rows.")`, DISCORD 행은 null·non-null 모두 허용 | design §4 | `요구`(U3) + 한도는 `상위`(Discord API) | T1 | `신규테스트` `io.premiumspread.domain.owneralert.DiscordLayoutTest` + `기존` `DiscordMessageTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.owneralert.*' --offline --no-daemon` | exit 0 |
| AC2 | `AlertFormat` 이 design §3.5 표와 같다: `stamp(2026-10-20T00:00:00Z)` = `2026-10-20 09:00 KST`; `clock` 같은 날 `09:30`·전날 `10-17 09:30`(zone 날짜 기준 — UTC 날짜가 달라도 KST 같은 날이면 `HH:mm`); `duration` 0→`0분`, 음수→`0분`, 59m→`59분`, 60m→`1시간`, 365m→`6시간 5분`, 720m→`12시간`; `threshold` 30m→`30분`, 6h→`6시간`, 90s→`90초`; `exchange` 4개 값 전부; `premium(1.775)`=`1.78%`·`premium(-0.5)`=`-0.50%`·`premium(0)`=`0.00%`; `usdtPrice(70000.5)`=`70,000.5 USDT`; `usdt2(14.7896)`=`14.79 USDT`; `signedUsdt2(-851.2)`=`-851.20 USDT`·`(0)`=`0.00 USDT`; `displayWidth("마지막 성공")`=11·`("10분 저점")`=9. `AccountPnlFormat` 과 같은 동작이어야 하는 함수(`percent`·`percentPoint`·`krw`·`krwOrZero`·`cost`·`plain`·`signedUsdt`)는 `0`·`-0.4`·`0.5`·`-0.5`·`1234567.5` 에서 `AccountPnlFormat` 의 같은 함수를 **직접 호출한 결과와** 같다(둘 다 domain internal — Task 13 에서 `AccountPnlFormat` 삭제 전 리터럴로 고정). A단계 동안 기존 golden 무수정 통과 | design §3.5 | `요구`(U4 KST·UTC 제거, U5 한글 거래소) + 규칙은 `추론`(A2) | T1 | `신규테스트` `io.premiumspread.domain.alertmessage.AlertFormatTest` + `기존` 5개 Content golden | `./gradlew :domain:test --tests 'io.premiumspread.domain.alertmessage.*' --tests 'io.premiumspread.domain.accountpnl.*' --tests 'io.premiumspread.domain.ownerops.*' --tests 'io.premiumspread.domain.ownermarket.*' --offline --no-daemon` | exit 0 |
| AC3 | (a) `MailDocument.body()` 가 design §3.2 규칙 1~5 대로 — 최상위 블록 사이 빈 줄 1개, Section 안 빈 줄 없음, Pairs 정렬(표시 폭 최댓값+2), children `  - ` 정렬, basis 줄, signature `--`, 끝 개행 없음. (b) `EmbedDocument.toDiscordMessage()` 가 §3.3 규칙 1~5 대로 — 1칸 행 inline false, 2~3칸 inline true, 2칸 행 뒤에 2칸 이상 행이 올 때만 U+200B 채움 칸(`EmbedDocument.SPACER`, 행 [3,2,3,2,1] → fields 12개), 값 1025자는 1024자(… 포함)로 절단, 대체 평문 description(`**` 제거, `\n` → ` / `, footer 마지막 줄), 칸 1~3개가 아닌 행·빈 rows 는 `IllegalArgumentException`. (c) `OwnerAlertRows.expand(message, route)` — (메일, Discord) 경로별 0·1·2행, 메일 먼저, 같은 eventKey, DISCORD 행 `subject`=title·`body`=대체 평문·`discordLayout`=layout, `route.discord == null` 이면 Discord 렌더를 하지 않는다. (d) `AccountPnlMail` 의 기존 불변식 — 3인자 생성은 EMAIL·route/color null, 채널과 route/color 가 어긋난 4조합 거부(삭제되는 `OwnerAlertRowsTest:24,31` 이식) | design §3.2~§3.4 | `요구`(U3·U4) + 규칙은 `추론`(A1) | T1 | `신규테스트` `io.premiumspread.domain.alertmessage.MailDocumentTest`·`EmbedDocumentTest` + `io.premiumspread.domain.accountpnl.OwnerAlertRowsMessageTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.alertmessage.*' --tests 'io.premiumspread.domain.accountpnl.OwnerAlertRowsMessageTest' --offline --no-daemon` | exit 0 |
| AC4 | `AccountPnlBreakdown.of(pnl)` — 입력(빗썸 0.266 BTC·평단 152,000,000·매수호가 158,500,000·진입 수수료 16,173원·sellFeeRate 0.0004, 바이낸스 −0.266·진입가 108,000·매도호가 111,200·수수료 14.36 USDT·펀딩 4.2 USDT·taker 0.0005, 환율 1400)에서 현김프 1.78, 매수김프 0.53, 차이 1.25, 빗썸 손익 1,729,000·평가액 42,161,000, 바이낸스 손익 −851.2 USDT·−1,191,680원·평가액 29,579.2 USDT·41,410,880원, 정리 수수료 16,864.4원·14.7896 USDT·20,705.44원, 수수료 합계 73,846.84원, 펀딩 5,880원. 불변식 3개(design §6.1)가 `compareTo == 0`. 진입 수수료·펀딩 null 이면 해당 값 null·합계는 0 으로. 음수 김프(빗썸 < 바이낸스×환율)도 부호 그대로. `toString` 에 금액·수량 문자열 없음. 바이낸스 방향 필드 없음(항상 숏 — design §6.1) | design §6.1 | `요구`(U6 공식) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.AccountPnlBreakdownTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountPnlBreakdownTest' --offline --no-daemon` | exit 0 |
| AC5 | 실계정 순손익 golden 5종이 message-spec "실계정 순손익" 절의 구조와 같다 — 수익, 정시, 중단(빗썸 인증 실패, 마지막 성공 없음), 중단(환율 수집 지연, 마지막 성공 있음), 복구. AC4 입력의 수익 제목은 `💰 수익 알림 +1.11% +469,353원`(A4 승인 — 합계·순손익은 전체 정밀도 값을 반올림, spec 예시 `469,354` 와 1원 차), 메일 `수수료` `-73,847원`. 중단 `마지막 성공` = `notice.lastSuccessAt`(null → `없음`), `중단 감지`·복구 `복구 시각` = 렌더 인자 `now`. 빗썸 평단 수정 표시 없음(D2). 변형: 진입 수수료·펀딩 미반영(A5, 수동 입력 모드면 `수동 미입력`), 다른 실패 범주 6개의 제목 label(A6). eventKey 는 기존 형식 그대로(`account-pnl-profit-{ms}`·`-hourly-{정시 ms}`·`-outage-`·`-recovered-`). 복구 메일에 `기준 시각` 줄이 없다. (이식 — 삭제되는 `AccountPnlMailContentTest`) 4종 eventKey 가 `[A-Za-z0-9._-]{1,128}` 이고 서로 다름(:204), 메일 제목·본문과 Discord 모든 문자열에 fake secret 부재(:219), 정시 eventKey 는 같은 시간대(`00:59:59Z`)면 같고 다음 시간대는 `account-pnl-hourly-1792458000000`(:407) | message-spec §실계정 순손익, design §6.2 | `요구`(U2·U6) + 결측 표기는 `추론`(A5·A6) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.AccountPnlAlertContentTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountPnlAlertContentTest' --offline --no-daemon` | exit 0 |
| AC6 | 계정 알림 golden 5종(포지션 변경 바이낸스→없음, 헤지 불일치, 급등, 급락, 리마인더 4건)이 message-spec "계정 알림" 절의 구조와 같다 — 안 바뀐 빗썸 칸 값은 `{qty} BTC\n평단 {avg}` 두 줄, 안 바뀐 바이낸스 칸 값은 한 줄(spec 그대로). 리마인더 footer·메일 문장의 `{HH}:00` 은 `reminderHour` 에서 온다(`reminderHour = 7` 변형이 `07:00`). 변형: 빗썸·바이낸스 둘 다 변경(제목 `빗썸·바이낸스`), 바이낸스 롱(A7), 안 바뀐 leg 가 빈 경우(A13), 리마인더에 오래된 항목만·빠진 항목만(칸·섹션 생략). eventKey 형식 무변경 | message-spec §계정 알림, design §6.3 | `요구`(U2) + 변형은 `추론`(A7·A13) | T1 | `신규테스트`(교체) `io.premiumspread.domain.accountpnl.AccountAlertContentTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountAlertContentTest' --offline --no-daemon` | exit 0 |
| AC7 | 운영 golden 4종(환율 수집 지연 첫 알림, 시세 끊김 빗썸 BTC/KRW, Job 연속 실패 2번째 알림, 시세 끊김 바이낸스 BTC/USD 해소)이 message-spec "운영 알림" 절의 구조와 같다. 변형: `since == null` → `없음`, Job 첫 알림(접미사 없음), 환율 2번째 알림(접미사 있음), 환율·Job 해소 첫 줄(A10). eventKey 형식 무변경 | message-spec §운영 알림, design §6.4 | `요구`(U2) + 변형은 `추론`(A9·A10) | T1 | `신규테스트`(교체) `io.premiumspread.domain.ownerops.OwnerOpsAlertContentTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.ownerops.*' --offline --no-daemon` | exit 0 |
| AC8 | 시장 golden 7종(급등, 급락, 상단 돌파, 하단 이탈, 24시간 신고가, 1시간 신저가, 일일 요약 전부 있음)이 message-spec "시장 알림" 절의 구조와 같다. 변형: 요약 일 집계 없음 + 환율 없음 + 실계정 `Unavailable`, 실계정 `NoPosition`(A11). author 는 `notice.pair` 에서 만든다 — `UPBIT` pair notice 면 `시장 · BTC 업비트/바이낸스`. eventKey 형식 무변경 | message-spec §시장 알림, design §6.5, §8 | `요구`(U2·U5) + 결측은 `추론`(A11) + pair 출처는 `상위`(architecture MarketPair) | T1 | `신규테스트`(교체) `io.premiumspread.domain.ownermarket.OwnerMarketAlertContentTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.ownermarket.OwnerMarketAlertContentTest' --offline --no-daemon` | exit 0 |
| AC9 | (a) **A단계 특성화**: 현재 서비스가 만드는 `subject`·`payload` 를 rate 6·threshold 5.00·ABOVE, rate −1.5·threshold −1·BELOW 두 경우로 `isEqualTo` 고정(현재 문구 그대로, 관측 시각 UTC 원문 포함). (b) **B단계**: 같은 두 경우가 message-spec "회원 알림" 구조 — 제목 `[김프 알림] BTC 김프 6.00% 도달`, 조건 `김프 5.00% 이상`/`이하`, 관측 시각 `2026-07-15 09:30 KST`, 끝 `--`·`premium-spread`, `기준 시각` 줄 없음. (c) 서비스가 zone 인자 `ZoneId.of("UTC")` 로 만든 인스턴스면 `2026-07-15 00:30 UTC` — 시스템 zone 무관. (d) 기존 `PremiumThresholdDeliveryServiceTest`(4인자 생성)·`NotificationDisabledContextTest` 무수정 통과. (e) 서비스 빈은 `NotificationDeliveryConfiguration` 이 `aggregation.zone` 으로 만든다 — 서비스 클래스에 `@Value`·`@Service` 없음(소스 확인) | design §6.6, §7 | `요구`(U1·U4) + 특성화 선행은 `상위`(testing 규칙) + 주입 방식은 사용자 결정(N1) | T1 | `신규테스트` `io.premiumspread.application.notification.PremiumThresholdDeliveryServiceMailTest` + `io.premiumspread.domain.notification.PremiumThresholdMailContentTest` + `기존` | `./gradlew :domain:test --tests 'io.premiumspread.domain.notification.*' :apps:batch:test --tests 'io.premiumspread.application.notification.*' --offline --no-daemon` + `grep -nE '@Value\|@Service' apps/batch/src/main/kotlin/io/premiumspread/application/notification/PremiumThresholdDeliveryService.kt` | exit 0 + grep 출력 없음 |
| AC10 | **22종 전수 적합성**: 아래 표의 22개 시나리오를 각각 dynamic test 로 돌려 (a) 메일 제목 접두어가 표의 값, (b) 메일 본문에 `기준 시각 yyyy-MM-dd HH:mm KST` 줄이 표의 유무대로 마지막(서명 앞) 줄, (c) Discord author 가 표의 값(회원은 Discord 없음), footer 유무가 표대로, timestamp = 기준 시각, 행별 칸 수가 표와 같다, (d) 메일·Discord 모든 문자열에 금지어가 없다 — 정규식 `청산\|gross\|\bBITHUMB\b\|\bBINANCE\b\|docs/runbooks\|조치 안내\|VWAP\|체결가\|대조용\|UTC\|거래소 API\|수동 입력\(env\)\|Exception\|http\|example\.test\|사용자 수정`, (e) Discord 한도(AC1) 안 | message-spec 전체, design §0.1 U4·U5 | `요구`(U2·U4·U5) | T1 | `신규테스트` `io.premiumspread.domain.alertmessage.AlertMessageSpecConformanceTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.alertmessage.AlertMessageSpecConformanceTest' --offline --no-daemon` | exit 0, 22개 dynamic test 모두 실행 |
| AC11 | (a) 빈 DB → latest 성공, 버전 1~23, `account_pnl_mail` 마지막 열 `discord_layout` `text` nullable. (b) V22 스키마에 EMAIL·DISCORD 행 → V23 → 기존 행 `discord_layout` NULL·그 밖 열 무변경. (c) CHECK — EMAIL 행에 `discord_layout` 값 거부, DISCORD 행 NULL·값 허용. (d) `verifyMigrations` 통과, 기존 migration 파일 무변경(R4) | design §5.2 | `요구`(합의 결정 V23 append-only) + CHECK 는 `추론` | T1 | `신규테스트` `io.premiumspread.infrastructure.common.migration.V23OwnerAlertDiscordLayoutMigrationIntegrationTest` | `./gradlew :infrastructure:common:integrationTest --tests 'io.premiumspread.infrastructure.common.migration.V23OwnerAlertDiscordLayoutMigrationIntegrationTest' :infrastructure:common:verifyMigrations --offline --no-daemon` | exit 0 (Docker 필요) |
| AC12 | (a) `DiscordLayoutJson` — write 결과가 design §5.2 키 순서·`"v":1`·`fields` 항상 배열(빈 목록 `[]`), read(write(x)) == x(null author·footer·timestamp·빈 fields 포함, 한글·이모지·`"`·`\`·개행 값), 깨진 JSON → reason `json`, `v` 2 → `version`, `fields` 누락·칸 이름 빈 문자열 → `shape`; 예외 메시지에 입력 내용 없음·cause 없음. (b) 실제 MySQL: layout 있는 DISCORD 행 enqueue(세 store 모두 — `JdbcAccountPnlAlertStore.save`·`JdbcOwnerAlertOutbox`·`JdbcOwnerMarketAlertStore`) → `claim` 결과 `discordLayout` 이 같다. (c) SQL 로 넣은 `discord_layout` NULL DISCORD 행 → claim 결과 layout null. (d) `discord_layout = '{bad'` 행 → claim 이 예외 없이 그 행을 layout null 로 돌려주고 WARN `Owner alert discord layout unreadable; sending plain description: id=…, reason=json`, 로그에 `{bad` 없음, 같은 claim 의 다른 행은 정상. (e) JSON 은 읽히지만 행 subject 255자 + layout 합계로 `DiscordMessage` 조립이 실패하는 행(SQL 로 삽입) → layout null·WARN `reason=shape`. (f) 직렬화 결과가 65,535 바이트를 넘는 행은 `IllegalArgumentException("discordLayout must be at most 65535 bytes in UTF-8.")`, DB 무변경. (g) 기존 `JdbcAccountPnlAlertStoreIntegrationTest`·`JdbcOwnerAlertOutboxIntegrationTest`·`JdbcOwnerMarketAlertStoreIntegrationTest` 무수정 통과 | design §5 | `요구`(합의 결정 — 옛 행 처리) + 손상 행 처리는 `추론`(K5) | T1 | `신규테스트` `io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.DiscordLayoutJsonTest`(test) + `…accountpnl.OwnerAlertDiscordLayoutStoreIntegrationTest`(integrationTest) + `기존` | `./gradlew :infrastructure:common:test --tests 'io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.*' :infrastructure:common:integrationTest --tests 'io.premiumspread.infrastructure.common.persistence.jdbc.*' --offline --no-daemon` | exit 0 (Docker 필요) |
| AC13 | `DiscordWebhookSenderAdapter` 가 (a) layout 있는 메시지를 `{"embeds":[{"author":{"name":…},"title":…,"color":…,"fields":[…],"footer":{"text":…},"timestamp":"…Z"}]…}` 로 보낸다 — `description` 키 없음, `fields` 키는 항상 있음(빈 목록이면 `[]`), author·footer·timestamp null 이면 그 키 없음, 칸 순서·inline 보존. (b) push SOUND·SILENT(`flags` 4096)·HERE(`content` @here, `allowed_mentions`) 규칙이 layout 유무와 무관. (c) 기존 `DiscordWebhookSenderAdapterTest` 무수정 통과(layout 없는 payload 무변경) | design §4 | `요구`(U3) | T1 | `신규테스트` `io.premiumspread.infrastructure.batch.notification.DiscordWebhookSenderAdapterLayoutTest` + `기존` | `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.notification.*' --offline --no-daemon` | exit 0 |
| AC14 | `OwnerAlertDispatcher` 가 DISCORD 행의 `discordLayout` 을 `DiscordMessage.layout` 으로 그대로 넘기고(null 은 null), EMAIL 행 전달·전이표는 무변경. 기존 `OwnerAlertDispatcherTest` 무수정 통과 | design §4 | `추론` | T1 | `신규테스트` `io.premiumspread.application.notification.owneralert.OwnerAlertDispatcherLayoutTest` + `기존` | `./gradlew :apps:batch:test --tests 'io.premiumspread.application.notification.owneralert.*' --offline --no-daemon` | exit 0 |
| AC15 | 세 Job(`AccountPnlMonitorJob`·`OwnerOpsSignalJob`·`OwnerMarketSignalJob`)이 만드는 행이 (a) 행 수·채널·순서·eventKey·라우팅·`JobResult` 가 base 와 같고(기존 테스트의 해당 단정 무수정), (b) 모든 채널을 켠 라우팅 — `OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true), overrides)`, overrides 는 시나리오 종류에 `OwnerAlertRouteOverride(email = true)` — 에서 DISCORD 행은 `discordLayout != null`, EMAIL 행 body 는 같은 입력으로 부른 그 family 템플릿의 `mail.body()` 와 같다. (b) 는 Job 별 신규 테스트 파일이 담는다 — 기존 Job 테스트(R3 허용 예외 포함)에는 새 단정을 넣지 않는다. 허용 예외(R3) 밖 테스트 무수정 | design §3.4, §9, §11 | `요구`(U7 발송 조건 무변경) | T1 | `기존`(허용 예외 줄만 수정) + `신규테스트` `io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTemplateRowsTest`(Task 8·9) · `io.premiumspread.application.job.ownerops.OwnerOpsSignalJobTemplateRowsTest`(Task 10) · `io.premiumspread.application.job.ownermarket.OwnerMarketSignalJobTemplateRowsTest`(Task 11) | `./gradlew :apps:batch:test --tests 'io.premiumspread.application.job.*' --tests 'io.premiumspread.config.*' --offline --no-daemon` | exit 0 |
| AC16 | 실제 MySQL(batch fixture, V23 열 포함) + mock Discord 서버로 (a) 시장 급등 회차 → 전달 Job → 요청 payload `embeds[0]` 에 author `시장 · BTC 빗썸/바이낸스`, 칸 3개, footer, timestamp, `description` 없음, 행 `SENT`. (b) SQL 로 넣은 `discord_layout` NULL DISCORD 행 → payload embed 키가 `title`·`description`·`color` 뿐. (c) 같은 회차 메일 행(테스트가 `OwnerAlertRouteOverride(email = true)` 로 `PREMIUM_SWING` 메일을 켬) subject 에 `[시장] ` 접두, body 에 `기준 시각 ` 줄. (d) 기존 batch integrationTest 는 허용 예외(`OwnerMarketAlertIntegrationTest:189-200`·`AccountPnlFixtureSchemaIntegrationTest` 열 목록) 외 무수정 통과 | design §5.3, §11 | `요구`(U3 실제 embed) | T1 | `신규테스트` `io.premiumspread.application.job.OwnerAlertDiscordLayoutIntegrationTest` + `기존` | `./gradlew :apps:batch:integrationTest --offline --no-daemon` | exit 0 (Docker 필요) |
| AC17 | runbook 3개의 알림 예시가 새 형식(접두어·KST·런북 줄 없음·한글 거래소)이고 message-spec 링크가 있다; `account-pnl-alert.md` 에 V23 배포 순서(API 먼저)와 순서 위반 시 장애 범위(owner 알림 enqueue·기존 대기 행 전달 전부 정지, OperatorAlert, 데이터 손상 없음 — design §5.3), 옛 행·손상 행 동작; `.ai/PROJECT_STATUS.md` `Flyway V1~V23`·V23 행; `ARCHITECTURE_DESIGN.md` 템플릿 계층 단락; `docs/check-documentation.sh` 통과 | design §14 | `요구`(문서 갱신) | T3 | 기록 문서 + `기존` 스크립트 | `bash docs/check-documentation.sh` | exit 0 + 사람 사후 검토 |
| AC18 | owner 가 자기 webhook·메일로 로컬 batch 를 띄워 (a) 수익 또는 정시 보고 1통이 Discord 데스크톱·모바일에서 칸이 spec 배치(5행)대로 보이고, (b) 같은 알림 메일이 plain text 로 섹션·정렬이 읽히며, (c) 운영 또는 시장 알림 1통이 author·footer·현지 시각 timestamp 로 보인다 | design §0, §3.3 | `요구`(U3 실제 표시) | T4 | owner 확인 | — | PR 코멘트에 owner 확인 기록 |

### AC10 시나리오 표 (22종)

| # | 알림 | 메일 접두어 | 기준 시각 줄 | Discord author | footer | 행별 칸 수 |
|---|---|---|---|---|---|---|
| 1 | 수익 | `[실계정]` | 있음 | `실계정 · BTC` | 있음 | 3·2·3·2·1 |
| 2 | 정시 보고 | `[실계정]` | 있음 | `실계정 · BTC` | 있음 | 3·2·3·2·1 |
| 3 | 중단(빗썸 인증) | `[실계정]` | 있음 | `실계정 · BTC` | 있음 | 2·1 |
| 4 | 중단(환율 지연) | `[실계정]` | 있음 | `실계정 · BTC` | 있음 | 2 |
| 5 | 복구 | `[실계정]` | **없음** | `실계정 · BTC` | 없음 | 1 |
| 6 | 포지션 변경 | `[실계정]` | 있음 | `실계정 · BTC` | 있음 | 2 |
| 7 | 포지션 변경(헤지 불일치) | `[실계정]` | 있음 | `실계정 · BTC` | 있음 | 2·1 |
| 8 | 순수익률 급등 | `[실계정]` | 있음 | `실계정 · BTC` | 있음 | 3·1 |
| 9 | 순수익률 급락 | `[실계정]` | 있음 | `실계정 · BTC` | 있음 | 3·1 |
| 10 | 미반영 리마인더 | `[실계정]` | 있음 | `실계정 · BTC` | 있음 | 1·1 |
| 11 | 환율 수집 지연 | `[운영]` | 있음 | `운영` | 있음 | 3 |
| 12 | 시세 끊김 | `[운영]` | 있음 | `운영` | 있음 | 3 |
| 13 | Job 연속 실패(재알림) | `[운영]` | 있음 | `운영` | 있음 | 3 |
| 14 | 운영 장애 해소 | `[운영]` | 있음 | `운영` | 없음 | 3·1 |
| 15 | 김프 급등 | `[시장]` | 있음 | `시장 · BTC 빗썸/바이낸스` | 있음 | 3 |
| 16 | 김프 급락 | `[시장]` | 있음 | `시장 · BTC 빗썸/바이낸스` | 있음 | 3 |
| 17 | 상단 기준선 돌파 | `[시장]` | 있음 | `시장 · BTC 빗썸/바이낸스` | 있음 | 2 |
| 18 | 하단 기준선 이탈 | `[시장]` | 있음 | `시장 · BTC 빗썸/바이낸스` | 있음 | 2 |
| 19 | 24시간 신고가 | `[시장]` | 있음 | `시장 · BTC 빗썸/바이낸스` | 있음 | 3 |
| 20 | 1시간 신저가 | `[시장]` | 있음 | `시장 · BTC 빗썸/바이낸스` | 있음 | 3 |
| 21 | 일일 요약 | `[시장]` | 있음 | `시장 · BTC 빗썸/바이낸스` | 있음 | 3·2·2·1 |
| 22 | 회원 임계값 도달 | `[김프 알림]` | **없음**(서명 `premium-spread`) | — (Discord 없음) | — | — |

**기준 개수**: 18개

**분할하지 않는 이유**: outbox 레이아웃(V23·직렬화·adapter)은 Discord 칸을 쓰는 첫 family 가 생기는 순간 전부 필요하고, 템플릿 계층·공용 서식은 모든 family 가 공유한다.
family 별로 PR 을 나누면 A단계 공통 기반을 첫 PR 이 다 짊어지고 나머지는 작은 문구 PR 이 된다 — 같은 리뷰 효과를 한 PR 안 family 별 커밋(design §12)으로 얻는다.

**티어 강등 사유**

- AC17 → T3: runbook 설명의 충분성은 exit code 로 잴 수 없다.
- AC18 → T4: 실 webhook·메일은 테스트·CI 에 쓰지 않는다(testing 규칙). Discord 클라이언트별 칸 배치는 사람만 본다.

## 회귀 방어선

base = `3d179e7` (Task 0 확인). RED 면제 대상.

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 모듈 경계·domain 의존 allowlist·앱 main infra import 금지·테스트 격리 | `./gradlew architectureTest --offline --no-daemon` |
| R2 | 전체 unit test | `./gradlew test --offline --no-daemon` |
| R3 | 기존 테스트 **무수정**, 단 아래 허용 예외만. 허용 예외 파일도 문구 단정 줄·기대 행 구성 줄·호출 줄·import 만 바뀐다(새 단정 추가 금지 — 새 단정은 신규 파일에). 삭제 허용: `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountPnlMailContentTest.kt`·`AccountPnlDiscordContentTest.kt`·`OwnerAlertRowsTest.kt`(비문구 계약은 삭제 전 신규 테스트로 이식 — plan Task 3·8). 교체 허용: `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountAlertContentTest.kt`, `domain/src/test/kotlin/io/premiumspread/domain/ownerops/OwnerOpsAlertContentTest.kt`, `domain/src/test/kotlin/io/premiumspread/domain/ownermarket/OwnerMarketAlertContentTest.kt`. 수정 허용: `apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/{AccountPnlMonitorJobTest,AccountPnlMonitorJobHourlyReportTest,AccountPnlMonitorJobOwnerAlertTest}.kt`, `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/notification/AccountPnlEmailSenderAdapterTest.kt`, `infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/migration/V19OwnerAlertChannelMigrationIntegrationTest.kt`(:34-50 목록 끝 1줄), `apps/batch/src/integrationTest/kotlin/io/premiumspread/config/AccountPnlFixtureSchemaIntegrationTest.kt`(:45-61 목록 끝 1줄), `apps/batch/src/integrationTest/kotlin/io/premiumspread/application/job/OwnerMarketAlertIntegrationTest.kt`(:189-200). `OwnerMarketSignalJobTest.kt`·`NotificationDisabledContextTest.kt` 는 **무수정** | `git diff --name-status 3d179e7 -- '*/src/test/*' '*/src/integrationTest/*'` 의 M·D·R 행이 위 목록 안인지 ⑨ 리뷰가 확인 (사람 판정). 이번 작업의 신규 파일(Task 1 의 `PremiumThresholdDeliveryServiceMailTest.kt`, Task 8~11 의 `*TemplateRowsTest.kt`, Task 3 의 `AlertFormatTest.kt` 등)은 대상 아님. 목록 밖 기존 테스트를 고쳐야 하면 구현자는 멈추고 보고 |
| R4 | 기존 migration 무변경(추가만) + 검증 | `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` + `git diff --name-only --diff-filter=MDR 3d179e7 -- infrastructure/common/src/main/resources/db/migration` 빈 출력 |
| R5 | 공통 persistence·migration 통합(V12·V16~V22, 기존 store·동시 claim·fencing) | `./gradlew :infrastructure:common:integrationTest --offline --no-daemon` |
| R6 | batch 통합 — #88~#91 E2E·fixture 스키마·회원 메일 빈 조건(`NotificationDisabledIntegrationTest`·`DurableNotificationDeliveryIntegrationTest`) | `./gradlew :apps:batch:integrationTest --offline --no-daemon` |
| R7 | API Flyway 전체 적용(V23 까지) | `./gradlew :apps:api:integrationTest --offline --no-daemon` (Docker 필요) |
| R8 | 정본 문서 정합성 | `bash docs/check-documentation.sh` |
| R9 | 하네스 무변경 확인 | `./gradlew harnessCheck harnessCheckTest --offline --no-daemon` |
| R10 | coverage 게이트(overall 70%·Domain 85%·Application 80%) | `./gradlew jacocoTestReport jacocoTestCoverageVerification --offline --no-daemon` (최종 증거 CI artifact) |
| R11 | ktlint·detekt | CI `quality-gate.yml` (로컬 실행 불가, 우회 금지) |
| R12 | eventKey 형식 무변경 | AC5~AC8 golden 의 eventKey 단정 + `git diff 3d179e7 -- domain/src/main` 에서 eventKey 를 만드는 식(`account-pnl-…`·`owner-ops.…`·`owner-market.…`) 무변경을 ⑨ 리뷰가 확인 |
| R13 | layout 없는 Discord payload 무변경 | `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.notification.DiscordWebhookSenderAdapterTest' --offline --no-daemon` (파일 무수정) |
| R14 | A단계 동작 보존 — plan Task 7 커밋 시점에 기존 golden 5개·`OwnerAlertRowsTest`·`DiscordMessageTest` 가 base 와 같은 파일로 통과 | Task 7 커밋에서 `git diff --name-status 3d179e7 -- domain/src/test infrastructure/batch/src/test apps/batch/src/test` 에 M·D 행 없음 + `./gradlew test --offline --no-daemon` |

## 변경 요청

| # | 날짜 | 기준 | 변경 내용 | 사유 | 승인 |
|---|---|---|---|---|---|

## 스펙 리뷰 라운드 기록 (⑥)

| 라운드 | 지적 | 처리 | 반영 커밋 |
|---|---|---|---|
| 1 | MAJOR 1 수익 fields 13→12 / 2 `displayWidth("10분 저점")` 7→9 / 3 A8 / 4 AC15(b) 테스트 위치 / 5 배포 순서 위반 장애 범위 | ACCEPT — AC2·AC3·AC6·AC15·AC17 갱신, design §3.3·§5.3·§6.3, plan Task 3·8~11·15 | 미커밋(DRAFT) |
| 1 | MINOR 6 `reminderHour` / 7 중단·복구 시각 출처 / 8 삭제 테스트 계약 이식 / 9 `AccountPnlFormat` 직접 비교 / 10 `NetPercentSwingPolicy` KDoc / 11 Task 0 예상 결과·grep / 12 `OwnerMarketSignalJobTest` 제외 / 13(a)~(e) | ACCEPT — AC1·AC2·AC3·AC5·AC6·AC12·AC13·AC16·R3, design §4·§5·§6, plan Task 0·3·4·6·7·8·10·11·13·14 | 미커밋(DRAFT) |
| 1 | 사용자 결정 D1·D2·A4 승인, A8 철회, N1 Configuration 주입, N2 유지 | design §13 결정됨, AC9(d)(e) 갱신 | 미커밋(DRAFT) |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

### Task 0 — Gate 1 기준선 (2026-10-30)

- HEAD `5846c72`, merge-base(origin/dev) `3d179e71e96d5f6a97070ac06462cf018add7c68` — base `3d179e7` 일치.
- R1·R2 `./gradlew test architectureTest --offline --no-daemon` → exit 0, 테스트 1338건(skipped 0, failures 0, errors 0; build/test-results XML 합산).
- R4 `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` → exit 0.
- R5 `:infrastructure:common:integrationTest`, R6 `:apps:batch:integrationTest`, R7 `:apps:api:integrationTest` → 모두 exit 0 (Docker 사용). 세 모듈 합산 367건, failures 0, errors 0, skipped 0.
- (g1) `V22__create_owner_market_alert_state.sql` — V23 번호 확인.
- (g2) `com.fasterxml.jackson.core:jackson-databind:2.21.4` 전이 존재.
- (g3) 예상 2건(`V19…:34`, `AccountPnlFixtureSchemaIntegrationTest:45`) 외에 `V20…:34`, `V21…:29` 가 더 걸렸으나 둘 다 account_pnl_mail 이 아닌 상태 테이블의 열 단언이다 — 영향 없음.
- (g4) 예상 파일 외 걸린 줄: `OwnerAlertDispatcherTest.kt:198`(fake 의 title 비교), `OwnerAlertDeliveryIntegrationTest.kt:116`·`DiscordWebhookSenderAdapterTest.kt:89-95`(요청 body 읽기) — 변경되는 문구를 단정하는 줄이 아니므로 R3 목록 변경 불요로 판단.
- (g5)~(g11) 예상과 일치(g5 `:63`·`:79`·`:84`·`:94`, g6 `:105`·`:119`·`:298`, g7 5개 파일, g8 `:225`·`:240`·`:242`, g9 `:153`·`:67`, g10 `OwnerOpsAlertContentTest:264-266` 3줄만, g11 `:33`·`:52`).

### Task 1 — 회원 김프 메일 특성화 (AC9-a, 2026-10-30)

- 특성화 PASS: `./gradlew :apps:batch:test --tests 'io.premiumspread.application.notification.PremiumThresholdDeliveryServiceMailTest' --offline --no-daemon` → 2건 통과(main 무변경).
- 변이 확인: 기대 subject 의 `6%` → `7%` 로 바꾸면 같은 명령이 `2 tests completed, 1 failed`(`ABOVE 구독의 메일 제목과 본문 전문` assertion 실패, BUILD FAILED). 되돌린 뒤 PASS.
- 커밋 전 `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL.

### Task 2 — Discord 레이아웃 domain 타입 (AC1, 2026-10-30)

- RED(stub: `DiscordField`·`DiscordLayout` 검증 없음): `./gradlew :domain:test --tests 'io.premiumspread.domain.owneralert.DiscordLayoutTest' --offline --no-daemon` → `15 tests completed, 8 failed`(assertion 실패, BUILD FAILED). 나머지 7건(합계 6000/6001·`AccountPnlMail` 불변식 등)은 stub 단계에서 이미 구현돼 통과.
- GREEN: `./gradlew :domain:test --tests 'io.premiumspread.domain.owneralert.*' --tests 'io.premiumspread.domain.accountpnl.*' --offline --no-daemon` → BUILD SUCCESSFUL (기존 golden·`DiscordMessageTest`·`OwnerAlertRowsTest` 무수정).
- 커밋 전 `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL.

### Task 3 — 템플릿 계층 `domain.alertmessage` (AC2·AC3, 2026-10-30)

- RED(stub: 렌더·서식 함수가 빈 값 반환, 검증 없음): `./gradlew :domain:test --tests 'io.premiumspread.domain.alertmessage.*' --tests 'io.premiumspread.domain.accountpnl.OwnerAlertRowsMessageTest' --offline --no-daemon` → `41 tests completed, 36 failed`(assertion 실패, BUILD FAILED).
- GREEN: `./gradlew :domain:test --offline --no-daemon` → BUILD SUCCESSFUL (기존 golden 무수정).
- 커밋 전 `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL.

### Task 4 — AccountPnlBreakdown (AC4, 2026-10-30)

- RED(stub: `of` 가 모든 값 0·null 반환): `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountPnlBreakdownTest' --offline --no-daemon` → `6 tests completed, 5 failed`(assertion 실패, BUILD FAILED). `toString` 테스트 1건은 stub 에서도 통과.
- GREEN: `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.*' --offline --no-daemon` → BUILD SUCCESSFUL (`AccountNetPnl` 무변경, 기존 golden 무수정).
- AC4 숫자: 현김프 1.78·매수김프 0.53·차이 1.25, 빗썸 손익 1,729,000·평가액 42,161,000, 바이낸스 손익 −851.2 USDT·−1,191,680원·평가액 29,579.2 USDT·41,410,880원, 정리 수수료 16,864.4원·14.7896 USDT·20,705.44원, 수수료 합계 73,846.84원, 펀딩 5,880원, 순손익 469,353.16원(1.11%) 모두 `compareTo` 일치. 불변식 3개 통과.

### Task 5 — V23 migration 과 fixture (AC11, 2026-10-30)

- RED(V23 없음): `./gradlew :infrastructure:common:integrationTest --tests 'io.premiumspread.infrastructure.common.migration.V23OwnerAlertDiscordLayoutMigrationIntegrationTest' --offline --no-daemon` → `3 tests completed, 3 failed`(열 없음, BUILD FAILED).
- GREEN: 같은 테스트 + `:infrastructure:common:verifyMigrations` → BUILD SUCCESSFUL. `./gradlew :infrastructure:common:integrationTest --offline --no-daemon` → BUILD SUCCESSFUL, 84건(skipped 0, failures 0, errors 0; V19 테스트 포함).
- `git diff --name-only --diff-filter=MDR 3d179e7 -- infrastructure/common/src/main/resources/db/migration` → 빈 출력.
- 커밋 전 `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL.

### Task 6 — outbox 레이아웃 직렬화와 claim (AC12, 2026-10-30)

- RED(코덱 stub `write`="{}"·`read`=빈 layout, store 는 열 미사용): `./gradlew :infrastructure:common:test --tests 'io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.*' --offline --no-daemon` → `12 tests completed, 11 failed`(assertion 실패). `:infrastructure:common:integrationTest --tests '*OwnerAlertDiscordLayoutStoreIntegrationTest'` → `7 tests completed, 6 failed`.
- GREEN: 같은 unit 명령 → BUILD SUCCESSFUL. `./gradlew :infrastructure:common:integrationTest --offline --no-daemon` → BUILD SUCCESSFUL, 91건(skipped 0, failures 0, errors 0; 기존 store IT 무수정 포함). `jackson-databind` 선언 추가(D1 승인), lockfile 변경 없음(기존 2.21.4).
- 커밋 전 `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL.

### Task 7 — Discord adapter payload 와 dispatcher (AC13·AC14, 2026-10-30)

- RED: `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.notification.DiscordWebhookSenderAdapterLayoutTest' --offline --no-daemon` → `4 tests completed, 4 failed`(layout 무시, assertion 실패). `OwnerAlertDispatcherLayoutTest` 의 layout 전달 테스트 1건 FAILED(나머지 2건은 기존 동작이라 통과).
- GREEN: `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.notification.*' :apps:batch:test --tests 'io.premiumspread.application.notification.owneralert.*' --offline --no-daemon` → BUILD SUCCESSFUL (`DiscordWebhookSenderAdapterTest`·`OwnerAlertDispatcherTest` 무수정).
- A단계 종료(R14): `git diff --name-status 3d179e7 -- domain/src/test infrastructure/batch/src/test apps/batch/src/test` 에 M·D 행 없음(A 행만).
- 커밋 전 `./gradlew test architectureTest --offline --no-daemon` → 아래 커밋 직전 결과 참조(BUILD SUCCESSFUL).

### Task 8 — 실계정 순손익 5종 (AC5, 2026-10-30)

- RED(stub: 템플릿이 빈 문서 반환): `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountPnlAlertContentTest' --offline --no-daemon` → `13 tests completed, 10 failed`(assertion 실패, BUILD FAILED). eventKey 형식·색 상수 등 3건은 stub 에서도 통과.
- GREEN: 같은 명령 → BUILD SUCCESSFUL(13건). `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.*' :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.*' :infrastructure:batch:test --offline --no-daemon` 범위 모두 통과(Job 테스트 77건 + 신규 `AccountPnlMonitorJobTemplateRowsTest` 2건).
- `grep -rn 'AccountPnlMailContent\|AccountPnlDiscordContent' --include=*.kt domain apps infrastructure` → 출력 없음.
- 수정한 기존 테스트(R3 허용): `AccountPnlMonitorJobTest`·`AccountPnlMonitorJobHourlyReportTest`·`AccountPnlMonitorJobOwnerAlertTest`·`AccountPnlEmailSenderAdapterTest` — 문구 단정·기대 행(layout 인자)·호출·import 줄만. 행 수·채널·eventKey·`JobResult` 단정 줄 무변경.
- 문구 변경으로 대체된 단정: `환율 관측: 2026-10-17`(새 spec 에 환율 관측 시각 없음) → `환율 1,400.00원/USD`, `계산 시각: …12:00:03`(초 단위 표기 없음) → `기준 시각 2026-10-20 12:00 KST`(eventKey 단정이 초 정밀도를 이미 보장).
- 커밋 전 `./gradlew test architectureTest --offline --no-daemon` → BUILD SUCCESSFUL, `compileIntegrationTestKotlin` → BUILD SUCCESSFUL.

### Task 9 — 계정 알림 (AC6, 2026-10-30)

- RED(stub: `message` 가 빈 문서 반환): `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountAlertContentTest' --offline --no-daemon` → `15 tests completed, 12 failed`(assertion 실패, BUILD FAILED).
- GREEN: `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.*' :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.*' --offline --no-daemon` → BUILD SUCCESSFUL(신규 Job 행 시나리오 `POSITION_CHANGED` 포함, `AccountPnlMonitorJobAccountAlertTest` 무수정 통과).
- 커밋 전 `./gradlew test architectureTest compileIntegrationTestKotlin --offline --no-daemon` → BUILD SUCCESSFUL.
- 옛 `route(kind, mail, discord)` 삭제. `reminderHour` 는 `alert.reminderHour` 출처(`09:00` 리터럴 없음). 바이낸스 칸 이름은 leg 방향과 무관하게 `바이낸스 선물 숏` 고정(spec), 롱이면 값 앞 `롱 `(A7).

### Task 10 — 운영 알림 (AC7, 2026-10-30)

- RED(변이: 안내 문장 `1번 알립니다` → `한 번 알립니다`): `./gradlew :domain:test --tests 'io.premiumspread.domain.ownerops.*' --offline --no-daemon` → `29 tests completed, 2 failed`(assertion 실패, BUILD FAILED). 변이 되돌림.
- GREEN: `./gradlew :domain:test --tests 'io.premiumspread.domain.ownerops.*' --tests 'io.premiumspread.domain.ownermarket.*' :apps:batch:test --tests 'io.premiumspread.application.job.ownerops.*' --offline --no-daemon` → BUILD SUCCESSFUL(`OwnerOpsSignalJobTest` 무수정, 신규 `OwnerOpsSignalJobTemplateRowsTest` 포함).
- `grep -rnE 'OwnerOpsAlertContent\.(RUNBOOK|elapsed)|OwnerMarketAlertContent\.RUNBOOK' --include=*.kt domain apps infrastructure` → 출력 없음.
- 커밋 전 `./gradlew test architectureTest compileIntegrationTestKotlin --offline --no-daemon` → BUILD SUCCESSFUL.

### Task 11 — 시장 알림 (AC8, 2026-10-30)

- RED(변이: 급등 안내 `올랐습니다` → `상승했습니다`): `./gradlew :domain:test --tests 'io.premiumspread.domain.ownermarket.*' --offline --no-daemon` → `74 tests completed, 1 failed`(assertion 실패, BUILD FAILED). 변이 되돌림.
- GREEN: `./gradlew :domain:test --tests 'io.premiumspread.domain.ownermarket.*' --tests 'io.premiumspread.domain.ownerops.*' :apps:batch:test --tests 'io.premiumspread.application.job.ownermarket.*' --offline --no-daemon` → BUILD SUCCESSFUL(`OwnerMarketSignalJobTest` 무수정, 신규 `OwnerMarketSignalJobTemplateRowsTest` 2건 포함).
- 메일 정렬은 design §3.2 규칙(표시 폭 최댓값 + 2)의 출력이다(A1) — spec 의 손 정렬 공백 개수와 다르다.
- 허용 예외: `OwnerMarketAlertIntegrationTest` 요약 단정 줄(메일 `■ 환율 (USD/KRW)`·`순수익률  +`, Discord `layout.fields` 값에 `9.99` 없음)만 수정. `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.application.job.OwnerMarketAlertIntegrationTest'` → BUILD SUCCESSFUL.
- `OwnerOpsAlertContent.threshold` 삭제에 맞춰 `OwnerOpsAlertContentTest` 의 `threshold 표기` 테스트 제거(같은 단정이 `AlertFormatTest` 에 있음). `grep -rnE 'OwnerOpsAlertContent\.threshold|OwnerMarketAlertContent\.(RUNBOOK|mail|discord)' --include=*.kt domain apps infrastructure` → 출력 없음.
- 커밋 전 `./gradlew test architectureTest compileIntegrationTestKotlin --offline --no-daemon` → BUILD SUCCESSFUL.

### Task 12 — 회원 김프 메일 (AC9-b·c·d·e, 2026-10-30)

- RED(특성화 테스트 기대값을 새 문구로 교체, 구현 전): `./gradlew :apps:batch:test --tests 'io.premiumspread.application.notification.PremiumThresholdDeliveryServiceMailTest' --offline --no-daemon` → `2 tests completed, 2 failed`(assertion 실패).
- GREEN: `./gradlew :domain:test --tests 'io.premiumspread.domain.notification.*' :apps:batch:test --tests 'io.premiumspread.application.notification.*' --offline --no-daemon` → BUILD SUCCESSFUL(`PremiumThresholdDeliveryServiceTest`·`NotificationDisabledContextTest` 무수정). zone 사례(UTC → `관측 시각  2026-07-15 00:30 UTC`) 포함.
- `grep -nE '@Value|@Service' apps/batch/src/main/kotlin/io/premiumspread/application/notification/PremiumThresholdDeliveryService.kt` → 출력 없음.
- 통합: `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.application.notification.*'` → 1차 FAILED(`Cannot subclass final class PremiumThresholdDeliveryService` — `@Service` 제거로 allopen 대상에서 빠져 `@Transactional` CGLIB 프록시 실패). `@Transactional` 을 메서드에서 클래스 수준으로 옮겨 allopen 이 클래스를 open 으로 만들게 한 뒤 BUILD SUCCESSFUL(enabled 컨텍스트 빈 생성·disabled 부재 확인).
- 커밋 전 `./gradlew test architectureTest compileIntegrationTestKotlin --offline --no-daemon` → BUILD SUCCESSFUL.
