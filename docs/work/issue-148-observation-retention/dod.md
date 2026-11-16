---
feature: 실계정 포지션 관측 이력 보존 기간 정책 — 관측·레그 행 정리 Job
slug: issue-148-observation-retention
status: FROZEN · VERIFIED
frozen_at: 2026-11-16 (사용자 계약 승인 — 기본 보존 기간 365d)
verdict_commit: 48533d0
source: https://github.com/geonyeop123/premium-spread/issues/148
---

## 범위

**포함**

- 보존 기간을 넘긴 관측·레그 행 삭제 store (design D1·D3)
- 정리 Job·Scheduler·설정·lock (D2·D4·D5)
- 보존 기간 vs 정산 `max-age` 시작 검증 (D2)
- runbook 설정·운영 절 갱신

**제외**

- 다운샘플링, 건·사건·정산 정리, 다른 시계열 정리, 조회 API

## 기존 gate 조사

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| 보존 Job 선례 | 있음 — `NotificationPiiRetentionJob`(배치·상한 반복) | 패턴 참고. 테스트는 신규 |
| Job 실행 계약 테스트 | 있음 — `AccountRealizedPnlJob` 단위 테스트(executor·취소) 패턴 | 예. 같은 패턴으로 신규테스트 |
| store 통합 테스트 | 있음 — common `LatestSchemaFixture`·`SharedMySqlContainer` 기반 | 예. 신규 클래스 |
| 설정·조건부 배선 테스트 | 있음 — apps:batch `ApplicationContextRunner` 기반 Configuration 테스트들 | 예. 신규테스트 |
| architecture test | 있음 — root `architectureTest`(Scheduler→Job, domain 경계) | 회귀 방어선 |
| 문서 검사 | 있음 — `docs/check-documentation.sh` | 회귀 방어선 |

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | `deleteObservedBefore(cutoff, limit)` 는 `observed_at < cutoff` 인 관측 행 중 오래된 순 최대 `limit` 개와 그 키의 레그 행(바이낸스·빗썸)을 지우고 지운 관측 수를 반환한다. `observed_at = cutoff` 이후 행과 그 레그 행, 건·사건 행은 그대로다. 레그가 없는 관측(FLAT)도 지워진다 | 이슈 수용 기준 1·2, design D1·D3 | `요구` | T1 | `신규테스트` `io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountObservationRetentionStoreIntegrationTest` | `./gradlew :infrastructure:common:integrationTest --tests io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountObservationRetentionStoreIntegrationTest --offline --no-daemon` | exit 0 |
| AC2 | 레그 DELETE 뒤 관측 DELETE 가 실패하도록 주입하면 예외가 전파되고 그 배치의 레그 행도 남아 있다(한 트랜잭션) | design D3 | `추론` | T1 | `신규테스트` AC1 과 같은 클래스 | AC1 과 같음 | exit 0 |
| AC3 | Job 은 `cutoff = clock.now − retention` 으로 store 를 부르고, 배치가 `batch-size` 보다 적게 지우면 멈추며, `max-batches-per-run` 에 닿으면 더 부르지 않고 WARN 한다. `JobId.ACCOUNT_OBSERVATION_RETENTION` 설정으로 `JobExecutor` 를 거친다. store 예외면 `JobResult.Failure`, interrupt 가 서 있으면 다음 배치를 부르지 않는다 | 이슈 수용 기준 4, design D4 | `요구`(상한) + `추론`(취소·실패) | T1 | `신규테스트` `io.premiumspread.application.job.accountpnl.AccountObservationRetentionJobTest` | `./gradlew :apps:batch:test --tests io.premiumspread.application.job.accountpnl.AccountObservationRetentionJobTest --offline --no-daemon` | exit 0 |
| AC4 | `retention` 이 `realized-pnl.max-age` + 1일 이하(기본 max-age 90d 면 91d 이하)면 context 시작이 실패하고, 초과면 시작된다. max-age 를 바꾸면 기준도 따라간다. `account-pnl-monitor.enabled=false` 이거나 `realized-pnl.enabled=false` 여도 사용자가 넣은 max-age 로 같은 경계가 적용된다 | 이슈 수용 기준 3, design D2 | `요구` + `리뷰`(codex ISSUE-1: 비활성 조합) | T1 | `신규테스트` `io.premiumspread.config.AccountObservationRetentionConfigurationTest` | `./gradlew :apps:batch:test --tests io.premiumspread.config.AccountObservationRetentionConfigurationTest --offline --no-daemon` | exit 0 |
| AC5 | `retention.enabled=false` 면 Scheduler bean 이 없고, 기본값이면 있다. `account-pnl-monitor.enabled=false` 여도 기본값이면 Scheduler 가 있다 | 이슈 수용 기준 5, design D5 | `요구`(끄기) + `추론`(모니터 무관) | T1 | `신규테스트` AC4 와 같은 클래스 | AC4 와 같음 | exit 0 |
| AC6 | 보존 설정을 하나도 넣지 않으면 retention 은 `365d`, batch-size 1000, max-batches-per-run 50, enabled true 로 바인딩되고 기본 max-age(90d) 검증을 통과해 시작된다 | design D2(기본값 고정) | `리뷰`(codex ISSUE-2) | T1 | `신규테스트` AC4 와 같은 클래스 | AC4 와 같음 | exit 0 |

