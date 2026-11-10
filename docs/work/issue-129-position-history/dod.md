---
feature: 실계정 포지션 기록과 매분 관측 이력 저장
slug: issue-129-position-history
status: DRAFT
frozen_at:
verdict_commit:
source: https://github.com/geonyeop123/premium-spread/issues/129 (상위 #128, 2026-11-10 대화 합의) · design.md
---

## 범위

**포함**

- 포지션 한 건(어느 한 leg 라도 있으면 열림 → 둘 다 비면 닫힘)의 열림·닫힘 기록 (design D1)
- 건 안의 사건 `OPENED`·`INCREASED`·`REDUCED`·`ADJUSTED`·`CLOSED` 기록 (D2)
- 건마다 기준 평단(reference) 유지 (D3)
- 매분 관측 이력 `HOLDING`·`FLAT` 적재 (D4)
- 기존 저장 뒤 별도 트랜잭션으로 기록, 실패 격리 (D5)
- V24 migration 3개 테이블, env `ACCOUNT_PNL_POSITION_HISTORY_ENABLED` (D6·D7)
- runbook `docs/runbooks/account-pnl-alert.md` 에 이력 절 추가

**제외** *(명시적으로 하지 않는 것 — scope creep 차단선)*

- 확정 손익·체결 조회 (#130), 단계 알림·재진입 알림 (#131)
- 조회 API·화면·보고서
- 기존 알림 규칙·문구, `account_pnl_latest`, `account_pnl_monitor_state` 변경
- 이력 보존 기간·삭제 작업
- 과거 로그에서 이력 역이행

## 기존 gate 조사

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있음 — `./gradlew architectureTest` (모듈 의존·앱의 infrastructure import 금지, `PrivateAccountReadOnlyArchitectureTest`) | 예 — R2. 새 port·adapter 배치가 경계를 지키는지 |
| lint 규칙 | `lint` task 없음. ktlint·detekt 는 CI `5. ktlint + detekt` | CI 에서만. 로컬 gate 로 쓰지 않는다 |
| custom gradle task | `:infrastructure:common:verifyMigrations`(빈 DB·이전 버전→최신, 버전 유일성, 파괴 SQL gate), `:infrastructure:common:integrationTest`, `:apps:batch:test`, `:domain:test` | 예 — AC5·R3, 신규 테스트의 실행 수단 |
| CI job | `.github/workflows/quality-gate.yml` 7개 잡 | 최종 SHA 의 회귀 확인. `6. Dependency + security scan` 은 #82 이후 상태를 PR 에서 확인 |
| 기존 Job 테스트 | `apps/batch/.../accountpnl/AccountPnlMonitorJob*Test`·`AccountPnlMonitorLatestRecordTest` 8개 | 예 — R1(무수정 통과) |

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | 열린 건이 없을 때 leg 가 생기면 `OPENED`, 수량이 늘면 `INCREASED`, 줄면 `REDUCED`, 엇갈리거나 평단만 바뀌면 `ADJUSTED`, 둘 다 비면 `CLOSED` 가 정확히 1건 결정되고, leg 가 같으면 사건이 없다. 한쪽 leg 만 남은 상태는 닫힘이 아니다 | design D1·D2 | `요구`(0→0 한 건, 나눠 진입·정리) · `추론`(`ADJUSTED`, 한쪽 leg 열림) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.PositionEpisodePolicyTest` | `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.PositionEpisodePolicyTest --offline --no-daemon` | exit 0 |
| AC2 | 진입 → 추가 진입 → 부분 정리 → 전량 정리를 순서대로 기록하면 DB 에 건 1행(`CLOSED`, `event_count` 4)·사건 4행(seq 1~4)이 남고, 이어서 재진입하면 건이 2행이 된다. 헤지가 성립한 사건에서만 reference 4열이 갱신된다 | 이슈 #129 수용 기준 1·2, design D3 | `요구` | T1 | `신규테스트` `io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPositionHistoryStoreIntegrationTest` | `./gradlew :infrastructure:common:integrationTest --tests io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPositionHistoryStoreIntegrationTest --offline --no-daemon` | exit 0 |
| AC3 | 같은 leg·같은 `observedAt` 로 `record` 를 두 번 호출하거나, 새 store 인스턴스(재시작)로 이어서 호출해도 건·사건·관측 행 수가 늘지 않는다. 열린 건이 있는 상태에서 두 번째 `OPEN` 행을 직접 넣으면 제약 위반으로 실패한다 | 이슈 #129 수용 기준 3, design D5 | `요구` | T1 | `신규테스트` 위 AC2 와 같은 클래스의 `idempotent*`·`singleOpenEpisode*` 메서드 | AC2 와 같은 명령 | exit 0 |
| AC4 | 헤지 성립 회차는 `HOLDING` 관측 1행(순수익률·순손익·현재 김프·매수김프·차이·환율 채움), 열린 건이 없고 reference 가 있는 닫힌 건이 있는 회차는 그 건에 매달린 `FLAT` 1행(순수익률·순손익 NULL, 매수김프는 reference 평단과 **그 회차 환율**로 계산)이 남는다. 관측 실패·한쪽 leg 만 있는 회차·기준 건이 없는 회차는 0행이다. 김프 값은 같은 입력의 `AccountPnlBreakdown` 값과 같다 | design D4 | `요구`(매분 저장, 포지션 없는 기간 포함) · `추론`(적재하지 않는 회차 3종) | T1 | `신규테스트` `io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobPositionHistoryTest` | `./gradlew :apps:batch:test --tests io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobPositionHistoryTest --offline --no-daemon` | exit 0 |
| AC5 | 이력 store 가 예외를 던져도 그 회차의 `JobResult`·저장된 모니터 상태·outbox 행·전달 호출이 store 가 없을 때와 같다. `save()` 가 false(버전 충돌)거나 저장 전에 취소된 회차는 이력 store 를 호출하지 않는다 | 이슈 #129 수용 기준 4, design D5 | `요구` | T1 | `신규테스트` AC4 와 같은 클래스의 `historyFailure*`·`skipped*` 메서드 | AC4 와 같은 명령 | exit 0 |
| AC6 | V24 가 빈 DB 와 V23 → 최신 두 경로에서 적용되고, 버전 유일성·파괴 SQL gate 를 통과한다 | design D7 | `요구`(저장) | T1 | `기존` `:infrastructure:common:verifyMigrations` | `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` | exit 0 |
| AC7 | `account-pnl-monitor.position-history.enabled=false` 면 Job 에 이력 store 가 주입되지 않고, 기본값(미설정)이면 주입된다 | design D6 | `추론`(끄는 스위치) | T1 | `신규테스트` `io.premiumspread.config.AccountPnlMonitorPositionHistoryWiringTest` | `./gradlew :apps:batch:test --tests io.premiumspread.config.AccountPnlMonitorPositionHistoryWiringTest --offline --no-daemon` | exit 0 |
| AC8 | owner 가 로컬 batch 를 실계정으로 돌려 `account_position_episode`·`account_pnl_observation` 에 행이 쌓이는 것을 확인한다(보유 중이면 `OPENED` 1건 + 분당 `HOLDING` 1행, 없으면 건 0행) | 이슈 #129, design §3 | `요구` | T4 | 사람 육안 — runbook 확인 쿼리 | — | owner 가 PR 코멘트로 확인 |

**기준 개수**: 8개

**티어 강등 사유** *(T1이 아닌 항목만)*

- AC8 → T4: 실계정 credential 은 owner 로컬 환경에만 있다. 테스트·CI 에 실제 계정 값을 넣지 않는 것이 저장소 규칙이다(`.ai/rules/testing.md`). 자동 기준(AC1~AC7)이 같은 동작을 fixture 로 잰다.

## 회귀 방어선

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 기존 실계정 모니터 Job 테스트가 **수정 없이** 통과한다(생성자 끝 인자 추가만 허용) | `./gradlew :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.*' --offline --no-daemon` |
| R2 | 모듈 경계·조회 전용 규칙 | `./gradlew architectureTest --offline --no-daemon` |
| R3 | 기존 accountpnl Domain 정책·저장소 | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.*' --offline --no-daemon` · `./gradlew :infrastructure:common:integrationTest --offline --no-daemon` |
| R4 | 문서 검사 | `bash docs/check-documentation.sh` |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

## 사람 확인 (T4)

> 판정 주체는 사람뿐이다. AI가 이 표를 채우지 않는다.

| # | 확인 사항 | 확인자 | 날짜 | 앵커 |
|---|---|---|---|---|
| AC8 | 로컬 실계정 실행에서 건·관측 행 적재 | | | |

## 변경 요청

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|

## 최종 판정

> 구현 뒤 기록한다.
