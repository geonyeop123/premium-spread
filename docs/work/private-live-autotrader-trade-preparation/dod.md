---
feature: 거래 준비 (Trade Preparation)
slug: private-live-autotrader-trade-preparation
status: FROZEN
frozen_at: 2026-08-30T13:26:51+09:00
verdict_commit:
source: docs/work/private-live-autotrader-trade-preparation/design.md (D1~D17)
---

## 범위

**포함**

- 실잔고 조회 Domain port와 declared 어댑터 (D1)
- 표시용·판정용 2단 잔고 계약 (D2)
- `balanceBasis` 라벨과 exposure 증가 요청의 fail-closed (D3)
- 사건 기반 계획 무효화 (D4)
- 잔고 스냅샷에 결속된 durable 계획 (D5)
- owner 희망 프리미엄 입력과 직전 종료 포지션 참조값 (D6, D8)
- 프리미엄 조건 평가와 무장 상태 전이 (D7) — 신선도 fail-closed (D14)
- 보유 `ACTIVE` tracking 존재 시 거절 (D13)
- 무효화 producer — 체결(동일 트랜잭션)·주기 reconcile Job (D17)
- owner당 `WATCHING` 유일성의 DB 강제 (D16)

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
| 2R | 4건 | high 2 · medium 2 | 전부 ACCEPT (REBUT 0). 미해결 3건(`TP-OPEN-3`·`4`·`6`)을 owner 승인으로 해소 (D13~D17) | 2 |
| 3R | 2건 | high 2 | 전부 ACCEPT (REBUT 0). write-skew → D18 owner 잠금, production ARMED 불가 → D19 도달 상태 명시 | 2 |
| 4R | 3건 | high 2 · medium 1 | 전부 ACCEPT (REBUT 0). 계약 정렬 → D20, 평가 모듈 분해 → D21, fixture 배선 배제 → D22 | 2 |
| 5R | 1건 | high 1 | ACCEPT (REBUT 0). 활성 계획 단일성 → D23 (유일성 범위를 `WATCHING`·`ARMED` 전체로) | 1 |

**상한 도달 (5R).** 정지 규칙에 따라 라운드를 더 돌지 않고 범위 판단을 owner에게 올린다.
추이는 3 → 2 → 2 → 2 → 1로 수렴 중이며, 5R 반영(D23)은 유일성 범위 확장 한 건이다.
이 반영 자체는 아직 외부 리뷰를 받지 않았다 — 그 잔여 위험은 ⑦ 사용자 리뷰와 구현 단계
코드 리뷰(⑨)가 본다.

**owner 선택 (2026-08-30).** 상한 도달 처리 **A — 승인으로 진행**. D19(production 도달 상태는
`WATCHING` + 조건 관측까지, `ARMED`는 `ACT-2` 이후)를 함께 수용했다. 이 승인이 ⑦이며 이 계약서를
`FROZEN`으로 전이한다. 5R 반영(D23)의 미리뷰 잔여 위험은 ⑨ 코드 리뷰와 AC11이 담당한다.

## 검사 산출물` 절은 비어 있다.

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | 거래 준비 요청이 잔고·물량·레버리지·캡 판정과 `balanceBasis`·`observedAt`을 담은 응답을 반환한다. 응답 키 집합을 대조한다 | design.md §1.1, D2 | `상위` `P3-O2` | T1 | `신규테스트` `io.premiumspread.interfaces.api.tradeprep.TradePreparationContractTest` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationContract*' --offline --no-daemon` | exit 0 |
| AC2 | `R`·`L`·`Q` 산출이 `ECO-5` §2 관계식과 일치하고, lot/step-size 반올림 뒤 `Q`·`L`·캡을 **다시 판정**하며 양 leg 수량이 같다. 경계값(캡 직전·직후, 잔고 0, 반올림이 캡을 넘기는 경우)을 포함한다 | ECO-5 §2, design.md §3·D12 | `상위` `ECO-5` | T1 | `신규테스트` `io.premiumspread.domain.tradeprep.TradePreparationSizingTest` | `./gradlew :domain:test --tests '*TradePreparationSizing*' --offline --no-daemon` | exit 0 |
| AC3 | 레버 캡·효율 캡·청산 거리 중 하나라도 위반하면 계획을 만들지 않고 위반한 캡을 응답에 명시한다 | design.md §3, ECO-5 §7 | `요구` "특정 캡에 도달하면 자동 매매를 중지한다" | T1 | `신규테스트` `io.premiumspread.domain.tradeprep.TradePreparationCapTest` | `./gradlew :domain:test --tests '*TradePreparationCap*' --offline --no-daemon` | exit 0 |
| AC4 | `balanceBasis`가 `STALE`일 때 조회는 라벨과 함께 계획을 반환하고, verified 원천이 있는 `registerTarget`은 거절한다 | design.md D3·D20 | `리뷰` Phase 0 `STALE_MARKET` 원칙 · `SAFE-9` | T1 | `신규테스트` `io.premiumspread.interfaces.api.tradeprep.TradePreparationStaleBalanceTest` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationStaleBalance*' --offline --no-daemon` | exit 0 |
| AC5 | 판정용 잔고를 캐시에서 읽을 수 없다. 계획은 잔고 스냅샷 id에 결속되고, 그 id가 바뀌면 무효다 | design.md D2, D5 | `상위` `P3-O17` | T1 | `신규테스트` `io.premiumspread.domain.tradeprep.TradePreparationSnapshotBindingTest` | `./gradlew :domain:test --tests '*TradePreparationSnapshotBinding*' --offline --no-daemon` | exit 0 |
| AC6 | 우리 체결·owner refresh·reconcile 불일치 각각이 계획을 무효화한다. 시간 경과만으로는 무효화하지 않는다 | design.md D4 | `요구` 사건 기반 무효화 | T1 | `신규테스트` `io.premiumspread.domain.tradeprep.TradePreparationInvalidationTest` | `./gradlew :domain:test --tests '*TradePreparationInvalidation*' --offline --no-daemon` | exit 0 |
| AC11 | 무효화와 조건 충족 평가가 **동시에** 일어나도 `INVALIDATED`가 `ARMED`로 되돌아가지 않는다. **owner당 활성 계획(`WATCHING`·`ARMED`)은 어떤 교차에서도 최대 하나다** — 동시 `registerTarget`은 정확히 하나만 성공하고, `ARMED`가 존재하면 새 등록이 거절되며, 두 계획을 `ARMED`로 만드는 시도는 DB 유일성이 막는다. 실제 DB 트랜잭션으로 검증한다 | design.md D11·D16·D23 | `리뷰` codex 1R ISSUE-3 · 2R ISSUE-1 · 5R ISSUE-1 | T1 | `신규테스트` `io.premiumspread.infrastructure.tradeprep.TradePreparationConcurrencyIntegrationTest` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationConcurrency*' --offline --no-daemon` | exit 0 |
| AC7 | owner 희망 프리미엄에 도달하면 계획이 무장 상태로 전이하고 **주문을 제출하지 않는다.** 직전 종료 포지션의 진입 프리미엄과 현재 gap이 응답에 있다 | design.md D6, D7, D8 | `요구` "희망 premium rate와 함께 계약을 수행하면 사건 기반 판정으로 체결" | T1 | `신규테스트` `io.premiumspread.interfaces.api.tradeprep.TradePreparationArmingContractTest` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationArming*' --offline --no-daemon` | exit 0 |
| AC8 | Domain이 거래소·HTTP·캐시 구현에 의존하지 않는다. 잔고 조회는 Domain port로만 표현된다 | `.ai/rules/architecture.md` Domain 허용 경계, design.md D1 | `상위` `P3-O1` | T1 | `기존` `./gradlew architectureTest` | `./gradlew architectureTest --offline --no-daemon` | exit 0 |
| AC9 | 거래 준비 endpoint 전부가 인증을 요구한다. `PublicEndpointPolicy`에 추가되지 않았다 | `.ai/rules/http.md` 인증 경계 | `리뷰` Phase 0 `AC9` 동형 | T1 | `신규테스트` `io.premiumspread.interfaces.api.tradeprep.TradePreparationAuthContractTest` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationAuth*' --offline --no-daemon` | exit 0 |
| AC12 | owner는 인증 principal에서 도출되며 요청 body의 owner 필드를 받지 않는다. 타 회원이 남의 계획을 조회·목표등록·무효화하면 **존재를 노출하지 않는 404**를 받고 DB가 변하지 않는다. 허가된 owner가 아닌 회원의 생성 요청은 거절된다 | design.md D10 | `상위` `P3-O12` | T1 | `신규테스트` `io.premiumspread.interfaces.api.tradeprep.TradePreparationOwnerScopeContractTest` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationOwnerScope*' --offline --no-daemon` | exit 0 |
| AC13 | `DeclaredBalanceAdapter`의 `UNVERIFIED` 스냅샷으로는 `VerifiedBalance`를 만들 수 없고, `UNVERIFIED` 결속 계획은 조건이 충족돼도 `WATCHING`을 유지하며 `conditionFirstMetAt`·당시 프리미엄이 기록된다. `RecordedBalanceAdapter` 결속으로는 같은 조건에서 `ARMED`에 도달한다 | design.md D9·D19 | `리뷰` codex 1R ISSUE-1 · 3R ISSUE-2 | T1 | `신규테스트` `io.premiumspread.domain.tradeprep.TradePreparationBalanceTrustTest` | `./gradlew :domain:test --tests '*TradePreparationBalanceTrust*' --offline --no-daemon` | exit 0 |
| AC14 | 계획 레코드가 `MarketPair`와 해외가·FX·프리미엄의 snapshot id·관측 시각·출처를 보존해, 같은 계획을 나중에 같은 입력으로 재현할 수 있다 | design.md D12 | `리뷰` codex 1R ISSUE-4 | T1 | `신규테스트` `io.premiumspread.infrastructure.tradeprep.TradePreparationProvenanceIntegrationTest` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationProvenance*' --offline --no-daemon` | exit 0 |
| AC15 | ⑥ 스펙 리뷰를 아래 `## 스펙 리뷰 정지 규칙`까지 수행하고 라운드별 지적 수·심각도를 기록한다 | `feature-workflow` ⑥, `#68` `f-stop-rule-unbounded` | `리뷰` Phase 0 `AC20` 실패 교훈 | T4 | 사람 확인 | 아래 `## 스펙 리뷰 라운드 기록` | 정지 조건 충족 + 라운드 이력 기록됨 |
| AC16 | owner의 `ACTIVE` tracking이 존재하면 `prepare`와 `registerTarget`이 거절되고 DB가 변하지 않는다. tracking 생성과 `registerTarget`이 **서로의 미커밋 상태를 못 보는 교차 순서를 강제**해도 owner member 행 잠금이 직렬화해 `ACTIVE` tracking과 `WATCHING`·`ARMED` 계획이 공존 커밋되지 않는다 | design.md D13·D17·D18 | `리뷰` codex 2R ISSUE-2 · 3R ISSUE-1 | T1 | `신규테스트` `io.premiumspread.interfaces.api.tradeprep.TradePreparationActiveTrackingContractTest` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationActiveTracking*' --offline --no-daemon` | exit 0 |
| AC17 | **scheduler → Job → 전이 경로**에서, 관측 시각이 `MAX_AGE` 밖(과거·미래)이거나 `MarketPair`가 다르거나 stream 최신값이 없으면 조건값이 충족돼도 `ARMED`로 전이하지 않고 `WATCHING`을 유지한다. fresh + 충족 + verified 결속이면 `ARMED`, `UNVERIFIED` 결속이면 관측만 기록한다 | design.md D14·D19·D21 | `리뷰` codex 2R ISSUE-4 · 4R ISSUE-2 | T1 | `신규테스트` `io.premiumspread.application.job.TradePreparationEvaluationJobIntegrationTest` | `./gradlew :apps:batch:integrationTest --tests '*TradePreparationEvaluationJob*' --offline --no-daemon` | exit 0 |
| AC18 | reconcile Job이 결속 스냅샷과 현재 판정용 잔고의 불일치를 발견하면 `WATCHING`·`ARMED` 계획을 `INVALIDATED`로 전이시키고, 일치하면 상태를 바꾸지 않는다. **Job 실행 경로**(scheduler → `JobExecutor` → 무효화)로 검증한다 — 무효화 메서드 직접 호출은 이 기준을 충족하지 않는다 | design.md D17 | `리뷰` codex 2R ISSUE-3 | T1 | `신규테스트` `io.premiumspread.application.job.TradePreparationReconcileJobIntegrationTest` | `./gradlew :apps:batch:integrationTest --tests '*TradePreparationReconcile*' --offline --no-daemon` | exit 0 |
| AC19 | declared 원천만 있을 때 `registerTarget`이 `UNVERIFIED` 결속으로 `WATCHING`을 만들고 `VerifiedBalance`는 생성되지 않는다. verified 원천이 있고 `STALE`이면 거절된다 — 두 경로를 실제 API 응답으로 검증한다 | design.md D20 | `리뷰` codex 4R ISSUE-1 | T1 | `신규테스트` `io.premiumspread.interfaces.api.tradeprep.TradePreparationRegisterTargetContractTest` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationRegisterTarget*' --offline --no-daemon` | exit 0 |
| AC20 | production 배선을 로드하면 `BalanceReadPort` 구현이 `DeclaredBalanceAdapter`뿐이다. `RecordedBalanceAdapter`는 main classpath에 존재하지 않는다 | design.md D22 | `리뷰` codex 4R ISSUE-3 | T1 | `신규테스트` `io.premiumspread.config.TradePreparationWiringContractTest` | `./gradlew :apps:api:test --tests '*TradePreparationWiring*' --offline --no-daemon` | exit 0 |
| AC10 | owner가 응답을 보고 물량·레버리지·캡 판정이 자기 잔고와 맞는지 확인한다 | design.md §0 "중간 성취" | `추론` | T4 | 사람 확인 | 아래 `## 사람 확인` 표 | 앵커 기록됨 |

