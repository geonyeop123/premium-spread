# Quality Gate Docker image build 시간 단축 (issue #107) — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (태스크 단위 실행).
> 체크박스(`- [ ]`)로 진행을 기록한다.

**Goal:** PR Quality Gate 의 Docker 경로(`T_docker`)를 테스트·검사 job 최장(`T_other`) 이하로 만들고, GHA cache 를 저장소
한도 아래로 유지한다.

**Architecture:** 세 이미지를 matrix leg 로 병렬 빌드하고 조립 job(`7. Docker image build`)이 part 3개를 확인한 뒤 기존
artifact 계약을 그대로 만든다. buildkit cache 는 dev push 만 쓴다. PR closed workflow 는 그 PR ref 의 cache 를 전부 지우고,
하루 1회 workflow 는 dev 의 `gradle-*` cache 만 지운다(buildkit 은 일정 삭제하지 않는다 — design §1.1).

**Tech Stack:** GitHub Actions, docker/build-push-action v6.18.0, docker/setup-buildx-action v3.11.1,
actions/upload-artifact v4.6.2(+ `/merge`), actions/checkout v4.2.2, GitHub CLI(`gh api`), bash, python3.

> design: `docs/work/docker-build-time/design.md` · dod: `docs/work/docker-build-time/dod.md`
> 모든 명령은 worktree 루트 `/home/yeop/IdeaProjects/premium-spread/.worktrees/chore-issue-107-docker-build-time` 에서 실행한다.
> **Dockerfile·Gradle 빌드 파일·`docker/deploy.sh`·compose 는 고치지 않는다.** 고쳐야 할 것 같으면 멈추고 보고한다.
> dod 가 `FROZEN` 이 되기 전에는 Task 1 이후를 시작하지 않는다.

## 공통 정의

고정 SHA(이미 저장소에 있는 값 — 새 action·새 SHA 를 추가하지 않는다):

```text
actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
docker/setup-buildx-action@e468171a9de216ec08956ac3ada2f0791b6bd435 # v3.11.1
docker/build-push-action@263435318d21b8e681c14492fe198d362a7d2c83 # v6.18.0
actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2
actions/upload-artifact/merge@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2
```

YAML 작성 규칙: 계약 테스트의 블록 추출기(`yaml_top_level_block`)는 0열에서 시작하는 줄을 만나면 블록을 끝낸다. workflow
파일의 주석은 **파일 맨 위(`name:` 앞)에만** 둔다. 들여쓰기는 기존 `quality-gate.yml` 과 같은 2칸.

정확 일치 줄 표기: 번호 목록 **안**의 code block 은 markdown 목록 들여쓰기 3칸이 앞에 붙어 있다. 실제 YAML 줄(=단정 문자열)은
그 3칸을 뺀 값이다. 표 안의 백틱 문자열은 앞 공백까지 그대로가 실제 줄이다.

태스크 순서와 의존:

```text
Task 0  외부 사실 확인 — ④ 개정에서 완료, dod 증거 로그에 기록됨 (구현자는 읽기만)
Task 1  Quality Gate Docker matrix + 조립 job + 계약 테스트 개정 (RED → GREEN)
Task 2  삭제 스크립트 + 행동 계약 (RED → GREEN) ── Task 3 의 전제
Task 3  cleanup workflow 2개 + 정적 계약(mutation 자가검증)
Task 4  배포 런북 갱신 (조립 job·rerun 창·BlobNotFound 수동 복구)
Task 5  로컬 전체 검증 (커밋 없음)
Task 6  PR CI 실측 — AC4·AC5·AC6 (커밋 없음, push 뒤)
Task 7  머지 뒤 관찰 — AC7·AC8 (커밋 없음)
```

---

## Task 0 — 외부 사실 확인 (완료, 커밋 없음)

F1~F8 은 컨트롤러가 확인했고 결과·출처를 design §7 과 dod `### Task 0 외부 사실` 에 옮겼다. 이 plan 은 그 결과를 전제로 한다.

| ID | 결과 | 이 plan 에 주는 영향 |
|---|---|---|
| F1 | TRUE — merge 입력·출력 확인, `overwrite` 없음 | Task 1 merge step 을 그대로 쓴다 |
| F2 | TRUE — upload 에 `overwrite` 있음 | Task 1 part step 의 `overwrite: true` 유지 |
| F3 | LIKELY — 빈 `cache-to` 는 인자를 만들지 않을 것 | 소스로 확정하지 않는다. **PR 로그로 판정**(Task 6 Step 2, dod AC5) |
| F4 | TRUE — `ignore-error` 는 export 실패만 무시 | Task 1 그대로 |
| F5 | FALSE — 없는 blob 은 빌드 실패, blob key 는 전역 sha256 | 사실로 기록만 한다(분기 없음). stale 은 gradle 만(Task 2·3), BlobNotFound 수동 복구(Task 4) |
| F6 | TRUE — 기본 브랜치 `dev`, PUBLIC | Task 3 그대로 |
| F7 | PARTIAL — endpoint·필드 확인, 권한 문구 미확인 | 권한은 Task 7 Step 1 `deleted>=1` 로 확인 |
| F8 | TRUE — gh 2.86.0 의 `--paginate --slurp`, GET `-f` | Task 2 호출 형태 그대로 |

---

## Task 1 — Quality Gate Docker matrix + 조립 job + 계약 테스트 개정

**Files:**
- Modify: `ci/quality-gate-contract-test.sh`
- Modify: `.github/workflows/quality-gate.yml` (현재 `docker-build` job :201-260 교체)

### 결합점 — 새 Docker job 전문

`.github/workflows/quality-gate.yml` 의 `docker-build:` job 블록(현재 :201-260) 전체를 아래 두 job 으로 바꾼다.
위치는 `dependency-security` 와 `common-integration` 사이 그대로.

