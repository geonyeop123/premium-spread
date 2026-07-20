---
feature: PRIVATE LIVE Phase -1 CI 실행 계약 단순화
slug: private-live-autotrader-phase-minus-1
status: FROZEN
frozen_at: 2026-07-20T18:11:19+09:00
source: 사용자 승인과 .ai/planning/private-live-autotrader/task_plan.md §7 Phase -1
---

## 범위

**포함**

- Quality Gate를 PR 및 `dev`/`main` push event와 GitHub/OCI 표준 provenance로 제한
- custom target SHA/dependency fingerprint/checksum bundle 제거
- dependency bootstrap을 PR-only 고정 marker와 Gradle 표준 lock/verification metadata review로 단순화
- secret-bearing deploy workflow 제거와 기존 `docker/deploy.sh`의 host-local 계약 보존
- Phase 기준선, 운영 책임 경계와 검증 증거 기록

**제외** *(명시적으로 하지 않는 것 — scope creep 차단선)*

- self-hosted/JIT runner와 신규 deploy/soak/migration/activation workflow
- Strategy/Live 기능 코드, 실제 host 배포, 실제 credential 또는 주문
- Git commit ID, OCI revision, GitHub artifact ID, Gradle/Flyway/quality-tool 표준 checksum 제거
- GitHub repository branch protection 또는 Environment 외부 설정

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 원문 | 티어 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|
| AC1 | PR 또는 `dev`/`main` push일 때 Quality Gate가 event checkout의 `github.sha`로 artifact name·image tag·OCI revision을 묶고 manual/custom target SHA 경로를 포함하지 않는다. | 사용자: "SHA 관련한 내용이 자주 나오는데 ... 제거해" + 승인된 master plan Phase -1 | T1 | `bash ci/quality-gate-contract-test.sh` | exit 0 |
| AC2 | 고정 marker가 있는 PR일 때 dependency bootstrap이 Gradle lock/verification metadata review artifact만 만들고 target SHA, dependency fingerprint, `SHA256SUMS`를 만들지 않으며 marker가 없으면 strict 경로를 선택한다. | 사용자: "제거해" + 승인된 master plan Phase -1 | T1 | `bash ci/dependency-bootstrap-contract-test.sh` | exit 0 |
| AC3 | repository 검증 시 secret-bearing deploy workflow가 존재하지 않고 host-local deploy script의 no-host-build, API readiness 선행, rollback 계약이 유지된다. | 사용자: "ci 관련 runner는 적용한 적이 전무하므로 ... 해당 내용도 제거하는 방향" 및 "보안적으로 너무 과한 구현 ... 완화" | T1 | `bash docker/deploy-contract-test.sh` | exit 0 |
| AC4 | Phase 문서 검증 시 GitHub Actions에 production/exchange credential을 제공하지 않는 책임, host-local artifact/secret/runtime 경계, 기준선과 Task 1~3 direct·review 결과 및 실제 host/activation `NOT_RUN`이 기록되고 활성 runbook이 GitHub production Environment/SSH secret 배포를 요구하지 않는다. | 사용자: "수정사항을 모두 수용할 수 있게 계획문서를 작성해 ... 사전에 명시한 '완료 조건'" | T1 | 아래 `AC4 semantic command` | exit 0 |

### AC4 semantic command

