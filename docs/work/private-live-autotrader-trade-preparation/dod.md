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
DoD VERDICT: private-live-autotrader-trade-preparation @ <commit SHA>
  수용기준 표:     20개  (T1 18 · T2 0 · T3 0 · T4 2)
  T1/T2 자동:      18개 중 <p>개 PASS
  T3 기록 제출:    0개
  T4 사람 확인:    2개 중 <r>건 완료, <2-r>건 대기
  변경 요청:       <k>건
  =>
```

**사람 확인이 필요한 항목**

- AC10
- AC15
