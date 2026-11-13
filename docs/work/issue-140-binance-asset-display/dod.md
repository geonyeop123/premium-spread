---
feature: 실계정 알림 자산 표기 정합 — 바이낸스 평가액을 증거금+손익 기준으로
slug: issue-140-binance-asset-display
status: FROZEN
frozen_at: 2026-11-13 (사용자 계약 승인 — 자산 칸 `바이낸스 증거금+손익` 행 삭제)
verdict_commit:
source: https://github.com/geonyeop123/premium-spread/issues/140
---

## 범위

**포함**

- 수익·정시 알림(메일·Discord) 바이낸스 블록 `평가액`을 증거금+손익(원화 + USDT)으로 변경 (design D1·D2)
- 같은 블록에 `포지션 규모 {명목 USDT} ({n}x)` 줄 추가, 레버리지 없으면 배수 생략 (D3·D4)
- `ForeignLegBreakdown` 필드 정리(`notionalUsdt`·`leverage`) (D5)
- 자산 칸 `바이낸스 증거금+손익` 행 삭제 (D7)
- 문구 정본 `message-spec.md`와 runbook `account-pnl-alert.md` 갱신 (D6)

**제외** *(명시적으로 하지 않는 것 — scope creep 차단선)*

- 순손익·김프·총 자산 계산 변경
- 청산 근접·중단·복구 알림 문구
- 레버리지 조회 방식(#138) 변경

## 기존 gate 조사

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있음 — root `architectureTest`(모듈 경계·domain 의존 금지) | 예. 회귀 방어선 |
| 문구 golden 테스트 | 있음 — `AccountPnlAlertContentTest`(수익·정시 메일 본문·Discord rows·fields golden) | 예. AC1~AC4 를 같은 클래스에서 검증 |
| Breakdown 단위 테스트 | 있음 — `AccountPnlBreakdownTest`, `AccountAssetBreakdownTest` | 예. 회귀 방어선(필드 변경 반영) |
| lint | 있음 — CI `quality-gate.yml` ktlint·detekt | 예. 회귀 방어선 (CI 확인) |
| 문서 검사 | 있음 — `docs/check-documentation.sh`(정본 문서), `./gradlew harnessCheck` | 문서 검사는 회귀 방어선. 문구 내용 검사는 없음 → AC5 는 T2 |

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | 픽스처(margin 10,000.50 USDT·환율 1,400·레버리지 5)의 수익·정시 알림에서 메일 바이낸스 블록이 `평가액  14,000,700원 (10,000.50 USDT)` 다음 줄 `포지션 규모  29,579.20 USDT (5x)`이고, Discord 바이낸스 칸 값이 `…\n평가액 14,000,700원 (10,000.50 USDT)\n포지션 규모 29,579.20 USDT (5x)`로 끝나며, 메일 `■ 자산`과 Discord rows 어디에도 `바이낸스 증거금+손익` 라벨이 없다 | 이슈 수용 기준 1·2, design D1·D3, D7(계약 승인 결정) | `요구` | T1 | `기존`(golden 갱신) `io.premiumspread.domain.accountpnl.AccountPnlAlertContentTest` (`수익 알림 golden`, `정시 보고 golden`) | `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.AccountPnlAlertContentTest --offline` | exit 0 |
| AC2 | 환율이 소수(1,387.37)이고 원화 잔고·margin 이 원 미만 자리를 갖는 입력에서, 메일에 표시된 빗썸 평가액 + 빗썸 잔여 원화 + 바이낸스 평가액(원화)의 합과 표시된 총 자산의 차이가 2원 이하이다 | 이슈 수용 기준 1(합 = 총 자산, 표시 반올림 제외), design 검산 | `요구` | T1 | `신규테스트` 같은 클래스 (`세 평가액의 합은 총 자산과 같다`) | AC1 과 같음 | exit 0 |
| AC3 | symbolConfig 가 null 이면 메일·Discord 모두 `포지션 규모 29,579.20 USDT`이고 `x)`가 없다 | 이슈 수용 기준 2, design D4 | `요구` | T1 | `신규테스트` 같은 클래스 (`레버리지를 모르면 포지션 규모에 배수를 붙이지 않는다`) | AC1 과 같음 | exit 0 |
| AC4 | 바이낸스 잔고가 null 이면 블록 `평가액`이 `없음`, 수동 모드면 블록 `평가액`이 `수동 모드 — 표시 안 함`이고 자산 칸 표기(총 자산 `없음`·`잔여 금액` 바이낸스 `없음`/숨김)는 그대로다 | 이슈 범위(수동 모드 숨김), design D2 — 잔고 null 표기는 #105 규칙을 따른 추론 | `추론` | T1 | `기존`(단언 추가) 같은 클래스 (`바이낸스 잔고가 없으면 총 자산 증거금 바이낸스 잔여가 없음이다`, `수동 모드는 바이낸스 칸을 표시하지 않고 총 자산은 없음이다`) | AC1 과 같음 | exit 0 |
| AC5 | 문구 정본 `message-spec.md` 수익·정시 두 절과 runbook `account-pnl-alert.md` 예시에 `포지션 규모` 줄이 있고, 바이낸스 블록 `평가액` 예시가 `14,000,700원 (10,000.50 USDT)`이다 | 이슈 범위 "문구 정본" | `요구` | T2 | 재현 명령 | `grep -n "포지션 규모" docs/work/alert-message-template/message-spec.md docs/runbooks/account-pnl-alert.md` | message-spec 4건 이상(메일·Discord × 2절), runbook 1건 이상 |

**기준 개수**: 5개

**분할하지 않는 이유**: 해당 없음(8개 이하)

**티어 강등 사유**

- AC5 → T2: 산문 문서의 문구 일치는 코드 산출물이 없다. 문구 정본을 테스트가 읽게 만드는 것은 이 이슈 범위를 넘는다. Gate 1 승인 대상.

## 회귀 방어선

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | domain 전체 테스트(Breakdown·자산 계산 포함) | `./gradlew :domain:test --offline` |
| R2 | 모듈 경계 | `./gradlew architectureTest --offline` |
| R3 | 정본 문서 검사 | `bash docs/check-documentation.sh` |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

### AC1 (2026-11-13, 0d5fbd9)
- RED: Breakdown 필드 교체는 컴파일 실패(`Unresolved reference 'valueKrw'/'valueUsdt'`)로 확인. main 을 컴파일만 되게 맞춘 뒤 `수익 알림 golden`·`정시 보고 golden` 이 기대 문자열 불일치로 FAILED (알림 테스트 8건 FAILED / 27).
- GREEN: `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.AccountPnlAlertContentTest --offline` 통과.

### AC2 (2026-11-13, 0d5fbd9)
- RED: `세 평가액의 합은 총 자산과 같다` FAILED (바이낸스 평가액이 명목가였음). GREEN: 위 명령 통과 (|합 − 총 자산| ≤ 2).

### AC3 (2026-11-13, 0d5fbd9)
- RED: `레버리지를 모르면 포지션 규모에 배수를 붙이지 않는다` FAILED. GREEN: 위 명령 통과.

### AC4 (2026-11-13, 0d5fbd9)
- RED: `바이낸스 잔고가 없으면 …`·`수동 모드는 …` FAILED. GREEN: 위 명령 통과. 전체 `:domain:test` 695건 중 실패 2건은 `AlertMessageSpecConformanceTest`(수익·정시) — message-spec.md 가 아직 옛 행 수 [3,2,3,2,1,2,2]; Task 3 에서 해소 예정.

### AC5 (Task 3)
```
docs/runbooks/account-pnl-alert.md:180 포지션 규모  29,579.20 USDT (5x)
docs/runbooks/account-pnl-alert.md:511, 582, 584 (자산 설명·체크리스트 b·d)
docs/work/alert-message-template/message-spec.md:23, 50, 72, 86, 113, 135
```
spec 6회, runbook 4회.

### R1/R3 (Task 3)
- `:domain:test` 전체 BUILD SUCCESSFUL, AlertMessageSpecConformanceTest 수익/정시 통과. 테스트 안의 하드코딩 cellsPerRow [3,2,3,2,1,2,2] 를 [3,2,3,2,1,1,2] 로 갱신(구 레이아웃 잔재).
- `bash docs/check-documentation.sh` exit 0.