```bash
bash docs/check-documentation.sh &&
rg -Fq 'GitHub Actions에는 exchange credential을 제공하지 않는다' .ai/planning/private-live-autotrader/progress.md &&
for value in 'host-local' 'Quality Gate artifact' 'github.sha' 'OCI revision' 'artifact ID' 'host secret source' 'GitHub Actions에는 production 또는 exchange credential을 제공하지 않는다'; do
  rg -Fq "$value" docs/runbooks/deployment.md
done &&
rg -Fq 'operator-controlled host secret source' docs/runbooks/configuration-profiles.md &&
rg -Fq 'runtime service account' docs/deploy/aws-setup.md &&
rg -Fq 'operator-owned command' docs/deploy/aws-setup.md &&
rg -Fq 'deploy workflow는 제거' .ai/PROJECT_STATUS.md &&
rg -Fq '기존 GitHub-hosted Quality Gate만' .ai/PROJECT_STATUS.md &&
rg -Fq 'NOT_DEPLOYED' .ai/PROJECT_STATUS.md &&
for task in 1 2 3; do
  rg -Fq "Task ${task} direct verification: PASS" .ai/planning/private-live-autotrader/progress.md
  rg -Fq "Task ${task} Spec Review: PASS" .ai/planning/private-live-autotrader/progress.md
  rg -Fq "Task ${task} Code Review: PASS" .ai/planning/private-live-autotrader/progress.md
done &&
rg -Fq '실제 host/activation: NOT_RUN' .ai/planning/private-live-autotrader/progress.md &&
! rg -n 'GitHub.*production.*Environment|EC2_SSH_KEY|Deploy workflow' \
  docs/runbooks/deployment.md docs/runbooks/configuration-profiles.md docs/deploy/aws-setup.md
```

**티어 강등 사유** *(T1이 아닌 항목만. Gate 1에서 사람이 함께 승인한다)*

- 없음

## 회귀 방어선

이번 변경으로 깨질 수 있는 기존 동작. RED 면제 대상이다.

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | Kotlin compile, unit test와 architecture boundary | `./gradlew compileKotlin test architectureTest --offline --no-daemon` |
| R2 | Web lock install, lint와 production build(Node 20.9+) | `npm --prefix apps/web ci --include=optional && npm --prefix apps/web run lint && npm --prefix apps/web run build` |
| R3 | shell syntax와 documentation link/path 계약 | `for f in ci/*.sh docker/*.sh; do bash -n "$f"; done && bash docs/check-documentation.sh` |
| R4 | GitHub Action pin, strict Gradle verification, NVD scan 순서와 7개 Quality Gate job | `bash ci/quality-gate-contract-test.sh` |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

### AC1

동결 시점에는 RED/GREEN을 실행하지 않았다. Task 3에서 새 contract test를 먼저 작성한 뒤 결과를 append한다.

### AC2

동결 시점에는 RED/GREEN을 실행하지 않았다. Task 1에서 새 behavioral contract를 먼저 작성한 뒤 결과를 append한다.

### AC3

동결 시점에는 RED/GREEN을 실행하지 않았다. Task 2에서 새 deploy contract를 먼저 작성한 뒤 결과를 append한다.

### AC4

동결 시점에는 RED/GREEN을 실행하지 않았다. Task 4에서 문서 변경 전후에 AC4 semantic command를 실행한 뒤 결과를
controller가 append한다.

## 변경 요청

> 동결 후 기준을 바꿔야 할 때만 작성. 사람이 승인하기 전까지 구현을 중단한다.

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|
| AC4·evidence 경로 | uncommitted 초안의 문서 경계 일부와 evidence owner 미명시 | semantic command 전체와 controller-only append 경로 명시 | 구현 전 독립 리뷰 HIGH/MEDIUM 5건 해소; 구현은 아직 시작하지 않음 | 승인된 master plan의 검증 가능성을 강화하며 사용자 `이제 작업 진행해` 범위 안에서 최종 동결 |

## 최종 판정

```text
DoD VERDICT: private-live-autotrader-phase-minus-1
  T1/T2 자동:      0/4 PASS
  T3 기록 제출:    0건
  T4 사람 확인:    0건 대기
  => FAILED
```

**사람 확인이 필요한 항목**

- 없음

## Evidence 기록 소유권

Implementer와 Reviewer는 이 파일을 수정하지 않는다. `work` lifecycle controller가 각 Task의 fresh RED/GREEN 출력과 review
verdict를 받은 직후 해당 AC 절에 append하고, Phase acceptance 검증과 PR Quality Gate가 끝난 뒤 수용기준 문장을 바꾸지
않고 `최종 판정` 숫자와 상태만 갱신한다.
