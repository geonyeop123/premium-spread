---
feature: 기준 포지션 대비 단계 알림 — 포지션 없는 기간 포함
slug: issue-131-reference-position-step-alert
status: FROZEN
frozen_at: 2026-11-11
verdict_commit: 722cc72
source: https://github.com/geonyeop123/premium-spread/issues/131 (상위 #128, 선행 #129) · design.md
---

## 범위

**포함**

- 판정 값 = 기준 포지션 매수김프 대비 현재 김프 차이(%p), #129 `PositionObservation` 재사용 (design D1)
- 국면 판별: 보유(헤지 성립)·무포지션(완전 flat + 기준 있음)·판정 안 함(한쪽 leg·실패·기준 없음) (D2)
- 무포지션 기준 조회 port `AccountReferencePositionReadPort` + JDBC 구현 (D2)
- 기준 식별(평단 2값)과 전환 시 단계 초기화 (D3)
- 단계 폭 경계 히스테리시스 판정, 초기화 회차 무알림 (D4)
- 새 알림 종류 `REFERENCE_STEP`·문구·eventKey, 기본 경로 Discord PNL (D5·D6)
- V25 상태 3열과 저장·복원 (D7)
- env `ACCOUNT_PNL_REFERENCE_STEP_PERCENT` (D8)
- runbook `docs/runbooks/account-pnl-alert.md`, 문구 정본 `docs/work/alert-message-template/message-spec.md` 갱신

**제외** *(명시적으로 하지 않는 것 — scope creep 차단선)*