**기준 개수**: 20개

**분할하지 않는 이유**: 18개가 D1~D17 결정 열일곱을 덮는다. AC1~AC7·AC11~AC14·AC16~AC18이 각각
다른 결정을 검증하고 서로 다른 검증 수단을 갖는다. 분할하면 durable 계획(D5)과 그 무효화(D4·D11·D17)·
무장 전이(D7·D14)·신뢰 경계(D9·D13)가 다른 단위로 갈라져 **상태 기계와 신뢰 경계가 두 계약서에
걸친다** — 그게 더 나쁘다. AC8은 기존 gate 재사용, AC10·AC15는 사람 확인이다.

1R에서 5개(AC11~AC15), 2R에서 3개(AC16~AC18), 4R에서 2개(AC19~AC20)가 늘었다. 늘어난 것이 전부
**신뢰 경계·인가·동시성·producer·배선 검증**이며 기능 추가가 아니다. 5라운드 상한 도달 시 범위
축소의 후퇴선은 D17의 대안 B(`ARMED` 도달 불가로 축소)다.

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
| 2R | 4건 | high 2 · medium 2 | 전부 ACCEPT (REBUT 0). 미해결 3건(`TP-OPEN-3`·`4`·`6`)을 owner 승인으로 해소 (D13~D17) | 2 |
| 3R | 2건 | high 2 | 전부 ACCEPT (REBUT 0). write-skew → D18 owner 잠금, production ARMED 불가 → D19 도달 상태 명시 | 2 |
| 4R | 3건 | high 2 · medium 1 | 전부 ACCEPT (REBUT 0). 계약 정렬 → D20, 평가 모듈 분해 → D21, fixture 배선 배제 → D22 | 2 |
| 5R | 1건 | high 1 | ACCEPT (REBUT 0). 활성 계획 단일성 → D23 (유일성 범위를 `WATCHING`·`ARMED` 전체로) | 1 |

**상한 도달 (5R).** 정지 규칙에 따라 라운드를 더 돌지 않고 범위 판단을 owner에게 올린다.
추이는 3 → 2 → 2 → 2 → 1로 수렴 중이며, 5R 반영(D23)은 유일성 범위 확장 한 건이다.
이 반영 자체는 아직 외부 리뷰를 받지 않았다 — 그 잔여 위험은 ⑦ 사용자 리뷰와 구현 단계
코드 리뷰(⑨)가 본다.

**owner 선택 (2026-08-30).** 상한 도달 처리 **A — 승인으로 진행**. D19(production 도달 상태는
`WATCHING` + 조건 관측까지, `ARMED`는 `ACT-2` 이후)를 함께 수용했다. 이 승인이 ⑦이며 이 계약서를
`FROZEN`으로 전이한다. 5R 반영(D23)의 미리뷰 잔여 위험은 ⑨ 코드 리뷰와 AC11이 담당한다.

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

### T1 — Domain 잔고 port와 사이징 (2026-08-30)

**대상 AC**: AC2·AC3·AC13 (설계 근거: D2·D9·D12, `ECO-5` §2·§3)

**신설 파일**: `domain/src/main/kotlin/io/premiumspread/domain/tradeprep/`
`BalanceBasis.kt`·`BalanceSnapshot.kt`·`VerifiedBalance.kt`·`TradePrepPorts.kt`·`CapVerdict.kt`·
`TradePrepPolicy.kt`·`TradePrepSizing.kt` (+ `TradePrepSizingResult`) 와
`domain/src/test/kotlin/io/premiumspread/domain/tradeprep/`
`TradePreparationSizingTest.kt`·`TradePreparationCapTest.kt`·`TradePreparationBalanceTrustTest.kt`

**RED** — 테스트를 먼저 작성하고, 아래 3곳을 의도적으로 틀린 stub으로 구현해 컴파일은 되지만
기대한 assertion이 기대한 값으로 실패하는 것을 확인했다.
- `VerifiedBalance.from`이 `balanceBasis`를 검사하지 않고 항상 변환 (D9 위반 재현)
- `TradePrepPolicy.judge`가 레버 캡을 `leverage > leverageCap` (초과만) 로 판정 (`>=` "도달" 누락)
- `TradePrepSizing.size`가 lot/step 반올림을 적용하지 않고 원시값을 그대로 최종값으로 사용

