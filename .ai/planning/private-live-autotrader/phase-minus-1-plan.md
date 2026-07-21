# PRIVATE LIVE Phase -1 Implementation Plan

> **For agentic workers:** REQUIRED SKILL: 독립 `subagent-driven-development`를 `lifecycle owner=work`, `mode=dispatch`,
> `commit policy=controller-only`로 사용한다. 각 Task는 Implementer → Spec Reviewer → Code Reviewer 순서로 닫는다.

**Goal:** 기존 GitHub-hosted Quality Gate를 PR 및 `dev`/`main` 검증으로 단순화하고 custom SHA orchestration과
secret-bearing deploy workflow를 제거한다.

**Architecture:** GitHub event SHA, OCI revision label, artifact ID와 Gradle 표준 verification checksum만 유지한다.
Dependency metadata 갱신은 PR-only 고정 marker로 요청하고, 운영 배포는 기존 `docker/deploy.sh`를 operator가 host-local로
실행한다.

**Tech Stack:** GitHub Actions YAML, Bash, Gradle dependency locking/verification, Docker/OCI, Markdown contract tests

**Spec:** `.ai/planning/private-live-autotrader/phase-minus-1-design.md`  
**Frozen DoD:** `docs/dod/private-live-autotrader-phase-minus-1.dod.md`  
**Base/branch:** `dev` → `feat/private-live-autotrader`  
**Worktree:** `/mnt/c/Users/yeop/IdeaProjects/premium-spread-private-live-autotrader`

---

## 파일 구조와 책임

| 파일 | Phase -1 책임 |
|---|---|
| `.github/workflows/quality-gate.yml` | PR 및 `dev`/`main` push 검증, GitHub/OCI 표준 provenance |
| `ci/quality-gate-contract-test.sh` | trigger, job, dependency bootstrap, artifact/image provenance의 정적 계약 |
| `ci/check-dependency-bootstrap-request.sh` | PR-only exact marker validator |
| `ci/generate-dependency-bootstrap.sh` | Gradle lock/verification metadata 생성 orchestration |
| `ci/validate-dependency-bootstrap-output.sh` | generated file allowlist와 Gradle metadata 강도 검증 |
| `ci/dependency-bootstrap-contract-test.sh` | marker와 output validator의 독립 behavioral contract |
| `docker/deploy.sh` | 변경하지 않는 host-local 배포·readiness·rollback 실행기 |
| `docker/deploy-contract-test.sh` | deploy workflow 없이 host-local 실행기와 compose 계약 검증 |
| `docs/runbooks/deployment.md` | operator-owned host-local publish/deploy/rollback 절차 |
| `docs/runbooks/configuration-profiles.md` | `prd` secret 공급 경계를 host secret source로 고정 |
| `docs/deploy/aws-setup.md` | private host 준비와 operator 실행 절차 |
| `.ai/PROJECT_STATUS.md` | deploy workflow 제거와 현재 미배포 상태 기록 |
| `.ai/planning/private-live-autotrader/progress.md` | 기준선과 Phase acceptance evidence |

## 의존성 DAG

```text
Task 1 ─┐
        ├─→ Task 3 → Task 4 → Phase acceptance
Task 2 ─┘
```

Task 1과 Task 2는 파일 scope가 분리되지만 target worktree 하나에서 controller-only로 직렬 실행한다. Task 3은 두 계약을
Quality Gate에 통합하므로 두 Task의 Spec/Code Review가 모두 닫힌 뒤 시작한다. Task 4는 구현 계약이 green인 뒤 문서를
현재 동작에 맞춘다.

## Task 1: Dependency bootstrap을 PR-only 고정 marker로 단순화

**Covered DoD:** AC2

**Files:**

- Modify: `ci/check-dependency-bootstrap-request.sh`
- Modify: `ci/generate-dependency-bootstrap.sh`
- Modify: `ci/validate-dependency-bootstrap-output.sh`
- Test: `ci/dependency-bootstrap-contract-test.sh`
- Delete: `ci/dependency-fingerprint.sh`

**허용 write scope:** 위 다섯 파일만 수정 또는 삭제한다. Gradle lock과 verification metadata는 behavioral fixture 외 실제
repository 파일을 갱신하지 않는다.

**제외:** workflow wiring, deploy 파일, 문서, dependency version 변경, commit/push.