```yaml
  docker-image:
    name: 7a. Docker image (${{ matrix.component }})
    runs-on: ubuntu-latest
    timeout-minutes: 25
    strategy:
      fail-fast: false
      matrix:
        component: [api, batch, web]
    env:
      DOCKER_BUILD_RECORD_UPLOAD: 'false'
    steps:
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
      - uses: docker/setup-buildx-action@e468171a9de216ec08956ac3ada2f0791b6bd435 # v3.11.1
      - name: Prepare immutable image archive directory
        run: mkdir -p "${RUNNER_TEMP}/docker-images"
      - name: Build image
        uses: docker/build-push-action@263435318d21b8e681c14492fe198d362a7d2c83 # v6.18.0
        with:
          context: .
          file: apps/${{ matrix.component }}/Dockerfile
          push: false
          tags: ghcr.io/${{ github.repository }}/${{ matrix.component }}:${{ github.sha }}
          labels: org.opencontainers.image.revision=${{ github.sha }}
          outputs: type=docker,dest=${{ runner.temp }}/docker-images/${{ matrix.component }}.tar
          cache-from: type=gha,scope=${{ matrix.component }}
          cache-to: ${{ github.event_name == 'push' && github.ref == 'refs/heads/dev' && format('type=gha,mode=max,scope={0},ignore-error=true', matrix.component) || '' }}
      - name: Publish Docker image part
        uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2
        with:
          name: docker-image-part-${{ matrix.component }}-${{ github.sha }}
          path: ${{ runner.temp }}/docker-images/${{ matrix.component }}.tar
          if-no-files-found: error
          compression-level: 0
          retention-days: 1
          overwrite: true

  docker-build:
    name: 7. Docker image build
    needs: docker-image
    if: ${{ !cancelled() }}
    permissions:
      actions: read
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - name: Require every image build to succeed
        env:
          IMAGE_BUILD_RESULT: ${{ needs.docker-image.result }}
        run: |
          if [[ "${IMAGE_BUILD_RESULT}" != "success" ]]; then
            echo "::error::Docker image builds finished with result=${IMAGE_BUILD_RESULT}"
            exit 1
          fi
      - name: Require exactly the three unexpired image parts
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          expected="$(printf 'docker-image-part-%s-%s\n' api "${GITHUB_SHA}" batch "${GITHUB_SHA}" web "${GITHUB_SHA}")"
          actual="$(gh api --paginate "repos/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}/artifacts" --jq '.artifacts[] | select(.expired == false) | select(.name | startswith("docker-image-part-")) | .name' | LC_ALL=C sort)"
          if [[ "${actual}" != "${expected}" ]]; then
            echo "::error::Expected exactly three unexpired Docker image parts for ${GITHUB_SHA}; re-run all jobs if parts expired"
            exit 1
          fi
      - name: Publish Docker image archives
        id: docker-archives
        uses: actions/upload-artifact/merge@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2
        with:
          name: docker-images-${{ github.sha }}
          pattern: docker-image-part-*-${{ github.sha }}
          compression-level: 0
          retention-days: 14
          delete-merged: true
      - name: Record Docker artifact provenance
        run: |
          {
            echo "run_id=${GITHUB_RUN_ID}"
            echo "commit=${GITHUB_SHA}"
            echo "artifact_id=${{ steps.docker-archives.outputs.artifact-id }}"
          } | tee -a "${GITHUB_STEP_SUMMARY}"
```

- `separate-directories` 는 기본 false(F1)라 쓰지 않는다. merge 에는 `overwrite` 입력이 없다(F1) — 넣지 않는다.
- part 검사 step: GitHub 의 기본 `run` 셸은 `pipefail` 이 없어 `gh` 실패가 파이프 안에서 삼켜지지만, 그 경우 `actual` 이 비거나
  어긋나 비교가 실패하므로 fail-closed 다. `gh api --jq` 는 gh 내장 jq 라 `jq` 설치가 필요 없다. 이전 attempt 의 part 도
  run 단위 artifact 목록에 나오므로 "Re-run failed jobs" 에서 성공 leg 의 part 를 센다.
- job 단위 `permissions` 는 최상위를 대체한다 — 이 job 의 token 은 `actions: read` 만 갖는다(checkout 없음, `contents` 불필요).

### 결합점 — 계약 테스트가 단정할 것

`ci/quality-gate-contract-test.sh` 의 아래 지점을 바꾼다. 줄 번호는 현재 파일 기준.

| 위치 | 현재 | 변경 후 단정 | 실패 메시지 |
|---|---|---|---|
| :98-100 | 들여쓴 `permissions:` 0줄 | 들여쓴 `permissions:` 정확히 1줄이고, 그것이 `docker-build` 블록의 `    permissions:` 이며, 그 아래 6칸 들여쓴 줄 모음이 정확히 `      actions: read` 한 줄 (최상위 단정 :94-97 은 그대로) | `only docker-build may override permissions, and only with actions: read` |
| :108-111 | `docker-build` job 에 build record env (multi-line 문자열을 `grep -Fq` 로 검사 — **각 줄을 OR 로 매치하는 약한 검사**다) | `docker-image` job 블록이 `$'    env:\n      DOCKER_BUILD_RECORD_UPLOAD: '\''false'\'''` 를 **부분 문자열로 연속 포함**(`[[ "${block}" == *"${expected}"* ]]`) | `docker-image job must disable the default Docker build record artifact` |
| :197 | job 8개 | `expected_jobs=(compile-architecture unit-coverage api-integration batch-integration static-analysis dependency-security docker-image docker-build common-integration)` | `Quality Gate job IDs must be exactly the nine required jobs in order` |
| :206-223 | 배열의 `'Publish Docker image archives'` / `'docker-images-${{ github.sha }}'` | 같은 자리를 `'Publish Docker image part'` / `'docker-image-part-${{ matrix.component }}-${{ github.sha }}'` 로 교체 (루프는 그대로 — pinned `upload-artifact@` 와 정확한 name 단정) | 기존 메시지 |
| :233-234 | `uses: actions/upload-artifact@` 7개 | 7개 유지 + `uses: actions/upload-artifact/merge@` 정확히 1개 | `Quality Gate must publish exactly seven upload-artifact steps` · `Quality Gate must assemble exactly one merged artifact` |
| :261-264 | `needs:` 0줄 | `needs:` 정확히 1줄이고 그 줄이 `docker-build` job 블록의 `    needs: docker-image` (주석도 "Docker 조립 job 만 matrix 를 기다린다" 로 갱신) | `only docker-build may wait, and only for docker-image` |
| :335-374 | 단일 job outputs 3개·API/Batch/Web step·archive·provenance | 아래 목록으로 전부 교체 | 아래 |
| :195 다음 | — | Task 2·3 에서 `bash "${root_dir}/ci/delete-actions-caches-test.sh"`, `bash "${root_dir}/ci/actions-cache-cleanup-contract-test.sh"` 추가 (Task 1 에서는 넣지 않는다) | — |

:335-374 를 대체하는 단정(정확 일치 줄은 `grep -Fxq`, 개수는 `grep -Fxc`/`grep -c`):

1. `docker-image` 블록에 아래 strategy 가 연속 포함(부분 문자열 비교) — `Docker image job must build the api/batch/web matrix without fail-fast`
   ```text
       strategy:
         fail-fast: false
         matrix:
           component: [api, batch, web]
   ```
2. 워크플로 전체 `uses: docker/build-push-action@` 정확히 1개, `uses: docker/setup-buildx-action@` 정확히 1개, 둘 다 `docker-image` 블록 안 — `Docker images must be built only by the docker-image matrix`
3. `Build image` step(`workflow_job_step_block docker-image "Build image"`)에 정확 일치 줄 7개 — 각각 실패 메시지 `Docker image step is missing: <줄>`
   ```text
             file: apps/${{ matrix.component }}/Dockerfile
             push: false
             tags: ghcr.io/${{ github.repository }}/${{ matrix.component }}:${{ github.sha }}
             labels: org.opencontainers.image.revision=${{ github.sha }}
             outputs: type=docker,dest=${{ runner.temp }}/docker-images/${{ matrix.component }}.tar
             cache-from: type=gha,scope=${{ matrix.component }}
             cache-to: ${{ github.event_name == 'push' && github.ref == 'refs/heads/dev' && format('type=gha,mode=max,scope={0},ignore-error=true', matrix.component) || '' }}
   ```
