---
feature: 거래 준비 (Trade Preparation)
slug: private-live-autotrader-trade-preparation
status: DRAFT
frozen_at:
verdict_commit:
source: docs/work/private-live-autotrader-trade-preparation/design.md (D1~D8)
---

## 범위

**포함**

- 실잔고 조회 Domain port와 declared 어댑터 (D1)
- 표시용·판정용 2단 잔고 계약 (D2)
- `balanceBasis` 라벨과 exposure 증가 요청의 fail-closed (D3)
- 사건 기반 계획 무효화 (D4)
- 잔고 스냅샷에 결속된 durable 계획 (D5)
- owner 희망 프리미엄 입력과 직전 종료 포지션 참조값 (D6, D8)
- 프리미엄 조건 평가와 무장 상태 전이 (D7)

**제외** *(scope creep 차단선)*

- 주문 제출과 durable intent 전송 (`SAFE-11`, `P3-O5`)
- 진입 판정 규칙 — owner가 숫자를 준다 (D6)
- 보유 중 감시와 청산 방어 (`SAFE-7`, `P3-O16`)
- 실거래소 어댑터 구현 — port만 정의 (`ACT-2` 이후)
- credential 관리·egress 경계 (`P3-O1`)
- 경제 엔진 (`ECO-1`, Phase 1)

## 기존 gate 조사

> Gate 1 필수. 수용기준을 쓰기 전에 저장소가 이미 가진 검증 수단을 실제로 찾았다.

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | **있다** — `architecture-tests/` 독립 source set, `./gradlew architectureTest`. `ArchitectureBoundaryTest`·`ArchitectureTarget`·`KotlinSourceDependencyScanner` | **그대로 쓴다.** 모듈 경계·import 위반 검증에 새 테스트를 얹을 자리가 있다 |
| lint 규칙 | 있다 — ktlint·detekt (CI job 5). 로컬 gradle task 없고 `.ci-tools/ktlint.jar` 부트스트랩이 CI 전용 | 회귀 방어선으로만. 이 단위의 수용기준에 쓰지 않는다 |
| custom gradle task | **있다** — `unitTest`, `architectureTest`, `verifyTestIsolationPolicy`, `verifyCoverageExclusions`, `verifySecurityDependencyVersions`, `verifyMigrations`, `*:integrationTest` | **그대로 쓴다** |
| CI job | 있다 — 7개 (compile+architecture / unit+coverage / API integration / batch integration / ktlint+detekt / dependency+security / docker build) | 회귀 방어선 |
| 계약 테스트 패턴 | **있다** — `apps/api/src/integrationTest/.../TrackingRouteContractTest`, `TrackingGrossPnlContractTest`. 실행 중인 앱에 실제 HTTP 요청을 보내 응답 키 집합을 대조하는 형태 | **이 패턴을 그대로 따른다.** 새 검사기를 만들 필요가 없다 |

**결론.** 이 단위의 모든 수용기준을 **`기존` 또는 `신규테스트`로 덮을 수 있다.**
`신규스크립트`는 필요하지 않으므로 `## 스펙 리뷰 정지 규칙

> Phase 0 `AC20`은 정지 기준을 "critical·high 0" 으로 두었으나 **저자가 통제할 수 없는 형태**였고
> 대체 종료 경계가 없어 18라운드에서 사후 규칙 변경으로 끝났다 (`#68` `f-stop-rule-unbounded`, high).
> 이번에는 착수 전에 경계를 고정한다.

| 조건 | 값 |
|---|---|
| 종료 | 한 라운드의 **신규 high 이상 지적이 0건**이면 종료한다 |
| 상한 | **5라운드.** 도달해도 종료가 아니라 **범위 축소를 검토**한다 |
| 기록 | 라운드별 지적 수·심각도·처리(ACCEPT/REBUT)를 아래 표에 남긴다 |

상한 도달이 종료가 아닌 이유. 5라운드를 돌고도 신규 high가 계속 나온다는 것은 문서가 아직
안정되지 않았다는 뜻이고, 그때 필요한 것은 라운드를 더 도는 것이 아니라 **이 단위가 너무 큰지 묻는
것**이다. Phase 0이 "매 라운드 새로운 실질 결함이 나오는 상태에서 종료" 한 것이 그 신호를 무시한 결과다.

## 스펙 리뷰 라운드 기록