- [x] **Step 1: 새 marker behavioral contract를 먼저 작성한다**

  request fixture의 exact bytes를 다음 한 줄과 LF로 고정한다.

  ```text
  request=gradle-dependency-bootstrap-v1
  ```

  contract는 marker 없음 → `requested=false`, exact marker + `pull_request` → `requested=true`, push/symlink/mode
  `120000`/CRLF/추가 줄/다른 값 → non-zero를 각각 실행한다. parent SHA, fingerprint, expiry와 marker-only commit fixture는
  제거한다.

- [x] **Step 2: 기존 구현에서 RED를 확인한다**

  Run: `bash ci/dependency-bootstrap-contract-test.sh`

  Expected: 기존 validator가 `pull_request`를 거절하거나 여섯 줄 legacy marker를 요구해 non-zero.

- [x] **Step 3: marker validator를 최소 구현한다**

  핵심 계약은 아래와 동일해야 한다.

  ```bash
  expected='request=gradle-dependency-bootstrap-v1'
  [[ "${GITHUB_EVENT_NAME:-}" == "pull_request" ]] || fail "marker is accepted only for a pull_request event"
  [[ -f "${marker}" && ! -L "${marker}" ]] || fail "marker must be a regular non-symlink file"
  [[ "$(git ls-files -s -- "${marker}" | awk '{print $1}')" == "100644" ]] || fail "marker Git mode must be 100644"
  [[ "$(cat "${marker}")" == "${expected}" ]] || fail "marker content must match the fixed v1 request"
  [[ "$(wc -l < "${marker}")" -eq 1 ]] || fail "marker must contain one LF-terminated line"
  grep -q $'\r' "${marker}" && fail "marker must use LF line endings"
  echo 'requested=true' >> "${output_file}"
  ```

  marker가 없을 때 regular/symlink 검사 전에 `requested=false`로 exit 0 해야 한다.

- [x] **Step 4: generator에서 target SHA 인자를 제거한다**

  첫 인자를 review directory로 바꾸고 `HEAD^`, 40-hex 검증을 삭제한다.

  ```bash
  review_dir="${1:?review output directory is required}"
  [[ -z "$(git status --porcelain)" ]] || fail "checkout must be clean before generation"
  ```

  root/build-logic Gradle 명령의 `--write-locks`, `--write-verification-metadata sha256`, `--refresh-dependencies`,
  `--no-daemon`과 마지막 output validator 호출은 유지한다.

- [x] **Step 5: review artifact에서 custom checksum bundle을 제거한다**

  16개 generated file allowlist, resolved-artifact manifest, XML SHA-256/trusted-key 검증은 유지한다. output directory에는
  `files/`, 두 resolved manifest와 `review.patch`만 생성한다. 아래 두 동작은 삭제한다.

  ```text
  request-marker.txt 생성
  SHA256SUMS 생성
  ```

- [x] **Step 6: output과 generator behavioral contract를 갱신한다**

  valid fixture에서 `files/gradle.lockfile`, `review.patch`, 두 manifest가 존재하고 `SHA256SUMS` 및
  `request-marker.txt`가 존재하지 않는지 검사한다. non-allowlisted path, non-SHA256/trusted-key, checksum 없는 artifact는
  계속 non-zero여야 한다.

  별도 temp Git repository에는 generator, validator와 executable fake `gradlew`를 복사한다. fake는 repository 밖
  `GRADLE_INVOCATION_LOG`에 인자를 기록하고 16개 allowlisted lock/metadata 및 두 resolved-artifact manifest를 생성한다.
  temp repository의 `build/`와 `build-logic/build/`은 `.gitignore`로 제외하고 baseline을 commit해 generator의 clean-check를
  통과시킨다. 실제 generator를 아래 signature로 실행해 review artifact까지 만들어야 한다.

  ```bash
  GRADLE_INVOCATION_LOG="${tmp_dir}/gradle-invocations" \
    bash ci/generate-dependency-bootstrap.sh "${tmp_dir}/generator-review"
  ```

  log에서 root/build-logic 두 호출과 `--write-locks`, `--write-verification-metadata sha256`, `--refresh-dependencies`,
  `--no-daemon`을 검사한다. review directory의 allowlisted files/patch/manifests 존재와 custom checksum bundle 부재도 검사한다.

- [x] **Step 7: legacy fingerprint script를 삭제하고 direct verification을 실행한다**

  Run: `bash ci/dependency-bootstrap-contract-test.sh`

  Expected: `dependency bootstrap behavioral contracts verified`, exit 0.

  Run: `bash -n ci/check-dependency-bootstrap-request.sh && bash -n ci/generate-dependency-bootstrap.sh && bash -n ci/validate-dependency-bootstrap-output.sh && bash -n ci/dependency-bootstrap-contract-test.sh`

  Expected: exit 0.

