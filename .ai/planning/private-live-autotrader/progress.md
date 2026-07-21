# PRIVATE LIVE Autotrader 진행 기록

> append 중심의 Phase acceptance 증거 문서다. 소스 구조의 정본은 `.ai/architecture/ARCHITECTURE_DESIGN.md`, 프로그램 범위와
> 완료 조건의 정본은 `task_plan.md`다.

## 프로그램 상태

- 현재 Phase: `-1`
- 현재 판정: `IN_PROGRESS`
- feature branch: `feat/private-live-autotrader`
- worktree: `/mnt/c/Users/yeop/IdeaProjects/premium-spread-private-live-autotrader`
- Draft PR: `https://github.com/geonyeop123/premium-spread/pull/63`
- base `dev`: `b877d42b9d07fd6034f60495fa78136e55b8f6ed`
- master plan commit: `b6e16edb3632978728f62918bddb25f791501467`

## Phase -1 기준선 — 2026-07-20

### 격리

주 작업트리에는 아래 사용자 소유 변경이 있었으며 feature worktree로 가져오거나 수정하지 않았다.

```text
 M .gitignore
 M deploy/nginx.ssl.conf
 M deploy/setup-server.sh
?? .ai/planning/private-live-autotrader/
?? .claude/worktrees/
?? .oci-launch.env
```

project-local `.worktrees/`는 ignore 대상이 아니고 `.gitignore`가 사용자 변경 중이므로 외부 linked worktree를 사용했다.

### 변경 전 검증

| 명령 | 결과 | 구분 |
|---|---|---|
| `./gradlew compileKotlin test architectureTest --offline --no-daemon` | exit 0, `BUILD SUCCESSFUL in 5m 15s`, 67 tasks | 기준선 GREEN |
| `bash ci/quality-gate-contract-test.sh && bash ci/dependency-bootstrap-contract-test.sh && bash docker/deploy-contract-test.sh && bash docs/check-documentation.sh` | exit 0 | 기존 계약 GREEN. Phase -1과 반대인 legacy 계약을 검증하므로 새 contract RED/GREEN과 구분 |
| `npm --prefix apps/web ci --include=optional` | exit 0, 706 packages, audit moderate 2건 | install 기준선 GREEN; Phase -1 dependency 변경 없음 |
| `npm --prefix apps/web run lint` | exit 0 | 기준선 GREEN |
| `npm --prefix apps/web run build` | exit 1: Node 18.20.8, Next.js 16은 Node 20.9+ 요구 | 환경 BLOCKED; 소스 compile 이전 실패 |

Node 20을 사용하는 Draft PR의 기존 Quality Gate가 Phase acceptance의 Web/Docker build 증거를 제공한다. 로컬 Node runtime을
이 Phase에서 다운로드하거나 repository scope로 추가하지 않는다.

### CI·운영 책임 경계

- GitHub Actions에는 exchange credential을 제공하지 않는다.
- 기존 GitHub-hosted `Quality Gate`만 유지한다.
- 장기 soak는 production credential이 없는 validation host에서 operator가 실행한다.
- migration, 배포, canary와 activation은 PRIVATE_LIVE host에서 operator가 host-local 명령으로 실행한다.
- runtime service account는 operator-owned 명령 파일과 배포 bundle을 수정할 수 없어야 한다.
- Phase -1은 runner, validation host, PRIVATE_LIVE host 또는 실제 secret을 구성하지 않는다.

## Phase acceptance 기록

Phase -1 구현·review·검증 결과와 acceptance commit/push는 이 아래에 append한다.

### Task 1~3 direct/review 결과 — 2026-07-20

- Task 1 direct verification: PASS — `bash ci/dependency-bootstrap-contract-test.sh`, exit 0,
  `dependency bootstrap behavioral contracts verified`