4. 워크플로 전체 `cache-to:` 줄 정확히 1개(위 줄) — `Docker cache may be written only by dev pushes`
5. `Publish Docker image part` step 에 정확 일치 줄 — `Docker image part upload contract is broken`
   ```text
             path: ${{ runner.temp }}/docker-images/${{ matrix.component }}.tar
             if-no-files-found: error
             compression-level: 0
             retention-days: 1
             overwrite: true
   ```
6. `docker-build` 블록 머리 4줄이 연속 포함 — `docker-build must assemble after every image leg, even when a leg fails`
   ```text
     docker-build:
       name: 7. Docker image build
       needs: docker-image
       if: ${{ !cancelled() }}
   ```
7. `docker-build` 블록에 `actions/checkout@`·`docker/` 가 없다 — `docker-build must assemble archives without rebuilding`
8. `Require every image build to succeed` step 에 `          IMAGE_BUILD_RESULT: ${{ needs.docker-image.result }}`,
   `          if [[ "${IMAGE_BUILD_RESULT}" != "success" ]]; then`, `            exit 1` 정확 일치 — `docker-build must fail when any image leg did not succeed`
9. `Require exactly the three unexpired image parts` step 에 정확 일치 줄 — `docker-build must verify exactly three unexpired image parts before merging`
   ```text
             GH_TOKEN: ${{ github.token }}
             expected="$(printf 'docker-image-part-%s-%s\n' api "${GITHUB_SHA}" batch "${GITHUB_SHA}" web "${GITHUB_SHA}")"
             actual="$(gh api --paginate "repos/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}/artifacts" --jq '.artifacts[] | select(.expired == false) | select(.name | startswith("docker-image-part-")) | .name' | LC_ALL=C sort)"
             if [[ "${actual}" != "${expected}" ]]; then
               exit 1
   ```
10. `Publish Docker image archives` step 에 정확 일치 줄 — `Docker archive merge contract is broken`
    ```text
            id: docker-archives
            uses: actions/upload-artifact/merge@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2
              name: docker-images-${{ github.sha }}
              pattern: docker-image-part-*-${{ github.sha }}
              compression-level: 0
              retention-days: 14
              delete-merged: true
    ```
    (10 이상은 목록 들여쓰기가 4칸이다 — 실제 줄은 그 4칸을 뺀 값)
11. `docker-build` 블록 안 step 순서: `Require every image build to succeed` < `Require exactly the three unexpired image parts` < `Publish Docker image archives` < `Record Docker artifact provenance`, 각 step 이름 정확히 1회 — `Docker assembly steps must run result check, part check, merge, provenance in order`
12. provenance step(`workflow_job_step_block docker-build "Record Docker artifact provenance"`): 현재 :367-372 의 `echo "run_id=…"`·`echo "commit=…"`·`echo "artifact_id=${{ steps.docker-archives.outputs.artifact-id }}"` 세 단정 유지, :373-374 를 `} | tee -a "${GITHUB_STEP_SUMMARY}"` 포함으로 교체 — `Docker provenance must be written to both GITHUB_STEP_SUMMARY and the step log`

:375 이후(Dockerfile·materializer 단정)는 바꾸지 않는다.

- [x] **Step 1: 계약 테스트 개정** — 위 표와 목록대로 `ci/quality-gate-contract-test.sh` 를 고친다. workflow 는 아직 건드리지 않는다.

- [x] **Step 2: RED 확인**

```bash
bash ci/quality-gate-contract-test.sh
```

예상: exit 1, stderr `quality gate contract failed: only docker-build may override permissions, and only with actions: read`
(파일 안에서 가장 먼저 도는 바뀐 단정 :98 — 현재 workflow 에 job 단위 permissions 가 0개다). 다른 메시지(문법 오류·unbound
variable)면 RED 가 아니다 — 고친 뒤 다시. 출력과 HEAD SHA 를 dod `### AC1` 에 `[RED]` 로 붙인다.

- [x] **Step 3: workflow 교체** — 위 "새 Docker job 전문" 대로.

- [x] **Step 4: GREEN 확인**

```bash
bash ci/quality-gate-contract-test.sh
bash docker/deploy-contract-test.sh
```

예상: 둘 다 exit 0, 첫 명령 마지막 줄 `quality gate and supply-chain contracts verified`. dod `### AC1` 에 `[GREEN]`.

- [x] **Step 5: 커밋**

```bash
git add .github/workflows/quality-gate.yml ci/quality-gate-contract-test.sh
git commit -m "ci: Docker 이미지 matrix 병렬 빌드와 dev 전용 cache 기록" -m "- API·Batch·Web 를 matrix leg 로 병렬 빌드하고 7. Docker image build 가 part 3개 확인 뒤 기존 docker-images artifact 로 조립
- buildkit cache-to 는 dev push 에서만, PR 은 cache-from 만
- 조립 job 에만 actions: read, provenance 를 summary 와 로그에 함께 기록
- 계약 테스트의 Docker·업로드·needs·permissions 단정을 새 구조로 개정"
```

---

## Task 2 — 삭제 스크립트와 행동 계약

**Files:**
- Create: `ci/delete-actions-caches.sh` (실행 권한 `chmod +x`)
- Create: `ci/delete-actions-caches-test.sh`
- Modify: `ci/quality-gate-contract-test.sh` (:195 다음 한 줄)

### 결합점 — 스크립트 인터페이스

design D4·D6 의 인터페이스·검증 규칙·호출 형태·rank 표·경계 조건·로그 계약을 그대로 구현한다. 다시 적으면 갈리므로 design 을
정본으로 하고, 테스트가 의존하는 정확한 문자열만 반복한다.

```text
argv:     pr-ref <ref> | stale <ref> <days>
env:      GH_REPO, DRY_RUN ('true'|'false'), GH_TOKEN(읽지 않음)
exit:     0 성공 · 1 API 실패 또는 stale 의 해석 불가 시간 · 2 검증 실패(gh 호출 0회)
GET:      gh api --method GET --paginate --slurp repos/<GH_REPO>/actions/caches -f ref=<ref> -F per_page=100
DELETE:   gh api --method DELETE repos/<GH_REPO>/actions/caches/<id>
대상:     pr-ref = 그 ref 의 rank 0~4 전부 · stale = 그 ref 의 rank 2·3 중 last_accessed_at < cutoff
stdout:   cache action=<delete|would-delete|already-gone> id=<id> rank=<0-4> key=<key> size_bytes=<n> last_accessed_at=<원문>
          cache-cleanup summary mode=<pr-ref|stale> ref=<ref> listed=<n> selected=<n> deleted=<n> would_delete=<n> already_gone=<n> freed_bytes=<n> cutoff=<YYYY-MM-DDTHH:MM:SSZ|none>
stderr:   cache-cleanup error: <사유>
```