## Task 2: Secret-bearing deploy workflow를 제거하고 host-local contract를 보존

**Covered DoD:** AC3

**Files:**

- Delete: `.github/workflows/deploy.yml`
- Test: `docker/deploy-contract-test.sh`

**허용 write scope:** 위 두 파일만 수정 또는 삭제한다. `docker/deploy.sh`, compose와 runtime 설정은 읽기 전용이다.

**제외:** host provisioning, registry login/push 구현, Quality Gate 변경, 문서, commit/push.

- [x] **Step 1: deploy contract를 host-local 기준으로 먼저 변경한다**

  workflow parser와 `publish-images` assertions를 삭제하고 다음 negative contract를 추가한다.

  ```bash
  deploy_workflow="${root_dir}/.github/workflows/deploy.yml"
  [[ ! -e "${deploy_workflow}" ]] || fail "secret-bearing deploy workflow must not exist"
  ```

  Quality Gate API/Batch exact integration command 검증, `docker/deploy.sh` no-host-build/readiness/rollback, compose health/management
  port, monitoring image digest와 compose config 검증은 유지한다. `docker/deploy.sh`와 Quality Gate 양쪽에 `git pull` 또는
  server-side build가 없어야 한다.

- [x] **Step 2: workflow가 남은 상태에서 RED를 확인한다**

  Run: `bash docker/deploy-contract-test.sh`

  Expected: `.github/workflows/deploy.yml` 존재 assertion으로 non-zero.

- [x] **Step 3: deploy workflow를 삭제한다**

  `.github/workflows/deploy.yml` 전체를 삭제한다. 대체 workflow, reusable workflow, runner 설정은 만들지 않는다.

- [x] **Step 4: host-local direct verification을 실행한다**

  Run: `bash docker/deploy-contract-test.sh`

  Expected: `deploy contracts verified`, exit 0.

  Run: `test ! -e .github/workflows/deploy.yml && bash -n docker/deploy.sh && bash -n docker/deploy-contract-test.sh`

  Expected: exit 0.

## Task 3: Quality Gate trigger와 GitHub/OCI provenance를 전환

**Covered DoD:** AC1, AC2, AC3의 Quality Gate 통합 부분

**Files:**

- Modify: `.github/workflows/quality-gate.yml`
- Test: `ci/quality-gate-contract-test.sh`
- Delete: `ci/verify-target-sha.sh`

**허용 write scope:** 위 세 파일만 수정 또는 삭제한다.

**제외:** job 수/Gradle exact command/Action pin/quality tool lock 변경, 신규 workflow, runtime 코드, 문서, commit/push.

- [x] **Step 1: 새 Quality Gate contract를 먼저 작성한다**

  contract가 다음을 검증하게 바꾼다.

  ```text
  pull_request 존재
  push branch는 dev/main만 존재
  workflow_dispatch, refactor/infrastructure-boundary, TARGET_SHA, verify-target-sha 부재
  deploy.yml 부재
  7개 required job 유지
  checkout ref override 0개
  dependency marker validator/generator는 compile job에 1회씩 존재
  artifact name과 Docker tag/OCI revision은 github.sha 사용
  docker archive upload id=docker-archives와 artifact-id/run_id/sha summary 기록
  ```

  기존 Action pin, strict Gradle, NVD, Dockerfile/lock/materializer 검증은 유지한다. legacy deploy workflow helper와 후반 publish
  assertions는 삭제한다.

- [x] **Step 2: 기존 workflow에서 RED를 확인한다**

  Run: `bash ci/quality-gate-contract-test.sh`

  Expected: manual trigger/stale branch/custom target SHA 또는 deploy workflow 존재 assertion으로 non-zero.

- [x] **Step 3: event와 checkout을 GitHub 기본 ref로 전환한다**

  workflow 상단은 다음 구조로 고정한다.

  ```yaml
  on:
    pull_request:
    push:
      branches:
        - dev
        - main

  permissions:
    contents: read

  concurrency:
    group: quality-gate-${{ github.sha }}
    cancel-in-progress: true
  ```

  `workflow_dispatch`, 전역 `env.TARGET_SHA`, 7개 checkout의 `with.ref`, 7개 exact verifier step을 제거하고
  `ci/verify-target-sha.sh`를 삭제한다.