- Task 1 Spec Review: PASS — open finding 0
- Task 1 Code Review: PASS — HIGH/MEDIUM 0, LOW test gap 3건은 비차단
- Task 2 direct verification: PASS — `bash docker/deploy-contract-test.sh`, exit 0, `deploy contracts verified`
- Task 2 Spec Review: PASS — open finding 0
- Task 2 Code Review: PASS — HIGH/MEDIUM 0, LOW test gap 3건은 비차단
- Task 3 direct verification: PASS — `bash ci/quality-gate-contract-test.sh`, exit 0,
  `quality gate and supply-chain contracts verified`
- Task 3 Spec Review: PASS — HIGH/MEDIUM/LOW 0
- Task 3 Code Review: PASS — HIGH/MEDIUM/LOW 0, review mutation 11개를 모두 fail-closed로 탐지

### 실제 운영 상태 — 2026-07-20

- 실제 host/activation: NOT_RUN
- Phase -1 범위에서는 실제 host, secret manager, runner 또는 activation을 구성하지 않는다.

### Task 4 direct/review 결과 — 2026-07-20

- Task 4 direct verification: PASS — documentation check, 확장 AC4 semantic contract와 `git diff --check`, 모두 exit 0
- Task 4 Spec Review: PASS — HIGH/MEDIUM/LOW 0
- Task 4 Code Review: PASS — 초기 MEDIUM 3건과 후속 migration 순서 MEDIUM 1건을 수정한 뒤 HIGH/MEDIUM/LOW 0
- 실제 host/activation: NOT_RUN

### Phase -1 로컬 acceptance — 2026-07-20

| 명령 | 결과 |
|---|---|
| `./gradlew compileKotlin test architectureTest --offline --no-daemon` | PASS, exit 0, `BUILD SUCCESSFUL in 1m 13s`, 67 tasks |
| `npm --prefix apps/web ci --include=optional` | PASS, exit 0, 706 packages; audit moderate 2건은 기존 기준선과 동일 |
| `npm --prefix apps/web run lint` | PASS, exit 0 |
| `npm --prefix apps/web run build` | 환경 BLOCKED, exit 1; Node 18.20.8은 Next.js 요구 `>=20.9.0` 미충족 |
| Quality/dependency/deploy/documentation contracts + shell syntax + `git diff --check` | PASS, exit 0 |

Web build는 green으로 기록하지 않는다. Phase acceptance commit을 push한 뒤 Node 20을 사용하는 Draft PR `#63`의 7개
Quality Gate job 결과로 판정한다.

### 동결 변경 재승인 — 2026-07-21

- Holistic Spec Review: 동결 AC4의 5→8문서 범위 확장에 대한 사용자 명시적 재승인 전까지 FAIL
- Holistic Code Review: PR artifact 오승격 가능성 MEDIUM 1건을 merged `dev`의 `event=push`, `branch=dev` 경계로 수정
- Holistic Code Re-review: PASS — HIGH/MEDIUM 0
- 사용자 재승인: `승인`

### 재승인 후 acceptance 재검증 — 2026-07-21

| 명령 | 결과 |
|---|---|
| `./gradlew compileKotlin test architectureTest --offline --no-daemon` | PASS, exit 0, `BUILD SUCCESSFUL in 24s`, 67 tasks |
| `npm --prefix apps/web ci --include=optional` | PASS, exit 0, 706 packages; 현재 advisory snapshot 1 low·2 moderate, lockfile 변경 없음 |
| `npm --prefix apps/web run lint` | PASS, exit 0 |
| `npm --prefix apps/web run build` | 환경 BLOCKED, exit 1; Node 18.20.8은 Next.js 요구 `>=20.9.0` 미충족 |
| AC1~AC4 contract, 전체 shell syntax와 `git diff --check` | PASS, exit 0 |

- Final Spec/DoD Re-review: PASS — HIGH/MEDIUM/LOW 0
- Final Code Re-review: PASS — HIGH/MEDIUM 0, 기존 README의 개발용 `--build` 안내 LOW는 Phase 0 후속
- PR #63 Node 20 Quality Gate: acceptance commit push 전이므로 PENDING
