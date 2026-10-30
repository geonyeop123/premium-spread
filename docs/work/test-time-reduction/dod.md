---
feature: 테스트 실행 시간 단축 (issue #104)
slug: test-time-reduction
status: FROZEN
frozen_at: 2026-10-29T17:12:51+09:00
verdict_commit: 2f3f950
source: design.md
---

## 범위

**포함**

- CI Quality Gate job 간 `needs` 제거와 common 통합 테스트 job 분리 (design D1)
- PR 의 Gradle cache 읽기 전용 (D2)
- unit 모듈 병렬 실행(`--parallel` 플래그)과 MockWebServer 2초 지연 단축 (D3, D4)
- batch 통합 테스트 Spring context 재사용과 공용 MockWebServer 초기화 (D5)
- common 통합 테스트 공용 MySQL 컨테이너 + 클래스별 database (D6)
- common adapter 테스트의 migrate 1회 + 데이터 초기화 (D7)
- orchestrator ⑩ 검증을 Gradle 한 번 호출로, 하네스·정본 문서 갱신 (D8)
- 전후 측정 (D9)

**제외** *(scope creep 차단선)*

- 앱·도메인·infrastructure `src/main` 코드, API, DB 스키마(Flyway) 변경
- `apps/api` 전용 컨테이너 2개(`TradePreparationConcurrencyIntegrationTest`, `TradePreparationActiveTrackingContractTest`)
- batch 커스텀 context 6개 통합, `apps/api` 통합 테스트 구조 변경
- WebSocket 회귀 테스트 sleep/backoff, `BinanceFuturesManualAccountAdapterTest` 지연 값
- 테스트 retry, run 간 container reuse, `@Disabled`, leak-detection 완화, Gradle build cache 재활성화
- 테스트 JVM 내부 병렬(`maxParallelForks`, JUnit parallel)
- `gradle.properties` 전역 `org.gradle.parallel`
- 저장소 branch protection 설정 변경 (design §6 N1 — 저장소 관리자 조치)

## 기존 gate 조사

> Gate 1 필수. 수용기준을 쓰기 전에 저장소가 이미 가진 검증 수단을 실제로 찾았다.

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있다 — `./gradlew architectureTest` (`architecture-tests/` 독립 source set) | 회귀 방어선(⑩ 명령에 포함). 이번 변경은 모듈 경계를 건드리지 않는다 |
| lint 규칙 | 있다 — ktlint·detekt (CI job `5. ktlint + detekt`). 로컬 jar 없음 | 회귀 방어선. 미사용 import 제거를 CI 가 잡는다 |
| custom gradle task | 있다 — `verifyTestIsolationPolicy`(reuse·leak·retry·timeout 강제), `harnessCheck`, `harnessCheckTest`, `verifyMigrations`, `*:integrationTest`, `unitTest` | **그대로 쓴다** — 격리 정책·하네스 무결성 회귀 방어선 |
| CI job | 있다 — 7개 (`compile-architecture`·`unit-coverage`·`api-integration`·`batch-integration`·`static-analysis`·`dependency-security`·`docker-build`) | 이번에 구조를 바꾸는 대상. 바뀐 구조는 아래 계약 스크립트로 검증한다 |
| CI 계약 스크립트 | 있다 — `ci/quality-gate-contract-test.sh`(job id·순서·upload·needs·명령 문자열 단언), `docker/deploy-contract-test.sh`(API/Batch job 명령 단언), `ci/local-offline-contract-test.sh` | **`ci/quality-gate-contract-test.sh` 를 갱신해 AC8 의 검증 수단으로 쓴다.** 나머지는 회귀 방어선 |
| 테스트 결과 집계 | 없다 — 결과 XML 을 합산하는 task·스크립트 없음 | 개수·시간 기준은 design D9 의 재현 명령(T2)으로 잰다. 강등 사유는 아래 |
| 누수 검사 | 있다 — `build-logic/.../NonDaemonThreadLeakListener.java`(세션 종료 시 비-daemon 스레드 검사, 실패 시 task 실패) | 회귀 방어선 — 각 `integrationTest` 가 green 이면 통과한 것이다 |

**결론.** 공용 자원 초기화의 정확성(AC6·AC7)은 `신규테스트` 로, CI 구조(AC8)는 갱신한 `기존` 계약 스크립트로
T1 검증한다. 시간·개수는 환경 의존 실측값이라 T2/T3 다.

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | 로컬(WSL2 24코어/31GB) 콜드 worktree 에서 ⑩ 실행 직후 `:infrastructure:common:integrationTest --rerun` 의 wall-clock 이 4분 이하다 | design D6·D7·D9 | `요구` issue #104 "로컬 common IT ≤ 4m (기준 9m45s)" | T2 | `기존` `:infrastructure:common:integrationTest` | `time ./gradlew :infrastructure:common:integrationTest --rerun --offline --no-daemon` | exit 0, `real` ≤ 4m00s |
| AC2 | 로컬 콜드 worktree 에서 ⑩ 명령(design D8) 1회의 wall-clock 이 7분 이하다 | design D3·D8·D9 | `요구` issue #104 "로컬 ⑩ 검증 전체 ≤ 7m (기준 ~17m)" | T2 | `기존` ⑩ Gradle 명령 | `time ./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon` | exit 0, `real` ≤ 7m00s |
| AC3 | 최종 SHA 의 PR Quality Gate run 에서 8개 job 이 모두 success 이고, `min(job.startedAt)`~`max(job.completedAt)` 이 6분 이하다 | design D1·D2·D9 | `요구` issue #104 "CI Quality Gate wall-clock ≤ 6m (기준 8.5~10m)" | T3 | `기존` CI workflow `Quality Gate` | `gh run view <run-id> --json jobs` | 8 job success, 구간 ≤ 6m00s. 증거 앵커 = run URL |
| AC4 | 변경 후 테스트 수가 기준선과 정확히 같다 — unit = `B_unit`, common IT = `B_common` + 3, api IT = `B_api`, batch IT = `B_batch` + 2 — 그리고 failures·errors 0, skipped 는 기준선과 같다 | design §5, D9 집계 C | `요구` issue #104 "통합 테스트 수·검증 계약 축소 없음, 실패 0" | T2 | `기존` 결과 XML (`build/test-results/{test,integrationTest}`) | design D9 집계 C 를 `infrastructure/common`·`apps/api`·`apps/batch` integrationTest 디렉터리와 모든 모듈 test 디렉터리에 실행 | 각 값이 왼쪽 등식과 일치 |
| AC5 | batch 통합 테스트 1회에서 Spring Boot context 기동 로그 수가 9 이하다 | design D5·D9 기호 K | `요구` issue #104 "batch context 기동 횟수 감소(기준 23, 목표 ≤ 9)" | T2 | `기존` `apps/batch` integrationTest 결과 XML | design D9 기호 K 명령 | 출력 ≤ 9 (설계 예상 8) |
| AC6 | `LatestSchemaFixture.reset()` 이 (a) `flyway_schema_history` 를 뺀 모든 base table 을 0행으로, history 행 수는 그대로, AUTO_INCREMENT 를 1부터로 되돌리고 (b) 스키마에서 CHECK 제약을 지운 뒤 호출되면 그 제약을 복구하며 (c) 빈 database 에서 첫 호출이 최신 schema 를 만든다 | design D7 | `리뷰` 사용자 합의 ③-4 "TRUNCATE 대상을 동적으로 조회 — 누락 위험 차단" + 설계 검토(DROP CHECK 테스트) | T1 | `신규테스트` `io.premiumspread.infrastructure.common.support.LatestSchemaFixtureIntegrationTest` | `./gradlew :infrastructure:common:integrationTest --tests '*LatestSchemaFixtureIntegrationTest' --offline --no-daemon` | exit 0 |
| AC7 | 공유 context 에서 앞 테스트가 공용 MockWebServer 에 남긴 enqueue 응답과 recorded request 가 다음 테스트에 보이지 않는다 | design D5-2 | `요구` 사용자 합의 ③-2 "enqueue 응답·recorded request 가 다음 클래스로 넘어가지 않도록 매 테스트 초기화" | T1 | `신규테스트` `io.premiumspread.support.SharedContextIsolationIntegrationTest` | `./gradlew :apps:batch:integrationTest --tests '*SharedContextIsolationIntegrationTest' --offline --no-daemon` | exit 0 |
| AC8 | Quality Gate workflow 에 `needs:` 가 없고, `common-integration` job 이 `:infrastructure:common:verifyMigrations :infrastructure:common:integrationTest` 를, `api-integration` 이 `:apps:api:integrationTest` 만 실행하며, Gradle job 5개의 setup-gradle 이 모두 `cache-read-only: ${{ github.ref != 'refs/heads/dev' }}` 를 갖고, unit 명령이 `--parallel` 을 포함한다 | design D1·D2·D3 | `요구` 사용자 합의 ③-1 | T1 | `기존` `ci/quality-gate-contract-test.sh` (이번에 단언 갱신) | `bash ci/quality-gate-contract-test.sh` | exit 0 |
| AC9 | orchestrator ⑩ 블록·`qa-agent`·`qa-verification`·`test-strategy`·`.ai/rules/testing.md`·`AGENTS.md`·`.ai/instructions.md` 가 전체 검증을 design D8 의 단일 명령 한 줄로 적고, `.ai/rules/testing.md`(개별 재실행 명령의 소유자) 밖에는 직렬 개별 명령 형태가 남아 있지 않다 | design D8 | `요구` 사용자 합의 ③-5 | T2 | `기존` 해당 문서 | `grep -rnF ':apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon' .claude AGENTS.md .ai/rules/testing.md .ai/instructions.md` 과 `grep -rnE '^\./gradlew :infrastructure:common:integrationTest --offline --no-daemon$' .claude/skills/orchestrator .claude/agents/qa-agent.md .claude/skills/qa-verification .claude/skills/test-strategy AGENTS.md .ai/instructions.md` | 첫 명령이 7개 파일 각 1건 이상, 둘째 명령 0건 |
| AC10 | `BithumbPrivateAccountAdapterTest`·`ExchangeRateClientTest` 의 2초 MockWebServer 지연이 1초로 바뀌고, 두 클래스의 테스트 수는 같으며, 지연을 쓰는 메서드 4개(Bithumb `네트워크 실패와 타임아웃 예외에도 secret 이 없다`·`응답 시간이 초과되면 NETWORK`, ExchangeRate `read timeout 은 설정한 횟수까지만 재시도한다`·`응답 헤더 지연으로 Netty responseTimeout 이 이기면 WebClientRequestException 으로 재시도한다`) 각각의 testcase time 이 기준선보다 0.8초 이상 짧다 | design D4·D9 메서드 시간 | `요구` 사용자 합의 ③-3 | T2 | `기존` `:infrastructure:batch:test` 결과 XML | `./gradlew :infrastructure:batch:test --rerun --offline --no-daemon` 후 두 클래스 `TEST-*.xml` 의 `<testsuite tests>` 와 4개 `<testcase time>` 속성 (plan `case_times`) | 수 동일, 4개 메서드 각각 기준선 − 0.8s 이하 |

**`출처`** — `요구`(사용자 요구 원문) / `상위`(상위 동결 문서의 ID) /
`리뷰`(리뷰에서 파생) / `추론`(근거 없음).

**기준 개수**: 10개

**분할하지 않는 이유**: issue #104 하나의 목표(전체 wall-clock 단축)를 재는 계약이다. AC2(로컬 ⑩)와 AC3(CI)은 P1~P6
전부의 합성 결과라 P 별로 계약을 나누면 두 기준이 여러 계약서에 걸친다. AC6·AC7 은 공용 자원을 도입하는 D6·D7·D5
의 안전성 증명이라 그 변경과 떨어질 수 없다. 나머지는 사용자 합의 ③ 항목과 issue 수용 기준을 하나씩 덮는다.

**티어 강등 사유** *(T1이 아닌 항목만. Gate 1에서 사람이 함께 승인한다 — design §6 N2)*

- AC1·AC2 → T2: wall-clock 은 실행 환경(코어 수·Docker·디스크)에 따라 달라지는 실측값이다. 명령은 재현 가능하지만
  통과 판정은 환경이 같을 때만 의미가 있어 exit code 로 고정할 수 없다.
- AC3 → T3: PR 이 열린 뒤에만 생기는 GitHub Actions run 의 관찰값이다. PR 생성 전까지 이 항목은 `AWAITING` 이며
  증거 앵커는 run URL 이다.
- AC4·AC5·AC10 → T2: 기준선이 Task 0 의 실측값이라 통과 조건이 기준선 대비 등식·부등식이다. 결과 XML 집계 스크립트가
  저장소에 없다 — 대안은 집계용 `신규스크립트` 를 만들어 T1 으로 올리는 것이고, 그 선택은 Gate 1 에서 묻는다.
- AC9 → T2: 문서 문구의 존재·부재 확인이다. `harnessCheck` 는 경로·frontmatter·필수 절만 단정하고 명령 문자열은
  단정하지 않는다. 하네스 게이트에 이 단정을 추가하면 T1 이 되지만 게이트 확장은 이번 범위 밖으로 둔다.

## 검사 산출물

> `신규스크립트` 를 검증 수단으로 쓰지 않으므로 비어 있다.

| 스크립트 | 경로 | 정상 표본 (통과해야 함) | 위반 표본 (잡아야 함) | 증명 |
|---|---|---|---|---|

## 회귀 방어선

이번 변경으로 깨질 수 있는 기존 동작. RED 면제 대상이다.

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 모든 Test task 가 timeout·leak detection·reuse 비활성을 갖고 retry plugin 이 없다 | `./gradlew verifyTestIsolationPolicy --offline --no-daemon` |
| R2 | 하네스 참조 무결성·frontmatter·계약과 검사기 자가검증 | `./gradlew harnessCheck harnessCheckTest --offline --no-daemon` |
| R3 | offline Gradle 설정이 CI 전용 도구와 무관하다 | `bash ci/local-offline-contract-test.sh` |
| R4 | 정본 문서 검사 | `bash docs/check-documentation.sh` |
| R5 | 배포 계약이 Quality Gate 의 API·common·Batch job 명령과 맞는다 | `bash docker/deploy-contract-test.sh` |
| R6 | 모듈 경계 | `./gradlew architectureTest --offline --no-daemon` (AC2 명령에 포함) |
| R7 | migration 버전 유일성·파괴적 SQL gate | `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` (AC2 명령에 포함) |
| R8 | 통합 테스트 세션 종료 시 비-daemon 스레드 누수 없음 | 각 `*:integrationTest` exit 0 (AC2 명령에 포함 — 누수가 있으면 task 가 실패한다) |
| R9 | unit coverage gate(overall 70 · Domain 85 · Application 80) | CI job `2. Unit + coverage` success (AC3 run) |
| R10 | ktlint·detekt (미사용 import 포함) | CI job `5. ktlint + detekt` success (AC3 run) |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

### 기준선 (Task 0)

```
[BASELINE] 2026-10-29 origin/dev 3d179e7 — 콜드 스냅샷(git archive, 빌드 산출물 없음)
B_unit   = tests=1303 skipped=0 failures=0 errors=0 (B_infra_batch_unit = 215)
B_common = tests=81 skipped=0 failures=0 errors=0
B_api    = tests=174 skipped=0 failures=0 errors=0
B_batch  = tests=112 skipped=0 failures=0 errors=0
B_ctx    = 35
B_wall   = 1516s (test+architectureTest 144s / common IT 889s / api IT 256s / batch IT 221s / verifyMigrations 6s)
unit 클래스 tests: BithumbPrivateAccountAdapterTest=33 · ExchangeRateClientTest=19 · BinanceFuturesManualAccountAdapterTest=16 (time 1.657s)
지연 메서드 testcase time:
  Bithumb `네트워크 실패와 타임아웃 예외에도 secret 이 없다` = 2.008
  Bithumb `응답 시간이 초과되면 NETWORK` = 2.004
  ExchangeRate `read timeout 은 설정한 횟수까지만 재시도한다` = 2.222
  ExchangeRate `응답 헤더 지연으로 Netty responseTimeout 이 이기면 WebClientRequestException 으로 재시도한다` = 3.436
비고: 측정 후반에 Task 5·6 subagent의 경량 작업(bash 계약 테스트·harnessCheck)이 겹쳤다.
```

> 측정: 2026-10-30, HEAD c3693c6 콜드 스냅샷(git archive). 1·2회차(92a147d·2447851)는 batch 실패로 판정에 쓰지 않는다 —
> 원인과 수정은 커밋 6d52c39(Redis reuse 공유)·c3693c6(Redis JVM 싱글턴)에 있다.

### AC1

```
[PASS] time ./gradlew :infrastructure:common:integrationTest --rerun --offline --no-daemon @ c3693c6
exit 0, real 61s (≤ 240s). 기준선 889s.
```

### AC2

```
[PASS] time ./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon @ c3693c6 (콜드)
exit 0, real 312s (≤ 420s). 기준선 B_wall 1516s.
```

### AC3

```
[AWAITING] PR Quality Gate run 이 생긴 뒤 기록한다.
```

### AC4

```
[PASS] 결과 XML 집계 @ c3693c6 (AC2 실행)
unit   tests=1303 skipped=0 failures=0 errors=0  (= B_unit 1303)
common tests=84   skipped=0 failures=0 errors=0  (= B_common 81 + 3)
api    tests=174  skipped=0 failures=0 errors=0  (= B_api 174)
batch  tests=114  skipped=0 failures=0 errors=0  (= B_batch 112 + 2)
```

### AC5

```
[PASS] grep 'Started [^ ]* in [0-9.]* seconds' apps/batch/build/test-results/integrationTest/TEST-*.xml | wc -l @ c3693c6
8 (≤ 9). 기준선 B_ctx 35.
```

### AC6

```
[PASS] LatestSchemaFixtureIntegrationTest @ c3693c6 (AC2 실행 결과 XML)
tests=3 skipped=0 failures=0 errors=0. RED 증거: task 3 구현 보고 — CHECK 복구 케이스 "expected 1 but was 0" 실패 후 지문 비교 추가로 GREEN.
```

### AC7

```
[PASS] SharedContextIsolationIntegrationTest @ c3693c6 (AC2 실행 결과 XML)
tests=2 skipped=0 failures=0 errors=0. RED 증거: task 4 구현 보고 — base 초기화 전 둘째 케이스 실패.
범위: 같은 공유 context 안 두 테스트 메서드 사이의 초기화를 증명한다(클래스 사이도 같은 base @BeforeEach 경로).
```

### AC8

```
[PASS] bash ci/quality-gate-contract-test.sh @ c3693c6 — exit 0
```

### AC9

```
[PASS] grep 1: 7개 파일 각 1건 이상 — .claude/skills/qa-verification/SKILL.md, .claude/skills/orchestrator/SKILL.md,
       .claude/skills/test-strategy/SKILL.md, .ai/rules/testing.md, AGENTS.md, .ai/instructions.md, .claude/agents/qa-agent.md
       grep 2: 0건 (rc=1) @ c3693c6
```

### AC10

```
[PASS] ./gradlew :infrastructure:batch:test --rerun --offline --no-daemon @ c3693c6
tests: Bithumb 33 · ExchangeRate 19 (기준선과 같음). testcase time(기준선 → 변경 후, 감소량):
  Bithumb `네트워크 실패와 타임아웃 예외에도 secret 이 없다` 2.008 → 1.005 (−1.003)
  Bithumb `응답 시간이 초과되면 NETWORK` 2.004 → 1.003 (−1.001)
  ExchangeRate `read timeout 은 설정한 횟수까지만 재시도한다` 2.222 → 1.223 (−0.999)
  ExchangeRate `응답 헤더 지연으로 Netty responseTimeout 이 이기면 …` 3.436 → 2.261 (−1.175)
```

### 회귀 방어선 @ c3693c6

```
R1 verifyTestIsolationPolicy exit 0 · R2 harnessCheck harnessCheckTest exit 0 · R3 local-offline-contract-test exit 0
R4 check-documentation exit 0 · R5 deploy-contract-test exit 0 · R6~R8 AC2 실행 exit 0 · R9·R10 은 AC3 run 에서 확인
```

### 최종 재측정 @ 2f3f950 (⑩ qa-agent, ⑨ 재작업·문서 동기화 이후)

> c3693c6 기준 증거는 ⑨ 재작업 코드 커밋(10a635c..6d08792)으로 만료되어 최종 SHA 로 전부 다시 쟀다. 콜드 스냅샷(git archive).

```
[PASS] AC1  common IT --rerun 52s (≤ 240s), exit 0
[PASS] AC2  ⑩ 단일 명령 163s (≤ 420s), exit 0 — 기준선 1516s
[AWAITING] AC3  PR Quality Gate run 이후 기록
[PASS] AC4  unit 1303 · common 84 · api 174 · batch 114, failures 0 · errors 0 · skipped 0
[PASS] AC5  grep -ho 'Started [^ ]* in [0-9.]* seconds' …/apps/batch/…/TEST-*.xml | wc -l = 8 (≤ 9)
[PASS] AC6  LatestSchemaFixtureIntegrationTest tests=3 failures=0
[PASS] AC7  SharedContextIsolationIntegrationTest tests=2 failures=0
[PASS] AC8  bash ci/quality-gate-contract-test.sh exit 0
[PASS] AC9  grep (a) 7개 파일 · grep (b) 0건
[PASS] AC10 Bithumb 33 · ExchangeRate 19, 메서드 1.005 / 1.003 / 1.222 / 2.412 (각 기준선 − 0.8s 이하)
[PASS] R1 verifyTestIsolationPolicy · R2 harnessCheck harnessCheckTest · R3 local-offline-contract-test
       R4 check-documentation · R5 deploy-contract-test · R6~R8 AC2 실행 exit 0 · R9·R10 은 AC3 run
경계면: CI job 명령 ↔ 계약 스크립트 단언 일치 · testing.md ↔ 코드(env reuse 차단, MySqlTestTuning, Redis property 연결) 일치
        · src/main(apps/domain/infrastructure) diff 0
```

## 사람 확인 (T4)

> 판정 주체는 사람뿐이다. AI가 이 표를 채우지 않는다.
> 앵커는 이 파일 밖에 있어야 한다 — PR 코멘트 URL, 커밋 SHA, 이슈 링크.

| # | 확인 사항 | 확인자 | 날짜 | 앵커 |
|---|---|---|---|---|

## 변경 요청

> 동결 후 기준을 바꿔야 할 때만 작성. 사람이 승인하기 전까지 구현을 중단한다.

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|
| 범위 제외 — `apps/api` 전용 MySQL 컨테이너 2개(`TradePreparationConcurrencyIntegrationTest`·`TradePreparationActiveTrackingContractTest`) | 손대지 않는다 | 테스트 MySQL 내구성 튜닝(tmpfs·fsync 끄기)만 적용한다. 전용 컨테이너 설계와 `ddl-auto=validate` 는 그대로 둔다 | AC2(⑩ ≤ 7m) 측정에서 최장 경로가 `:apps:api:integrationTest` 였고, 같은 튜닝을 api·batch 공용 컨테이너에 넣으면서 이 두 파일에도 넣었다. **승인 전에 먼저 고쳤다(커밋 2447851) — 순서 규칙 위반을 여기 기록한다.** ⑨ spec-reviewer BLOCKER 1 로 드러남 | 사용자 승인 2026-10-30 (orchestrator ⑨ AskUserQuestion) |

## 최종 판정

> 아래 수는 전부 위 수용기준 표에서 센 값이다. 표와 어긋나면 판정을 낼 수 없다.
> 판정이 가리키는 SHA와 브랜치 최종 SHA가 다르면 그 판정은 만료다.

```
DoD VERDICT: test-time-reduction @ 2f3f950
  수용기준 표:     10개  (T1 3 · T2 6 · T3 1 · T4 0)
  T1/T2 자동:      9개 중 9개 PASS
  T3 기록 제출:    1개 중 0건 (AC3 — PR run 대기)
  T4 사람 확인:    0개 중 0건 완료, 0건 대기
  변경 요청:       1건 (승인됨)
  => AWAITING_T3 — 로컬 기준 전부 충족, AC3(CI wall-clock ≤ 6m) 기록 후 완료
```

**사람 확인이 필요한 항목**

- (없음 — AC3 은 PR run 이 생긴 뒤 T3 기록으로 제출한다)

> DRAFT 개정: ⑥ spec-reviewer 1R 반영 — AC10 을 클래스 합계(2s·4s)에서 지연 메서드 4개 각각 ≥ 0.8s 감소로 변경,
> AC9 둘째 grep 대상에 `.claude/skills/test-strategy` 추가. 기준선 블록에 메서드 time 칸 추가.