```
./gradlew :domain:test --tests '*TradePreparationSizing*' --tests '*TradePreparationCap*' \
  --tests '*TradePreparationBalanceTrust*' --offline --no-daemon
```

결과: `28 tests completed, 7 failed`. 실패 목록 —
`TradePreparationBalanceTrustTest > UNVERIFIED 스냅샷으로는 VerifiedBalance를 만들 수 없다() FAILED`,
`... UNAVAILABLE 스냅샷으로도 ... FAILED`,
`TradePreparationCapTest > 레버리지가 캡에 정확히 도달하면 위반이다 — 도달 자체가 정지 조건이다() FAILED`,
`TradePreparationSizingTest > 레버리지가 캡에 도달하면 계획을 만들지 않고 위반한 캡을 명시한다() FAILED`,
`... 반올림이 물량을 0으로 만들면 캡이 안쪽이어도 계획을 만들지 않는다() FAILED`,
`... 양 leg 반올림 물량이 다르면 작은 쪽에 맞춘다() FAILED`,
`... 캡 안쪽이면 lot size로 내림 반올림하고 작은 쪽 물량을 채택해 leverage를 재계산한다() FAILED`.
모두 `AssertionFailedError`/`AssertionError` — 컴파일 실패가 아니라 기대값 불일치로 인한 실패다.

**GREEN** — 세 stub을 실제 로직으로 교체(`VerifiedBalance.from`의 basis 분기,
`judge`의 `>=` 비교, `size`의 내림 반올림 → 작은 쪽 채택 → `finalLeverage` 재계산 → 재판정)한 뒤 재실행.

```
./gradlew :domain:test --tests '*TradePreparationSizing*' --tests '*TradePreparationCap*' \
  --tests '*TradePreparationBalanceTrust*' --offline --no-daemon
```

결과: `BUILD SUCCESSFUL`. `TEST-*.xml` 기준 `TradePreparationSizingTest` 12건,
`TradePreparationCapTest` 11건, `TradePreparationBalanceTrustTest` 5건 — 총 28건 전부 통과, 실패 0건.

**architectureTest**

```
./gradlew architectureTest --offline --no-daemon
```

결과: `BUILD SUCCESSFUL`. `io.premiumspread.domain.tradeprep` 패키지가 domain 허용 경계
(jakarta.persistence-api·spring-context·spring-tx·spring-data-commons)만 참조하고 새 위반이 없다.

**회귀(R1)**

```
./gradlew test architectureTest --offline --no-daemon
```

결과: `BUILD SUCCESSFUL` (전 모듈 unit test + architectureTest, 기존 tracking/premium/ticker/member
계약 회귀 없음).