- `listed` = 응답 entry 중 `ref` 가 요청 ref 와 같은 것의 수. 다른 ref entry 는 세지도, 기준점(최근 접근) 계산에 쓰지도 않는다.
- 기준점은 rank 와 무관하게 요청 ref entry 전체의 최댓값이다(buildkit index 포함).
- `freed_bytes` = `deleted` 된 entry 의 `size_in_bytes` 합(dry-run·already-gone 제외).
- `cutoff=none`: pr-ref 모드, 그리고 stale 모드에서 `listed=0` 일 때.
- 소수 초 내림: `2026-10-31T05:48:00.999Z` → `2026-10-31T05:48:00Z`.
- stale 모드에서 요청 ref entry 중 하나라도 `last_accessed_at` 이 없거나 해석 불가면 DELETE 0회, stderr
  `cache-cleanup error: unparsable last_accessed_at id=<id>`(가장 작은 해당 id), summary 없이 exit 1. pr-ref 모드는 시간을 해석하지 않는다.

구현 요건(전문 아님):

- `set -euo pipefail`. 인자·env 검증을 gh 호출 전에 끝낸다.
- 목록 → 선별 → 정렬(rank, id) 은 python3 한 번(`python3 -I`)으로 하고, 결과를 탭 구분 줄로 bash 에 넘긴다. 해석 불가 시간은
  이 단계에서 판정해 삭제 루프에 들어가기 전에 끝낸다.
- 삭제 루프: DRY_RUN=true 면 `would-delete` 만 출력. gh 실패 시 stderr 를 변수로 받아 `HTTP 404` 를 포함하면 `already-gone`,
  아니면 `cache-cleanup error: delete failed id=<id> status=<HTTP 코드|unknown>` 후 summary 를 출력하고 exit 1.
- key 출력 전 0x20~0x7E 밖 문자를 `?` 로 치환.
- 목록 실패: `cache-cleanup error: list failed ref=<ref>` 후 exit 1 (summary 없음).

### 결합점 — fake `gh` 프로토콜 (`ci/delete-actions-caches-test.sh` 가 임시 디렉터리에 생성해 PATH 앞에 둔다)

| env | 의미 |
|---|---|
| `FAKE_GH_LOG` | 호출마다 argv 를 공백으로 이어 한 줄 append |
| `FAKE_GH_LIST_FIXTURE` | GET 호출 시 이 파일 내용을 stdout 으로 |
| `FAKE_GH_LIST_FAIL=1` | GET 호출 시 stderr `gh: Server Error (HTTP 500)`, exit 1 |
| `FAKE_GH_DELETE_404` | 공백 구분 id 목록 — DELETE 시 stderr `gh: Not Found (HTTP 404)`, exit 1 |
| `FAKE_GH_DELETE_500` | 공백 구분 id 목록 — DELETE 시 stderr `gh: Server Error (HTTP 500)`, exit 1 |

fake 는 GET argv 가 위 GET 형태와 정확히 같지 않거나, DELETE 경로가 `repos/<GH_REPO>/actions/caches/<숫자>` 가 아니면
stderr `fake gh: unexpected invocation: <argv>` 와 exit 99 — 스크립트가 계약을 벗어나면 테스트가 잡는다.
테스트는 `GH_REPO=premium/spread`, `GH_TOKEN=token-sentinel-6f1c` 로 돌린다.

### 결합점 — fixture 와 기대값 (테스트가 재는 것)

F-stale (`stale refs/heads/dev 3`, 단일 page). dev 최근 접근 = id 105 → cutoff `2026-10-31T05:48:00Z`.

| id | ref | key | last_accessed_at | size | 기대 |
|---|---|---|---|---|---|
| 101 | refs/heads/dev | `gradle-dependencies-v1-aaa` | `2026-10-25T01:00:00.1234567Z` | 1000 | 삭제 rank 3 |
| 102 | refs/heads/dev | `buildkit-blob-1-sha256:old1` | `2026-10-30T03:18:00.103452Z` | 200 | **유지** — rank 1, stale 대상 아님 (cutoff 이전이어도) |
| 103 | refs/heads/dev | `index-buildkit-api-old` | `2026-10-29T00:00:00Z` | 10 | **유지** — rank 0, stale 대상 아님 |
| 104 | refs/heads/dev | `gradle-home-v1\|Linux-X64\|unit-coverage[h]-old` | `2026-10-28T00:00:00Z` | 30 | 삭제 rank 2 |
| 105 | refs/heads/dev | `index-buildkit-api-new` | `2026-11-03T05:48:00.5Z` | 10 | 유지(기준점) |
| 106 | refs/heads/dev | `gradle-transforms-v1-boundary` | `2026-10-31T05:48:00.999Z` | 200 | 유지 — rank 3, 내림하면 cutoff 와 같음(경계) |
| 107 | refs/heads/dev | `quality-tools-Linux-x` | `2026-10-20T00:00:00Z` | 50 | 유지(rank 4) |
| 108 | refs/pull/9/merge | `gradle-dependencies-v1-pr` | `2026-11-10T00:00:00Z` | 200 | 유지(타 ref — 기준점에 쓰면 cutoff 가 `2026-11-07T00:00:00Z` 로 밀려 106·110 까지 지워진다) |
| 109 | refs/heads/dev | `buildkit-blob-1-sha256:old2` | `2026-10-30T23:59:59Z` | 300 | **유지** — rank 1, stale 대상 아님 |
| 110 | refs/heads/dev | `gradle-transforms-v1-bbb` | `2026-11-02T00:00:00Z` | 400 | 유지 |

기대 DELETE 순서 `104 101`, summary `mode=stale ref=refs/heads/dev listed=9 selected=2 deleted=2 would_delete=0 already_gone=0 freed_bytes=1030 cutoff=2026-10-31T05:48:00Z`.

F-pr (`pr-ref refs/pull/42/merge`, 단일 page). 시간을 해석하지 않음을 보이려고 201 의 `last_accessed_at` 은 `null`, 나머지는
`2026-11-01T00:00:00Z`.

| id | ref | key | 기대 |
|---|---|---|---|
| 201 | refs/pull/42/merge | `node-cache-Linux-x64-npm-h` | 삭제 rank 4 |
| 202 | refs/pull/42/merge | `buildkit-blob-1-sha256:a` | 삭제 rank 1 |
| 203 | refs/pull/42/merge | `index-buildkit-web` | 삭제 rank 0 |
| 204 | refs/heads/dev | `index-buildkit-api` | 유지(타 ref) |
| 205 | refs/pull/42/merge | `gradle-home-v1\|Linux-X64\|x[h]-h` | 삭제 rank 2 |
| 206 | refs/pull/42/merge | `evil` + LF + `::warning::injected` | 삭제 rank 4, 출력 key `evil?::warning::injected` |