- [x] **Step 4: dependency bootstrap wiring을 새 signature로 변경한다**

  marker validator는 그대로 `GITHUB_OUTPUT`을 받고 generator는 review directory만 받는다.

  ```yaml
  - name: Generate dependency locks and SHA-256 metadata for review
    if: steps.bootstrap-request.outputs.requested == 'true'
    run: bash ci/generate-dependency-bootstrap.sh build/reports/dependency-bootstrap-review
  ```

  artifact name은 `dependency-bootstrap-review-${{ github.sha }}`로 고정하고 marker run을 의도적으로 exit 1로 끝낸다.
  committed verification metadata가 없을 때 자동 생성하는 별도 fallback step과 artifact는 삭제한다. normal run은 committed
  metadata로 strict Gradle command를 실행한다.

- [x] **Step 5: 모든 evidence artifact name을 github.sha로 전환한다**

  unit, API integration, Batch integration, static analysis, dependency security, Docker archive와 dependency bootstrap review의
  이름이 `${{ github.sha }}`를 사용해야 한다. 사용자 입력, parent SHA 또는 fingerprint를 이름에 넣지 않는다.

- [x] **Step 6: Docker tag, OCI revision과 artifact ID를 결속한다**

  API/Batch/Web build 각각에 다음 tag와 label을 설정한다.

  ```yaml
  tags: ghcr.io/${{ github.repository }}/<component>:${{ github.sha }}
  labels: org.opencontainers.image.revision=${{ github.sha }}
  ```

  upload step과 summary는 다음 필드를 사용한다.

  ```yaml
  - name: Publish Docker image archives
    id: docker-archives
    uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02
    with:
      name: docker-images-${{ github.sha }}

  - name: Record Docker artifact provenance
    run: |
      {
        echo "run_id=${GITHUB_RUN_ID}"
        echo "commit=${GITHUB_SHA}"
        echo "artifact_id=${{ steps.docker-archives.outputs.artifact-id }}"
      } >> "${GITHUB_STEP_SUMMARY}"
  ```

  이는 플랫폼이 제공한 ID를 기록할 뿐 자체 digest를 계산하지 않는다.

- [x] **Step 7: Quality Gate contract를 GREEN으로 만든다**

  Run: `bash ci/quality-gate-contract-test.sh`

  Expected: `quality gate and supply-chain contracts verified`, exit 0.

  Run: `test ! -e ci/verify-target-sha.sh && ! rg -n 'workflow_dispatch|TARGET_SHA|verify-target-sha|refactor/infrastructure-boundary|target_sha|dependency_fingerprint|SHA256SUMS' .github/workflows/quality-gate.yml ci/check-dependency-bootstrap-request.sh ci/generate-dependency-bootstrap.sh ci/validate-dependency-bootstrap-output.sh`

  Expected: exit 0.

## Task 4: 활성 문서와 Phase evidence를 host-local 경계로 동기화

**Covered DoD:** AC4

**Files:**

- Modify: `docs/runbooks/deployment.md`
- Modify: `docs/runbooks/configuration-profiles.md`
- Modify: `docs/runbooks/auth-security.md`
- Modify: `docs/runbooks/v12-migration.md`
- Modify: `docs/deploy/aws-setup.md`
- Modify: `deploy/README.md`
- Modify: `.ai/PROJECT_STATUS.md`
- Modify: `.ai/planning/private-live-autotrader/progress.md`

**허용 write scope:** 위 여덟 문서만 수정한다.

**제외:** 역사 문서 `docs/superpowers/plans/2026-07-14-*`, master plan/design/DoD 기준 변경, code/workflow/script 수정,
실제 host/secret 설정, commit/push.

