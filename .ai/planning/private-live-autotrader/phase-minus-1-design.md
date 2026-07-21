# PRIVATE LIVE Phase -1 설계

> 상태: 승인된 마스터 계획에서 추출한 실행 설계  
> 작성일: 2026-07-20  
> 상위 정본: `.ai/planning/private-live-autotrader/task_plan.md` §7 Phase -1

## 1. 목표

Phase -1은 자동매매 기능을 추가하지 않는다. 최신 `dev`에서 분리한 feature branch의 검증 경계를 단순화해 이후 Phase가
다음 조건 아래에서 개발되도록 만드는 작업이다.

- GitHub Actions는 기존 GitHub-hosted `Quality Gate` 하나만 사용한다.
- PR run은 review evidence이고, `dev`/`main` push run은 merge 결과 evidence다.
- workflow가 검증할 commit은 사용자가 입력한 SHA가 아니라 GitHub가 해당 run에 제공한 `github.sha`다.
- 표준 Git commit ID, OCI revision label, GitHub artifact ID, Gradle verification checksum은 유지한다.
- 애플리케이션 또는 CI가 별도의 target SHA, dependency fingerprint, checksum bundle을 만들지 않는다.
- 운영 secret을 받는 GitHub deploy workflow는 제거하고 배포는 host-local operator 절차로만 남긴다.

## 2. 기준선과 격리

- base branch/ref: `dev` / `b877d42b9d07fd6034f60495fa78136e55b8f6ed`
- feature branch: `feat/private-live-autotrader`
- linked worktree: `/mnt/c/Users/yeop/IdeaProjects/premium-spread-private-live-autotrader`
- Draft PR: `#63`, base `dev`
- 주 작업트리의 `.gitignore`, `deploy/nginx.ssl.conf`, `deploy/setup-server.sh`, `.claude/worktrees/`, `.oci-launch.env`와
  untracked 계획 디렉터리는 사용자 소유 기준선으로 분류하며 feature worktree에서 수정하지 않는다.

로컬 Gradle 기준선은 offline/no-daemon으로 통과했다. Web install과 lint도 통과했으나 로컬 Node 18.20.8은 Next.js 16의
Node 20.9+ 요구를 만족하지 않아 build가 소스 실행 전에 차단됐다. 이 환경 차이는 `progress.md`에 기존 기준선으로 남기고,
Node 20을 쓰는 PR Quality Gate의 Web/Docker job을 Phase acceptance evidence로 사용한다.

## 3. Quality Gate 이벤트와 provenance

### 3.1 Trigger

허용 trigger는 다음뿐이다.

```yaml
on:
  pull_request:
  push:
    branches:
      - dev
      - main
```

`workflow_dispatch`, stale `refactor/infrastructure-boundary` push trigger, 전역 `TARGET_SHA`, checkout `ref` override와
`ci/verify-target-sha.sh`를 제거한다. 각 job은 Actions가 준비한 event ref를 기본 checkout하고, run identity에는
`${{ github.sha }}`를 사용한다. PR의 `github.sha`는 merge ref일 수 있으므로 ReleaseCandidate 입력으로 사용하지 않는다.
ReleaseCandidate는 향후 실제 merged `dev` push run만 허용한다.

### 3.2 Artifact와 OCI image

모든 report/archive artifact 이름과 API/Batch/Web image tag는 `${{ github.sha }}`를 사용한다. 세 Docker build에는 다음
표준 OCI label을 동일하게 설정한다.

```yaml
labels: org.opencontainers.image.revision=${{ github.sha }}
```

Docker archive upload step의 id는 `docker-archives`로 고정한다. upload action이 반환하는
`${{ steps.docker-archives.outputs.artifact-id }}`를 `github.run_id`와 `github.sha`와 함께 `GITHUB_STEP_SUMMARY`에 기록한다.
별도 digest나 서명 bundle은 만들지 않는다. contract test는 artifact name, image tag, OCI revision과 summary의 commit 값이
모두 `${{ github.sha }}`를 참조하고 artifact ID output이 실제 사용되는지만 검증한다.

### 3.3 유지할 공급망 통제

다음 값은 custom SHA orchestration이 아니므로 유지한다.

- GitHub Action의 immutable commit pin
- Gradle dependency verification metadata 안의 SHA-256
- `ci/quality-tools.lock`의 다운로드 checksum
- strict dependency verification
- Dependency-Check NVD data update/cache/scan의 fail-closed 순서
- 현재 7개 job과 exact Gradle command
- Dockerfile의 lock/verification metadata materialization 검사

Quality Gate 권한은 `contents: read`만 유지한다. `packages: write`, SSH key, DB/JWT/SMTP/exchange secret은 넣지 않는다.

## 4. Dependency bootstrap 최소 계약

### 4.1 요청 marker

dependency를 추가해 lock/verification metadata 갱신이 필요한 PR만 다음 파일을 함께 commit한다.