기대 DELETE 순서 `203 202 205 201 206`.

F-paged (`stale refs/heads/dev 3`, page 2개): page 1 = id 301 dev `index-buildkit-batch` `2026-11-03T00:00:00Z`;
page 2 = id 302 dev `gradle-dependencies-v1-old` `2026-10-20T00:00:00Z`. 기대 DELETE `302`.

F-empty (`stale refs/heads/dev 3`): 단일 page, entry 1개 id 401 ref `refs/pull/9/merge` key `gradle-dependencies-v1-x`
`2026-10-01T00:00:00Z`. 기대 DELETE 없음.

F-badtime (`stale refs/heads/dev 3`): 단일 page, id 501 dev `index-buildkit-api` `not-a-date`, id 502 dev
`gradle-dependencies-v1-old` `2026-10-01T00:00:00Z`, id 503 dev `gradle-transforms-v1-new` `2026-11-03T00:00:00Z`. 501 은 rank 0 이지만
기준점 계산에 쓰이므로 검사 대상이다. 기대 DELETE 없음.

테스트 케이스 13개(각각 무엇을 재는가):

| # | 케이스 | 단정 |
|---|---|---|
| 1 | stale 선별·순서 (F-stale, DRY_RUN=false) | exit 0, FAKE_GH_LOG 의 DELETE id 열이 정확히 `104 101`, **102·103·109(dev buildkit, cutoff 이전) 와 106·107·108·110 의 DELETE 가 없다**, summary 줄이 위 문자열과 정확히 같다 |
| 2 | stale dry-run (F-stale, DRY_RUN=true) | exit 0, DELETE 0회, `action=would-delete` 2줄(id 104·101), summary `deleted=0 would_delete=2 … freed_bytes=0` |
| 3 | pr-ref 전체 (F-pr) | exit 0, DELETE id 열 `203 202 205 201 206`, 204 미호출, `last_accessed_at` 이 `null` 인 201 도 삭제됨, summary `cutoff=none` |
| 4 | pr-ref 거부 | `refs/heads/dev`·`refs/pull/42/head`·`refs/pull/0/merge`·`refs/pull/42/merge;x`·빈 문자열 각각 exit 2, FAKE_GH_LOG 비어 있음 |
| 5 | stale 거부 | ref `refs/heads/main`, days `0`·`7`·`3x`·생략 각각 exit 2, gh 호출 0 |
| 6 | env 거부 | DRY_RUN 미설정·`yes`, GH_REPO `premium` 각각 exit 2, gh 호출 0 |
| 7 | 404 (F-pr, `FAKE_GH_DELETE_404=202`) | exit 0, DELETE 5회 모두 시도, `action=already-gone id=202` 1줄, summary `deleted=4 already_gone=1` |
| 8 | 500 중단 (F-pr, `FAKE_GH_DELETE_500=202`) | exit 1, DELETE id 열이 정확히 `203 202`, stderr 에 `cache-cleanup error: delete failed id=202 status=500` |
| 9 | 목록 실패 (`FAKE_GH_LIST_FAIL=1`) | exit 1, DELETE 0회, stderr `cache-cleanup error: list failed ref=refs/heads/dev` |
| 10 | 로그 위생 (케이스 3·8 의 출력 재사용) | stdout·stderr 어디에도 `token-sentinel-6f1c` 없음, `::` 로 시작하는 줄 없음, `key=evil?::warning::injected` 존재 |
| 11 | pagination (F-paged) | exit 0, DELETE id 열 `302`, summary `listed=2` |
| 12 | dev entry 0개 (F-empty) | exit 0, DELETE 0회, summary `mode=stale ref=refs/heads/dev listed=0 selected=0 deleted=0 would_delete=0 already_gone=0 freed_bytes=0 cutoff=none` |
| 13 | 해석 불가 시간 (F-badtime, DRY_RUN=false) | exit 1, DELETE 0회(502 도 지우지 않는다), stderr `cache-cleanup error: unparsable last_accessed_at id=501`, stdout 에 summary 줄 없음 |

마지막 줄 출력 `delete-actions-caches behavioral contract verified (cases: 13)`.

- [x] **Step 1: 테스트 작성** — `ci/delete-actions-caches-test.sh`. 형태는 `ci/dependency-bootstrap-contract-test.sh` 를 따른다
  (`set -euo pipefail`, `mktemp -d` + `trap` 정리, `fail()` 은 `delete-actions-caches behavioral contract failed: <사유>`).
  fixture JSON 은 테스트가 임시 디렉터리에 heredoc 으로 만든다(page 배열 형태, entry 필드 `id ref key version last_accessed_at created_at size_in_bytes`).

- [x] **Step 2: skeleton 스크립트** — `ci/delete-actions-caches.sh` 에 인자·env 검증과 summary 출력만(목록 조회·삭제 없음,
  `listed=0 selected=0 …`) 구현한다.

- [x] **Step 3: RED 확인**

```bash
bash ci/delete-actions-caches-test.sh
```

예상: exit 1, `delete-actions-caches behavioral contract failed:` 뒤에 케이스 1 의 DELETE id 열 불일치(기대 `104 101`, 실제 빈 열).
dod `### AC3` 에 `[RED]`.

- [x] **Step 4: 구현 → GREEN**

```bash
bash ci/delete-actions-caches-test.sh
```

예상: exit 0, 마지막 줄 `delete-actions-caches behavioral contract verified (cases: 13)`. dod `### AC3` 에 `[GREEN]`.

- [x] **Step 5: 위반 표본 증명(커밋하지 않음)** — 아래 일시 변형을 하나씩 적용해 테스트를 돌리고 실패를 확인한 뒤
  `git checkout -- ci/delete-actions-caches.sh` 로 되돌린다.
  - (a) 정렬을 id 만으로 → 케이스 1 실패(`101 104`)
  - (b) 응답 entry 의 ref 재확인 제거 → 케이스 1 실패(108 이 기준점이 돼 cutoff `2026-11-07T00:00:00Z`, 106·110 삭제, `listed=10`)
  - (c) 비교를 `<=` 로 → 케이스 1 실패(106 삭제)
  - (d) stale 대상에 rank 0·1 포함 → 케이스 1 실패(103·102·109 삭제) — F5 판정의 회귀 방어선
  네 결과를 dod `## 검사 산출물` 증명 칸 근거로 `### AC3` 에 남긴다.

- [x] **Step 6: Quality Gate 계약에 연결** — `ci/quality-gate-contract-test.sh` :195(`bash "${root_dir}/ci/dependency-bootstrap-contract-test.sh"`) 다음 줄에 `bash "${root_dir}/ci/delete-actions-caches-test.sh"`.

```bash
bash ci/quality-gate-contract-test.sh
```

예상: exit 0.

- [x] **Step 7: 커밋**

