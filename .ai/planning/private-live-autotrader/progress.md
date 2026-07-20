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

