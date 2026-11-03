# 실계정 알림 자산 칸 + Discord 2칸 행 폭 수정 Implementation Plan (#105)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 수익·정시 알림에 "자산" 묶음(총 자산 · 바이낸스 증거금+손익 · 잔여 금액(빗썸/바이낸스) · USDT 원화가)을 추가하고, Discord 2칸 행이 1/3 폭으로 렌더되는 문제를 행 끊기 칸으로 고친다.

**Architecture:** 잔고는 기존 `KoreaSpotAccountSnapshot`·`ForeignPerpAccountSnapshot` 의 기본값 `null` 필드로 싣는다. 빗썸은 이미 부르는 `/v1/accounts`·`/v1/ticker` 응답에서, 바이낸스는 adapter 안 `/fapi/v3/account` 1회로 읽고, 실패는 adapter 안에서 `null` + WARN 으로 흡수한다(취소만 전파). 값은 `AccountNetPnl.position` 을 통해 이미 `AccountPnlAlertContent` 까지 흐르므로 Job·port·Configuration 은 바꾸지 않는다. 총 자산은 Domain 값 객체 `AccountAssetBreakdown` 이 계산한다. `EmbedDocument` 의 inline 채움 칸을 non-inline `ROW_BREAK` 로 바꾼다.

**Tech Stack:** Kotlin 2.x, Spring WebFlux `WebClient`, Jackson, JUnit5 + AssertJ, okhttp3 MockWebServer, `OutputCaptureExtension`, MockK.

**Spec:** `docs/work/account-balance-alert/design.md` · **DoD:** `docs/work/account-balance-alert/dod.md`

**Discord 자산 행 배치 — ⑦ 결정 (2026-11-02): B안 채택.** 구현자는 본문의 A안 값 대신 `[B안]` 각주(Task 4 각주 1~4, Task 9 각주 5·6)를 적용한다. 본문 원문: 이 plan 은 design §5.2 **A안**(`[총 자산 | 증거금+손익 | USDT 원화가]` + `[잔여 금액]`)으로 쓴다. ⑦에서 **B안**(`[총 자산 | 증거금+손익]` + `[잔여 금액 | USDT 원화가]`)이 채택되면 각주 `[B안]` 이 붙은 지점만 바꾼다.

## Global Constraints

- 작업 디렉터리: `/home/yeop/IdeaProjects/premium-spread/.worktrees/feat-issue-105-account-balance-alert`. 모든 gradle 명령은 `--offline --no-daemon`.
- dod 가 `FROZEN` 이 되기 전에는 Task 0 외 구현을 시작하지 않는다.
- 컴파일 실패를 RED 로 쓰지 않는다. RED 는 stub 또는 기존 코드에서 난 **assertion 실패**다. 각 태스크의 Step 1(stub)이 그 조건을 만든다.
- Domain 에 새 framework 의존 금지. `apps:batch` main 무변경(Job·Configuration·yml). 앱은 infrastructure 타입을 import 하지 않는다.
- Flyway·Redis·`JobConfig`·`PublicEndpointPolicy`·http 샘플 무변경.
- 테스트는 실 거래소·실 credential·실 webhook 금지. MockWebServer·fixture·고정 `Clock`·명시적 `ZoneId.of("Asia/Seoul")`.
- 로그·예외 메시지·`toString()` 에 금액·API key·secret·`signature`·요청 query/URL·응답 본문·예외 메시지를 남기지 않는다(design §4.3).
- `PrivateAccountReadOnlyArchitectureTest` 대상 파일(`*PrivateAccountAdapter.kt`·`exchange/*AccountAdapter.kt`·`*ReadClient.kt`)의 소스·주석에 `withdraw`·`deposit`·`transfer`(대소문자 무관) 문자열을 쓰지 않는다 — `/fapi/v3/account` 응답의 `maxWithdrawAmount` 를 언급하지 않는다.
- detekt: `AccountPnlAlertContent.pnlMessage` 에는 줄을 2줄 넘게 더하지 않는다(`LongMethod` 100). 클래스·파일당 함수 20개 이하.
- 기존 테스트의 기존 메서드는 아래 태스크가 명시한 갱신 외에는 고치지 않는다. 기존 fixture 는 바꾸지 않고 새 fixture 를 더한다.
- 인터럽트를 쓰는 테스트는 끝에서(`finally`) `Thread.interrupted()` 로 플래그를 지운다 — 다음 테스트로 새면 무관한 테스트가 깨진다.
- 커밋: `<type>: <subject>` + 한글 bullet, `Co-Authored-By` 금지. **커밋은 오케스트레이터가 한다** — 각 태스크 끝의 Commit 줄은 메시지 제안이다.

## Review Focus

1. **실패 흡수 경계** — 잔고·USDT 경로의 실패가 `read()` 밖으로 새지 않는가, 그리고 취소는 새는가(Task 5·6). 흡수가 넓어져 기존 BTC·호가 실패까지 삼키지 않는가.
2. **바이낸스 호출 순서** — `/fapi/v3/account` 가 depth 앞이고 depth 가 여전히 마지막인가(호가 skew 5초).
3. **toString 노출** — 새 필드가 들어간 data class 3개 + `AccountAssetBreakdown` 의 `toString()` (Task 2·3).
4. **공통 렌더러 영향** — `ROW_BREAK` 교체가 시장·운영 golden 을 끊기 칸 inline 값 외에는 바꾸지 않는가(Task 1).
5. **빗썸 ticker** — "첫 행" 대신 `market` 으로 고르는가, fallback 이 `getJson` 단계 `UNEXPECTED` 에서만, 취소 아님일 때만 도는가(Task 5).

---

## File Structure

| 파일 | 책임 | 태스크 |
|---|---|---|
| `domain/src/main/kotlin/io/premiumspread/domain/alertmessage/EmbedDocument.kt` | `SPACER` → `ROW_BREAK`(inline=false) | 1 |
| `domain/src/test/kotlin/io/premiumspread/domain/alertmessage/GoldenFields.kt` | `GOLDEN_FILLER` → `GOLDEN_ROW_BREAK`, KDoc | 1 |
| `domain/src/test/kotlin/io/premiumspread/domain/alertmessage/EmbedDocumentTest.kt` | 끊기 칸 단정 갱신 | 1 |
| `domain/src/test/kotlin/io/premiumspread/domain/ownermarket/OwnerMarketAlertContentTest.kt` | `GOLDEN_FILLER` 1곳 교체 | 1 |
| `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountPnlAlertContentTest.kt` | `GOLDEN_FILLER` 1곳 교체(1), 자산 golden·변형(4) | 1·4 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountSnapshots.kt` | `ForeignPerpBalance`, snapshot 새 필드·불변식·toString, port KDoc | 2 |
| `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountBalanceSnapshotTest.kt` (신규) | AC2 | 2 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlBreakdown.kt` | `AccountAssetBreakdown`, `AccountPnlBreakdown.assets` | 3 |
| `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountAssetBreakdownTest.kt` (신규) | AC3 | 3 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlAlertContent.kt` | `■ 자산`·Discord 2행 | 4 |
| `domain/src/test/kotlin/io/premiumspread/domain/alertmessage/AlertMessageSpecConformanceTest.kt` | 수익·정시 `cellsPerRow` 2곳 | 4 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/AccountBalanceLog.kt` (신규) | WARN 키·취소 판별·root 예외명 | 5 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/bithumb/BithumbPrivateAccountAdapter.kt` | KRW 잔고·KRW-USDT·market 선택·fallback | 5 |
| `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange/bithumb/BithumbPrivateAccountAdapterTest.kt` | AC5 테스트 추가 | 5 |
| `infrastructure/batch/src/test/resources/fixtures/bithumb-private/ticker-btc-usdt.json` (신규) | 두 시장 ticker | 5 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceFuturesPrivateAccountAdapter.kt` | `/fapi/v3/account` | 6 |
| `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceFuturesPrivateAccountAdapterTest.kt` | AC6 테스트 추가 | 6 |
| `infrastructure/batch/src/test/resources/fixtures/binance-private/account.json` (신규) | v3 account 응답 | 6 |
| `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceFuturesManualAccountAdapterTest.kt` | R4 단정 1개 추가 | 6 |
| `apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJobTest.kt` | `FakeKorea`·`FakeForeign` 에 잔고 var 추가(기본 null) | 7 |
| `apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJobBalanceTest.kt` (신규) | R5 | 7 |
| `docs/work/alert-message-template/message-spec.md` | 수익·정시 예시 | 9 |
| `docs/work/alert-message-template/design.md` | §3.3 규칙 2·§6.2 U5 개정 메모 | 9 |
| `docs/runbooks/account-pnl-alert.md` | 권한·메시지 예·수동 모드(350·358행, 표)·동작 요약·체크리스트 | 9 |