- [x] **Step 1: 기존 문서에서 AC4 RED를 확인한다**

  Run: `bash -c 'bash docs/check-documentation.sh && rg -Fq "GitHub Actions에는 exchange credential을 제공하지 않는다" .ai/planning/private-live-autotrader/progress.md && for value in "host-local" "Quality Gate artifact" "github.sha" "OCI revision" "artifact ID" "host secret source" "GitHub Actions에는 production 또는 exchange credential을 제공하지 않는다"; do rg -Fq "$value" docs/runbooks/deployment.md; done && rg -Fq "operator-controlled host secret source" docs/runbooks/configuration-profiles.md && rg -Fq "operator-controlled host secret source" docs/runbooks/auth-security.md && rg -Fq "MIGRATION_V12_ALLOW_EMPTY=true bash docker/deploy.sh" docs/runbooks/v12-migration.md && rg -Fq "runtime service account" docs/deploy/aws-setup.md && rg -Fq "operator-owned command" docs/deploy/aws-setup.md && rg -Fq "/usr/local/lib/docker/cli-plugins/docker-compose" docs/deploy/aws-setup.md && rg -Fq "docker compose version" docs/deploy/aws-setup.md && rg -Fq "DEPRECATED" deploy/README.md && rg -Fq "docker/deploy.sh" deploy/README.md && rg -Fq "deploy workflow는 제거" .ai/PROJECT_STATUS.md && rg -Fq "기존 GitHub-hosted Quality Gate만" .ai/PROJECT_STATUS.md && rg -Fq "NOT_DEPLOYED" .ai/PROJECT_STATUS.md && for task in 1 2 3; do rg -Fq "Task ${task} direct verification: PASS" .ai/planning/private-live-autotrader/progress.md && rg -Fq "Task ${task} Spec Review: PASS" .ai/planning/private-live-autotrader/progress.md && rg -Fq "Task ${task} Code Review: PASS" .ai/planning/private-live-autotrader/progress.md; done && rg -Fq "실제 host/activation: NOT_RUN" .ai/planning/private-live-autotrader/progress.md && ! rg -n "GitHub.*production.*Environment|EC2_SSH_KEY|Deploy workflow" docs/runbooks/deployment.md docs/runbooks/configuration-profiles.md docs/runbooks/auth-security.md docs/deploy/aws-setup.md && ! rg -n "\\./deploy/deploy\\.sh" docs/runbooks/v12-migration.md deploy/README.md && ! rg -n "git clone -b prd|docker compose .*--build" deploy/README.md'`

  Expected: 기존 runbook의 GitHub production workflow/secret 문장 또는 새 evidence field 부재로 non-zero.

- [x] **Step 2: deployment runbook의 owner와 공급 경계를 바꾼다**

  owner를 PRIVATE_LIVE/validation host의 operator로 바꾼다. GitHub `production` Environment, EC2 SSH secret, workflow가 bundle을
  전송·image를 push한다는 문장을 제거한다. Quality Gate의 green artifact와 `github.sha`/OCI revision/artifact ID를 확인한 뒤
  operator가 archive를 확보·load하고 host registry credential로 publish하며, host secret source에서 runtime 값을 주입해
  `docker/deploy.sh`를 실행한다고 기록한다. 배포 후보는 `event=push`, `branch=dev`인 green Quality Gate run으로 제한하고
  `pull_request` artifact는 review evidence일 뿐 배포 후보가 아니라고 명시한다. GitHub Actions에는 production/exchange
  credential을 제공하지 않는다고 명시한다.

- [x] **Step 3: profile, 인증, migration 및 host setup 문서를 host-local로 바꾼다**

  `prd`는 승인된 commit-tag image와 operator-controlled host secret source를 사용한다고 기록한다. `EC2_SSH_KEY`, GitHub
  Environment secret/approval, Deploy workflow 지시를 삭제한다. Auth와 V12 runbook도 같은 host secret source와
  `docker/deploy.sh`를 정본으로 사용한다. AWS setup은 실제 `docker compose` CLI plugin 설치를 안내한다. legacy
  `deploy/README.md`는 서버 source pull/build 절차를 폐기하고 정본 runbook으로 연결한다. host runtime UID가
  operator-owned command/bundle/secret을 수정하지 못하는 권한 경계를 넣되 새 KMS/HSM/TOTP/runner를 요구하지 않는다.

- [x] **Step 4: Project Status와 progress를 갱신한다**

  `.ai/PROJECT_STATUS.md`의 현재 미배포 상태를 유지하면서 deploy workflow가 제거됐고 기존 GitHub-hosted Quality Gate만
  남았다고 기록한다. `progress.md`에는 Task 1~3 direct verification, review verdict와 Phase acceptance 전 상태를 append한다.
  실행하지 않은 실제 host/activation은 `NOT_RUN`으로 기록한다.

- [x] **Step 5: 문서 semantic contract를 GREEN으로 만든다**

  Run: `bash docs/check-documentation.sh`

  Expected: documentation check passed, exit 0.

  Run: `docs/dod/private-live-autotrader-phase-minus-1.dod.md`의 최신 `AC4 semantic command` 전체를 실행한다.

  Expected: exit 0.

