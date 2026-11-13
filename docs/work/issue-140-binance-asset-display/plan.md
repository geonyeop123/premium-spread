# #140 plan — 결정 수준

design D1~D7, dod AC1~AC5 를 구현한다. 모든 태스크는 `domain` 모듈 또는 문서만 바꾼다.

## Task 1 — Breakdown 필드 정리 (D5)

- 파일: `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlBreakdown.kt`, `domain/src/test/.../AccountPnlBreakdownTest.kt`
- 시그니처: `ForeignLegBreakdown` 에서 `valueUsdt`·`valueKrw` 제거, `notionalUsdt: BigDecimal`(= foreignExit × foreignQty) 와 `leverage: Int?`(= `foreignSnapshot.symbolConfig?.leverage`) 추가. toString 은 금액·레버리지를 노출하지 않는다(기존 형식 유지).
- 테스트: `AccountPnlBreakdownTest` 기존 `valueUsdt`/`valueKrw` 단언을 `notionalUsdt == 29579.2` 로 바꾸고, `바이낸스 레버리지는 symbolConfig 에서 오고 없으면 null 이다` 추가.
- DoD: 회귀 R1 (AC1·AC3 의 입력)
- 검증: `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.AccountPnlBreakdownTest --offline`

## Task 2 — 알림 문구 (D1·D2·D3·D4·D7)

- 파일: `AccountPnlAlertContent.kt`, `AccountPnlAlertContentTest.kt`
- 바이낸스 블록 pairs: `평단`·`현재가`·`손익`·`평가액`(= 자산 칸에서 쓰던 `usdtPair(marginBalanceKrw, marginBalanceUsdt)` 와 같은 문자열 — 수동/없음 규칙 포함)·`포지션 규모`(`"${usdt2(notionalUsdt)}" + leverage?.let { " (${it}x)" }`).
- 자산: 메일 `■ 자산`에서 `바이낸스 증거금+손익` pair 삭제. Discord 자산 첫 행은 `[총 자산]` 1칸. `AssetTexts.margin` 은 바이낸스 블록 평가액으로 재사용한다.
- 테스트(RED 먼저):
  - golden `profitBody`·`profitRows`·`profitFields` 갱신 — 픽스처에 `symbolConfig = ForeignPerpSymbolConfig(5, ISOLATED)` 기본값 추가 → AC1
  - `세 평가액의 합은 총 자산과 같다` — fx 1387.37, krwBalance·margin 에 원 미만 자리, 메일 본문에서 네 값을 파싱해 |합 − 총 자산| ≤ 2 → AC2
  - `레버리지를 모르면 포지션 규모에 배수를 붙이지 않는다` → AC3
  - 기존 `바이낸스 잔고가 없으면 …`·`수동 모드는 …`·`빗썸 KRW 잔고가 없으면 …` 테스트의 자산 행 기대값을 새 배치로 바꾸고 블록 평가액 단언 추가 → AC4
- 검증: `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.AccountPnlAlertContentTest --offline`

## Task 3 — 문구 정본·runbook (D6·D7)

- `docs/work/alert-message-template/message-spec.md` 수익·정시 두 절: Discord 행 목록·메일 예시의 바이낸스 블록(평가액 → `14,000,700원 (10,000.50 USDT)`, `포지션 규모 29,579.20 USDT (5x)` 추가)과 자산 행(증거금+손익 삭제), 자산 칸 주석 문장.
- `docs/runbooks/account-pnl-alert.md`: 메일 예시(§173~193), Discord 칸 수 설명(§203), 모드별 표(§398), 자산 표시 설명(§511), 체크리스트 (b)(d)(g).
- 검증: AC5 grep, `bash docs/check-documentation.sh`