---

### Task 0: Gate 1 기준선

**Files:** 없음 (dod `## 증거 로그` → `기준선` 에 append)

- [x] **Step 1: 기준선 실행·기록**
  ```bash
  ./gradlew test architectureTest --parallel --offline --no-daemon
  grep -rln "GOLDEN_FILLER\|SPACER" --include=*.kt domain apps infrastructure
  grep -rln "KoreaSpotAccountSnapshot(\|ForeignPerpAccountSnapshot(" --include=*.kt domain apps infrastructure
  grep -rn "KoreaSpotAccountSnapshot(\|ForeignPerpAccountSnapshot(" --include=*.kt domain apps infrastructure
  ```
- [x] **Step 2: 예상 결과** — gradle exit 0.
  - 첫 grep 은 정확히 5개 파일(2026-11-02 설계 시 확인):
    ```text
    domain/src/main/kotlin/io/premiumspread/domain/alertmessage/EmbedDocument.kt
    domain/src/test/kotlin/io/premiumspread/domain/alertmessage/EmbedDocumentTest.kt
    domain/src/test/kotlin/io/premiumspread/domain/alertmessage/GoldenFields.kt
    domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountPnlAlertContentTest.kt
    domain/src/test/kotlin/io/premiumspread/domain/ownermarket/OwnerMarketAlertContentTest.kt
    ```
  - 두 번째 grep 은 24개 파일(`AccountSnapshots.kt` 선언 포함), 세 번째 grep 은 45줄 = 선언 2 + 생성자 호출 43(design §3.1).
  - **다르면 멈추고 보고한다** — design §6.3 영향표가 틀린 것이다. `--offline` 이 cache 부재로 실패하면 온라인 1회로 cache 를 채우고 그 사실을 기록한다.
- [x] **Step 3: Commit 제안** — `docs: 자산 알림 Gate 1 기준선 기록`

---

### Task 1: Discord 행 끊기 칸 (AC1)

**Files:**
- Modify: `domain/src/main/kotlin/io/premiumspread/domain/alertmessage/EmbedDocument.kt`
- Modify: `domain/src/test/kotlin/io/premiumspread/domain/alertmessage/GoldenFields.kt`
- Modify: `domain/src/test/kotlin/io/premiumspread/domain/alertmessage/EmbedDocumentTest.kt`
- Modify: `domain/src/test/kotlin/io/premiumspread/domain/ownermarket/OwnerMarketAlertContentTest.kt` (347행 1곳)
- Modify: `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountPnlAlertContentTest.kt` (133행 1곳)

**코드 계약**

```kotlin
// EmbedDocument.companion — SPACER 는 삭제한다
/** 2칸 행 뒤 행 끊기 칸(design §6.2, #102 design §3.3 규칙 2 개정). 보이지 않는 non-inline 칸. */
val ROW_BREAK: DiscordField = DiscordField(ZWSP, ZWSP, inline = false)

// 삽입 조건은 그대로: row.size == 2 && (rows.getOrNull(index + 1)?.size ?: 0) >= 2 → add(ROW_BREAK)
```

```kotlin
// GoldenFields.kt — GOLDEN_FILLER 는 삭제한다. 구현의 ROW_BREAK 를 참조하지 않고 따로 만든다.
/** 2칸 inline 행 뒤 행 끊기 칸(non-inline U+200B). */
internal val GOLDEN_ROW_BREAK: DiscordField = DiscordField(Char(0x200B).toString(), Char(0x200B).toString(), false)
```

- [x] **Step 1: stub** — `EmbedDocument.companion` 에 `ROW_BREAK` 상수만 추가한다(`toDiscordMessage()` 는 아직 `SPACER` 를 넣는다). `GoldenFields.kt` 에 `GOLDEN_ROW_BREAK` 를 추가한다(`GOLDEN_FILLER` 는 아직 둔다).
- [x] **Step 2: 테스트 갱신** (요건)
  - `EmbedDocumentTest`
    - `행 3-2-3-2-1 …` : fields 12개, inline 목록 `T,T,T, T,T,F, T,T,T, T,T, F`(6번째 = 끊기 칸 false), `fields[5] == EmbedDocument.ROW_BREAK`, `fields.count { it == ROW_BREAK } == 1`. 테스트 이름에서 "채움" → "행 끊기".
    - 상수 검사: `ROW_BREAK.name`·`value` 가 각각 `Char(0x200B).toString()` 한 글자, `inline == false`.
    - `2칸 행 뒤에 2칸 행이 오면 …` : fields 5개, `fields[2] == ROW_BREAK`, `fields[0].inline && fields[1].inline && !fields[2].inline`.
    - 추가: `2칸 행 뒤에 3칸 행이 오면 끊는다` — rows `[2,3]` → fields 6개, `fields[2] == ROW_BREAK`.
    - 추가: `결과 fields 에 U+200B inline 칸이 없다` — rows `[3,2,3,2,1]`·`[2,2]`·`[2,3,2,2]` 각각 `fields.none { it.name == zwsp && it.inline }`.
    - `마지막 2칸 행 뒤…`·`2칸 행 뒤에 1칸 행…` 는 그대로 둔다.
  - `AccountPnlAlertContentTest.profitFields`·`OwnerMarketAlertContentTest`(일일 요약) : `GOLDEN_FILLER` → `GOLDEN_ROW_BREAK`. 주석의 "채움" → "행 끊기".
- [x] **Step 3: RED 확인**
  ```bash
  ./gradlew :domain:test --tests io.premiumspread.domain.alertmessage.EmbedDocumentTest --tests io.premiumspread.domain.accountpnl.AccountPnlAlertContentTest --tests io.premiumspread.domain.ownermarket.OwnerMarketAlertContentTest --offline --no-daemon
  ```
  예상: FAIL — `EmbedDocumentTest` `행 3-2-3-2-1 …` 의 inline 목록 `containsExactly` 에서 **6번째 값 실제 `true` vs 기대 `false`**(assertion, `EmbedDocumentTest.kt:29-35` 가 `fields[5]` 단정보다 먼저 실패한다). golden 두 테스트도 끊기 칸 `inline` 차이로 실패. dod AC1 증거 로그에 붙인다.