**컨트롤러 Ruling 3 (리뷰 라운드 1, 2026-08-30)**: AC2가 나열한 경계값 중 "반올림이 캡을 넘기는
경우"는 design.md §3의 채택 공식(빗썸 비중 = `B_k/(B_k+X·B_b)`, 잔고만의 함수) 하에서는 구성
불가능하다 — 내림 반올림은 물량만 줄이므로 `finalLeverage ≤ rawLeverage`가 항상 성립해 레버 캡을
반올림이 새로 어길 수 없고, `koreaShare`는 물량과 무관해 효율 캡도 반올림으로 바뀌지 않는다.
반올림이 만드는 유일한 새 실패 모드는 "물량이 거래소 lot size로 0까지 내려가는 경우"이므로 이
경계로 대체 검증했다(`TradePreparationSizingTest`의 "반올림이 물량을 0으로 만들면 캡이 안쪽이어도
계획을 만들지 않는다"). 리뷰어가 독립적으로 이 수식을 검산해 같은 결론에 도달했고, 컨트롤러가
당초 제기했던 "효율 캡을 투입액 기준으로 재판정해야 한다"는 이견은 §3 원문이 효율 캡을 빗썸
비중(잔고만의 함수)으로 명시한 것과 배치돼 철회했다. 구현은 변경하지 않는다.

### T2 — Domain 계획 엔티티와 상태 기계 (2026-08-30)

**대상 AC**: AC5·AC6 (설계 근거: D4·D5·D11·D12·D15·D16·D19·D21·D23)

**신설 파일**: `domain/src/main/kotlin/io/premiumspread/domain/tradeprep/`
`TradePreparation.kt`(+ `TradePreparationSpec`) · `TradePreparationStatus.kt` ·
`TradePreparationInvalidationReason.kt` · `TradePreparationConditionOutcome.kt` ·
`InvalidTradePreparationException.kt` · `TradePreparationRepository.kt` ·
`TradePreparationService.kt` 와
`domain/src/test/kotlin/io/premiumspread/domain/tradeprep/`
`TradePreparationSnapshotBindingTest.kt` · `TradePreparationInvalidationTest.kt`

**RED** — 테스트를 먼저 작성하고, 엔티티에 의도적으로 틀린 stub 3곳을 심어 컴파일은 되지만
기대한 assertion이 기대한 값으로 실패하는 것을 확인했다.
- `evaluateCondition`의 조건 비교를 `currentPremiumRate <= desired`(방향 반대)로 둠
- `invalidateOnReconcileMismatch`가 스냅샷 id 일치 여부와 무관하게 항상 무효화하도록 둠
- `invalidate`가 이미 `INVALIDATED`여도 사유·시각을 덮어쓰도록 가드를 생략함

```
./gradlew :domain:test --tests '*TradePreparationSnapshotBinding*' --tests '*TradePreparationInvalidation*' \
  --offline --no-daemon
```

결과: `14 tests completed, 4 failed`. 실패 목록(전부 `AssertionFailedError`, 컴파일 실패 아님) —
`TradePreparationInvalidationTest > 프리미엄이 희망값과 정확히 같으면 조건 충족으로 판정해 ARMED로
전이한다 — 도달이 충족이다() FAILED` (`expected: ARMED but was: NOT_MET`),
`... INVALIDATED는 종점이다 — 다른 트리거로 재무효화를 시도해도 상태·사유가 바뀌지 않는다() FAILED`
(`Expecting value to be false but was true`),
`TradePreparationSnapshotBindingTest > 결속 스냅샷 id와 현재 판정용 잔고 id가 같으면 계획을
무효화하지 않는다() FAILED` (`Expecting value to be false but was true`),
`... 한 번 무효화된 계획은 스냅샷 id가 다시 바뀌어도 재무효화되지 않는다 — INVALIDATED는 종점이다()
FAILED` (`Expecting value to be false but was true`).

**GREEN** — 세 stub을 교정(조건 비교를 `<`로 바꿔 "도달"을 충족에 포함, `invalidateOnReconcileMismatch`에
스냅샷 id 일치 시 조기 반환 추가, `invalidate`에 `status == INVALIDATED` 종점 가드 추가)한 뒤 재실행.

```
./gradlew :domain:test --tests '*TradePreparationSnapshotBinding*' --tests '*TradePreparationInvalidation*' \
  --offline --no-daemon
```

결과: `BUILD SUCCESSFUL`. `TEST-*.xml` 기준 `TradePreparationInvalidationTest` 9건,
`TradePreparationSnapshotBindingTest` 5건 — 총 14건 전부 통과, 실패 0건.

**architectureTest**

```
./gradlew architectureTest --offline --no-daemon
```

결과: `BUILD SUCCESSFUL`. `io.premiumspread.domain.tradeprep` 패키지(엔티티 포함)가 domain 허용
경계(jakarta.persistence-api·spring-context·spring-tx·spring-data-commons)만 참조하고 새 위반이 없다.

**회귀(R1)**

```
./gradlew test architectureTest --offline --no-daemon
```

결과: `BUILD SUCCESSFUL` (전 모듈 unit test + architectureTest, 기존 tracking/premium/ticker/member/
notification 계약 회귀 없음).

**설계 결정 — brief가 값을 정하지 않은 항목**

- **"해외가·FX·프리미엄의 snapshot id·관측 시각·출처"(D12)**: `PremiumSnapshot`·`ExchangeRateSnapshot`
  둘 다 domain에 surrogate id를 노출하지 않는 기존 관례를 따라, 별도 opaque id 컬럼을 신설하지
  않고 `referenceForeignPrice`·`referenceFxRate`·`referencePremiumRate`·`referenceObservedAt`·
  `referenceFxSource`·`referenceFxObservedAt` 원시값 자체를 재현에 필요한 provenance로 보존했다
  (`Tracking`의 `entryFxRate`·`entryPremiumRate`·`entryObservedAt` 패턴과 동형). `(pair, observedAt)`이
  이 코드베이스에서 이미 premium/fx의 식별자 역할을 한다.
- **재진입 조건의 비교 방향**: `currentPremiumRate >= desiredPremiumRate`("도달"이 충족)로
  판정했다 — `TradePrepPolicy.judge`의 레버 캡이 "도달"을 위반에 포함시키는 것과 같은 경계값
  관례이며, plan.md/eco-5 문서의 "재진입 | 프리미엄이 직전 진입 수준으로 복귀 시" 서술과 일관된다.
  design.md에 명시적 부등호가 없어 내린 판단이라 T5·T7 리뷰에서 재확인이 필요하다.
- **`version`은 business 필드(수동 증가), Hibernate `@Version`은 별도 hidden `lockVersion`**:
  `NotificationSubscription`(`revision` + hidden `lockVersion`)과 동일한 패턴이다. D11의 조건부
  갱신문(`WHERE id=? AND version=? AND status=?`)은 T3/T4가 실제 SQL(JPQL bulk update 등)로
  구성한다 — 이 필드는 그 계약이 참조할 값을 노출할 뿐이며, T2는 CAS의 실제 DB 경합(AC11)을
  검증하지 않는다(과제 경계).
- **DRAFT도 결속 잔고 스냅샷을 갖는다**: `prepare`가 이미 표시용 `BalanceSnapshot`을 읽으므로
  `boundBalanceSnapshotId`/`boundBalanceBasis`를 생성 시점부터 non-null로 요구했다.
  `registerTarget`은 D20에 따라 자체적으로 새로 읽은 값으로 재기록한다(재바인딩).
- **저장소 파일 분리**: 네이밍 규칙이 "domain port는 묶음 파일(`TradePrepPorts.kt`)"을 언급하지만,
  `TradePrepPorts.kt`(T1)는 잔고 조회 capability 전용으로 문서화돼 있어, 기존 `TrackingRepository`·
  `NotificationSubscriptionRepository`·`PremiumRepository` 관례(entity당 별도 repository 파일)를
  따라 `TradePreparationRepository.kt`를 새 파일로 분리했다.
- **capVerdict 상세(koreaShare·violations 등) 미저장**: brief가 "산출 Q·L"만 명시해 `quantity`·
  `leverage`만 보존했다. 캡 판정은 `prepare` 시점에 이미 통과한 계획만 생성되므로 재저장하지 않았다.

**T5·T7이 참고할 시그니처**: `TradePreparation.evaluateCondition(currentPremiumRate, observedAt):
TradePreparationConditionOutcome`가 D21의 "조회·조건부 전이(arm/관측 기록)"를 한 메서드로 묶는다.
호출자(T7 Job)는 D14 신선도·pair 일치 판정을 먼저 통과시킨 뒤에만 호출해야 한다 — 이 메서드
자체는 신선도를 판단하지 않는다. `TradePreparationRepository.findAllWatchingByPair(pair)`가 T7의
순회 진입점이다. `TradePreparationRepository`는 D11 조건부 갱신의 실제 SQL을 정의하지 않는다 —
T4가 구현을 결정한다.

### T2 수정 라운드 1 — 진입 조건 부등호 반전 (2026-08-30, 리뷰 반영)

**문제.** `TradePreparation.evaluateCondition`이 `currentPremiumRate < desired`를 미충족으로 판정해
"현재 프리미엄이 목표 이상이면 충족"으로 구현돼 있었다. 그러나 이 단위는 **진입** 준비이고
(master spec §1.2 "낮은 executable premium에서 후보 진입"), ECO-5 §1 "재진입 | 프리미엄이 직전
진입 수준으로 복귀 시"·owner 원문("종료 이후 다시 목표 진입 프리미엄 도달 시 다시 거래")이 가리키는
방향은 반대다 — 종료 직후 프리미엄은 진입보다 높고(진입+1.0%p), 재진입은 그 값이 목표까지
**내려와야** 충족이다. 컨트롤러 Ruling 5로 확인된 load-bearing 결함이었다(T2 자체 보고서
"우려 1"로 사전 고지, dod.md 최초 버전은 반대 방향으로 구현·문서화됨).

**수정.** `evaluateCondition`의 분기를 `if (currentPremiumRate > desired) return NOT_MET`으로
뒤집었다(경계값 `==`는 여전히 충족). 즉시 충족(등록 시점에 이미 목표 이하)은 별도 crossing
요구 없이 자연히 허용된다 — 컨트롤러 Ruling 6.

**추가 검증.** 기존 테스트의 방향 전제 값을 전부 재검토해 뒤집고(`armedPlan()`의 평가값을
목표보다 낮은 값으로, "시간이 지나도 무효화 안 됨" 테스트의 미충족 값을 목표보다 높은 값으로),
다음을 신설했다.
- 목표보다 높으면 미충족(`프리미엄이 희망값보다 높으면 조건 미충족이다`)
- 정확히 같으면 충족(`...경계값은 포함된다`, 기존 테스트 방향만 교정)
- 등록 즉시 목표 이하면 즉시 충족(`등록 시점에 이미 목표보다 낮은 프리미엄이면...`)
- `UNVERIFIED` 결속 계획의 `OBSERVED_ONLY` 분기(`WATCHING` 유지 + `conditionFirstMetAt`/
  `conditionFirstMetPremiumRate` 기록) — 리뷰 F1
- `version` 증가(`registerTarget`·`evaluateCondition`의 `ARMED` 전이·`invalidate`)와 no-op에서
  불변(`invalidateOnReconcileMismatch` id 일치·이미 `INVALIDATED`인 재무효화) — 리뷰 F2
- `registerTarget`의 blank `boundBalanceSnapshotId` 재바인딩 가드 — 리뷰 F3(엔티티에 검증 추가)
- `DRAFT`에서 직접 `INVALIDATED`로 가는 경로(`WATCHING`을 거치지 않아도 됨) — 리뷰 F4
- `OWNER_REFRESH`가 `ARMED` 계획도 무효화하는 경로 — 리뷰 F5

```
./gradlew :domain:test --tests '*TradePreparation*' --offline --no-daemon
```

결과: `BUILD SUCCESSFUL`. `TEST-*.xml` 기준 `TradePreparationInvalidationTest` 14건(9→14),
`TradePreparationSnapshotBindingTest` 5건, `TradePreparationBalanceTrustTest` 5건·
`TradePreparationCapTest` 11건·`TradePreparationSizingTest` 12건(T1, 무변경) — `tradeprep` 패키지
전체 47건 전부 통과, 실패 0건.

```
./gradlew architectureTest --offline --no-daemon
```

결과: `BUILD SUCCESSFUL`.

**"청산" 용어 점검.** T2가 만든 파일 중 "청산"을 자발적 종료 의미로 쓴 곳은 없다 — 저장소에 남은
"청산" 용례(`TradePrepPolicy.kt`·`CapVerdict.kt`·`TradePreparationCapTest.kt`, 전부 T1 소유)는
`liquidationDistance`(강제청산 거리) 개념이라 용어 분리 대상이 아니다. 수정 없음.

### T2 수정 라운드 1 보강 [F0-e] — `desiredPremiumRate` → `desiredEntryPremiumRate` (2026-08-30)

**문제.** owner 확인으로 목표 프리미엄이 **둘**임이 확정됐다 — 진입 목표(예: 1.5%, 이 값 이하로
내려오면 무장)와 종료 목표(진입가 대비 +1.0%p 상대값, 열린 포지션에 붙는 값). 기존 필드명
`desiredPremiumRate`는 둘 중 무엇인지 말하지 않았다.

**수정.** `TradePreparation`의 필드·파라미터·컬럼을 전부 `desiredEntryPremiumRate`/
`desired_entry_premium_rate`로 개명했다(`registerTarget` 파라미터, `evaluateCondition`의 내부
참조·예외 메시지 포함). `conditionFirstMetPremiumRate`는 관측값이라 개명하지 않았다. KDoc과
테스트 이름의 "희망 프리미엄"/"희망값"도 "진입 목표 프리미엄"/"진입 목표"로 바꿔 종료 목표와
구분되게 했다. `registerTarget`의 KDoc에 "종료 목표는 이 단위 범위 밖이며 실제 진입 후 여는
`Tracking`이 소유한다"를 명시했다 — **종료 목표 필드는 추가하지 않았다**(YAGNI, 팀장 지시).
migration(V16, T3)이 아직 없어 컬럼명 변경에 따른 마이그레이션 영향은 없다.

```
./gradlew :domain:test --tests '*TradePreparation*' --offline --no-daemon
./gradlew architectureTest --offline --no-daemon
```

결과: 둘 다 `BUILD SUCCESSFUL`. `tradeprep` 패키지 47건 전부 통과(개명 후 재실행, 실패 0건).

### T2 재리뷰 잔여 3건 (R1~R3) 반영 (2026-08-30)

**R1 (Important) — 오도성 테스트.** `DRAFT 계획도 체결 사건으로 직접 무효화된다` 테스트가 이름과
달리 `invalidateOnOwnerRefresh`를 호출해, `DRAFT`+`TRACKING_EVENT` 직접 무효화 경로가 파일
전체에서 한 번도 검증되지 않고 있었다. 본문을 `invalidateOnTrackingEvent` 호출·
`TRACKING_EVENT` assert로 교정해 이름이 주장하는 경로를 실제로 덮도록 했다.

**R2 — blank 가드 미검증.** `TradePreparation.create`와 `registerTarget` 양쪽의
`boundBalanceSnapshotId.isBlank()` 가드에 대응하는 테스트가 없었다. 빈 문자열·공백 문자열 두
입력 모두 `InvalidTradePreparationException`을 던지는지 검증하는 테스트 2건을
`TradePreparationSnapshotBindingTest`에 신설했다(`create` 1건, `registerTarget` 1건 — 후자는
실패 후 엔티티가 여전히 `DRAFT`로 남아 부분 변경이 없음도 확인한다).

**R3 — ARMED→INVALIDATED의 version 미검증.** `owner 명시 refresh가 ARMED 계획도 무효화한다`에
`version` assert가 없었다. 실측한 결과 `armedPlan()`(`create`(0)→`registerTarget`(1)→
`evaluateCondition`의 `ARMED` 전이(2))의 version은 `2`이고, `invalidateOnOwnerRefresh` 이후는
`3`이다 — 이 값을 그대로 assert에 반영했다(추정치가 아니라 실제 실행으로 확인).

```
./gradlew :domain:test --tests '*TradePreparation*' --offline --no-daemon
./gradlew architectureTest --offline --no-daemon
```

결과: 둘 다 `BUILD SUCCESSFUL`. `tradeprep` 패키지 49건(`TradePreparationSnapshotBindingTest`
5→7, `TradePreparationInvalidationTest` 14 유지) 전부 통과, 실패 0건.

### T6 — Interfaces REST (2026-08-30)

**대상 AC**: AC1·AC9·AC12·AC16·AC19 (설계 근거: D2·D3·D6·D10·D13·D17·D18·D20, §1.1·§3)

**신설 파일**: `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/tradeprep/`
`TradePreparationController.kt`·`TradePreparationRequest.kt`·`TradePreparationResponse.kt`,
`apps/api/src/test/.../tradeprep/TradePreparationControllerTest.kt`,
`apps/api/src/integrationTest/.../tradeprep/` `TradePreparationContractTestBase.kt`·
`TradePreparationContractTest.kt`·`TradePreparationAuthContractTest.kt`·
`TradePreparationOwnerScopeContractTest.kt`·`TradePreparationRegisterTargetContractTest.kt`·
`TradePreparationRegisterTargetStaleContractTest.kt`·`TradePreparationActiveTrackingContractTest.kt`,
`http/api/trade-preparations.http`. `http/README.md` 파일 목록 갱신.
`ApplicationError`·`GlobalExceptionHandler` 6개 매핑은 T5가 이미 추가해 두어 손대지 않았다.

**RED** — 계약 테스트를 먼저 작성했다. 테스트는 raw JSON + `jsonPath`/`JsonNode` 로만 쓰여
production DTO 타입을 참조하지 않으므로, controller 가 없는 상태에서도 컴파일된다. 즉 RED 는
컴파일 실패가 아니라 "경로가 없어 응답이 기대와 다르다"는 실패다.

```
./gradlew :apps:api:integrationTest --tests '*TradePreparationContract*' --tests '*TradePreparationAuth*' \
  --tests '*TradePreparationOwnerScope*' --tests '*TradePreparationRegisterTarget*' --offline --no-daemon
```

결과: `21 tests completed, 20 failed`. 통과한 1건은
`TradePreparationRegisterTargetContractTest > 판정용 port 빈이 없어 VerifiedBalance 를 만들 경로가 없다()`
로, 배선 부재를 확인하는 단언이라 controller 유무와 무관하게 옳게 통과한다. 나머지 20건은
`AssertionFailedError`(응답 status/키 집합 불일치)와 `NullPointerException`(`planId` 가 없는 오류
본문에서 계획 id 를 읽으려다 실패) 이며 컴파일 오류가 아니다.

**GREEN** — controller·Request/Response DTO 구현 후 동결된 명령을 **각각** 실행했다.

```
./gradlew :apps:api:integrationTest --tests '*TradePreparationContract*' --offline --no-daemon      # exit 0
./gradlew :apps:api:integrationTest --tests '*TradePreparationAuth*' --offline --no-daemon          # exit 0
./gradlew :apps:api:integrationTest --tests '*TradePreparationOwnerScope*' --offline --no-daemon    # exit 0
./gradlew :apps:api:integrationTest --tests '*TradePreparationRegisterTarget*' --offline --no-daemon # exit 0
./gradlew :apps:api:integrationTest --tests '*TradePreparationActiveTracking*' --offline --no-daemon # exit 0
```

상위 gate도 함께 실행했다.

```
./gradlew test architectureTest --offline --no-daemon        # BUILD SUCCESSFUL
./gradlew :apps:api:integrationTest --offline --no-daemon    # BUILD SUCCESSFUL, 54 classes / 162 tests / 실패 0
```

**AC16 — 교차 순서를 실제로 강제했다.** 순차 호출은 이 기준을 충족하지 못하므로
(나중 트랜잭션이 앞선 트랜잭션의 **커밋된** 상태를 읽어 잠금 없이도 통과한다) 두 방향 모두
두 트랜잭션이 동시에 열린 채 서로의 미커밋 상태를 보지 못하는 교차를 만들었다.

- 교차 ①: `TransactionTemplate` 로 tracking 생성 트랜잭션을 열어 member 를 `FOR UPDATE` 로 잠그고
  `ACTIVE` tracking 을 **미커밋**으로 유지한 상태에서 `registerTarget` HTTP 요청을 던진다.
- 교차 ②: 반대 순서. `registerTarget` 이 member 를 잠근 뒤 `resolveBinding` 단계에서 멈추도록
  `VerifiedBalanceReadPort` 를 빗장으로 쓰고, 그 사이 `POST /api/v1/trackings` 를 던진다.

각 방향에서 나중에 들어온 쪽이 **막힌다**는 것을 `Future.get(3s)` 의 `TimeoutException` 으로
확인하고, 빗장을 푼 뒤 최종 상태가 공존하지 않음(`countActiveByMemberId == 1` 이면서
`findActiveByOwnerId == null`)을 함께 단언한다.

**테스트가 무엇을 재는지 변이로 확인했다** — 통과하는 테스트는 아직 무엇을 측정하는지에 대한
증거가 아니다. production 호출을 하나씩 지우고 기대한 테스트만 실패하는지 본 뒤 되돌렸다.

| 변이 | 기대 | 실측 |
|---|---|---|
| `TradePreparationFacade.registerTarget` 의 `lockOwner` 제거 | 교차 ①·② 실패 | `4 tests completed, 2 failed` — 둘 다 `Expecting code to raise a throwable`(= 막히지 않고 통과) |
| `TrackingFacade.record` 의 `lockOwner` 제거 | 교차 ② 실패 | **`BUILD SUCCESSFUL`(실패 0)** — 아래 참고 |
| `TrackingFacade.record` 의 `invalidateActivePlanOnTrackingEvent` 제거 | 교차 ② 실패 | `4 tests completed, 1 failed` (교차 ②) |
| controller `statusOf` 를 항상 `CREATED` 로 고정 | AC1 캡 3건 실패 | `8 tests completed, 3 failed` |
| `Preparation.from` 이 `capViolations` 를 버림 | AC1 캡 본문 1건 실패 | `8 tests completed, 1 failed` |

**참고 — `TrackingFacade.lockOwner` 는 이 교차에서 잉여다.** 제거해도 교차 ②가 통과한다. 원인은
`fk_position_member`(V8, member(id) 참조)다: tracking `INSERT` 의 FK 검사가 부모 member 행에
공유 잠금을 걸어 `registerTarget` 의 배타 잠금과 충돌하므로, 애플리케이션 잠금이 없어도 같은
직렬화가 일어난다. 또 이 경로에는 `INSERT` 앞에 consistent read 가 없어 read view 가 늦게 열리고,
`findActiveByOwnerId` 가 커밋된 `WATCHING` 을 보게 되어 D17 무효화도 그대로 동작한다. **D18 의
tracking 쪽 잠금이 불필요하다는 뜻은 아니다** — archive 경로와 `INSERT` 앞에 조회가 오는 순서에는
FK 잠금이 없다. 다만 이 테스트가 그 잠금을 재지는 못한다는 사실을 기록해 둔다.

**AC19 가 두 클래스인 이유.** 동결 FQN 은 `TradePreparationRegisterTargetContractTest` 하나이지만,
"declared 원천만 있을 때"와 "verified 원천이 있고 `STALE` 일 때"는 `VerifiedBalanceReadPort` 빈이
있으면서 동시에 없어야 해 한 Spring context 에 담을 수 없다. 동결 FQN 이 declared 경로(production
배선, D22)를 갖고, `TradePreparationRegisterTargetStaleContractTest` 가 verified 경로를 갖는다.
동결된 명령 `--tests '*TradePreparationRegisterTarget*'` 가 두 클래스를 함께 잡는다.

**`PublicEndpointPolicy` 는 건드리지 않았다** (AC9). 그 타입은 `infrastructure:api` 에 있고
`apps:api` 는 `runtimeOnly` 로만 소비해 test compile classpath 에 없으므로, 목록을 문자열로
베끼는 대신 실행 중인 filter chain 에 직접 물어 판정한다 — 인증 없는 5종 401 과, 같은 method+path
인증 시 401·404·405 가 아님을 **짝으로** 확인한다(음성 판정만 두면 controller 를 지워도 통과한다).

### T6 수정 라운드 1 — 리뷰 반영 (2026-08-30)

**대상 AC**: AC16(교차 경로 확대) · AC1·AC3(422 error code 완결)

**[정정] `TrackingFacade.lockOwner` 에 대한 T6 최초 기록.** 위 T6 절이 그 잠금을 "아무 테스트도
지키지 않는다"로 읽히게 썼는데 틀렸다. 지우면 `TrackingFacadeTest` 2건(`:79`, `:212` 의
`verifyOrder`)이 실패한다 — 구조적 회귀는 `:apps:api:test` 가 잡는다. 없던 것은 실제 DB 로
write-skew 를 증명하는 **행위** 수준 테스트이며, 아래 교차 ③이 그것을 채운다.