```bash
git add ci/delete-actions-caches.sh ci/delete-actions-caches-test.sh ci/quality-gate-contract-test.sh
git commit -m "ci: Actions cache 삭제 스크립트와 행동 계약 추가" -m "- PR merge ref 전체 삭제와 dev gradle cache 의 활동 기준 3일 미접근 entry 삭제를 한 스크립트로
- stale 모드는 buildkit index·blob 을 지우지 않는다 (없는 blob 은 빌드 실패)
- 404 는 이미 삭제로 간주, 그 밖 실패·해석 불가 시간은 즉시 중단
- fake gh 로 선별·순서·거부·경계·로그 위생 13개 케이스 검증"
```

---

## Task 3 — cleanup workflow 2개와 정적 계약

**Files:**
- Create: `.github/workflows/actions-cache-pr-cleanup.yml`
- Create: `.github/workflows/actions-cache-stale-cleanup.yml`
- Create: `ci/actions-cache-cleanup-contract-test.sh`
- Modify: `ci/quality-gate-contract-test.sh` (Task 2 에서 넣은 줄 다음 한 줄)

### 결합점 — workflow 전문

`.github/workflows/actions-cache-pr-cleanup.yml`:

```yaml
name: Actions cache PR cleanup

on:
  pull_request_target:
    types:
      - closed

permissions:
  actions: write
  contents: read

concurrency:
  group: actions-cache-pr-cleanup-${{ github.event.pull_request.number }}
  cancel-in-progress: false

jobs:
  delete-pr-caches:
    name: Delete closed PR caches
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
        with:
          persist-credentials: false
      - name: Delete every Actions cache of the closed PR merge ref
        env:
          GH_TOKEN: ${{ github.token }}
          GH_REPO: ${{ github.repository }}
          DRY_RUN: 'false'
          PR_MERGE_REF: refs/pull/${{ github.event.pull_request.number }}/merge
        run: bash ci/delete-actions-caches.sh pr-ref "${PR_MERGE_REF}"
```

`.github/workflows/actions-cache-stale-cleanup.yml`:

```yaml
name: Actions cache stale cleanup

on:
  schedule:
    - cron: '17 18 * * *'
  workflow_dispatch:
    inputs:
      dry_run:
        description: Report stale dev Gradle caches without deleting them
        type: boolean
        default: true

permissions:
  actions: write
  contents: read

concurrency:
  group: actions-cache-stale-cleanup
  cancel-in-progress: false

jobs:
  delete-stale-dev-caches:
    name: Delete stale dev Gradle caches
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
        with:
          persist-credentials: false
      - name: Delete dev Gradle caches idle for three days of dev activity
        env:
          GH_TOKEN: ${{ github.token }}
          GH_REPO: ${{ github.repository }}
          DRY_RUN: ${{ inputs.dry_run && 'true' || 'false' }}
        run: bash ci/delete-actions-caches.sh stale refs/heads/dev 3
```

stale 대상이 gradle 뿐이라는 것은 스크립트(rank 2·3)가 보장하고 Task 2 케이스 1 이 단정한다. workflow 는 모드·ref·일수만 넘긴다.

### 결합점 — 정적 계약 단정과 mutation

`ci/actions-cache-cleanup-contract-test.sh` 구조 요건:

- `check_workflows <dir>` 함수가 아래 단정 전부를 **S1→S15 순서로** `<dir>` 의 파일에 대해 수행한다. 실패는 stderr
  `actions cache cleanup contract failed: <메시지>` + 0 아닌 종료(서브셸 안에서 `exit 1`).
- 본문: (1) `check_workflows "${root_dir}/.github/workflows"` 통과 (2) `.github/workflows/*.yml` 을 임시 디렉터리에 복사해
  M1~M9 를 하나씩 적용한 사본마다 `( check_workflows "${mutant_dir}" )` 가 실패하고 stderr 에 기대 메시지가 있는지 확인 —
  하나라도 통과하거나 메시지가 다르면 `mutation <id> was not rejected` 로 실패. 거부된 mutation 마다 stdout 에
  `mutation <id> rejected: <메시지>` 한 줄 (3) 마지막 줄 `actions cache cleanup contracts verified (mutations rejected: 9)`.
- 블록 추출은 `ci/quality-gate-contract-test.sh` 의 `yaml_top_level_block`·`workflow_job_block` 과 같은 awk 방식에 파일 인자를 받는 형태.

단정 목록(두 파일 공통은 "양쪽"):

| # | 단정 | 실패 메시지 |
|---|---|---|
| S1 | PR 파일 `on` 블록 == `$'  pull_request_target:\n    types:\n      - closed'` | `pr cleanup trigger must be exactly pull_request_target closed` |
| S2 | stale `on` 블록에 `        default: true` 정확 일치 | `stale cleanup manual runs must default to dry_run true` |
| S3 | stale 파일 `on` 블록이 위 전문의 8줄(`  schedule:` ~ `        default: true`)과 정확히 같다 | `stale cleanup trigger must be exactly the daily schedule and manual dispatch` |
| S4 | 양쪽 `permissions` 블록 == `$'  actions: write\n  contents: read'`, `^permissions:$` 1개, 들여쓴 `permissions:` 0개 | `cleanup workflow permissions must be exactly actions: write and contents: read` |
| S5 | 양쪽 `uses:` 줄이 정확히 1개이고 `      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2` | `cleanup workflows may only use the pinned checkout action` |
| S6 | 양쪽 `          persist-credentials: false` 정확히 1개 | `cleanup checkout must not persist credentials` |
| S7 | 양쪽 `^[[:space:]]+ref:` 0개, `pull_request.head`·`head_ref`·`head.sha` 0개 | `cleanup workflows must not override the checkout ref` |
| S8 | 양쪽 `secrets.` 0개, PR 파일의 `github\.event\.pull_request\.[A-Za-z_.]*` 출현이 전부 `github.event.pull_request.number` | `cleanup workflows may read only the pull request number` |
| S9 | 양쪽 `run:` 줄에 `${{` 0개 | `cleanup run steps must not interpolate expressions` |
| S10 | PR 파일에 정확 일치: `        run: bash ci/delete-actions-caches.sh pr-ref "${PR_MERGE_REF}"`, `          PR_MERGE_REF: refs/pull/${{ github.event.pull_request.number }}/merge`, `          DRY_RUN: 'false'` | `pr cleanup must delete exactly the closed PR merge ref` |
| S11 | stale 파일에 정확 일치: `        run: bash ci/delete-actions-caches.sh stale refs/heads/dev 3`, `          DRY_RUN: ${{ inputs.dry_run && 'true' || 'false' }}` | `stale cleanup must invoke the script with refs/heads/dev and 3 days` |
| S12 | 양쪽 `          GH_TOKEN: ${{ github.token }}`, `          GH_REPO: ${{ github.repository }}` 정확 일치 | `cleanup workflows must pass only the job token and repository` |
| S13 | concurrency: PR `  group: actions-cache-pr-cleanup-${{ github.event.pull_request.number }}`, stale `  group: actions-cache-stale-cleanup`, 양쪽 `  cancel-in-progress: false` | `cleanup workflows must not cancel an in-flight deletion` |
| S14 | `<dir>/*.yml` 중 `actions: write` 를 담은 파일 집합 == {`actions-cache-pr-cleanup.yml`, `actions-cache-stale-cleanup.yml`} | `actions: write is allowed only in the two cleanup workflows` |
| S15 | 두 파일의 job id 가 각각 정확히 `delete-pr-caches`·`delete-stale-dev-caches` 하나 | `cleanup workflows must define exactly one reviewed job` |