```text
path: ci/dependency-bootstrap-request
exact bytes: request=gradle-dependency-bootstrap-v1\n
```

validator는 다음을 모두 만족할 때만 `requested=true`를 출력한다.

1. `GITHUB_EVENT_NAME=pull_request`
2. marker가 regular non-symlink file
3. Git mode `100644`
4. LF 한 줄이며 위 exact bytes와 일치

marker가 없으면 `requested=false`로 strict gate를 계속한다. marker가 push event, symlink, 다른 mode, CRLF, 추가 공백·줄 또는
다른 내용이면 fail-closed한다. parent commit SHA, branch name, expiry, dependency fingerprint와 marker-only commit 제약은
검사하지 않는다. PR 한 개에서 dependency 선언과 marker를 함께 review할 수 있게 한다.

### 4.2 생성과 review artifact

`generate-dependency-bootstrap.sh`는 review directory 하나만 받는다. root/build-logic의 lock과 verification metadata를
Gradle 표준 옵션으로 생성한 뒤 allowlist validator를 실행한다.

```text
--write-locks
--write-verification-metadata sha256
--refresh-dependencies
--no-daemon
```

validator는 기존 16개 lock/metadata allowlist, resolved-artifact manifest 존재, metadata의 `verify-metadata=true`, artifact별
lowercase SHA-256, trusted key/weak algorithm 금지를 유지한다. review artifact에는 다음만 넣는다.

- `files/` 아래 allowlisted 생성 파일
- `root-resolved-artifacts.txt`
- `build-logic-resolved-artifacts.txt`
- `review.patch`

`request-marker.txt`, `SHA256SUMS`, dependency fingerprint는 만들지 않는다. marker run은 review artifact를 올린 뒤 의도적으로
실패하며 acceptance로 세지 않는다. 사람이 allowlisted diff를 검토해 follow-up commit에 반영하고 marker를 제거한 뒤 같은
PR Quality Gate가 green이어야 한다.

## 5. Host-local deployment 경계

`.github/workflows/deploy.yml`은 삭제한다. 이 저장소에는 deploy/soak/migration/activation workflow나 self-hosted/JIT runner를
추가하지 않는다. `docker/deploy.sh`는 다음 host-local 계약을 계속 소유한다.

- server source pull/build 금지
- operator가 고른 40자리 Git commit image tag pull
- Batch 중지 후 migration preflight와 API migration/readiness 선행
- Batch/Web/Nginx 순차 기동과 smoke
- 실패 시 이전 성공 image tag rollback
- DB down migration 금지

operator는 validation/PRIVATE_LIVE host에서 registry image와 배포 bundle을 확보하고 host secret source에서 환경변수를
주입해 script를 직접 실행한다. GitHub Actions는 host SSH, production DB/JWT/SMTP 또는 exchange credential을 받지 않는다.
runtime service account는 operator-owned command/bundle을 수정할 수 없어야 한다. Phase -1은 실제 host, secret manager,
runner 또는 activation을 구성하지 않는다.

## 6. 실패 처리

- marker가 없으면 기존 strict verification 경로를 실행한다.
- marker가 잘못됐으면 generation을 시작하지 않고 validator에서 실패한다.
- allowlist 밖 파일이 생성되거나 verification metadata가 약화되면 artifact를 publish하지 않는다.
- PR marker run의 의도된 실패를 green acceptance로 해석하지 않는다.
- deploy contract는 GitHub workflow 존재를 요구하지 않고 host-local script의 no-build/readiness/rollback만 검증한다.
- 문서 semantic command를 변경 전에 실행해 RED를 확인하고, 문서와 contract가 GitHub production secret 전달을 다시
  요구하면 Phase -1 회귀로 판정한다.

## 7. 테스트 전략

1. dependency behavioral contract를 먼저 새 계약으로 바꾸고 기존 구현에서 RED를 확인한다. contract는 temp Git repository와
   fake `gradlew`를 사용해 generator까지 실행하며 Gradle option, allowlisted output과 review artifact를 관찰한다.
2. deploy contract를 host-local 계약으로 바꾸고 deploy workflow가 남아 있는 상태에서 RED를 확인한다.
3. quality contract를 새 trigger/provenance/negative contract로 바꾸고 기존 workflow에서 RED를 확인한다.
4. 각 구현 뒤 해당 direct contract를 GREEN으로 만든다.
5. shell syntax, Gradle unit/architecture, Web lint/build, docs와 PR의 7개 Quality Gate job을 Phase acceptance에서 재검증한다.

## 8. 제외 범위

- Strategy/Live application 모듈과 domain 구현
- 신규 GitHub workflow 또는 runner scanner
- GHCR publish/deploy 자동화
- production credential, NVD API key, exchange API key 구성
- 법률·거래소 자격 확인과 실제 주문
- GitHub repository branch protection/Environment 설정