**[Important 1] `recordFromMarket` 교차를 덮었다.** 리뷰어 실측대로 두 tracking 경로의 성질이
다르다.

| tracking 경로 | 무효화 SELECT 가 커밋된 계획을 보는가 |
|---|---|
| `record()` — `INSERT` 가 트랜잭션의 첫 DB 문장 | **본다.** FK(`fk_position_member`)가 write 를 직렬화하고 read view 도 그 뒤에 열린다 |
| `recordFromMarket()` — `INSERT` 앞에 consistent read | **못 본다.** `premiumService.findLatestSnapshot`(`TrackingFacade.kt:88`)이 read view 를 먼저 연다 |

`recordFromMarket` 이 production endpoint 다. 그 경로에서는 FK 가 write 를 직렬화해도 트랜잭션이
`registerTarget` 커밋 **이전** 스냅샷을 읽고 있어 `invalidateActiveOnTrackingEvent` 가 빈손이 되고,
`ACTIVE` tracking 과 `WATCHING` 계획이 공존 커밋된다. 여기서는 tracking 측 member 잠금(D18)이
유일한 방어다. AC16 동결 문안이 "tracking 생성과 `registerTarget`"이라 경로를 한정하지 않으므로
production 경로를 비워 두면 기준을 실질적으로 충족하지 못한다.