**기준 개수**: 6개

**분할하지 않는 이유**: 해당 없음(8개 이하)

**티어 강등 사유**: 없음

## 회귀 방어선

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 전 모듈 unit + 모듈 경계 | `./gradlew test architectureTest --parallel --offline --no-daemon` |
| R2 | common·batch 통합(이력·정산 포함) | `./gradlew :infrastructure:common:integrationTest :apps:batch:integrationTest --offline --no-daemon` |
| R3 | 정본 문서 검사 | `bash docs/check-documentation.sh` |
| R4 | lint (CI 전용 — 로컬 도구 다운로드가 막혀 있다) | CI `5. ktlint + detekt` |

## 증거 로그

| # | RED (구현 전) | GREEN |
|---|---|---|
| AC1·AC2 | `JdbcAccountObservationRetentionStoreIntegrationTest` 스텁 store — `6 tests completed, 5 failed`(단언 실패, "지울 것이 없으면 0" 만 통과) | 6/6 (679305d). 롤백 테스트는 실패 주입 전 레그 DELETE 4행을 확인해 공허 통과를 막는다 |
| AC3 | `AccountObservationRetentionJobTest` 스텁 — Job 6 중 5 실패(`cutoff …` AssertionFailedError) | 6/6 (2beb4e9) → 취소 cause 체인 2건 추가 8/8 (430ae32) |
| AC4·AC5·AC6 | `AccountObservationRetentionConfigurationTest` 스텁 — 7 중 5 실패 | 7/7 (2beb4e9) → 검증 범위·root cause 단언 추가 9/9 (430ae32) |
| 배선 회귀 | codex 표준 리뷰 P1: store 가 `CommonInfrastructureAutoConfiguration` 에 없어 실제 batch 앱이 기본 설정으로 시작 실패. `BatchComponentScanBoundaryTest` 에 bean 1개 단언 — 등록 제거 시 FAILED | 복원 후 2/2 (430ae32) |
| R1·R2 | — | `./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon` @ 48533d0 → `BUILD SUCCESSFUL in 4m 18s`, exit 0, FAILED 0 |
| R3 | — | `bash docs/check-documentation.sh` → `documentation check passed (20 files, 15 required paths)` |
| R4 | — | CI `5. ktlint + detekt` — PR 에서 확인 |
| 환경 | Task 1 GREEN 직전 Gradle 자동 캐시 정리가 `junit-bom 5.11.4` 를 지워 `--offline` 빌드 불가 → 온라인 `compileKotlin compileTestKotlin` 1회로 캐시만 복구(프로젝트 변경 없음), 이후 모든 검증은 `--offline` | — |

## 판정

```
DoD VERDICT: issue-148-observation-retention @ 48533d0
  수용기준 표:     6개  (T1 6 · T2 0 · T3 0 · T4 0)
  T1/T2 자동:      6개 중 6개 PASS
  T3 기록 제출:    0개
  T4 사람 확인:    0개
  변경 요청:       0건
  => VERIFIED
```