- [x] **Step 4: 구현** — `toDiscordMessage()` 가 `ROW_BREAK` 를 넣게 바꾸고 `SPACER` 를 삭제한다. `EmbedDocument.kt` 주석의 `design §3.3 규칙 2` → `design §3.3 규칙 2(#105 개정: non-inline 행 끊기)`. `GoldenFields.kt` 의 `GOLDEN_FILLER` 를 삭제하고 **5행 KDoc** `구현의 SPACER 를 참조하지 않고 design §3.3 규칙 2 에서 따로 만든다` → `구현의 ROW_BREAK 를 참조하지 않고 design §3.3 규칙 2(#105 개정)에서 따로 만든다` 로 고친다.
- [x] **Step 5: GREEN 확인** — Step 3 명령 + `./gradlew :domain:test --offline --no-daemon`. 예상: exit 0. `grep -rn "GOLDEN_FILLER\|SPACER" --include=*.kt domain apps infrastructure` 출력 없음.
- [x] **Step 6: Commit 제안** — `fix: Discord 2칸 행 뒤 채움 칸을 non-inline 행 끊기 칸으로 교체`

---

### Task 2: snapshot 잔고 필드와 불변식 (AC2)

**Files:**
- Modify: `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountSnapshots.kt`
- Create: `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountBalanceSnapshotTest.kt`

**코드 계약** — design §3.1 전문 그대로. 요약(전문):

```kotlin
data class ForeignPerpBalance(val marginBalanceUsdt: BigDecimal, val availableBalanceUsdt: BigDecimal) {
    init { /* 둘 다 signum() >= 0, 위반 시 IllegalArgumentException("… must not be negative.") — 메시지에 값 없음 */ }
    override fun toString(): String = "ForeignPerpBalance"
}

data class KoreaSpotAccountSnapshot(
    val quantity: BigDecimal,
    val averageBuyPrice: BigDecimal,
    val averageBuyPriceModified: Boolean,
    val entryFeeKrw: BigDecimal?,
    val sellFeeRate: BigDecimal,
    val bidLevels: List<PriceLevel>,
    val lastPrice: BigDecimal,
    val bidObservedAt: Instant,
    val krwBalance: BigDecimal? = null,
    val usdtLastPriceKrw: BigDecimal? = null,
) {
    init { /* krwBalance == null || >= 0 ; usdtLastPriceKrw == null || > 0 */ }
    override fun toString(): String = "KoreaSpotAccountSnapshot(bidObservedAt=$bidObservedAt)"
}

data class ForeignPerpAccountSnapshot(
    val signedQuantity: BigDecimal,
    val entryPrice: BigDecimal,
    val entryCommissionUsdt: BigDecimal?,
    val fundingUsdt: BigDecimal?,
    val takerFeeRate: BigDecimal,
    val askLevels: List<PriceLevel>,
    val lastPrice: BigDecimal,
    val askObservedAt: Instant,
    val positionSource: ForeignPerpPositionSource = ForeignPerpPositionSource.EXCHANGE_API,
    val balance: ForeignPerpBalance? = null,
) {
    override fun toString(): String = "ForeignPerpAccountSnapshot(positionSource=$positionSource, askObservedAt=$askObservedAt)"
}

// port KDoc 에 한 줄 추가(두 port 공통):
// /** 잔고·USDT 시세 실패는 read() 를 실패시키지 않는다(해당 필드 null). 취소는 예외로 전파한다 (#105). */
```

`toString()` 노출 프로퍼티(전문): `ForeignPerpBalance` 없음 · `KoreaSpotAccountSnapshot` `bidObservedAt` · `ForeignPerpAccountSnapshot` `positionSource`·`askObservedAt`.

- [x] **Step 1: stub** — 세 필드와 `ForeignPerpBalance` 를 **불변식·toString override 없이** 추가한다(컴파일만 되게).
- [x] **Step 2: 테스트 작성** — `AccountBalanceSnapshotTest` (요건; 고정 `Instant.parse("2026-10-20T00:00:00Z")`)
  - 기본값: 새 필드를 생략한 두 snapshot 의 `krwBalance`·`usdtLastPriceKrw`·`balance` 가 `null`.
  - 경계: `krwBalance = 0` 허용, `-0.00000001` → `IllegalArgumentException`. `usdtLastPriceKrw = 0` → 예외, `0.0001` 허용. `ForeignPerpBalance(0, 0)` 허용, `(-0.01, 1)`·`(1, -0.01)` → 예외.
  - 예외 메시지에 입력 값 문자열(`-0.01`)이 없다.
  - toString: 모든 금액·수량·가격 필드를 sentinel `987654.321` 로 채운 세 객체의 `toString()` 이 `987654.321` 을 담지 않고, 각각 `"ForeignPerpBalance"`, `"KoreaSpotAccountSnapshot(bidObservedAt=2026-10-20T00:00:00Z)"`, `"ForeignPerpAccountSnapshot(positionSource=EXCHANGE_API, askObservedAt=2026-10-20T00:00:00Z)"` 와 같다.
  - `AccountHedgePosition(korea, foreign).toString()` 도 sentinel 을 담지 않는다.
- [x] **Step 3: RED 확인**
  ```bash
  ./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.AccountBalanceSnapshotTest --offline --no-daemon
  ```
  예상: FAIL — 음수 입력에 예외가 나지 않음(`Expecting code to raise a throwable`), toString 이 sentinel 포함(assertion).
- [x] **Step 4: 구현** — 계약대로 불변식·toString·port KDoc.
- [x] **Step 5: GREEN 확인** — Step 3 명령, 이어 `./gradlew :domain:test :infrastructure:batch:test :apps:batch:test --offline --no-daemon`(기본값이라 기존 생성자 호출 43곳 무변경). 예상: exit 0.
- [x] **Step 6: Commit 제안** — `feat: 실계정 snapshot 에 잔고·USDT 시세 필드 추가`

---

### Task 3: 총 자산 계산 (AC3)

**Files:**
- Modify: `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlBreakdown.kt`
- Create: `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountAssetBreakdownTest.kt`

**코드 계약** — design §3.3 전문.

```kotlin
@Suppress("LongParameterList")
data class AccountAssetBreakdown(
    val totalKrw: BigDecimal?,
    val bithumbKrwBalance: BigDecimal?,
    val marginBalanceUsdt: BigDecimal?,
    val marginBalanceKrw: BigDecimal?,
    val availableBalanceUsdt: BigDecimal?,
    val availableBalanceKrw: BigDecimal?,
    val usdtLastPriceKrw: BigDecimal?,
) {
    override fun toString(): String = "AccountAssetBreakdown"

    companion object {
        fun of(
            korea: KoreaSpotAccountSnapshot,
            foreign: ForeignPerpAccountSnapshot,
            koreaValueKrw: BigDecimal,
            fxRate: BigDecimal,
        ): AccountAssetBreakdown
    }
}

// AccountPnlBreakdown 마지막 프로퍼티 추가 (toString 무변경 — assets 미노출)
val assets: AccountAssetBreakdown,
// AccountPnlBreakdown.of: assets = AccountAssetBreakdown.of(position.korea, position.foreign, korea.valueKrw, fx)
```

`of` 규칙은 design §3.3 표(값 전부). 반올림 없음.