| 라운드 | 지적 | 심각도 | 처리 | 신규 high |
|---|---|---|---|---|
| 1R | 4건 | high 3 · medium 1 | 전부 ACCEPT (REBUT 0) | 3 |

## 검사 산출물` 절은 비어 있다.

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | 거래 준비 요청이 잔고·물량·레버리지·캡 판정과 `balanceBasis`·`observedAt`을 담은 응답을 반환한다. 응답 키 집합을 대조한다 | design.md §1.1, D2 | `상위` `P3-O2` | T1 | `신규테스트` `io.premiumspread.interfaces.api.tradeprep.TradePreparationContractTest` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationContract*' --offline --no-daemon` | exit 0 |
| AC2 | `R`·`L`·`Q` 산출이 `ECO-5` §2 관계식과 일치하고, lot/step-size 반올림 뒤 `Q`·`L`·캡을 **다시 판정**하며 양 leg 수량이 같다. 경계값(캡 직전·직후, 잔고 0, 반올림이 캡을 넘기는 경우)을 포함한다 | ECO-5 §2, design.md §3·D12 | `상위` `ECO-5` | T1 | `신규테스트` `io.premiumspread.domain.tradeprep.TradePreparationSizingTest` | `./gradlew test --tests '*TradePreparationSizing*' --offline --no-daemon` | exit 0 |
| AC3 | 레버 캡·효율 캡·청산 거리 중 하나라도 위반하면 계획을 만들지 않고 위반한 캡을 응답에 명시한다 | design.md §3, ECO-5 §7 | `요구` "특정 캡에 도달하면 자동 매매를 중지한다" | T1 | `신규테스트` `io.premiumspread.domain.tradeprep.TradePreparationCapTest` | `./gradlew test --tests '*TradePreparationCap*' --offline --no-daemon` | exit 0 |
| AC4 | `balanceBasis`가 `STALE`일 때 조회는 라벨과 함께 계획을 반환하고, exposure를 늘리는 요청은 거절한다 | design.md D3 | `리뷰` Phase 0 `STALE_MARKET` 원칙 · `SAFE-9` | T1 | `신규테스트` `io.premiumspread.interfaces.api.tradeprep.TradePreparationStaleBalanceTest` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationStaleBalance*' --offline --no-daemon` | exit 0 |
| AC5 | 판정용 잔고를 캐시에서 읽을 수 없다. 계획은 잔고 스냅샷 id에 결속되고, 그 id가 바뀌면 무효다 | design.md D2, D5 | `상위` `P3-O17` | T1 | `신규테스트` `io.premiumspread.domain.tradeprep.TradePreparationSnapshotBindingTest` | `./gradlew test --tests '*TradePreparationSnapshotBinding*' --offline --no-daemon` | exit 0 |
| AC6 | 우리 체결·owner refresh·reconcile 불일치 각각이 계획을 무효화한다. 시간 경과만으로는 무효화하지 않는다 | design.md D4 | `요구` 사건 기반 무효화 | T1 | `신규테스트` `io.premiumspread.domain.tradeprep.TradePreparationInvalidationTest` | `./gradlew test --tests '*TradePreparationInvalidation*' --offline --no-daemon` | exit 0 |
| AC11 | 무효화와 조건 충족 평가가 **동시에** 일어나도 `INVALIDATED`가 `ARMED`로 되돌아가지 않는다. evaluator·refresh·reconcile 교차 시나리오를 실제 DB 트랜잭션으로 검증한다 | design.md D11 | `리뷰` codex 1R ISSUE-3 | T1 | `신규테스트` `io.premiumspread.infrastructure.tradeprep.TradePreparationConcurrencyIntegrationTest` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationConcurrency*' --offline --no-daemon` | exit 0 |
| AC7 | owner 희망 프리미엄에 도달하면 계획이 무장 상태로 전이하고 **주문을 제출하지 않는다.** 직전 종료 포지션의 진입 프리미엄과 현재 gap이 응답에 있다 | design.md D6, D7, D8 | `요구` "희망 premium rate와 함께 계약을 수행하면 사건 기반 판정으로 체결" | T1 | `신규테스트` `io.premiumspread.interfaces.api.tradeprep.TradePreparationArmingContractTest` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationArming*' --offline --no-daemon` | exit 0 |
| AC8 | Domain이 거래소·HTTP·캐시 구현에 의존하지 않는다. 잔고 조회는 Domain port로만 표현된다 | `.ai/rules/architecture.md` Domain 허용 경계, design.md D1 | `상위` `P3-O1` | T1 | `기존` `./gradlew architectureTest` | `./gradlew architectureTest --offline --no-daemon` | exit 0 |
| AC9 | 거래 준비 endpoint 전부가 인증을 요구한다. `PublicEndpointPolicy`에 추가되지 않았다 | `.ai/rules/http.md` 인증 경계 | `리뷰` Phase 0 `AC9` 동형 | T1 | `신규테스트` `io.premiumspread.interfaces.api.tradeprep.TradePreparationAuthContractTest` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationAuth*' --offline --no-daemon` | exit 0 |
| AC12 | owner는 인증 principal에서 도출되며 요청 body의 owner 필드를 받지 않는다. 타 회원이 남의 계획을 조회·목표등록·무효화하면 **존재를 노출하지 않는 404**를 받고 DB가 변하지 않는다. 허가된 owner가 아닌 회원의 생성 요청은 거절된다 | design.md D10 | `상위` `P3-O12` | T1 | `신규테스트` `io.premiumspread.interfaces.api.tradeprep.TradePreparationOwnerScopeContractTest` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationOwnerScope*' --offline --no-daemon` | exit 0 |
| AC13 | `DeclaredBalanceAdapter`가 만든 `UNVERIFIED` 스냅샷으로는 `VerifiedBalance`를 만들 수 없고 계획이 `ARMED`에 도달하지 못한다. `RecordedBalanceAdapter`로는 도달한다 | design.md D9 | `리뷰` codex 1R ISSUE-1 | T1 | `신규테스트` `io.premiumspread.domain.tradeprep.TradePreparationBalanceTrustTest` | `./gradlew test --tests '*TradePreparationBalanceTrust*' --offline --no-daemon` | exit 0 |
| AC14 | 계획 레코드가 `MarketPair`와 해외가·FX·프리미엄의 snapshot id·관측 시각·출처를 보존해, 같은 계획을 나중에 같은 입력으로 재현할 수 있다 | design.md D12 | `리뷰` codex 1R ISSUE-4 | T1 | `신규테스트` `io.premiumspread.infrastructure.tradeprep.TradePreparationProvenanceIntegrationTest` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationProvenance*' --offline --no-daemon` | exit 0 |
| AC15 | ⑥ 스펙 리뷰를 아래 `## 스펙 리뷰 정지 규칙`까지 수행하고 라운드별 지적 수·심각도를 기록한다 | `feature-workflow` ⑥, `#68` `f-stop-rule-unbounded` | `리뷰` Phase 0 `AC20` 실패 교훈 | T4 | 사람 확인 | 아래 `## 스펙 리뷰 라운드 기록` | 정지 조건 충족 + 라운드 이력 기록됨 |
| AC10 | owner가 응답을 보고 물량·레버리지·캡 판정이 자기 잔고와 맞는지 확인한다 | design.md §0 "중간 성취" | `추론` | T4 | 사람 확인 | 아래 `## 사람 확인` 표 | 앵커 기록됨 |