**교차 ③** 을 추가했다 — 교차 ②와 같은 순서(`registerTarget` 이 member 를 잠근 채 gate 에서 멈춤)
이되 tracking 을 `POST /api/v1/trackings/from-market` 으로 만든다.

> **경로 정정**: 리뷰 지시의 `POST /api/v1/trackings/auto` 는 존재하지 않는다. `recordFromMarket`
> 의 경로는 `TrackingController.kt:20` 의 `/from-market` 이고, `/auto` 는 Phase 0 에서 제거된 옛
> `positions/auto` 다(`TrackingRouteContractTest` 가 404 를 단언한다). 실제 경로로 작성했다.

**교차 ③의 판별력을 변이로 확인했다.** `TrackingFacade.recordFromMarket` 의 `lockOwner` 만 제거:

```
5 tests completed, 1 failed
TradePreparationActiveTrackingContractTest > recordFromMarket 교차에서도 tracking 생성이 직렬화돼 활성 계획을 무효화한다() FAILED
  org.opentest4j.AssertionFailedError: expected: null but was: io.premiumspread.domain.tradeprep.TradePreparation@...
  at ...assertNoCoexistence(TradePreparationActiveTrackingContractTest.kt:290)
```

timeout probe 가 아니라 `assertNoCoexistence` 에서 실패한다 — FK 가 write 는 여전히 직렬화하므로
"막힘"은 관측되지만, 계획이 `WATCHING` 으로 남아 `ACTIVE` tracking 과 **공존 커밋된다.** 교차 ②는
이 변이에서 통과한다(`BUILD SUCCESSFUL`). 즉 교차 ③이 교차 ②가 못 하던 판별을 한다. 원복 확인함.

**[부수] 공유 Redis 캐시로 인한 잠복 flake 를 함께 막았다.** `findLatestSnapshotByPair` 는 Redis
캐시를 먼저 읽는다(`JpaPremiumRepositoryAdapter:37`). Redis container 는 모듈 전체가 공유하므로
다른 클래스가 남긴 낡은 `observedAt` 이 `recordFromMarket` 의 60초 신선도 판정을 흔들 수 있다.
AC16 setUp 에 `flushAll` 을 추가했다.

**[Important 2] 422 는 전부 안정된 code 를 갖는다.** `isPlannable = finalQuantity > 0 &&
!capVerdict.isViolated` 라, 반올림으로 물량이 0 이 되고 캡은 위반하지 않은 경우 `code` 가 비어
있었다. 클라이언트가 `code` 로 분기하면 그 응답을 파싱 실패와 구별하지 못한다.
`ApplicationError.NOT_PLANNABLE` 을 **세 곳 짝으로** 추가하고(enum · `statusOf` → 422 ·
`ERROR_MESSAGES`), `code` 를 `capViolations` 있으면 `CAP_VIOLATED`, `!plannable` 이면
`NOT_PLANNABLE`, 아니면 `null` 로 준다. 응답 키 집합은 그대로라 AC1 단언에 영향이 없다.

실제 경로를 계약 테스트로 덮었다 — `koreaBalance=100000`·`foreignBalance=40` 이면
`koreaShare ≈ 0.636 >= 0.60`, `leverage = 0 < 7` 로 위반한 캡이 없는데 `rawQuantity ≈ 0.00077` 이
바이낸스 lot(0.001) 내림에서 0 이 된다.

**[Minor 3] id 스캔 단언을 행 수 단언으로 바꿨다.** `(1L..20L).mapNotNull { findById(it) }` 는
`DatabaseCleanUp` 이 `TRUNCATE` 라 AUTO_INCREMENT 가 리셋되기 때문에만 옳고, 정리가 `DELETE` 로
바뀌면 공허하게 통과한다. `SELECT COUNT(*) FROM trade_preparation WHERE owner_id = ?` 로 바꿨다
(`DRAFT` 는 `active_key` 가 `NULL` 이라 `findActiveByOwnerId` 로 잡히지 않아 테이블을 직접 센다).

**[Minor 4] 계약 base 의 fixture 시각을 고정했다.** `TradePreparationContractTestBase.savePremium`
의 `Instant.now()` → `FIXTURE_OBSERVED_AT = 2026-08-30T00:00:00Z`. 이 base 를 쓰는 계약은 시각을
단언하지 않고 신선도 경계도 건드리지 않는다.

AC16 클래스의 `savePremium` 은 `Instant.now()` 를 유지한다 — `recordFromMarket` 이 snapshot
`observedAt` 을 애플리케이션의 실제 clock 과 60초 창으로 비교하므로(`TrackingFacade.isFresh`)
고정 시각을 쓰면 신선도에서 거절돼 교차 ③이 성립하지 않는다. 이 값은 어떤 단언의 대상도 아니며
그 이유를 코드에 주석으로 남겼다.

**archive 경로는 손대지 않았다.** archive 가 성립하려면 커밋된 `ACTIVE` tracking 이 있어야 하고
그것이 곧 `registerTarget` 을 409 로 만들어 write-skew 교차 자체가 구성되지 않는다. archive 의
`lockOwner` 가 지키는 것은 교착 회피이고 `TrackingFacadeTest.kt:100` 이 이미 덮는다.

**검증** — 동결 명령 5개와 상위 gate 를 순차 실행했다.

```
./gradlew :apps:api:integrationTest --tests '*TradePreparationContract*' --offline --no-daemon      # exit 0
./gradlew :apps:api:integrationTest --tests '*TradePreparationAuth*' --offline --no-daemon          # exit 0
./gradlew :apps:api:integrationTest --tests '*TradePreparationOwnerScope*' --offline --no-daemon    # exit 0
./gradlew :apps:api:integrationTest --tests '*TradePreparationRegisterTarget*' --offline --no-daemon # exit 0
./gradlew :apps:api:integrationTest --tests '*TradePreparationActiveTracking*' --offline --no-daemon # exit 0
./gradlew :apps:api:test architectureTest --offline --no-daemon                                     # exit 0
```

### T9 — 전체 gate 와 DoD 증거 (2026-08-31)

**대상 AC**: 수용기준 20건 전부. 기계 검증 18건 + 사람 확인 2건.
**게이트 실행 SHA**: `e567b57` (`e567b570a394434facc5200b81de15afc16715bf`) — working tree clean.

**명령을 손으로 옮기지 않았다.** `.superpowers/sdd/plan/gate.sh` 가 위 동결된 수용기준 표에서
`./gradlew` 로 시작하는 검증 명령을 직접 추출해 순차 실행한다. 추출 결과가 18건이고 표의 T1
기준 수 18과 일치한다 — 옮겨 적다 빠뜨린 기준이 GREEN 으로 기록되는 경로를 없앤다.