- [x] **Step 1: stub** — `AccountAssetBreakdown` 과 `assets` 를 추가하고 `of` 는 7필드 모두 `null` 을 돌려준다(toString override 는 아직 없음).
- [x] **Step 2: 테스트 작성** — `AccountAssetBreakdownTest` (요건; fx `1400`, koreaValueKrw `42161000`)
  - 정상: krw `1234567`, balance `(10000.50, 8000.25)`, usdt `1412` → `totalKrw` `compareTo` `57396267.00` == 0, `marginBalanceKrw` `14000700.00`, `availableBalanceKrw` `11200350.00`, `bithumbKrwBalance` `1234567`, `usdtLastPriceKrw` `1412`, USDT 원값 그대로.
  - 정밀도: balance margin `0.00000001`, fx `1400.123456` → `marginBalanceKrw == 0.00000001 × 1400.123456` 이고 scale 이 곱의 scale 그대로(반올림 없음).
  - krw `0` → `totalKrw == koreaValueKrw + marginKrw`.
  - krw `null` → `totalKrw == null`, 바이낸스 값은 그대로 계산.
  - balance `null`(EXCHANGE_API) → 바이낸스 4필드·`totalKrw` `null`, `bithumbKrwBalance` 유지.
  - `MANUAL_ENV` + balance `(10000.50, 8000.25)` 가 들어 있어도 → 바이낸스 4필드·`totalKrw` `null`.
  - usdt `null` → `totalKrw` 가 usdt 있을 때와 `compareTo == 0`.
  - toString: `"AccountAssetBreakdown"`.
  - 통합: message-spec 입력(`AccountPnlAlertContentTest` 의 0.266 BTC fixture + 위 잔고)으로 `AccountPnlBreakdown.of(pnl).assets.totalKrw` 가 `57396267` 과 `compareTo == 0`, `AccountPnlBreakdown.of(pnl).toString()` 은 기존 `AccountPnlBreakdown(calculatedAt=…)` 그대로.
- [x] **Step 3: RED 확인**
  ```bash
  ./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.AccountAssetBreakdownTest --offline --no-daemon
  ```
  예상: FAIL — `totalKrw` 기대 `57396267.00`, 실제 `null`(assertion).
- [x] **Step 4: 구현** — design §3.3 표대로 `of`, `toString`, `AccountPnlBreakdown.of` 배선.
- [x] **Step 5: GREEN 확인** — Step 3 명령 + `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.AccountPnlBreakdownTest --offline --no-daemon`. 예상: exit 0.
- [x] **Step 6: Commit 제안** — `feat: 실계정 총 자산 계산 값 객체 추가`

---

### Task 4: 수익·정시 문구 자산 묶음 (AC4, R2)

**Files:**
- Modify: `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlAlertContent.kt`
- Modify: `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountPnlAlertContentTest.kt`
- Modify: `domain/src/test/kotlin/io/premiumspread/domain/alertmessage/AlertMessageSpecConformanceTest.kt` (200·202행 `listOf(3, 2, 3, 2, 1)` → `listOf(3, 2, 3, 2, 1, 3, 1)`) `[B안]¹`

**코드 계약**

```kotlin
// AccountPnlAlertContent — private 상수 (값 전부)
private const val ASSET_NONE = "없음"
private const val ASSET_MANUAL_HIDDEN = "수동 모드 — 표시 안 함"   // — = U+2014

// private 함수 (pnlMessage 에는 mailBlocks 에 assetSection(b, manual) 1개, rows 에 + assetRows(b, manual) 결합만 더한다)
private fun assetSection(b: AccountPnlBreakdown, manual: Boolean): MailBlock.Section
private fun assetRows(b: AccountPnlBreakdown, manual: Boolean): List<List<EmbedCell>>
```

메일 섹션 구조(전문, A/B 공통):

```kotlin
MailBlock.Section(
    "자산",
    listOf(
        MailBlock.Pairs(
            listOf(
                MailPair("총 자산", total),
                MailPair("바이낸스 증거금+손익", margin),
                MailPair("잔여 금액", "", listOf(MailPair("빗썸", bithumbKrw), MailPair("바이낸스", binanceAvailable))),
                MailPair("USDT 원화가", usdt),
            ),
        ),
    ),
)
```

Discord 행 구조(전문, A안) `[B안]²`:

```kotlin
listOf(
    listOf(EmbedCell("총 자산", total), EmbedCell("바이낸스 증거금+손익", margin), EmbedCell("USDT 원화가", usdt)),
    listOf(EmbedCell("잔여 금액", "빗썸 $bithumbKrw\n바이낸스 $binanceAvailable")),
)
```

값 문자열 규칙은 design §5.3 표(값 전부). 두 함수가 문자열을 공유하면 private **일반 class**(data class 금지)로 둔다.

- [x] **Step 1: stub 없음** — 기존 코드가 컴파일된 상태에서 테스트만 갱신해 RED 를 만든다.
- [x] **Step 2: 테스트 갱신** (요건; zone `Asia/Seoul`, calculatedAt `2026-10-20T00:00:00Z`)
  - `pnl(...)` fixture 에 파라미터 추가: `krwBalance: BigDecimal? = BigDecimal("1234567")`, `usdtPrice: BigDecimal? = BigDecimal("1412")`, `balance: ForeignPerpBalance? = ForeignPerpBalance(BigDecimal("10000.50"), BigDecimal("8000.25"))` 를 snapshot 에 넘긴다.
  - `profitBody` 끝(`순손익` 줄 다음 빈 줄, `기준 시각` 앞)에 아래 블록을 넣는다. 정렬 폭은 `MailDocument` 규칙 출력이다(`총 자산` 뒤 공백 15, `바이낸스 증거금+손익` 뒤 2, `잔여 금액` 은 줄 끝 공백 제거로 라벨만, 자식 `빗썸` 뒤 6·`바이낸스` 뒤 2, `USDT 원화가` 뒤 11):
    ```text
    ■ 자산
    총 자산               57,396,267원
    바이낸스 증거금+손익  14,000,700원 (10,000.50 USDT)
    잔여 금액
      - 빗썸      1,234,567원
      - 바이낸스  11,200,350원 (8,000.25 USDT)
    USDT 원화가           1,412원
    ```
  - `profitRows` 끝에 design §5.2 A안 두 행(값: `57,396,267원` / `14,000,700원 (10,000.50 USDT)` / `1,412원` / `빗썸 1,234,567원\n바이낸스 11,200,350원 (8,000.25 USDT)`) `[B안]³`.
  - `profitFields` 끝에 `gf("총 자산", …, true)`, `gf("바이낸스 증거금+손익", …, true)`, `gf("USDT 원화가", …, true)`, `gf("잔여 금액", …, false)` — 총 16개. 주석 "칸 11개 + …" → "칸 15개 + 2번째 행 뒤 행 끊기 1개" `[B안]⁴`.
  - 수익·정시 golden 두 테스트는 위 기대값을 함께 쓰므로 그대로 둔다.
  - 변형 테스트 추가(각각 메일 본문 줄 `contains` + Discord 자산 행 `isEqualTo`):
    - KRW `null` → `총 자산               없음`, `  - 빗썸      없음`, Discord `[총 자산] 없음`, `[잔여 금액] 빗썸 없음\n바이낸스 11,200,350원 (8,000.25 USDT)`.
    - balance `null`(EXCHANGE_API) → 총 자산·증거금+손익·바이낸스 잔여 `없음`, 빗썸 `1,234,567원`.
    - usdt `null` → `USDT 원화가           없음`, 총 자산 `57,396,267원` 유지.
    - `MANUAL_ENV`(balance `null`) → 증거금+손익·바이낸스 잔여 `수동 모드 — 표시 안 함`, 총 자산 `없음`, 빗썸 `1,234,567원`, USDT `1,412원`.
    - `MANUAL_ENV` 인데 balance 가 들어 있어도 같은 표기(Domain 이 무시).
    - 반올림: KRW `1234567.5` → `1,234,568원`(HALF_UP).
  - 기존 비밀 부재 테스트는 그대로 통과해야 한다.
  - `AlertMessageSpecConformanceTest` 수익·정시 `cellsPerRow` 를 `listOf(3, 2, 3, 2, 1, 3, 1)` 로 `[B안]¹`.
- [x] **Step 3: RED 확인**
  ```bash
  ./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.AccountPnlAlertContentTest --tests io.premiumspread.domain.alertmessage.AlertMessageSpecConformanceTest --offline --no-daemon
  ```
  예상: FAIL — `mail.body()` 에 `■ 자산` 블록 없음, `rows` 크기 5 vs 7, `행별 칸 수` `[3,2,3,2,1]` vs `[3,2,3,2,1,3,1]`(assertion).