**기준 개수**: 15개

**분할하지 않는 이유**: 15개가 D1~D12 결정 열둘을 덮는다. AC1~AC7·AC11~AC14가 각각 다른 결정을
검증하고 서로 다른 검증 수단을 갖는다. 분할하면 durable 계획(D5)과 그 무효화(D4·D11)·무장 전이(D7)·
신뢰 경계(D9)가 다른 단위로 갈라져 **상태 기계와 신뢰 경계가 두 계약서에 걸친다** — 그게 더 나쁘다.
AC8은 기존 gate 재사용, AC10·AC15는 사람 확인이다.

1R에서 5개가 늘었다(AC11~AC15). 늘어난 것이 전부 **신뢰 경계·인가·동시성**이며 기능 추가가 아니다.

**티어 강등 사유** *(T1이 아닌 항목만)*

- AC10 → T4: owner가 자기 실계정 잔고와 대조하는 판정이며 기계가 대신할 수 없다.
  declared 어댑터 단계에서는 입력값을 owner가 준 것이므로 자기 참조가 되고(D9가 그 값으로
  `ARMED`에 도달하는 것을 막는다), 실계정 대조는 `ACT-2` 이후에만 가능하다.
- AC15 → T4: 리뷰 라운드 수행 여부와 정지 조건 충족은 사람이 판정한다. 라운드 기록 자체는
  아래 표가 남기지만 "충분히 돌았는가" 는 기계가 대신할 수 없다.