- 수수료·펀딩 반영 판정, 확정 손익(#130)
- 한 회차 여러 경계 건너뜀의 경계별 다건 발송
- 기존 알림(수익·정시 급변·정시 보고·포지션 변경·리마인더) 규칙·문구 변경
- #129 이력 기록 규칙 변경, 조회 API·화면

## 기존 gate 조사

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있음 — `./gradlew architectureTest` (모듈 의존, 앱의 infrastructure import 금지, `PrivateAccountReadOnlyArchitectureTest`) | 예 — R2. 새 port·adapter 배치 |
| lint 규칙 | 로컬 `lint` task 없음. ktlint·detekt 는 CI `5. ktlint + detekt` | CI 에서만 |
| custom gradle task | `:infrastructure:common:verifyMigrations`, `:infrastructure:common:integrationTest`, `:apps:batch:test`, `:domain:test` | 예 — R3·R5 실행 수단 |
| CI job | `.github/workflows/quality-gate.yml` | 최종 SHA 회귀 확인 |
| 라우팅 표 계약 | `OwnerAlertRoutingTest`(종류 이름 목록 대조), `OwnerAlertYamlContractTest`(모든 종류의 yml route 키) | 예 — R6. 새 종류 추가 시 이 두 테스트의 기대 목록은 갱신 대상이다(아래 R1 의 무수정 대상 아님) |
| migration 테스트 관례 | `V24AccountPositionHistoryMigrationIntegrationTest` | 예 — AC6 의 V25 판 |

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | 단계 폭 0.50 에서 같은 기준으로 판정 값이 0.10→0.50→0.70→1.00 으로 오르면 상승 판정이 0.50·1.00 에서 각 1건(총 2건)이고, 0.50↔0.45↔0.55 진동 입력은 0건이다. 0.50 에서 −0.10 으로 내려가면 0 경계 하락 1건, 한 회차에 0.10→1.60 이면 1.50 경계 1건만 낸다. 기준 전환 회차(상태 없음 · 기준 평단 변경 · 직전 국면 FLAT 에서 HOLDING, 평단이 같아도)는 `level = trunc(g/w)` 로만 바꾸고 0건이다. 수량만 바뀐 HOLDING 과 HOLDING→FLAT 은 전환이 아니다 | 이슈 수용 1·4, 요구 "같은 경계 반복 방지"·"새 포지션 기준 전환", design D4·D5 | `요구` · `추론`(초기화 무알림, 다중 경계 1건, 수량 무시) · `리뷰`(codex ISSUE-1 같은 평단 재진입) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.ReferenceStepPolicyTest` | `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.ReferenceStepPolicyTest --offline --no-daemon` | exit 0 |
| AC2 | 헤지 성립 회차는 HOLDING(현재 leg 기준), 완전 flat + 상태 기준 있음은 FLAT(그 기준), 완전 flat + 상태 기준 없음은 부트스트랩 기준, 한쪽 leg 만·기준 없음·관측 계산 불가는 판정 값 null 이다. 같은 leg·호가에서 환율만 1300→1400 으로 바꾼 입력을 이어 넣으면, 차이 0 기준과 단계 중간(0.30) 기준에서는 판정 정책이 알림 0건을, 경계 직전(0.49) 기준에서는 현재 환율로 계산한 차이가 0.50 이상이 되어 상승 1건을 낸다(실시간 환율 반영, 계약 승인 결정) | 이슈 수용 3, 위험 2, design D1·D2, 계약 승인 사용자 결정(실시간 환율 반영) | `요구` · `추론`(한쪽 leg 생략) · `리뷰`(codex ISSUE-3 경계 직전) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.ReferenceGapTest` | `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.ReferenceGapTest --offline --no-daemon` | exit 0 |
| AC3 | Job 회차를 이어 실행할 때: 보유 중 차이가 +0.5·+1.0 을 통과하면 `REFERENCE_STEP` outbox 행이 각 1건, 이어 전량 정리 뒤 무포지션 회차에서 차이가 0·−0.5·−1.0 에 도달하면 각 1건이며 0 도달 행의 제목에 `직전 진입 수준 도달` 이 들어간다. 이력 store 가 모든 회차에서 예외를 던져도 같은 행이 나온다. 부트스트랩 port 가 예외를 던지면 그 회차의 `JobResult`·다른 알림 행은 port 가 없을 때와 같고 단계 상태는 바뀌지 않는다 | 이슈 수용 1·2, design D3·D8 | `요구` · `리뷰`(codex ISSUE-2 이력 실패) · `추론`(조회 실패 격리) | T1 | `신규테스트` `io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobReferenceStepTest` | `./gradlew :apps:batch:test --tests io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobReferenceStepTest --offline --no-daemon` | exit 0 |
| AC4 | 단계 상태(기준 leg 4값·level·국면)를 저장한 뒤 새 store 인스턴스로 `load` 하면 같은 값이 나오고, V25 이전 행(새 열 NULL)은 `ReferenceStepState.INITIAL` 로 읽힌다. 일부 열만 NULL 인 행은 DB 제약으로 거부된다 | 이슈 수용 5 "재시작 후 단계 상태 유지", design D7 | `요구` | T1 | `신규테스트` `io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPnlAlertStoreIntegrationTest` 의 `referenceStep*` 메서드 | `./gradlew :infrastructure:common:integrationTest --tests io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPnlAlertStoreIntegrationTest --offline --no-daemon` | exit 0 |
| AC5 | `findLatestReference` 는 열린 건에 reference 가 있으면 그것, 없으면 reference 가 있는 가장 최근 닫힌 건의 reference, 둘 다 없으면 null 을 돌려준다 | design D3 부트스트랩, 이슈 위험 2 | `요구`(직전 포지션 기준) · `추론`(한쪽 leg 열림) | T1 | `신규테스트` `io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPositionHistoryStoreIntegrationTest` 의 `latestReference*` 메서드 | `./gradlew :infrastructure:common:integrationTest --tests io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPositionHistoryStoreIntegrationTest --offline --no-daemon` | exit 0 |
| AC6 | V25 가 V24 상태의 기존 행에 적용되면 새 열이 모두 NULL 이고 기존 열 값이 그대로다 | design D7 | `추론`(상태 지속의 전제) | T1 | `신규테스트` `io.premiumspread.infrastructure.common.migration.V25ReferenceStepStateMigrationIntegrationTest` | `./gradlew :infrastructure:common:integrationTest --tests io.premiumspread.infrastructure.common.migration.V25ReferenceStepStateMigrationIntegrationTest --offline --no-daemon` | exit 0 |
| AC7 | 문구는 보유 `기준 대비 김프`, 무포지션 `직전 포지션 대비 김프`, 무포지션 level 0 `직전 진입 수준 도달` 제목을 쓰고 eventKey 는 `account-pnl-reference-step-{up\|down}-{level}-{observedAt ms}` 다 | 이슈 "0 도달 문구 구분", design D6 | `요구` · `추론`(eventKey 형식) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.ReferenceStepContentTest` | `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.ReferenceStepContentTest --offline --no-daemon` | exit 0 |
| AC8 | `REFERENCE_STEP` 기본 경로는 email=false·Discord PNL·SOUND 다 | 이슈 "라우팅 OwnerAlertKind, 기본 Discord pnl", design D6 | `요구` | T1 | `신규테스트` `io.premiumspread.domain.owneralert.OwnerAccountAlertKindTest` 의 `referenceStep*` 메서드 | `./gradlew :domain:test --tests io.premiumspread.domain.owneralert.OwnerAccountAlertKindTest --offline --no-daemon` | exit 0 |
| AC9 | `account-pnl-monitor.account-alerts.reference-step-percent` 미설정이면 `AccountAlertSettings.referenceStepWidth` 가 0.50, `1.0` 이면 1.0 이고, 0 이하면 설정 생성이 `referenceStepWidth must be positive.` 로 실패한다 | 이슈 "단계 폭 기본 0.5%p, env", design D8 | `요구` | T1 | `신규테스트` `io.premiumspread.config.AccountPnlMonitorReferenceStepWiringTest` | `./gradlew :apps:batch:test --tests io.premiumspread.config.AccountPnlMonitorReferenceStepWiringTest --offline --no-daemon` | exit 0 |
| AC10 | owner 가 실계정 로컬 batch 로 Discord PNL 채널에서 단계 알림 1건 이상을 받는다 | 이슈 배경 | `요구` | T4 | 사람 육안 — runbook 체크리스트 | — | owner 가 PR 코멘트로 확인 |

**기준 개수**: 10개

**분할하지 않는 이유**: AC1~AC9 는 알림 하나를 계층별(정책·국면·Job·상태 저장·기준 조회·migration·문구·라우팅·설정)로 나눠 잰 것이다. 이슈를 나누면 어느 쪽도 혼자서는 알림을 보내지 못해 배포 단위가 되지 않는다.

**티어 강등 사유**

- AC10 → T4: 실계정 credential 과 Discord webhook 은 owner 로컬에만 있다. 테스트·CI 에 실제 주소를 넣지 않는 것이 저장소 규칙이다(`.ai/rules/testing.md`). AC1~AC9 가 같은 동작을 fixture 로 잰다.

## 검사 산출물

해당 없음(신규스크립트 없음).

## 회귀 방어선

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 이슈 수용 6: 기존 알림(1% 수익·정시 기준 급변·정시 보고) 테스트가 **수정 없이** 통과한다 | `git diff --exit-code dev -- domain/src/test/kotlin/io/premiumspread/domain/accountpnl/PnlAlertPolicyTest.kt domain/src/test/kotlin/io/premiumspread/domain/accountpnl/NetPercentSwingPolicyTest.kt apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJobHourlyReportTest.kt apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJobTest.kt apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJobAccountAlertTest.kt` · `./gradlew :domain:test :apps:batch:test --offline --no-daemon` |
| R2 | 모듈 경계 | `./gradlew architectureTest --offline --no-daemon` |
| R3 | 기존 저장소·통합 동작 | `./gradlew :infrastructure:common:integrationTest :apps:batch:integrationTest --offline --no-daemon` |
| R4 | 문서 검사 | `bash docs/check-documentation.sh` |
| R5 | migration 버전 유일성·파괴 SQL gate·빈 DB/이전→최신 경로 | `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` |
| R6 | 라우팅 표·yml route 키 계약(새 종류 추가로 기대 목록 갱신 허용) | `./gradlew :domain:test --tests io.premiumspread.domain.owneralert.OwnerAlertRoutingTest --offline --no-daemon` · `./gradlew :apps:batch:test --tests io.premiumspread.config.OwnerAlertYamlContractTest --offline --no-daemon` |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

### 기준선 (작성 시점, 구현 전)

```
[기준선] 2026-11-11 43f8833
$ ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon
  첫 시도 offline 캐시 누락(org.slf4j:slf4j-api:1.7.36)으로 설정 단계 실패 → 온라인 1회로 캐시 채움 → exit 0
$ git diff --exit-code dev -- (R1 대상 5파일)
  exit 0
$ ./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.ReferenceStepPolicyTest --offline --no-daemon
  ':domain:test' 실패 — 클래스 없음(No tests found). RED 아님. 진짜 RED 는 구현 태스크에서 남긴다
```

### 구현 RED (태스크별 implementer 보고 — .superpowers 작업 공간, 2026-11-11)

```
[RED] 095f923 AC1·AC2 — 시그니처 스텁(decide 무변화, of null) 상태: 32 tests completed, 23 failed (단언 실패)
[RED] b20bc55 AC4·AC5·AC6 — port 스텁 상태: 44 tests completed, 7 failed
  V25 테스트 2·store referenceStep 3: 새 열 부재 SQL 오류(예상된 RED), latestReference 2: findLatestReference()!! NPE
[RED] 5fc8950 AC3 — Job 의 decide(..., gap) 에서 gap 제거: 6 tests completed, 3 failed (단언 실패)
[RED] 1e9fda7 최종 리뷰 수정 — 폭 하한·판정 실패 격리·accountAlerts null·기동 0.001 테스트 4건 실패 후 GREEN
```

### 사후 mutation RED (AC7·AC8·AC9 — 구현 중 RED 미캡처분, 2026-11-11 @ 722cc72, 원복 후 GREEN)

```
[RED] ReferenceStepContent 제목 '직전 진입 수준 도달' → '직전 진입 수준', REFERENCE_STEP push SOUND → SILENT
$ ./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.ReferenceStepContentTest --tests io.premiumspread.domain.owneralert.OwnerAccountAlertKindTest --offline --no-daemon --continue
  ReferenceStepContentTest > 무포지션이고 level 0 이면 직전 진입 수준 도달() FAILED
  OwnerAccountAlertKindTest > 계정 알림 네 종류의 기본 경로는 설계 표와 같다() FAILED
  OwnerAccountAlertKindTest > referenceStep 은 메일 override 를 켜면 메일도 간다() FAILED
  13 tests completed, 3 failed
[RED] 설정 fallback reference-step-percent:0.50 → 0.60
$ ./gradlew :apps:batch:test --tests io.premiumspread.config.AccountPnlMonitorReferenceStepWiringTest --offline --no-daemon
  AccountPnlMonitorReferenceStepWiringTest > 미설정이면 단계 폭은 0_50 이다() FAILED — 6 tests completed, 1 failed
[GREEN] git checkout 원복 후 같은 명령 BUILD SUCCESSFUL
```

### GREEN (전체 검증, e45ba08 — 이후 커밋 722cc72 는 문서만)

```
$ ./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon
  BUILD SUCCESSFUL in 2m 45s, FAILED 0
  AC1 ReferenceStepPolicyTest 20/20 · AC2 ReferenceGapTest 12/12 · AC3 AccountPnlMonitorJobReferenceStepTest 8/8
  AC4 JdbcAccountPnlAlertStoreIntegrationTest 28/28 · AC5 JdbcAccountPositionHistoryStoreIntegrationTest 14/14
  AC6 V25ReferenceStepStateMigrationIntegrationTest 2/2 · AC7 ReferenceStepContentTest 8/8 · AC8 OwnerAccountAlertKindTest 5/5
  AC9 AccountPnlMonitorReferenceStepWiringTest 6/6 · R6 OwnerAlertRoutingTest 11/11, OwnerAlertYamlContractTest 2/2
  R2 architectureTest·R3 integrationTest·R5 verifyMigrations 포함 PASS
$ git diff --exit-code dev -- (R1 대상 5파일)   @ 722cc72 → exit 0
$ bash docs/check-documentation.sh            @ 722cc72 → documentation check passed (20 files, 15 required paths)
```

## 사람 확인 (T4)

| # | 확인 사항 | 확인자 | 날짜 | 앵커 |
|---|---|---|---|---|
| AC10 | | | | |

## 변경 요청

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|

## 최종 판정

```
DoD VERDICT: issue-131-reference-position-step-alert @ 722cc72
  수용기준 표:     10개  (T1 9 · T2 0 · T3 0 · T4 1)
  T1/T2 자동:      9개 중 9개 PASS
  T3 기록 제출:    0개 중 0건
  T4 사람 확인:    1개 중 0건 완료, 1건 대기
  변경 요청:       0건
  => AWAITING_HUMAN
```

**사람 확인이 필요한 항목**

- AC10: owner 가 실계정 로컬 batch 로 Discord PNL 채널에서 단계 알림 1건 이상 수신 — runbook "기준 포지션 단계 알림 체크리스트 (#131 AC10)" 후 PR 코멘트로 확인