- [x] **Step 4: 구현** — 계약대로. `manual` 은 기존 지역 변수(`b.foreign.source == MANUAL_ENV`)를 넘긴다. KDoc 첫 줄의 "4종" 문구는 그대로 둔다.
- [x] **Step 5: GREEN 확인** — Step 3 명령 + `./gradlew :domain:test --offline --no-daemon`. 예상: exit 0.
- [x] **Step 6: Commit 제안** — `feat: 수익·정시 알림에 자산 묶음 추가`

**`[B안]` 각주 (⑦에서 B안 채택 시에만)**
1. `cellsPerRow` → `listOf(3, 2, 3, 2, 1, 2, 2)`. RED 예상도 `[3,2,3,2,1]` vs `[3,2,3,2,1,2,2]`.
2. `assetRows` 반환 →
   ```kotlin
   listOf(
       listOf(EmbedCell("총 자산", total), EmbedCell("바이낸스 증거금+손익", margin)),
       listOf(EmbedCell("잔여 금액", "빗썸 $bithumbKrw\n바이낸스 $binanceAvailable"), EmbedCell("USDT 원화가", usdt)),
   )
   ```
3. `profitRows` 끝 두 행을 위 B안 배치로.
4. `profitFields` 끝 → `gf("총 자산", …, true)`, `gf("바이낸스 증거금+손익", …, true)`, `GOLDEN_ROW_BREAK`, `gf("잔여 금액", …, true)`, `gf("USDT 원화가", …, true)` — 총 **17개**. 주석 → "칸 15개 + 2번째·6번째 행 뒤 행 끊기 2개". 변형 테스트의 Discord 단정도 B안 칸 위치로.

---

### Task 5: 빗썸 KRW 잔고·USDT 원화가 (AC5)

**Files:**
- Create: `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/AccountBalanceLog.kt`
- Modify: `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/bithumb/BithumbPrivateAccountAdapter.kt`
- Modify: `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange/bithumb/BithumbPrivateAccountAdapterTest.kt` (테스트 추가)
- Create: `infrastructure/batch/src/test/resources/fixtures/bithumb-private/ticker-btc-usdt.json`

**코드 계약**

```kotlin
package io.premiumspread.infrastructure.batch.exchange

internal enum class BalanceItem { BITHUMB_KRW, BITHUMB_USDT_PRICE, BINANCE_USDT_ASSET }

internal enum class BalanceUnavailableReason { MISSING, NOT_NUMBER, NEGATIVE, NOT_POSITIVE, REQUEST_FAILED, TICKER_FALLBACK }

internal object AccountBalanceLog {
    fun unavailable(
        item: BalanceItem,
        reason: BalanceUnavailableReason,
        category: AccountReadFailureCategory? = null,
        errorClass: String? = null,
    )

    fun isCancellation(error: Throwable): Boolean

    fun rootErrorClass(error: Throwable): String
}
```

- 로그(값 전부): logger `AccountBalanceLog`, WARN, 형식 `Account balance unavailable: item={}, reason={}, category={}, error={}`, 없는 값은 `-`. 담지 않는 값: 금액·key·secret·signature·query·URL·응답 본문·예외 메시지·예외 객체.
- `isCancellation`: 원인 체인 최대 16단계에 `InterruptedException` 또는 `java.util.concurrent.CancellationException` 이 있거나 `Thread.currentThread().isInterrupted` 이면 true.
- `rootErrorClass`: 원인 체인(최대 16단계, `cause === current` 에서 멈춤) **맨 끝** 예외의 `javaClass.simpleName`. `REQUEST_FAILED`·`TICKER_FALLBACK` 의 `errorClass` 는 항상 이 값이다(design §4.3). 응답 해석 사유는 `category`·`errorClass` 를 넘기지 않는다(`-`).

빗썸 adapter 내부(시그니처 요건, private):

```kotlin
private data class Account(val quantity: BigDecimal, val averageBuyPrice: BigDecimal, val modified: Boolean, val krwBalance: BigDecimal?) {
    override fun toString(): String = "Account"
}
private data class Prices(val btc: BigDecimal, val usdt: BigDecimal?) {
    override fun toString(): String = "Prices"
}
private fun readPrices(): Prices   // readLastPrice() 를 대체
```

- 기존 private data class `Account`·`FeeCache`·`OrderRow` 중 이번에 고치는 `Account` 만 toString override 를 더한다(새 금액 필드 합류).
- `read()` 의 호출 순서·`@Synchronized` 무변경. snapshot 에 `krwBalance = account.krwBalance`, `usdtLastPriceKrw = prices.usdt`.
- 해석 규칙·fallback 은 design §4.1 두 표 그대로(값 전부). ticker query 상수는 `markets=KRW-BTC,KRW-USDT`, fallback query 는 `markets=KRW-BTC`.
- fallback 경계: 묶음 요청의 **`getJson` 호출**이 던진 `AccountReadException.category == UNEXPECTED` 이고 `!AccountBalanceLog.isCancellation(e)` 일 때만, 1회. `getJson` 이 성공한 뒤 해석 단계의 실패(배열 아님·`KRW-BTC` 행 문제)는 fallback 없이 `UNEXPECTED` 로 던진다. fallback 로그는 `unavailable(BITHUMB_USDT_PRICE, TICKER_FALLBACK, UNEXPECTED, rootErrorClass(e))`.
- 취소면 로그·재요청 없이 `throw e`.

fixture `ticker-btc-usdt.json`: 배열 2행, **순서를 `KRW-USDT` 먼저** 둔다(첫 행 규칙이 남아 있으면 깨지게). `KRW-USDT` `trade_price` `1412`, `KRW-BTC` `trade_price` 는 기존 `ticker.json` 과 같은 `8621000`.