## Task 5: GitHub-hosted runner 기본 도구 호환성 복구

**Covered DoD:** 회귀 방어선 R3, R4와 Phase acceptance 원격 검증

**Files:**

- Modify: `ci/quality-gate-contract-test.sh`
- Modify: `docs/check-documentation.sh`

**허용 write scope:** 위 두 contract script만 수정한다.

**제외:** runner package 설치, 신규 Action/workflow/job, contract 의미·검사 대상 완화, runtime 코드, commit/push.

- [x] **Step 1: 원격 및 로컬에서 숨은 ripgrep 의존성 RED를 확인한다**

  Remote: PR #63 Quality Gate run `29789180354`, job `88507065048`.

  Expected: `rg: command not found` 뒤 Quality Gate contract exit 1.

  Run: `bash -c 'rg() { return 127; }; export -f rg; bash ci/quality-gate-contract-test.sh'`

  Expected: `quality gate contract failed: Quality Gate must use pinned actions`, exit 1.

- [x] **Step 2: contract script를 runner 기본 grep/find 도구로 이식한다**

  `rg`의 extended-regex, filename suppression, recursive glob 의미를 각각 `grep -E`, `grep -h`, 명시적 file glob으로 보존한다.
  forbidden-pattern 검사는 command-not-found를 success로 오판하지 않아야 하고 documentation link 추출도 동일한 Markdown link
  집합을 검사해야 한다.

- [x] **Step 3: ripgrep이 없는 환경을 포함해 GREEN을 확인한다**

  Run: `! grep -En '\\brg\\b' ci/quality-gate-contract-test.sh docs/check-documentation.sh`

  Run: `bash -c 'rg() { return 127; }; export -f rg; bash ci/quality-gate-contract-test.sh && bash docs/check-documentation.sh'`

  Run: `bash ci/quality-gate-contract-test.sh && bash docs/check-documentation.sh && bash -n ci/quality-gate-contract-test.sh && bash -n docs/check-documentation.sh && git diff --check`

  Expected: 각 명령 exit 0.

## Phase acceptance verification

모든 Task의 Spec Review와 Code Review가 닫힌 뒤 controller가 worktree에서 순서대로 실행한다.

```bash
./gradlew compileKotlin test architectureTest --offline --no-daemon
npm --prefix apps/web ci --include=optional
npm --prefix apps/web run lint
npm --prefix apps/web run build
bash ci/quality-gate-contract-test.sh
bash ci/dependency-bootstrap-contract-test.sh
bash docker/deploy-contract-test.sh
bash docs/check-documentation.sh
git diff --check
```

로컬 Node가 20.9 미만이면 Web build를 green으로 기록하지 않는다. 환경 BLOCKED로 남기고 Phase acceptance commit을 push한 뒤
Node 20을 쓰는 Draft PR `#63`의 7개 Quality Gate job이 모두 성공해야 DoD 최종 판정을 `VERIFIED`로 바꿀 수 있다.

## DoD evidence closure — controller-only

Implementer/Reviewer write scope와 별개로 `work` lifecycle controller만
`docs/dod/private-live-autotrader-phase-minus-1.dod.md`의 증거 로그를 append한다.

1. 각 Task가 RED를 만든 직후 command, expected failure와 exit code를 해당 AC에 append한다.
2. 구현 후 같은 direct command의 GREEN output과 exit code를 append한다.
3. Task 1~4 Spec/Code Review 결과와 Phase acceptance 명령 결과를 progress에 기록한다.
4. acceptance commit push 뒤 PR `#63`의 7개 Quality Gate job 결과를 기록한다.
5. 네 AC가 모두 PASS일 때만 최종 판정을 `4/4 PASS`, `VERIFIED`로 갱신한다. 기준·tier·명령은 수정하지 않는다.

## Phase acceptance commit/push

controller만 아래 경계를 수행한다.

```text
commit: ci: PRIVATE LIVE 기능 브랜치 검증 계약 추가
push: origin feat/private-live-autotrader
PR: #63 Draft 유지
```

dependency bootstrap marker가 있는 의도된 red run은 acceptance가 아니다. 이 Phase에는 dependency 변경이 없으므로 marker를
commit하지 않는다.

원격 acceptance 실패를 수정하는 경우 controller는 독립 review와 로컬 재검증 후 별도 remediation commit
`fix: CI 계약 검사의 runner 도구 의존성 제거`를 push하고 새 PR run 전체를 다시 확인한다.