## 스펙 리뷰 정지 규칙

> Phase 0 `AC20`은 정지 기준을 "critical·high 0" 으로 두었으나 **저자가 통제할 수 없는 형태**였고
> 대체 종료 경계가 없어 18라운드에서 사후 규칙 변경으로 끝났다 (`#68` `f-stop-rule-unbounded`, high).
> 이번에는 착수 전에 경계를 고정한다.

| 조건 | 값 |
|---|---|
| 종료 | 한 라운드의 **신규 high 이상 지적이 0건**이면 종료한다 |
| 상한 | **5라운드.** 도달해도 종료가 아니라 **범위 축소를 검토**한다 |
| 기록 | 라운드별 지적 수·심각도·처리(ACCEPT/REBUT)를 아래 표에 남긴다 |

상한 도달이 종료가 아닌 이유. 5라운드를 돌고도 신규 high가 계속 나온다는 것은 문서가 아직
안정되지 않았다는 뜻이고, 그때 필요한 것은 라운드를 더 도는 것이 아니라 **이 단위가 너무 큰지 묻는
것**이다. Phase 0이 "매 라운드 새로운 실질 결함이 나오는 상태에서 종료" 한 것이 그 신호를 무시한 결과다.

## 스펙 리뷰 라운드 기록

| 라운드 | 지적 | 심각도 | 처리 | 신규 high |
|---|---|---|---|---|
| 1R | 4건 | high 3 · medium 1 | 전부 ACCEPT (REBUT 0) | 3 |

## 검사 산출물

> `신규스크립트`를 검증 수단으로 쓰지 않는다. `기존 gate 조사`에서 확인한 대로
> 모든 기준이 기존 gradle task 또는 신규 테스트로 덮인다. 이 절은 비어 있다.

## 회귀 방어선

이번 변경으로 깨질 수 있는 기존 동작. RED 면제 대상이다.

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 기존 tracking·premium·ticker·member 계약이 회귀하지 않는다 | `./gradlew test architectureTest --offline --no-daemon` |
| R2 | 기존 통합 계약이 회귀하지 않는다 | `./gradlew :apps:api:integrationTest --offline --no-daemon` |
| R3 | migration이 추가되지 않았거나 추가된 것이 append-only 계약을 지킨다 | `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` |
| R4 | 웹 lint가 회귀하지 않는다 | `npm --prefix apps/web run lint` |
| R5 | 웹 테스트가 회귀하지 않는다 | `npm --prefix apps/web run test` |
| R6 | 웹 production build가 회귀하지 않는다 | `npm --prefix apps/web run build` |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

*(구현 전. RED 로그부터 채운다.)*

## 사람 확인 (T4)

> 판정 주체는 사람뿐이다. AI가 이 표를 채우지 않는다.
> 앵커는 이 파일 밖에 있어야 한다 — PR 코멘트 URL, 커밋 SHA, 이슈 링크.

| # | 확인 사항 | 확인자 | 날짜 | 앵커 |
|---|---|---|---|---|
| AC10 | 응답의 물량·레버리지·캡 판정이 실제 잔고와 맞는가 | | | |
| AC15 | 스펙 리뷰가 정지 규칙까지 수행됐는가 | | | |

## 변경 요청

> 동결 후 기준을 바꿔야 할 때만 작성. 사람이 승인하기 전까지 구현을 중단한다.

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|

## 최종 판정

> 아래 수는 전부 위 수용기준 표에서 센 값이다. 표와 어긋나면 판정을 낼 수 없다.
> 판정이 가리키는 SHA와 브랜치 최종 SHA가 다르면 그 판정은 만료다.

```
DoD VERDICT: private-live-autotrader-trade-preparation @ <commit SHA>
  수용기준 표:     15개  (T1 13 · T2 0 · T3 0 · T4 2)
  T1/T2 자동:      13개 중 <p>개 PASS
  T3 기록 제출:    0개
  T4 사람 확인:    2개 중 <r>건 완료, <2-r>건 대기
  변경 요청:       <k>건
  =>
```

**사람 확인이 필요한 항목**

- AC10
- AC15