- [x] **Step 1: stub** — `AccountBalanceLog.kt` 를 계약 시그니처로 만들고 본문은 빈 구현(`unavailable` 아무것도 안 함, `isCancellation` = false, `rootErrorClass` = `"-"`). adapter 는 아직 바꾸지 않는다.
- [x] **Step 2: 테스트 추가** — `BithumbPrivateAccountAdapterTest` (요건; 기존 dispatcher·`responses: MutableMap<String, () -> MockResponse>` 맵을 그대로 쓴다 — 시그니처 변경 없음. `@ExtendWith(OutputCaptureExtension::class)`. 요청 query 는 `recorded()`(기록된 `RecordedRequest`)의 `requestUrl!!.queryParameter("markets")` 로 단정한다)
  - `accountsBody = fixture("accounts.json")`(KRW `balance` `10981650.10635`, `locked` `98246`) → `krwBalance` `compareTo` `10981650.10635` == 0 (locked 미포함).
  - 기본 `accounts(balance, locked)` helper(BTC 행만) → `krwBalance == 0`, WARN 없음.
  - KRW 행 `balance` `"-1"` → `krwBalance == null`, `read()` 성공, 출력에 `item=BITHUMB_KRW, reason=NEGATIVE, category=-, error=-`. `"abc"` → `reason=NOT_NUMBER`.
  - `responses["/v1/ticker"] = { json(fixture("ticker-btc-usdt.json")) }` → `lastPrice == 8621000`, `usdtLastPriceKrw == 1412`, `/v1/ticker` 요청 1건이고 `markets == "KRW-BTC,KRW-USDT"`.
  - 기존 `ticker.json`(BTC 만) → `usdtLastPriceKrw == null`, `reason=MISSING`, `lastPrice == 8621000`.
  - USDT `trade_price` `0` → `reason=NOT_POSITIVE`; `"x"` → `reason=NOT_NUMBER`.
  - fallback(호출 횟수 카운터 방식으로 확정): 테스트 지역 `var tickerCalls = 0`, `responses["/v1/ticker"] = { tickerCalls += 1; if (tickerCalls == 1) MockResponse().setResponseCode(404) else json(fixture("ticker.json")) }` → `read()` 성공, `/v1/ticker` 요청 2건이고 기록된 `markets` 가 순서대로 `KRW-BTC,KRW-USDT`·`KRW-BTC`, `usdtLastPriceKrw == null`, 출력에 `item=BITHUMB_USDT_PRICE, reason=TICKER_FALLBACK, category=UNEXPECTED, error=NotFound`.
  - fallback 의 두 번째 요청도 404(`responses["/v1/ticker"] = { MockResponse().setResponseCode(404) }`) → `read()` 가 `UNEXPECTED` 로 실패, `/v1/ticker` 요청 2건.
  - fallback 경계: 200 응답 `[{"market":"KRW-USDT","trade_price":1412}]`(KRW-BTC 행 없음) → `read()` 가 `UNEXPECTED` 로 실패, `/v1/ticker` 요청 1건, `TICKER_FALLBACK` 로그 없음.
  - 묶음 요청 429 → `NETWORK` 실패, `/v1/ticker` 요청 1건(fallback 없음).
  - **fallback 취소**: `val testThread = Thread.currentThread()`, `responses["/v1/ticker"] = { testThread.interrupt(); MockResponse().setResponseCode(404).setBodyDelay(500, TimeUnit.MILLISECONDS) }` → `read()` 가 `AccountReadException` 을 던지고, `/v1/ticker` 요청 1건, `/v1/orderbook` 요청 0건, 출력에 `TICKER_FALLBACK` 없음. `finally { Thread.interrupted() }`. (`getJson` 199-202행이 인터럽트를 `UNEXPECTED` 로 바꾸므로 `isCancellation` 검사만이 재요청을 막는다.)
  - 위 모든 출력에 `test-access-key`·`test-secret-key`·`10981650`·`1412`·`8621000` 이 없다.
  - 기존 `호가는 … 마지막으로 조회한다` 경로 목록 테스트는 그대로 통과.
- [x] **Step 3: RED 확인**
  ```bash
  ./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbPrivateAccountAdapterTest --offline --no-daemon
  ```
  예상: FAIL — `krwBalance` 기대 `10981650.10635` 실제 `null`, `markets` 기대 `KRW-BTC,KRW-USDT` 실제 `KRW-BTC`, `ticker-btc-usdt.json` 에서 `lastPrice` 기대 `8621000` 실제 `1412`(assertion).
- [x] **Step 4: 구현** — `AccountBalanceLog` 본문, adapter 변경. `getJson` 의 예외 정규화·`statusCategory` 는 바꾸지 않는다.
- [x] **Step 5: GREEN 확인** — Step 3 명령 + `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.exchange.bithumb.*' --offline --no-daemon` + `./gradlew architectureTest --offline --no-daemon`. 예상: exit 0.
- [x] **Step 6: Commit 제안** — `feat: 빗썸 KRW 잔고와 USDT 원화가를 계정 조회에 추가`

---

### Task 6: 바이낸스 `/fapi/v3/account` (AC6, R4)

**Files:**
- Modify: `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceFuturesPrivateAccountAdapter.kt`
- Modify: `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceFuturesPrivateAccountAdapterTest.kt` (테스트 추가)
- Create: `infrastructure/batch/src/test/resources/fixtures/binance-private/account.json`
- Modify: `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceFuturesManualAccountAdapterTest.kt` (`출처는 MANUAL_ENV` 테스트에 `balance == null` 단정 1줄 추가)

**코드 계약** (private, 시그니처 요건)

```kotlin
/** /fapi/v3/account 의 USDT 잔고. 요청 실패·해석 실패는 null + WARN. 취소는 다시 던진다. */
private fun readBalance(): ForeignPerpBalance?

/** 응답 해석. 예외를 던지지 않고 사유를 돌려준다. */
private fun parseBalance(root: JsonNode): BalanceParse

private sealed interface BalanceParse {
    class Ok(val balance: ForeignPerpBalance) : BalanceParse
    class Unavailable(val reason: BalanceUnavailableReason) : BalanceParse
}
```

- `read()` 순서(전문): `readPosition()` → (`resolveHistory`) → `readTakerFeeRate()` → `readBalance()` → (`commissionUsdt` 의 BNB price 가 필요하면 그 호출) → `client.price(SYMBOL)` → `client.askDepth(SYMBOL)`. depth 마지막·`/fapi/v2/ticker/price` 가 depth 앞이라는 기존 단정을 유지한다.
- 요청: `signedGet("/fapi/v3/account", "")`.
- 해석(값 전부): 최상위 `assets` 배열 → `asset == "USDT"` 첫 항목 → `marginBalance`·`availableBalance` (문자열·숫자 모두 허용). 사유 매핑은 design §4.2 표.
- `catch (e: AccountReadException)` 만 잡는다. `AccountBalanceLog.isCancellation(e)` 면 `throw e`(로그 없음). 아니면 `unavailable(BINANCE_USDT_ASSET, REQUEST_FAILED, e.category, AccountBalanceLog.rootErrorClass(e))` 후 `null`. 해석 사유는 `unavailable(BINANCE_USDT_ASSET, reason)`(category·error `-`).
- `ForeignPerpBalance` 생성 전에 음수를 검사한다(생성자 `IllegalArgumentException` 이 `read()` 로 새지 않게).
- snapshot 에 `balance = readBalance()` 결과.
- 소스·주석에 `withdraw`·`deposit`·`transfer` 문자열 금지(R1).

fixture `account.json`(바이낸스 공식 문서 v3 응답 shape 축약): 최상위 `totalWalletBalance` 등 일부 + `assets` 2항목 — `BNB`(`marginBalance` `"0.5"`)를 **먼저**, 그다음 `USDT`(`walletBalance` `"10100.50"`, `unrealizedProfit` `"-100.00"`, `marginBalance` `"10000.50"`, `availableBalance` `"8000.25"`). `positions` 는 빈 배열. `maxWithdrawAmount` 필드는 fixture 에도 넣지 않는다.