**cache 가 아니라 실제 실행을 관측했다.** 처음 돌린 `./gradlew test architectureTest` 는 모든
test task 가 `UP-TO-DATE` 로 끝났고, 그 시점 `:apps:batch:integrationTest` 의 XML 에는 클래스
3개·13건만 있었다 — 직전 필터 실행(`--tests '*TradePreparationReconcile*'` 등)이 남긴 부분
결과다. 그 수치를 그대로 적으면 전체 스위트를 관측했다고 잘못 기록하게 된다. 그래서 unit·
architecture 는 `--rerun` 으로 강제 재실행하고, 통합은 필터 없는 전체 스위트로 다시 돌렸다.
아래 수치는 전부 그 실행의 산출이며, 재실행 로그에서 12개 test task 가 `UP-TO-DATE` 없이
실행된 것을 확인했다.

#### 기계 검증 18건 (`gate-results.txt` 그대로)

| # | 검증 명령 | 결과 |
|---|---|---|
| `AC1` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationContract*' --offline --no-daemon` | exit 0 |
| `AC2` | `./gradlew :domain:test --tests '*TradePreparationSizing*' --offline --no-daemon` | exit 0 |
| `AC3` | `./gradlew :domain:test --tests '*TradePreparationCap*' --offline --no-daemon` | exit 0 |
| `AC4` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationStaleBalance*' --offline --no-daemon` | exit 0 |
| `AC5` | `./gradlew :domain:test --tests '*TradePreparationSnapshotBinding*' --offline --no-daemon` | exit 0 |
| `AC6` | `./gradlew :domain:test --tests '*TradePreparationInvalidation*' --offline --no-daemon` | exit 0 |
| `AC11` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationConcurrency*' --offline --no-daemon` | exit 0 |
| `AC7` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationArming*' --offline --no-daemon` | exit 0 |
| `AC8` | `./gradlew architectureTest --offline --no-daemon` | exit 0 |
| `AC9` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationAuth*' --offline --no-daemon` | exit 0 |
| `AC12` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationOwnerScope*' --offline --no-daemon` | exit 0 |
| `AC13` | `./gradlew :domain:test --tests '*TradePreparationBalanceTrust*' --offline --no-daemon` | exit 0 |
| `AC14` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationProvenance*' --offline --no-daemon` | exit 0 |
| `AC16` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationActiveTracking*' --offline --no-daemon` | exit 0 |
| `AC17` | `./gradlew :apps:batch:integrationTest --tests '*TradePreparationEvaluationJob*' --offline --no-daemon` | exit 0 |
| `AC18` | `./gradlew :apps:batch:integrationTest --tests '*TradePreparationReconcile*' --offline --no-daemon` | exit 0 |
| `AC19` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationRegisterTarget*' --offline --no-daemon` | exit 0 |
| `AC20` | `./gradlew :apps:api:test --tests '*TradePreparationWiring*' --offline --no-daemon` | exit 0 |

**18건 중 18건 exit 0. 실패 0건. 실행하지 못한 항목 0건.**

#### 전체 스위트 수치 (필터 없는 실행)

| 스위트 | tests | failures | errors | skipped |
|---|---|---|---|---|
| `:domain:test` | 185 | 0 | 0 | 0 |
| `:apps:api:test` | 138 | 0 | 0 | 0 |
| `:apps:batch:test` | 70 | 0 | 0 | 0 |
| `:apps:api:integrationTest` | 171 | 0 | 0 | 0 |
| `:apps:batch:integrationTest` | 93 | 0 | 0 | 0 |
| `:infrastructure:common:integrationTest` | 18 | 0 | 0 | 0 |
| `:architectureTest` | 25 | 0 | 0 | 0 |
| `:infrastructure:common:test` | 43 | 0 | 0 | 0 |
| `:infrastructure:api:test` | 31 | 0 | 0 | 0 |
| `:infrastructure:batch:test` | 71 | 0 | 0 | 0 |

migration 통합 test 는 `V16TradePreparationMigrationIntegrationTest` 2건,
`V17TradePreparationIndexMigrationIntegrationTest` 2건이다.

**skipped 가 전 스위트 0 이다.** `@Disabled` 로 미룬 계약이 없다는 뜻이다. 저장소 전체에서
`@Disabled` 문자열이 나오는 곳은 그것을 금지하는 `TestIsolationArchitectureTest` 한 곳뿐이다.

#### 회귀 방어선 R1~R6

| # | 검증 명령 | 결과 |
|---|---|---|
| R1 | `./gradlew test architectureTest --offline --no-daemon` | exit 0 (`BUILD SUCCESSFUL in 5m 3s`) |
| R2 | `./gradlew :apps:api:integrationTest --offline --no-daemon` | exit 0 — 171건 전부 통과 |
| R3 | `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` | exit 0 (`BUILD SUCCESSFUL in 7s`) |
| R4 | `npm --prefix apps/web run lint` | exit 0 — eslint 무출력 |
| R5 | `npm --prefix apps/web run test` | exit 0 — Test Files 1 passed, Tests 10 passed |
| R6 | `npm --prefix apps/web run build` | exit 0 — 6개 route 빌드 |

`bash docs/check-documentation.sh` → exit 0 (`documentation check passed (20 files, 15 required paths)`).

이 단위는 `apps/web` 을 한 줄도 바꾸지 않았다 (`git diff --name-only 920e027..HEAD -- apps/web`
= 0건). 그래도 R4~R6 은 게이트 항목이므로 실제로 돌리고 결과를 적었다.

#### 사람 확인 2건은 채우지 않았다

`AC10`·`AC15` 는 T4 이며 판정 주체가 사람이다. 아래 `## 사람 확인 (T4)` 표는 비워 둔다 —
앵커를 AI 가 지어내면 그것이 곧 기록 위조다. 두 건 모두 `AWAITING_HUMAN` 이다.

- `AC10` — owner 가 응답의 물량·레버리지·캡 판정을 자기 실제 잔고와 대조해야 한다. 잔고를
  아는 사람만 판정할 수 있다. 덧붙여 D19 에 따라 production 배선의 도달 상태는 `WATCHING`
  까지이고 실계정 대조는 `ACT-2` 이후에만 가능하다
- `AC15` — 근거는 위 `## 스펙 리뷰 라운드 기록` 표(1R~5R, 지적 14건 전부 ACCEPT, 5R 에서
  high 1건으로 수렴, 상한 도달 처리 A 를 owner 가 승인)가 이미 갖고 있다. 그래도 "충분히
  돌았는가" 의 판정 주체는 사람이므로 서명 칸을 비워 둔다

#### 계획 대비 차이

계획 시점의 범위 실측은 28커밋 / 82파일 / +8306 -31 이었다. T9 실행 시점은 **33커밋 / 94파일 /
+9788 -31** 이다 — 계획서 작성 이후 T8 마무리 커밋 5건이 더 쌓인 결과이며 범위 변경이 아니다.
같은 이유로 `:apps:api:test` 는 127 → 138, `:apps:batch:test` 는 65 → 70 으로 늘었다.
`:apps:api:integrationTest` 171 · `:apps:batch:integrationTest` 93 · V16 2 · V17 2 는
리뷰어 실측치와 정확히 일치한다.

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
| AC2·AC3·AC5·AC6·AC13·AC20 검증 명령 | `./gradlew test --tests '…'` | `./gradlew :domain:test --tests '…'` (AC20은 `:apps:api:test`) | **동결된 명령이 GREEN 구현에서도 exit 0을 낼 수 없다.** 루트 `test`는 전 모듈에 필터를 전파하고, 매칭 테스트가 없는 모듈에서 gradle이 `No tests found`로 실패시킨다. T1 실측: 루트 형태 `BUILD FAILED`(`:apps:api:test`), 모듈 지정 형태 `BUILD SUCCESSFUL`. 기준 문장은 그대로이고 그 문장을 실제로 검사하지 못하던 명령을 고친다 (Phase 0 `CR-3`와 동형) | **승인 (2026-08-30, owner)** |
| `AC3` 검증 명령 (**CR-2**) | `./gradlew :domain:test --tests '*TradePreparationCap*'` | `./gradlew :apps:api:integrationTest --tests '*TradePreparationContract*'` (AC1 과 동일) | **동결 명령이 동결 문장을 검사하지 못한다.** AC3 문장은 "…위반한 캡을 **응답에** 명시한다"로 끝나는데 지정 명령은 `TradePrepPolicy.judge` 순수 단위 테스트라 응답이 범위 밖이다. 응답 동작은 이미 `TradePreparationContractTest` 가 덮으므로 검증 공백은 아니다 — 명령이 자기 문장을 가리키지 못할 뿐이다. `CR-1` 과 동형 | 대기 |
| `AC3` 기준 문장 (**CR-3**) | "레버 캡·효율 캡·**청산 거리** 중 하나라도 위반하면" | "레버 캡·효율 캡 중 하나라도 위반하면" | 코드는 청산 거리를 독립 위반 대상으로 모델링하지 않는다. `CapVerdict` KDoc 이 청산 거리 `= 1/L` 이라 `LEVERAGE_CAP` 에 포섭된다고 논증하고 그 논증은 타당하다. 다만 동결 문장과 코드가 다르므로 문장을 코드에 맞추거나 별도 위반 값을 추가해야 한다 | 대기 |
| `AC5` 검증 수단 (**CR-4**) | `TradePreparationSnapshotBindingTest` | 위 + no-cache 계약을 검사하는 신규 테스트 | AC5 첫 문장 "판정용 잔고를 **캐시에서 읽을 수 없다**"를 **어느 테스트도 재지 않는다.** 규칙이 `VerifiedBalanceReadPort` KDoc 에만 있어 `ACT-2` 에서 캐시하는 `ExchangeBalanceAdapter` 가 들어와도 잡을 장치가 없다 — 이 기준이 막으려는 바로 그 시점에 작동하지 않는다. 현재는 판정용 구현이 0개라 무해하다 | 대기 |