mutation(사본에 적용, 각자 독립). 기대 메시지는 각 변형이 **처음** 위반하는 단정이다 — 단정 순서를 바꾸면 이 표도 같이 고친다.

| id | 변형 | 처음 위반하는 단정 |
|---|---|---|
| M1 | PR 파일 `pull_request_target:` → `pull_request:` | S1 |
| M2 | PR checkout `with:` 아래에 `          ref: ${{ github.event.pull_request.head.sha }}` 추가 | S7 |
| M3 | PR 파일 permissions 에 `  pull-requests: write` 추가 | S4 |
| M4 | PR 파일 `persist-credentials: false` 줄 삭제 | S6 |
| M5 | PR run 줄을 `        run: bash ci/delete-actions-caches.sh pr-ref "refs/pull/${{ github.event.pull_request.number }}/merge"` 로 | S9 (`.number` 만 쓰므로 S8 은 통과한다) |
| M6 | stale run 줄의 `3` → `7` | S11 |
| M7 | stale steps 끝에 `      - uses: example/third-party@0123456789abcdef0123456789abcdef01234567` 추가 | S5 |
| M8 | `quality-gate.yml` 사본의 top-level permissions 에 `  actions: write` 추가 | S14 |
| M9 | stale `default: true` → `default: false` | S2 (S3 보다 먼저 검사한다) |

- [x] **Step 1: 계약 테스트 작성** — workflow 파일은 아직 만들지 않는다.

- [x] **Step 2: 파일 없음 실패 확인(RED 아님 — 기록만)**

```bash
bash ci/actions-cache-cleanup-contract-test.sh
```

예상: exit 1, 대상 파일 부재 메시지. dod `### AC2` 에 "[PRE] 파일 없음 — skill 정의상 RED 아님(design N2)" 으로만 남긴다.

- [x] **Step 3: workflow 2개 작성** — 위 전문 그대로.

- [x] **Step 4: GREEN + mutation 증명**

```bash
bash ci/actions-cache-cleanup-contract-test.sh
```

예상: exit 0, `mutation M1 rejected: …` ~ `mutation M9 rejected: …` 9줄과 마지막 줄
`actions cache cleanup contracts verified (mutations rejected: 9)`. 전체 출력을 dod `### AC2` 에 `[GREEN]`·`[RED-대체: mutation]` 으로 붙인다.

- [x] **Step 5: Quality Gate 계약에 연결**

`ci/quality-gate-contract-test.sh` 의 `bash "${root_dir}/ci/delete-actions-caches-test.sh"` 다음 줄에
`bash "${root_dir}/ci/actions-cache-cleanup-contract-test.sh"`.

```bash
bash ci/quality-gate-contract-test.sh
```

예상: exit 0. 기존 pinned-action 루프(:236-245)가 새 workflow 2개도 검사해 통과한다.

- [x] **Step 6: 커밋**

```bash
git add .github/workflows/actions-cache-pr-cleanup.yml .github/workflows/actions-cache-stale-cleanup.yml ci/actions-cache-cleanup-contract-test.sh ci/quality-gate-contract-test.sh
git commit -m "ci: PR 종료·일일 Actions cache 정리 workflow 추가" -m "- PR 종료 시 pull_request_target 로 그 merge ref cache 를 전부 삭제, PR 코드는 checkout 하지 않는다
- 매일 KST 03:17 dev 의 gradle cache 중 활동 기준 3일 미접근 entry 삭제, 수동 실행은 기본 dry-run
- actions: write 를 두 workflow 에만 두고 mutation 9종으로 정적 계약을 자가검증"
```

---

## Task 4 — 배포 런북 갱신

**Files:** Modify: `docs/runbooks/deployment.md` (`## 불변 배포 단위` 절 :14-31, 그리고 그 절 끝에 새 하위 절)

요건:

- :17-18 문장 뒤에 추가: 세 이미지는 `7a. Docker image (<component>)` job 이 병렬로 build 하고 `7. Docker image build` job 이
  만료되지 않은 part 3개를 확인한 뒤 `docker-images-${{ github.sha }}` 하나로 조립한다. `docker-image-part-*` 는 조립용 임시
  artifact(1일 보존, 조립 직후 삭제)이며 배포 후보·검증 대상이 아니다.
- :26 의 "Quality Gate summary" 를 "`7. Docker image build` job summary(같은 값이 그 job 의 `Record Docker artifact provenance`
  step 로그에도 있다)" 로 구체화한다. run ID·commit·artifact ID 확인 절차는 그대로.
- archive 이름·tag·OCI revision·ReleaseCandidate 조건(:31-32)은 바꾸지 않는다.
- 새 하위 절 `### Quality Gate Docker 재실행과 cache 복구` — 아래 두 내용(design D3·D6.1):
  1. **rerun 창**: part 는 1일 보존이다. 원 run 뒤 1일이 지나 실패 job 만 다시 돌리면 part 검사가 실패한다 — "Re-run all jobs" 로 다시 만든다.
     조립 job(`7. Docker image build`)이 merge 성공 뒤(part 가 `delete-merged` 로 지워진 뒤) 실패했으면 1일 안이어도 "Re-run all jobs" 가 필요하다.
  2. **BlobNotFound 수동 복구**: 증상 — `7a.` leg 가 gha cache import 중 `BlobNotFound`/404 로 실패. 원인 — GitHub 의 7일 미사용
     만료·한도 초과 LRU 축출이 blob 을 지우고 index 는 남겼다. 같은 job 을 다시 돌려도 회복되지 않는다. 절차 — 실패 leg 의 scope
     (`api`·`batch`·`web`) 확인 → `gh cache list --ref refs/heads/dev --key index- --json id,key` 로 그 scope 의 dev index entry 를
     찾아 `gh cache delete <id>` (scope 를 특정할 수 없으면 dev `index-*` 전부) → 실패 job 재실행(cache 없이 빌드) → 다음 dev push
     export 가 index 와 blob 을 다시 쓴다. 일정 cleanup 은 buildkit 을 지우지 않는다는 사실도 한 줄 적는다.
- placeholder 단어 금지(`docs/check-documentation.sh` 가 막는다).
- `docs/deploy/aws-setup.md` :89-95 는 "Quality Gate summary" 라는 일반 표현이라 바꾸지 않는다.

- [x] **Step 1: 수정 후 검사**

```bash
bash docs/check-documentation.sh
```

예상: exit 0.