- [x] **Step 1: stub 없음** — 새 필드는 Task 2 에서 이미 있다. 테스트만 추가하면 기존 코드에서 assertion 으로 실패한다.
- [x] **Step 2: 테스트 추가** — `BinanceFuturesPrivateAccountAdapterTest` (요건; 기존 dispatcher 에 `"/fapi/v3/account" -> json(accountBody)` 를 더하고 `accountBody` 기본값 = `account.json`. 기존 `responses` 맵으로 경로별 응답을 덮어쓴다. `OutputCaptureExtension`)
  - 정상: `balance!!.marginBalanceUsdt` `compareTo` `10000.50`, `availableBalanceUsdt` `8000.25`(BNB 항목이 먼저여도 USDT 선택).
  - 요청: `/fapi/v3/account` 1건, 헤더 `X-MBX-APIKEY` 존재, query 에 `recvWindow=`·`timestamp=`·`signature=` 존재, 메서드 GET.
  - 순서: `/fapi/v1/time` 제외 경로 목록에서 `/fapi/v3/account` 가 `/fapi/v1/commissionRate` 뒤, `/fapi/v2/ticker/price` 앞, 마지막은 `/fapi/v1/depth`.
  - 실패 흡수(각각 `read()` 성공, `signedQuantity`·`askLevels`·`lastPrice` 정상, `balance == null`, 출력에 `item=BINANCE_USDT_ASSET` 와 아래 값):
    - 401 `{"code":-2015,…}` → `reason=REQUEST_FAILED, category=BINANCE_AUTH, error=Unauthorized`
    - 503 → `reason=REQUEST_FAILED, category=NETWORK, error=ServiceUnavailable`
    - 깨진 JSON → `reason=REQUEST_FAILED, category=UNEXPECTED, error=JsonParseException`
    - `assets` 에 USDT 없음 / `assets` 없음 → `reason=MISSING, category=-, error=-`
    - `availableBalance` 누락 / `"abc"` → `reason=NOT_NUMBER`
    - `marginBalance` `"-1"` → `reason=NEGATIVE`
  - `-1021` 1회 후 성공 → 서버 시각 재측정 후 `balance` 정상(기존 재시도 경로 재사용 확인).
  - **취소(방식 확정)**: `val testThread = Thread.currentThread()`, `responses["/fapi/v3/account"] = { testThread.interrupt(); json(fixture("account.json")).setBodyDelay(1, TimeUnit.SECONDS) }` → dispatcher 가 `/fapi/v3/account` 요청을 받는 순간 테스트 스레드가 interrupt 되고 `block()` 이 깨진다. 단정: `read()` 가 `AccountReadException` 을 던진다 / `/fapi/v1/depth` 요청 0건 / 출력에 `reason=REQUEST_FAILED` WARN 없음. `finally { Thread.interrupted() }`.
  - 출력 비노출: 위 모든 경우 출력에 테스트 API key·secret 문자열, `signature=`, `10000.50`·`8000.25` 가 없다.
  - 기존 테스트는 dispatcher 에 경로가 추가된 것 외에는 수정하지 않는다.
  - `BinanceFuturesManualAccountAdapterTest` `출처는 MANUAL_ENV`: `assertThat(snapshot.balance).isNull()` 추가. `한 회차 요청은 ticker 다음 depth 두 건…` 은 그대로(R4).
- [x] **Step 3: RED 확인**
  ```bash
  ./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapterTest --offline --no-daemon
  ```
  예상: FAIL — `balance` 기대 not null 실제 `null`, `/fapi/v3/account` 요청 0건(assertion). 취소 테스트는 기존 코드에서 `/fapi/v3/account` 를 부르지 않으므로 `read()` 가 정상 종료해 "예외 기대" assertion 으로 실패한다.
- [x] **Step 4: 구현** — 계약대로.
- [x] **Step 5: GREEN 확인**
  ```bash
  ./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.exchange.binance.*' --offline --no-daemon
  ./gradlew architectureTest --offline --no-daemon
  ```
  예상: 둘 다 exit 0.
- [x] **Step 6: Commit 제안** — `feat: 바이낸스 USDT 잔고를 v3 account 로 조회`

---

### Task 7: Job 실패 분리 회귀 방어선 (R5)

**Files:**
- Modify: `apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJobTest.kt` (fake 에 var 추가만)
- Create: `apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJobBalanceTest.kt`

**코드 계약** — production 무변경. fake 확장(전문):

```kotlin
// FakeKorea
var krwBalance: BigDecimal? = null
var usdtPrice: BigDecimal? = null
// read(): KoreaSpotAccountSnapshot(..., krwBalance = krwBalance, usdtLastPriceKrw = usdtPrice)

// FakeForeign
var balance: ForeignPerpBalance? = null
// read(): ForeignPerpAccountSnapshot(..., balance = balance)
```

- [x] **Step 1: 테스트 작성** — `AccountPnlMonitorJobBalanceTest` (요건; `MutableClock(Instant.parse("2026-10-20T03:00:30Z"))`, zone 기본 `Asia/Seoul`, `hourlyReportEnabled = true`, 기존 `FakeStore`·`FakeSender`·mockk `JobExecutor` 패턴은 `AccountPnlMonitorJobHourlyReportTest` 와 같게)
  - 잔고·USDT 모두 `null`(기본) 1회 → `JobResult.Success`, 저장 행 eventKey 에 `account-pnl-profit-`·`account-pnl-hourly-` 가 있고, 수익 메일 본문에 `■ 자산` 과 `총 자산               없음` 이 있다.
  - 같은 상태로 1분 간격 7회(6분 경과) → 모든 회차 `Success`, `account-pnl-outage-` 행 0개, `store.state.outage.lastSuccessAt` == 마지막 회차 시각.
  - 잔고 채움(`krwBalance` `1000000`, `usdtPrice` `1412`, `balance` `(1000, 900)`) → 수익 메일 본문에 `없음` 이 `■ 자산` 섹션 안에 없다.
- [x] **Step 2: 실행**
  ```bash
  ./gradlew :apps:batch:test --tests io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobBalanceTest --offline --no-daemon
  ```
  예상: exit 0 (회귀 방어선 — RED 면제. Task 4 이전에 돌리면 `■ 자산` 단정이 실패한다는 점이 이 테스트가 무언가를 잰다는 근거다).
- [x] **Step 3:** `./gradlew :apps:batch:test --offline --no-daemon` exit 0.
- [x] **Step 4: Commit 제안** — `test: 잔고 조회 실패가 실계정 알림과 중단 판정에 영향 없음을 고정`

---

### Task 8: 통합·경계·전체 gate

**Files:** 없음 (dod 증거 로그 append)

> 완료 메모: 이 태스크는 ⑩ qa 실행(dod 증거 로그 커밋 `docs: 자산 알림 검증 증거 기록`)으로 흡수됐다.

- [x] **Step 1: 실행**
  ```bash
  ./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon
  ./gradlew jacocoTestCoverageVerification --offline --no-daemon
  ```
- [x] **Step 2: 예상 결과** — 둘 다 exit 0. 통합 테스트 중 `OwnerAlertDiscordLayoutIntegrationTest`·`OwnerAlertDiscordLayoutStoreIntegrationTest`·`AccountPnlAlertDurabilityIntegrationTest`·`OwnerAlertDeliveryIntegrationTest`·`OwnerAccountAlertIntegrationTest` 는 무수정으로 통과해야 한다(design §6.3). Docker 부재로 못 돌린 task 는 green 으로 기록하지 않고 `BLOCKED` 로 적는다.
- [x] **Step 3:** ktlint·detekt 는 로컬 Gradle task 가 없다. `.ci-tools/` jar 가 있으면 `java -jar .ci-tools/ktlint.jar '**/src/**/*.kt' '!**/build/**'` 와 CI 와 같은 detekt 인자로 돌리고, 없으면 PR CI 결과(R9)로 판정한다고 기록한다.
- [x] **Step 4: Commit 제안** — 증거 로그만 있으면 `docs: 자산 알림 검증 증거 기록`

---

### Task 9: 문서 (AC7)

**Files:**
- Modify: `docs/work/alert-message-template/message-spec.md`
- Modify: `docs/work/alert-message-template/design.md`
- Modify: `docs/runbooks/account-pnl-alert.md`

**요건**

- `message-spec.md` "실계정 순손익" → "수익 알림"·"정시 보고" 두 절:
  - Discord rows 에 `6. [총 자산] 57,396,267원 | [바이낸스 증거금+손익] 14,000,700원 (10,000.50 USDT) | [USDT 원화가] 1,412원`, `7. [잔여 금액] 빗썸 1,234,567원 ⏎ 바이낸스 11,200,350원 (8,000.25 USDT)` `[B안]⁵`.
  - 메일 코드블록의 `순손익` 줄 다음에 design §5.1 의 `■ 자산` 블록(빈 줄로 구분).
  - 두 절 각각 아래에 한 줄 메모: 값이 없으면 `없음`, 바이낸스 수동 모드면 바이낸스 두 칸 `수동 모드 — 표시 안 함`·총 자산 `없음`(design §5.3 링크).
  - 기존 줄(1~5행·`■ 합산` 까지)은 바꾸지 않는다.