### CR-1. T1 검증 명령의 구조적 실패 (2026-08-30, 구현 중 발견)

**문제.** 위 6개 AC의 검증 명령이 루트 `./gradlew :domain:test --tests '…'` 형태다. 루트 `test` task는
필터를 모든 모듈에 전파하며, 필터에 매칭되는 테스트가 없는 모듈의 `test` task는
`No tests found for given includes`로 **실패**한다. `TradePreparation*` 테스트는 `:domain`에만
있으므로 `:apps:api:test`가 실패하고 전체가 `BUILD FAILED`가 된다.

**실측 (T1, commit `e42a14a`)**

```
./gradlew test --tests '*TradePreparationSizing*' --offline --no-daemon
  → :apps:api:test FAILED — No tests found for given includes
  → BUILD FAILED

./gradlew :domain:test --tests '*TradePreparationSizing*' --tests '*TradePreparationCap*' \
  --tests '*TradePreparationBalanceTrust*' --offline --no-daemon
  → BUILD SUCCESSFUL
```

**성격.** 기준을 약화하지 않는다. 수용기준 문장("`R`·`L`·`Q` 산출이 관계식과 일치한다" 등)은
그대로이고, 그 문장을 검사하지 못하던 명령을 고친다. 구현이 옳아도 통과할 수 없는 명령이므로
정정하지 않으면 DoD 판정 자체가 불가능하다.

**영향 범위.** AC2·AC3·AC13(T1), AC5·AC6(T2), AC20(T6/T5 배선 — `:apps:api:test`).
`:apps:api:integrationTest`·`:apps:batch:integrationTest`를 쓰는 AC는 이미 모듈이 지정돼 있어
영향 없다.

## 최종 판정

> 아래 수는 전부 위 수용기준 표에서 센 값이다. 표와 어긋나면 판정을 낼 수 없다.
> 판정이 가리키는 SHA와 브랜치 최종 SHA가 다르면 그 판정은 만료다.

```
DoD VERDICT: private-live-autotrader-trade-preparation @ 44c4b17
  수용기준 표:     20개  (T1 18 · T2 0 · T3 0 · T4 2)
  T1/T2 자동:      18개 중 18개 PASS   (전부 --rerun 강제 실행)
  T3 기록 제출:    0개
  T4 사람 확인:    2개 중 0건 완료, 2건 대기
  변경 요청:       1건 승인 (CR-1) · 3건 대기 (CR-2·CR-3·CR-4)
  => AWAITING_HUMAN — 기계 검증 18/18 PASS, 실행 실패·미실행 0건.
     AC10·AC15 에 사람 서명이 기록되면 DONE.
```

**이 판정은 `e567b57` 기준 이전 판정을 대체한다.** 아래 정정을 함께 읽어야 한다.

### 정정 1 — `AC12` 를 PASS 로 기록한 것이 사실이 아니었다

`e567b57` 게이트는 `AC12` 의 동결 명령이 exit 0 임을 관측하고 18/18 PASS 로 기록했다. 명령은
실제로 통과했다. 그러나 **기준의 셋째 문장이 구현되지 않은 상태였다** —

> 허가된 owner 가 아닌 회원의 생성 요청은 거절된다

당시 `prepare` 에는 owner 검사가 없었고, `POST /api/v1/members/register` 가 공개 endpoint 이므로
**아무나 가입 → 로그인 → `POST /api/v1/trade-preparations` → 201 + 계획 행**이 성립했다.
명시된 테스트(`TradePreparationOwnerScopeContractTest`)는 principal 도출과 타 회원 404 만 덮었고
비허가 회원의 **생성 거절**은 어느 테스트에도 없었다. **명령은 통과했으나 그 명령이 기준의
셋째 문장을 검사하지 않았다.**

발견 경로: 태스크별 리뷰 9회는 각자 범위 안에서 clean 이었다. 이 결함은 **전체 브랜치 최종
리뷰**가 동결 문장과 구현을 대조해 드러냈다.

**조치 — 기준을 고치지 않고 구현을 채웠다.**
- `TradePreparationOwnerPolicy`(Domain) + `trade-preparation.owner.allowed-emails` 도입.
  `prepare` 와 `registerTarget` 에 허가 검사. **빈 목록은 전원 거부(fail-closed)**
- `invalidate`·`refresh`·`findById` 는 제외 — owner-scoped 라 비허가 회원에게는 보이는 계획이
  0건이고, 막으면 허가가 회수된 owner 가 잔여 계획을 정리할 수 없다
- 동결 명령이 덮는 케이스 4 → 7. 거절 시 **계획 행 미생성**까지 단언한다
- 검증: 검사를 제거하면 새 케이스 2건이 `expected: 404 but was: 201` 로 실패함을 확인 후 원복

### 정정 2 — `AC8` 의 동결 명령이 검사 대상 변경에 재실행되지 않았다

`architectureTest` 가 ArchUnit 으로 읽는 jar 여섯 개 중 `infrastructure:common`·`api`·`batch`
셋이 **task 의 선언된 input 이 아니었다.** `dependsOn` 은 task 의존성이지 file input 이 아니고,
경로는 `doFirst` 안에서 주입돼 up-to-date 검사 이후였다.

실측: `infrastructure/common/src/main` 에 클래스를 추가하니 그 jar 은 재생성됐는데
`architectureTest` 는 `UP-TO-DATE`, 테스트 0건, exit 0 이었다. **그리고 이 단위가
`infrastructure/common` 과 `infrastructure/batch` main 을 고쳤다** — 이번 게이트가 봐야 할 변경을
보지 못한 것이다. 누락된 input 을 선언해 고쳤고, 같은 조작에 이제 task 가 실행된다.

**함의:** 이 수정 이전에 `architectureTest` 로 기록된 GREEN 은 `infrastructure:*` 변경을 검사하지
않았을 수 있다.

### 정정 3 — 이번 게이트는 전부 `--rerun` 강제 실행이다

`e567b57` 게이트에서 첫 `./gradlew test architectureTest` 가 **전 태스크 UP-TO-DATE 로 exit 0** 을
냈다. 그 시점 배치 통합 XML 에는 이전 필터 실행(`--tests '*TradePreparationReconcile*'`)의 잔여
13건이 남아 있었다 — 그대로 기록했으면 필터된 캐시를 전체 스위트 관측으로 적었을 것이다.

동결 명령 자체에는 캐시 방지 장치가 없다. 이번 판정은 18건 전부에 `--rerun` 을 붙여 실행했고,
아래 전체 스위트 수치도 강제 재실행 결과다.

### 이번 게이트의 전체 스위트 (`44c4b17`, 전부 `--rerun`)

| 스위트 | tests | failures | errors | skipped |
|---|---|---|---|---|
| `:domain:test` | 187 | 0 | 0 | 0 |
| `:apps:api:test` | 143 | 0 | 0 | 0 |
| `:apps:api:integrationTest` | 174 | 0 | 0 | 0 |
| `:apps:batch:test` | 70 | 0 | 0 | 0 |
| `:apps:batch:integrationTest` | 93 | 0 | 0 | 0 |
| `:infrastructure:common:test` | 43 | 0 | 0 | 0 |
| `:infrastructure:common:integrationTest` | 18 | 0 | 0 | 0 |
| `:infrastructure:api:test` | 31 | 0 | 0 | 0 |
| `:infrastructure:batch:test` | 71 | 0 | 0 | 0 |
| `architectureTest` | 25 | 0 | 0 | 0 |

`verifyMigrations` exit 0. **`skipped 0` 은 그 자체로 증거다** — 저장소에서 `@Disabled` 문자열이
나오는 곳은 그것을 금지하는 `TestIsolationArchitectureTest` 하나뿐이다.

**SHA 유효성.** 게이트는 `e567b57` 에서 clean working tree 로 실행했다. 이 판정을 기록하는
커밋이 브랜치 tip 을 한 칸 옮기지만 그 커밋의 변경은 이 파일과 T9 보고서뿐이며 production·
test 코드는 한 줄도 바뀌지 않는다 — `git diff --stat e567b57..HEAD` 로 확인할 수 있다.
코드가 바뀌는 커밋이 그 뒤에 붙으면 이 판정은 만료이고 게이트를 다시 돌려야 한다.

**사람 확인이 필요한 항목**

- AC10
- AC15