- [x] **Step 2: 커밋**

```bash
git add docs/runbooks/deployment.md
git commit -m "docs: Docker archive 조립 job 과 cache 복구 절차 설명" -m "- 세 이미지 병렬 build 와 7. Docker image build 조립 job·part 검사를 런북에 반영
- provenance 확인 위치를 조립 job summary 와 step 로그로 명시
- part 1일 rerun 창과 BlobNotFound 수동 복구 절차 추가"
```

---

## Task 5 — 로컬 전체 검증 (커밋 없음)

> 완료: ⑩ qa 검증 증거를 dod 에 기록했다(c85a68c).

```bash
bash ci/quality-gate-contract-test.sh
bash ci/actions-cache-cleanup-contract-test.sh
bash ci/delete-actions-caches-test.sh
bash docker/deploy-contract-test.sh
bash ci/local-offline-contract-test.sh
bash docs/check-documentation.sh
git status --short
```

예상: 앞 여섯 개 exit 0. `git status` 비어 있음. Gradle·Kotlin 은 바뀌지 않았으므로 `./gradlew` 게이트는 이번 변경의 검증 대상이
아니다(compile job 이 CI 에서 그대로 돈다). 결과를 dod 회귀 방어선 근거로 남긴다.

---

## Task 6 — PR CI 실측 (커밋 없음, push·PR 생성은 오케스트레이터)

> 미완료: PR CI 실측.

최종 SHA 의 PR Quality Gate run 을 `<run-id>`, PR 번호를 `<n>`, SHA 를 `<sha>` 로 둔다.

- [ ] **Step 1: AC4 시간**

```bash
gh run view <run-id> --json jobs
```

design D8 정의로 `T_docker`·`T_leg(api|batch|web)`·`T_merge`·`T_other`(와 그 job 이름)를 계산해 dod `### AC4` 에 run URL 과 함께
기록. 판정 `T_docker ≤ T_other`. 실패하면 leg 로그의 레이어 시간(`DONE <n>s`)으로 원인(cache miss·merge 시간)을 함께 적고 보고한다.
A5(merge 30~50s) 실측값도 여기 적는다.

- [ ] **Step 2: AC5 PR 의 cache 무기록 (F3 판정 포함)**

```bash
gh run view <run-id> --json createdAt,jobs
gh cache list --ref refs/pull/<n>/merge --key buildkit-blob- --json id,createdAt
gh cache list --ref refs/pull/<n>/merge --key index- --json id,createdAt
gh run view <run-id> --job <api-leg-job-id> --log
gh run view <run-id> --job <batch-leg-job-id> --log
gh run view <run-id> --job <web-leg-job-id> --log
```

예상: 두 cache 목록에서 `createdAt` 이 run 시작(세 leg 의 `startedAt` 최솟값) 이후인 entry 0개. 그 이전 entry(이 변경 전 push 가
남긴 것)가 있으면 개수와 `createdAt` 을 함께 기록한다. 세 leg 로그의 `Build image` step 에 `--cache-to` 인자(build-push-action 이
출력하는 `docker buildx build` 명령줄)와 `exporting to GitHub Actions Cache` 가 각각 0건 — 이것이 F3(LIKELY) 의 판정이다.

- [ ] **Step 3: AC6 최종 artifact**

```bash
gh api repos/<owner>/<repo>/actions/runs/<run-id>/artifacts
art_dir="$(mktemp -d)"
gh run download <run-id> -n docker-images-<sha> -D "${art_dir}"
ls -1 "${art_dir}"
docker load -i "${art_dir}/api.tar"
docker load -i "${art_dir}/batch.tar"
docker load -i "${art_dir}/web.tar"
docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' ghcr.io/<owner>/<repo>/api:<sha>
docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' ghcr.io/<owner>/<repo>/batch:<sha>
docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' ghcr.io/<owner>/<repo>/web:<sha>
gh run view <run-id> --log --job <docker-build-job-id>
```

예상: artifact 목록에 `docker-images-<sha>` 1개·`docker-image-part-` 0개(A2 확인), `ls` 결과 정확히 `api.tar batch.tar web.tar`,
`docker load` 가 각 tag 를 로드, revision 3개 모두 `<sha>`, 마지막 명령의 `Record Docker artifact provenance` step 로그에
`run_id=<run-id>`·`commit=<sha>`·`artifact_id=<목록의 id>` 세 줄, part 검사 step 성공. 확인 뒤 로드한 이미지와 `${art_dir}` 를 지운다.

---

## Task 7 — 머지 뒤 관찰 (커밋 없음)

> 미완료: 머지 뒤 관찰.

- [ ] **Step 1: AC7** — 머지 뒤 처음 닫히는 PR(이 PR 자신이 base workflow 로 실행되면 그것)의 `Actions cache PR cleanup` run:

```bash
gh run list --workflow actions-cache-pr-cleanup.yml --limit 5
gh run view <run-id> --log
gh cache list --ref refs/pull/<n>/merge --json id
```

예상: success, 로그에 `cache-cleanup summary mode=pr-ref ref=refs/pull/<n>/merge` 이고 **`deleted>=1`**(F7 PARTIAL 의 권한 판정 —
그 PR 이 cache 를 하나도 남기지 않았으면 판정에 쓰지 않고 다음 PR 을 본다), 목록 `[]`.

AC7 표본 선택 절차: PR 을 닫기 **전에** 그 PR merge ref 의 cache 수를 확인한다.

```bash
gh cache list --ref refs/pull/<n>/merge --json id --limit 100
```

entry 가 1개 이상인 PR 만 AC7 표본으로 쓴다. 그런 PR 이 닫히지 않으면 AC7 은 AWAITING_HUMAN 으로 남는다. 이 변경 뒤 PR run 은
cache 를 거의 쓰지 않으므로(buildkit export 꺼짐, Gradle cache read-only) 표본은 드물다.

- [ ] **Step 2: AC8** — dry-run 수동 실행 → 다음 schedule 실행 → 다음 dev push:

```bash
gh workflow run actions-cache-stale-cleanup.yml -f dry_run=true
gh run list --workflow actions-cache-stale-cleanup.yml --limit 5
gh run view <dry-run-id> --log
gh run view <scheduled-run-id> --log
gh run view <dev-push-run-id> --json jobs
```

예상: dry-run 로그 summary `mode=stale ref=refs/heads/dev … deleted=0 … cutoff=<ISO>`(would_delete 값 기록), 모든 `action=` 줄의
`rank` 가 2 또는 3. schedule run success(deleted·freed_bytes 기록). 그 뒤 dev push run 의 세 `7a.` leg 와 `7. Docker image build`
success. dev push leg 로그의 export 시간도 기록. leg 가 `BlobNotFound` 로 실패하면 런북의 수동 복구 절차를 따르고 그 사실을 기록한다
(일정 cleanup 이 buildkit 을 지우지 않으므로 원인은 GitHub 자체 축출이다 — design D6.1).