- `design.md`(#102) 두 곳에 개정 메모. 규칙·원문 줄은 고치지 않는다.
  - §3.3 규칙 2 바로 아래 인용 블록 1단락: `> 개정 (#105, 2026-11-02): 채움 칸은 non-inline 행 끊기 칸 EmbedDocument.ROW_BREAK = DiscordField(ZWSP, ZWSP, inline = false) 로 바뀌었다. 조건은 같다. 근거·영향은 docs/work/account-balance-alert/design.md §6.`
  - §6.2 **316행** `- 바이낸스 포지션 출처(거래소 API/수동 env)는 수익·정시 본문에 쓰지 않는다(U5).` 바로 아래 인용 블록 1단락: `> 개정 (#105, 2026-11-02, 사용자 합의 7): 예외 — 자산 칸의 바이낸스 두 값(증거금+손익·바이낸스 잔여)은 수동 모드에서 "수동 모드 — 표시 안 함" 으로 쓰므로 manual 모드가 드러난다. 그 밖 칸은 U5 그대로. docs/work/account-balance-alert/design.md §12.`
- `account-pnl-alert.md`:
  - `## Key 발급` 표 바이낸스 행 "허용할 권한" 칸: `Enable Reading 만 — 포지션·체결·펀딩·수수료율과 계정 잔고(`/fapi/v3/account`) 조회가 모두 이 권한이다. 선물 거래·출금·이체 off`. (runbook 은 architecture 스캔 대상이 아니므로 "출금·이체" 단어 사용 가능.)
  - `### 알림 메시지 예` 메일 코드블록에 `■ 자산` 블록, Discord 설명 문장을 "일곱 줄의 칸(… / 순손익 1칸 / 총 자산·바이낸스 증거금+손익·USDT 원화가 3칸 / 잔여 금액 1칸)" 으로 `[B안]⁶`.
  - `## 바이낸스 수동 입력 모드 (manual)` → `### 메일 표기`:
    - **350행** `수익·정시 메일과 Discord 는 바이낸스 출처(api/manual)를 따로 표시하지 않는다(#102).` 뒤에 문장 추가: `예외: 자산 칸(#105)의 바이낸스 두 값은 manual 모드에서 "수동 모드 — 표시 안 함" 으로 쓰므로 이 칸에서만 모드가 드러난다.` 이어지는 "값을 입력했으면 두 모드의 표기가 같고…" 문장은 "기존 칸은 값을 입력했으면…" 으로 범위를 좁힌다.
    - **358행** `manual 모드인지 확인하려면 알림이 아니라 batch 기동 설정(`BINANCE_MODE`)을 본다.` → `manual 모드는 자산 칸의 "수동 모드 — 표시 안 함" 으로도 보이지만, 확인의 정본은 batch 기동 설정(`BINANCE_MODE`)이다.`
    - 표(머리 `| 위치 | 값 있음 (api·manual 공통) | 값 없음 — api | 값 없음 — manual |`)에 행 2개를 같은 열 배치로 추가:
      ```text
      | 메일 `■ 자산` → `바이낸스 증거금+손익`·`잔여 금액` 하위 `바이낸스` / Discord 같은 칸 | `14,000,700원 (10,000.50 USDT)` ※ | `없음` | `수동 모드 — 표시 안 함` |
      | 메일 `■ 자산` → `총 자산` / Discord `총 자산` 칸 | `57,396,267원` ※ | `없음` | `없음` |
      ```
      표 바로 아래 각주 한 줄: `※ 자산 행의 "값 있음" 은 api 모드에서만 나온다 — manual 모드는 잔고를 조회하지 않으므로(/fapi/v3/account 미호출) 항상 오른쪽 열이다.`
  - `## 동작 요약` 표 행 추가: `자산 표시` | `총 자산 = 빗썸 BTC 평가액 + 빗썸 KRW 잔고(주문 잠금 제외) + 바이낸스 증거금+손익 × 환율. USDT 원화가는 표시만. 잔고·USDT 시세 조회 실패는 그 칸만 '없음'(총 자산 포함)이고 알림·중단 판정에 영향 없음. 실패는 WARN 'Account balance unavailable: item=…, reason=…'`.
  - `## 중단 메일 범주별 조치` 표 아래 한 줄: 잔고·USDT 시세 실패는 중단 메일 범주가 아니다 — 로그 `Account balance unavailable` 를 본다.
  - 새 절 `## 자산 칸 Discord·잔고 확인 체크리스트 (#105, account-balance-alert AC8·AC9)`: dod AC8 (a)~(e), AC9 세 항목을 체크박스로. 결과는 PR 코멘트.
- [x] **Step 1:** 위 수정.
- [x] **Step 2: 확인(T2, dod AC7)**
  ```bash
  grep -c "■ 자산" docs/work/alert-message-template/message-spec.md
  grep -n "/fapi/v3/account" docs/runbooks/account-pnl-alert.md
  grep -n "#105" docs/work/alert-message-template/design.md
  grep -n "수동 모드 — 표시 안 함" docs/runbooks/account-pnl-alert.md
  ```
  예상: `2` 이상 / Key 발급 표 행 포함 1줄 이상 / 2줄 이상(§3.3 구간 1·§6.2 구간 1) / `### 메일 표기` 구간 1줄 이상. 결과를 dod AC7 증거 로그에 붙인다.
- [x] **Step 3:** `bash docs/check-documentation.sh` exit 0 (정본 문서를 건드리지 않았음을 재확인).
- [x] **Step 4: Commit 제안** — `docs: 자산 알림 문구 정본·runbook·#102 렌더 규칙과 U5 개정 메모 갱신`

**`[B안]` 각주 (⑦에서 B안 채택 시에만)**
5. message-spec rows → `6. [총 자산] 57,396,267원 | [바이낸스 증거금+손익] 14,000,700원 (10,000.50 USDT)`, `7. [잔여 금액] 빗썸 1,234,567원 ⏎ 바이낸스 11,200,350원 (8,000.25 USDT) | [USDT 원화가] 1,412원`.
6. runbook Discord 설명 → "일곱 줄의 칸(… / 순손익 1칸 / 총 자산·바이낸스 증거금+손익 2칸 / 잔여 금액·USDT 원화가 2칸)".

---

## Self-Review

- 합의 1~10 → design §0 표, 각 항목이 Task 2~6·9 와 dod AC 에 대응한다. 합의 10(제외)은 Global Constraints·dod 제외 목록. 합의 7 의 #102 U5 개정 기록은 Task 9.
- 어느 태스크에도 정의되지 않은 타입 참조 없음: `ForeignPerpBalance`(2), `AccountAssetBreakdown`(3), `ROW_BREAK`·`GOLDEN_ROW_BREAK`(1), `AccountBalanceLog`·`BalanceItem`·`BalanceUnavailableReason`(5), `BalanceParse`(6).
- 새 data class 의 `toString()` 노출 프로퍼티: `ForeignPerpBalance`·`KoreaSpotAccountSnapshot`·`ForeignPerpAccountSnapshot`(Task 2), `AccountAssetBreakdown`(Task 3), 빗썸 private `Account`·`Prices`(Task 5). `BalanceParse` 는 data class 가 아니다.
- 취소 테스트 두 개(Task 5·6)는 같은 방식(dispatcher 가 대상 요청을 받는 순간 테스트 스레드 interrupt + 지연 응답)이고 열린 대안 문구가 없다.
- 태스크 순서: Domain 렌더러·불변식(1·2) → 계산(3) → 문구(4) → adapter(5·6) → Job(7) → 통합·architectureTest(8) → 문서(9). Facade 없음.
- B안 각주는 Task 4(1~4)·Task 9(5·6) 여섯 곳뿐이다. 메일·Domain·adapter 는 A/B 무관.
