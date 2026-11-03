# Quality Gate Docker image build 시간 단축 (issue #107) — 설계

> dod: `docs/work/docker-build-time/dod.md` · plan: `docs/work/docker-build-time/plan.md`
> 브랜치 `chore/issue-107-docker-build-time` (base `origin/dev` b1141a8)
> **이미지 내용·Dockerfile·Gradle 빌드 로직·배포 파이프라인 변경 없음.** 바뀌는 것은 `.github/workflows/` 와
> `ci/` 계약 스크립트, 그리고 배포 런북의 artifact 조립 설명뿐이다.

## 0. 배경과 측정 근거

실측 원본(컨트롤러 수집): `ci-durations.txt`(최근 30회), `docker-log-slow-40997176643.txt`, `docker-log-fast-41585526105.txt`,
`caches.txt`, `gradle-caches.txt`.

| 구간 | 값 | 해석 |
|---|---|---|
| `7. Docker image build` | 중앙값 229s · p90 400s · max 421s | 30회 중 8회가 다른 모든 job 최장보다 길다 |
| 테스트·검사 job 최장 | `3. API integration` 중앙값 370s · p90 474s | Docker 를 뺀 critical path |
| 느린 회차(40997176643) API 이미지 | 의존성 레이어 `materializeProductionBuildArtifacts` **113.1s** 재실행 + `exporting to GitHub Actions Cache` **88.4s** | 같은 run 의 Batch 이미지는 같은 step 6~21 을 전부 `CACHED` 로 통과했다 — 입력 변경이 아니라 **api scope 의 레이어가 사라진 것**이다 |
| 빠른 회차(41585526105) | API ≈ 79~96s · Batch ≈ 63~87s · Web 4~7s, export API 19.8s · Batch 20.2s | 세 이미지를 **한 job 에서 직렬**로 빌드한다 |
| GHA cache | 311개 · 11.04GB (저장소 한도 10GB 초과) | 한도 초과분은 LRU 로 축출된다 |
| 그중 PR ref buildkit | `refs/pull/{106,108,109}/merge` blob 4.60GB + index 24개 | PR 이 `cache-to mode=max` 로 자기 ref 에 레이어 전체를 쓴다 |
| dev gradle | 92개 · 3.36GB (`gradle-dependencies` 10개 1.9GB, `gradle-transforms` 19개 1.1GB) | 대체된(superseded) entry 가 7일 만료 전까지 남는다 |

**원인 가설** — PR 의 buildkit export 가 저장소 한도를 넘겨 LRU 가 dev api scope 레이어를 축출한다 → 다음 빌드가 113s
의존성 레이어를 다시 만들고 88s 를 들여 다시 export 한다. 여기에 세 이미지 직렬 빌드가 기본 비용을 더한다.
느린 회차에서 batch 가 hit, api 가 miss 였다는 사실이 "입력 변경" 대안 가설을 기각한다.

## 1. 범위

**포함** — 사용자 합의 ③ 그대로.

| ID | 내용 | 결정 |
|---|---|---|
| S1 | Docker `cache-to` 를 dev push 에서만, PR 은 `cache-from` 만 | D1 |
| S2 | PR closed 시 그 PR merge ref 의 모든 Actions cache 삭제 workflow | D4, D5 |
| S3 | 하루 1회 + 수동 실행, dev 의 gradle·buildkit cache 중 오래 미접근 entry 삭제 | D4, D6 |
| S4 | API·Batch·Web matrix 병렬 빌드 → 마지막 job 이 기존 `docker-images-${{ github.sha }}` artifact 하나로 조립 + provenance | D2, D3 |
| S5 | `ci/quality-gate-contract-test.sh` 의 Docker·업로드 단정 개정, 새 workflow 계약 단정 추가 | D7 |
| S6 | 계약 테스트 통과 + PR CI 실측 기록 | D8 |

**제외** — 이미지 내용·Dockerfile 동작, 배포 파이프라인, Gradle 빌드 로직, setup-gradle 설정, Docker 외 job, branch protection
설정(저장소 밖), 이미 닫힌 PR(#106·#108·#109)의 기존 cache 정리(7일 만료에 맡긴다 — §6 Q3).

## 2. 설계 체크리스트 (architect 필수 항목)

| 항목 | 이번 작업 |
|---|---|
| Facade / Domain port / infrastructure 배치 | 해당 없음 — 애플리케이션 코드 무변경. CI 인프라 작업이다 |
| MarketPair identity | 해당 없음 — 데이터 경로를 건드리지 않는다 |
| 시간·범위 계약 | 해당 없음(애플리케이션). CI 쪽 시간 계약은 D6 의 stale 기준 — UTC ISO-8601 `last_accessed_at` 을 초 단위로 내림 비교, `last_accessed_at < cutoff` (strict) |
| DB·캐시 순서 | 애플리케이션 해당 없음. GHA cache 삭제 순서는 "참조하는 쪽 먼저"(index → blob, gradle-home → 나머지 gradle) — D6 |
| Flyway | 해당 없음 |
| 배치 lock/lease/JobResult | 해당 없음. 대신 workflow `concurrency` — D2·D5·D6 |
| 알림 event identity | 해당 없음 |
| 공개 endpoint | 해당 없음 |
| needs 그래프·실패 전파 | D2 |
| 권한 | D5 — `actions: write` 는 cleanup workflow 2개에만, Quality Gate 는 `contents: read` 유지 |
| pinned action SHA | 새 action 없음. 이미 고정한 4개 SHA 를 재사용한다(`upload-artifact/merge` 는 `upload-artifact` 와 **같은 저장소·같은 SHA** 의 sub-action) — D3 |
| 재실행 시 artifact 이름 충돌 | D3 |
| 비용·시간 추정 | §4 |
| 테스트 전략 | §5 |

## 3. 결정

### D1. buildkit cache 는 dev push 만 쓴다 (S1)

matrix leg 의 build step 에 아래 두 줄만 둔다.

```yaml
          cache-from: type=gha,scope=${{ matrix.component }}
          cache-to: ${{ github.event_name == 'push' && github.ref == 'refs/heads/dev' && format('type=gha,mode=max,scope={0},ignore-error=true', matrix.component) || '' }}
```

- scope 이름은 기존 `api`·`batch`·`web` 그대로 — 머지 직후에도 dev 의 기존 index 를 그대로 읽는다.
- PR(`refs/pull/N/merge`)·`main` push 는 식이 `''` 가 되고, `docker/build-push-action` v6 는 빈 입력 목록이면 `--cache-to`
  를 넘기지 않는다(F3). PR 의 cache 조회는 자기 ref → base(`dev`) → 기본 브랜치(`dev`) 순이다. 자기 ref 에 더 이상 쓰지
  않으므로 dev index 를 읽는다.
- `mode=max` 유지 — builder stage 의 113s 의존성 레이어를 cache 에 남기는 것이 이 작업의 핵심 이득이다. 비용(export
  20~88s)은 dev push 에만 든다.
- **`ignore-error=true` 추가** — cache export 실패가 이미지 빌드 gate 를 빨갛게 만들지 않게 한다. cache 는 최적화이고 정본이
  아니다. 실패한 export 는 다음 dev push 가 다시 쓴다. 근거·실패 시 동작: F4.
- [ASSUMPTION] A1 — 저장소 기본 브랜치는 `dev` 다(`docs/superpowers/plans/2026-07-14-infrastructure-boundary-refactoring-result.md:22`,
  `.ai/PROJECT_STATUS.md:32`). 그래서 `main` push 도 dev cache 를 읽는다. Task 0 이 `gh repo view` 로 재확인한다(F6).

### D2. matrix 병렬 빌드와 조립 job (S4)

```text
docker-image (matrix: api | batch | web, fail-fast: false)  ──►  docker-build "7. Docker image build"
   각 leg: buildx build → docker-image-part-<c>-<sha> 업로드          if: !cancelled()
                                                                    1) needs 결과가 success 가 아니면 실패
                                                                    2) parts → docker-images-<sha> 로 merge
                                                                    3) provenance summary
```

- **job id·name**: matrix job 은 새 id `docker-image`, name `7a. Docker image (${{ matrix.component }})`. 조립 job 은
  **기존 id `docker-build`·name `7. Docker image build` 를 유지**한다. 지난 dod·진행 기록들이 "7. Docker image build: SUCCESS"
  를 Docker gate 의 판정으로 써 왔고, required status check 를 나중에 걸더라도 이 이름 하나가 "세 이미지가 모두 빌드되고
  archive 가 조립됐다" 를 뜻하게 한다.
- YAML 순서: `docker-image` 를 `docker-build` 바로 앞에 둔다. job 순서는 계약 테스트의 `expected_jobs` 가 고정한다.
- **`needs` 는 이 한 곳뿐**이다. 나머지 job 은 #104 D1 대로 needs 없이 동시에 시작한다. 계약 테스트의 "needs 0개" 단정을
  "정확히 한 줄, `docker-build` 의 `needs: docker-image`" 로 바꾼다.
- **실패 전파**:
  - `fail-fast: false` — 한 leg 가 실패해도 나머지 leg 가 끝까지 돌아 세 이미지의 실패를 한 번에 보여 준다(`--continue` 와
    같은 원칙). 비용은 실패 run 에서 leg 몇 분.
  - 조립 job 은 `if: ${{ !cancelled() }}` 로 **leg 실패 시에도 실행**하고 첫 step 이 `needs.docker-image.result != 'success'`
    면 `exit 1` 한다. 기본값(`success()`)이면 조립 job 이 `skipped` 가 되는데, GitHub 는 skipped job 의 check 를 통과로
    취급한다 — `7. Docker image build` 가 초록으로 보이는 거짓 통과를 막는다.
  - run 취소(concurrency) 시에는 `!cancelled()` 가 false 라 조립 job 도 취소된다.
- 조립 job 은 checkout·buildx 를 하지 않는다(재빌드 금지 — 계약 테스트가 단정).
- `DOCKER_BUILD_RECORD_UPLOAD: 'false'` 는 buildx 를 쓰는 `docker-image` job 의 `env` 로 옮긴다.
- `timeout-minutes`: `docker-image` 25(이미지 하나 최악 ≈ 5m 의 5배), `docker-build` 10.
- `concurrency` 는 workflow 단위 `quality-gate-${{ github.sha }}` 그대로다.

### D3. 임시 artifact 와 최종 artifact (S4)

| 구분 | 이름 | 내용 | 설정 |
|---|---|---|---|
| leg 임시 | `docker-image-part-${{ matrix.component }}-${{ github.sha }}` | `<component>.tar` 1개 (`${{ runner.temp }}/docker-images/<component>.tar`) | `upload-artifact` pinned SHA, `if-no-files-found: error`, `compression-level: 0`, **`retention-days: 1`**, **`overwrite: true`** |
| 최종 | `docker-images-${{ github.sha }}` | 루트에 `api.tar`·`batch.tar`·`web.tar` | `upload-artifact/merge` 같은 SHA, `pattern: docker-image-part-*-${{ github.sha }}`, `compression-level: 0`, `retention-days: 14`, **`delete-merged: true`**, `separate-directories` 기본값(false) |

- `upload-artifact/merge` 는 `actions/upload-artifact` 저장소의 sub-action 이며 이미 고정한
  `ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2` 를 그대로 쓴다. 새 action·새 SHA 가 생기지 않는다. 출력
  `artifact-id` 가 기존 provenance 의 `steps.docker-archives.outputs.artifact-id` 자리를 그대로 채운다(F1).
- `separate-directories: false` 면 각 part 의 파일이 merge artifact 루트에 평탄화된다 → 기존과 같은 `api.tar` 등 3개.
- prefix 를 `docker-image-part-` 로 둔 이유: `docker-images-<sha>` 와 glob 이 겹치지 않는다(`docker-image-part-*` 는
  `docker-images-…` 와 매치 불가).
- 임시 artifact 정리: 정상 경로는 `delete-merged: true` 가 merge 직후 지운다. leg 실패로 merge 를 못 한 경우에만 남고
  `retention-days: 1`(최솟값)로 하루 뒤 만료된다. `delete-merged` 는 runner 의 artifact runtime token 으로 지우므로
  `actions: write` 가 필요 없다는 것이 전제다 — [ASSUMPTION] A2, PR CI run 에서 part 가 남지 않음으로 확인한다(dod AC6).
- 정확히 3개 보장: 조립 job 은 `needs.docker-image.result == 'success'` 일 때만 merge 한다 → 세 leg 가 모두 성공했고, 각
  leg 는 `if-no-files-found: error` 로 자기 tar 를 반드시 올렸다. matrix 값은 계약 테스트가 `[api, batch, web]` 로 고정한다.
- **재실행(rerun)**:
  - "Re-run failed jobs": 실패한 leg 와 그에 의존하는 `docker-build` 만 다시 돈다. 성공한 leg 의 part 는 이전 attempt 의
    artifact 로 run 에 남아 있어 merge 가 집어간다. 실패 leg 가 upload 뒤(post step)에서 실패했던 경우에도
    `overwrite: true` 라 이름 충돌이 없다.
  - "Re-run all jobs": 세 part 를 다시 올린다(`overwrite: true`). 최종 `docker-images-<sha>` 는 이전 attempt 가 이미
    올렸다면 이름이 겹칠 수 있다 — **이 노출은 현재 단일 job 구조와 같다**(현재도 같은 이름을 다시 올린다). merge
    sub-action 에는 `overwrite` 입력이 없다는 것이 내 이해다(F1 에서 확인). 회귀가 아니므로 이번에 바꾸지 않는다 — §6 Q4.
- 런북 계약(`docs/runbooks/deployment.md:16-31`, `docs/deploy/aws-setup.md:89-95`)이 의존하는 것 — 이름
  `docker-images-${{ github.sha }}`, 세 tar, 이미지 tag `ghcr.io/<repo>/<c>:<sha>`, OCI revision label, summary 의
  run_id·commit·artifact_id — 는 전부 유지된다. summary 는 `7. Docker image build` job 에 남는다(같은 이름).

### D4. cache 삭제는 저장소 스크립트 하나가 한다 (S2·S3 공통)

두 workflow 가 같은 스크립트 `ci/delete-actions-caches.sh` 를 부른다. workflow 안 inline 셸로 두지 않는 이유: 삭제 대상
선별·순서·검증은 틀리면 cache 를 날리는 로직이라 **fake `gh` 로 행동 테스트**(`ci/delete-actions-caches-test.sh`)해야 하고,
그 테스트는 Quality Gate 의 compile job 이 매 PR 에 돌린다.

인터페이스(결합점 — plan Task 2 가 그대로 구현):

```text
ci/delete-actions-caches.sh pr-ref <ref>
ci/delete-actions-caches.sh stale <ref> <days>

env  GH_REPO   owner/repo (필수)
     GH_TOKEN  gh 가 읽는다 (스크립트는 읽지도 출력하지도 않는다)
     DRY_RUN   'true' | 'false' (필수 — 기본값 없음, 다른 값이면 거부)

exit 0  성공(삭제 0건 포함)
     1  GitHub API 실패 (목록 조회 실패, 또는 404 가 아닌 삭제 실패 — 그 자리에서 중단)
     2  인자·env 검증 실패 (gh 를 한 번도 부르지 않는다)
```

검증 규칙:

| 입력 | 허용 | 그 밖 |
|---|---|---|
| `pr-ref <ref>` | `^refs/pull/[1-9][0-9]*/merge$` | exit 2 |
| `stale <ref>` | 정확히 `refs/heads/dev` | exit 2 |
| `stale <days>` | 정수 `1`~`6` (7 이상은 GitHub 7일 만료와 같아 의미가 없다) | exit 2 |
| `GH_REPO` | `^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$` | exit 2 |
| `DRY_RUN` | `true`·`false` | exit 2 |

GitHub 호출(정확히 이 두 형태만 — fake `gh` 와의 결합점):

```text
gh api --method GET --paginate --slurp "repos/${GH_REPO}/actions/caches" -f "ref=${ref}" -F per_page=100
gh api --method DELETE "repos/${GH_REPO}/actions/caches/${id}"
```

- 목록 응답은 `--slurp` 로 page 배열(`[{"total_count":…,"actions_caches":[…]}, …]`)이다. JSON 처리는 `python3` 로 한다 —
  저장소 CI 스크립트가 이미 쓰는 도구(`ci/validate-dependency-bootstrap-output.sh:44`)이고 `jq` 의존을 새로 만들지 않는다.
- **방어적 ref 재확인**: API 의 `ref` 필터를 믿지 않고 응답 entry 의 `ref` 가 요청 ref 와 정확히 같은 것만 대상으로 한다.
- 삭제 응답이 `HTTP 404` 면 이미 없어진 것(경합·만료)으로 보고 `already-gone` 으로 센다. 그 외 실패는 즉시 exit 1.
  순서가 "참조하는 쪽 먼저" 라 중간에 멈춰도 참조가 깨진 상태를 남기지 않는다(D6).

순위(삭제 순서, 같은 순위 안에서는 `id` 오름차순):

| rank | key prefix | stale 대상 | pr-ref 대상 |
|---|---|---|---|
| 0 | `index-` (buildkit cache manifest) | 예 | 예 |
| 1 | `buildkit-blob-` | 예 | 예 |
| 2 | `gradle-home-` (setup-gradle 의 entry 목록을 담은 home entry) | 예 | 예 |
| 3 | 그 밖의 `gradle-` | 예 | 예 |
| 4 | 그 밖 전부(`quality-tools-*`, setup-node `node-cache-*` 등) | **아니오** | 예 |

로그 계약(stdout, 한 줄 한 사건):

```text
cache action=<delete|would-delete|already-gone> id=<id> rank=<0-4> key=<key> size_bytes=<n> last_accessed_at=<원문 ISO>
cache-cleanup summary mode=<pr-ref|stale> ref=<ref> listed=<n> selected=<n> deleted=<n> would_delete=<n> already_gone=<n> freed_bytes=<n> cutoff=<UTC ISO 초 단위|none>
```

오류는 stderr 에 `cache-cleanup error: <사유>` 한 줄. 담지 않는 값: `GH_TOKEN`, gh 의 원문 stderr·응답 본문, 요청 header.
**key 는 PR(포크 포함) 이 만든 임의 문자열**이므로 출력 전에 0x20~0x7E 밖 문자를 `?` 로 바꾼다 — 개행이 섞인 key 로
`::warning::` 같은 workflow command 를 새 줄 머리에 심는 것을 막는다(특권 컨텍스트인 `pull_request_target` job 의 로그다).

### D5. PR closed cleanup workflow 와 권한 (S2)

`.github/workflows/actions-cache-pr-cleanup.yml` — 전문은 plan Task 3.

- 트리거 `pull_request_target: types: [closed]` (merged 포함, merge 여부와 무관하게 지운다).
- **`pull_request` 가 아니라 `pull_request_target` 인 이유**: 포크 PR 의 `pull_request` 이벤트 token 은 `permissions` 선언과
  무관하게 읽기 전용이라 cache DELETE 가 403 이 된다. 포크 PR 도 자기 merge ref 에 cache 를 쓸 수 있으므로 지울 수 있어야 한다.
- **`pull_request_target` 보안 함의와 대응** — 이 이벤트는 base 브랜치의 workflow 로, 쓰기 token 을 가진 채 돈다.
  - PR 코드를 checkout 하지 않는다: `actions/checkout` 에 `ref:` 를 주지 않는다 → 기본값은 base 브랜치 최신 commit
    (`github.sha`)이라 실행되는 `ci/delete-actions-caches.sh` 는 이미 리뷰·머지된 코드다.
  - PR 이 통제하는 값(title·body·head ref·branch 이름)을 쓰지 않는다. 쓰는 값은 정수 `github.event.pull_request.number`
    하나이며, 그것도 `run:` 에 직접 보간하지 않고 `env` 로만 넘긴다.
  - `persist-credentials: false`, secrets 미사용, checkout 외 action 없음(제3자 action 이 쓰기 token 을 볼 일이 없다).
- 권한: 최상위 `permissions: { actions: write, contents: read }` 만. `actions: write` 는 cache 목록·삭제 REST 의 요구 권한이고
  (F7), `contents: read` 는 checkout 용이다. job 단위 override 없음.
- Quality Gate 의 `permissions: contents: read` 단일 블록·job override 금지 정책(`ci/quality-gate-contract-test.sh:94-100`)은
  그대로다. **`actions: write` 는 저장소 전체에서 cleanup workflow 2개에만** 있게 계약 테스트가 단정한다.
- `concurrency: group: actions-cache-pr-cleanup-${{ github.event.pull_request.number }}`, `cancel-in-progress: false`.
- 이 workflow 는 기본 브랜치에 머지된 뒤에야 동작한다(`pull_request_target` 은 base 의 workflow 파일을 쓴다).

### D6. stale cleanup workflow 와 삭제 기준 (S3)

`.github/workflows/actions-cache-stale-cleanup.yml` — 전문은 plan Task 3.

- 트리거 `schedule: cron '17 18 * * *'`(UTC 18:17 = KST 03:17, 정각 혼잡 회피·개발 시간대 밖) + `workflow_dispatch`
  (input `dry_run` boolean, **기본값 `true`**). schedule 실행은 input 이 없어 `DRY_RUN=false` 로 실제 삭제한다.
  `DRY_RUN: ${{ inputs.dry_run && 'true' || 'false' }}`.
- 대상: `refs/heads/dev` 의 rank 0~3 entry. 그 밖 ref 는 PR cleanup·GitHub 7일 만료가 맡는다.
- **N = 3일, 기준점은 벽시계가 아니라 "dev cache 의 가장 최근 접근"** 이다.
  `cutoff = max(dev entry 의 last_accessed_at) − 3일`, `last_accessed_at < cutoff` 인 entry 삭제.
  - 벽시계 기준이면 4일 연휴 동안 CI 가 한 번도 안 돌았을 때 살아 있는 cache 까지 전부 지운다. 상대 기준이면 가장 최근
    run 이 쓴 entry 는 언제나 남고(자기 자신이 기준점), "최근 활동 이후 3일 동안 아무 run 도 건드리지 않은 entry" 만 지운다.
  - 3일 근거: 사용자 기본 제안. dev push 가 거의 매일 있고(`ci-durations.txt`), 활동 기준 3일이면 한 번 대체된 gradle·buildkit
    entry 는 GitHub 7일 만료보다 4일 일찍 빠진다.
  - 시간 비교: ISO-8601 `last_accessed_at` 의 소수 초를 자릿수와 무관하게 버리고 초 단위 UTC 로 비교한다. 경계값
    (`== cutoff`)은 남긴다.
- **삭제 순서 — 참조하는 쪽 먼저**: index(rank 0) → blob(1) → gradle-home(2) → 나머지 gradle(3).
  index 를 먼저 지우면 "지워진 blob 을 가리키는 index" 가 존재하는 시간 창이 가장 짧다. index 를 지우는 것은 항상 안전하다
  (index 가 없으면 그 scope 전체가 cache miss → 재빌드). gradle-home 은 setup-gradle 이 복원할 나머지 entry key 목록을 가진다.
- **index 가 깨진 blob 을 가리킬 때의 buildx 동작**(사용자 지정 위험):
  - 내 이해(Task 0 F5 에서 buildkit 소스로 확인): buildkit gha importer 는 cache record 를 쓰기 전에 각 레이어 blob 의
    존재를 cache API 조회로 확인(`ciProvider.CheckDescriptor` → `loadEntry`)하고, 없으면 그 record 를 쓰지 않는다 → 해당
    step 을 **다시 실행**(cache miss)한다. 빌드 실패가 아니다. 다시 만든 레이어는 다음 dev export 가 다시 올린다(자가 회복).
  - 확인 전의 남은 위험 두 가지와 대응:
    1. 존재 확인과 실제 다운로드 사이에 blob 이 지워지는 경합 → 다운로드 실패로 빌드가 실패할 수 있다. 대응: cleanup 을
       개발 시간대 밖(KST 03:17)에 하루 1회만 돌린다. 발생하면 rerun 으로 회복된다(재시도 시 존재 확인이 miss 를 낸다).
    2. dev export 가 lazy 레이어(가져오지 않은 레이어)를 export 하려다 원격에 없어 실패 → `ignore-error=true`(D1) 로 빌드는
       성공하고 export 만 건너뛴다.
  - **F5 가 "missing blob 은 빌드 실패" 로 확인되면** Task 0 에서 멈추고 보고한다. 대안 설계: stale cleanup 에서 rank 1
    (`buildkit-blob-`)을 빼고 index·gradle 만 지운다(index 삭제는 항상 안전, 고아 blob 은 7일 만료). 범위 변경이므로 재승인.
- gradle entry 가 사라졌을 때: setup-gradle 은 해당 entry 복원을 miss 로 기록하고 진행하며, Gradle 은
  `--dependency-verification strict` 로 의존성을 다시 받는다(느려질 뿐 실패하지 않음) — [ASSUMPTION] A3.
- [ASSUMPTION] A4 — PR run 이 dev entry 를 복원(조회)할 때도 그 entry 의 `last_accessed_at` 이 갱신된다. 틀리더라도 상대 기준은
  dev push 가 3일 넘게 없는 기간에만 영향을 받고, 그때 지워지는 것은 다음 dev push 가 다시 쓴다.
- `concurrency: group: actions-cache-stale-cleanup`, `cancel-in-progress: false`. 권한은 D5 와 같다.
- schedule 은 기본 브랜치(`dev`, A1)의 workflow 로만 돈다.

### D7. 계약 테스트 (S5)

세 스크립트. 셋 다 `ci/quality-gate-contract-test.sh` 가 부르므로 Quality Gate compile job 이 매 PR 에 돌린다
(`ci/dependency-bootstrap-contract-test.sh` 를 부르는 기존 방식과 같다).

| 스크립트 | 무엇을 재는가 |
|---|---|
| `ci/quality-gate-contract-test.sh` (개정) | Docker matrix·조립 job 구조, cache-to 조건식, part·최종 artifact 계약, provenance, needs 그래프. 기존의 pinned SHA·github.sha 이름·build record 비활성·provenance 단정은 새 위치에서 유지·강화 |
| `ci/actions-cache-cleanup-contract-test.sh` (신규) | cleanup workflow 2개의 트리거·권한·checkout 안전성·스크립트 호출·concurrency, 저장소 전체에서 `actions: write` 의 위치. **매 실행마다 변형(mutation) 사본으로 자기 단정이 위반을 잡는지 확인**한다 |
| `ci/delete-actions-caches-test.sh` (신규) | fake `gh` 로 `ci/delete-actions-caches.sh` 의 선별·순서·검증·404·실패 중단·dry-run·로그 위생 |

- 현재 `ci/quality-gate-contract-test.sh` 에는 자가검증(픽스처·negative case)이 없다. 이번 개정의 Docker 단정은 **현재
  workflow 에 대해 실패(RED)** 함으로써 무엇을 재는지 증명한다. 기존 단정 전체를 mutation 자가검증으로 바꾸는 것은 범위 밖이다.
- pinned action 정책(`ci/quality-gate-contract-test.sh:236-245`)은 이미 `.github/workflows/` 의 **모든** 파일을 검사하므로
  새 workflow 에 자동 적용된다. 신규 계약 테스트는 거기에 더해 "cleanup workflow 의 `uses:` 는 checkout 1개뿐, Quality Gate 와
  같은 SHA" 를 단정한다.

### D8. 측정과 수용 (S6)

| 기호 | 정의 (`gh run view <run-id> --json jobs` 의 `startedAt`·`completedAt`, 큐 대기 제외) |
|---|---|
| `T_docker` | `docker-build.completedAt − min(docker-image legs.startedAt)` — 조립 job 의 runner 대기까지 포함한 Docker 경로 |
| `T_leg(c)` | leg `c` 의 `completedAt − startedAt` |
| `T_merge` | `docker-build.completedAt − docker-build.startedAt` |
| `T_other` | Docker 를 뺀 7개 job(`1`~`6`, `8`) 각각의 `completedAt − startedAt` 중 최댓값 |

수용: 최종 SHA 의 PR Quality Gate run 에서 모든 job success 이고 `T_docker ≤ T_other` (issue AC). 기록: `T_leg` 3개,
`T_merge`, leg 로그의 `exporting to GitHub Actions Cache` 부재, 머지 뒤 첫 dev push run 의 같은 값(기록만, 판정 아님).

## 4. 비용·시간 추정

| 경로 | 현재 | 변경 후 (추정) |
|---|---|---|
| PR, cache hit | 직렬 합 ≈ 150~200s + 3회 export 40s | leg 최장(API) ≈ setup 15s + 빌드 60~75s(export 없음) + part upload 10s ≈ **90~110s**, 조립 ≈ 대기 10s + merge 30~50s → `T_docker` ≈ **130~170s** |
| PR, api 의존성 레이어 miss | 277~283s(API) + Batch·Web | API leg ≈ 15 + 113 + 56 + 10 ≈ 195s, 조립 포함 ≈ **240s** (`T_other` 중앙값 370s 이하) |
| dev push | 위와 같음 + export | leg 마다 export 20~88s 추가(병렬) — PR 경로 아님 |
| runner 분 | 1 job | leg 3개의 setup 중복(+30s) + 조립 job(≈ 1분) → run 당 약 +1.5 runner-분 |
| cache 저장량 | 11.04GB | PR buildkit 0 + dev gradle·buildkit 의 대체 entry 정리 → dev 합(현재 6.4GB) 이하로 유지 예상 |

[ASSUMPTION] A5 — tar 3개 합이 수백 MB 라 merge(다운로드+재업로드)가 30~50s 다. Task 6 실측으로 확인하고, 크게 벗어나면 기록한다.

트레이드오프(수용된 범위의 결과): PR 이 자기 cache 를 쓰지 않으므로 의존성 파일(`gradle.lockfile`·`build.gradle.kts`)을 바꾼
PR 은 **push 마다** 의존성 레이어를 다시 만든다(+113s, 병렬이라 경로는 위 miss 행). 이전에는 그 PR 의 첫 push 만 냈다.

## 5. 테스트 전략

| 계층 | 무엇을 | 어디서 |
|---|---|---|
| 삭제 로직 | 선별(ref·family·cutoff 경계)·순서·dry-run·404·실패 중단·입력 거부·로그 위생·pagination | `ci/delete-actions-caches-test.sh` (fake `gh`, 고정 fixture — 벽시계 무관) |
| cleanup workflow 정적 계약 | 트리거·권한·checkout 안전성·호출 문자열 + mutation 자가검증 | `ci/actions-cache-cleanup-contract-test.sh` |
| Quality Gate 구조 | Docker matrix·조립·artifact·needs | `ci/quality-gate-contract-test.sh` (RED → GREEN) |
| 배포 계약 회귀 | 배포 스크립트·compose 계약 | `bash docker/deploy-contract-test.sh` |
| 실제 GitHub 동작 | 시간, PR 의 cache 무기록, 최종 artifact 내용(tar 3개·tag·OCI revision), part 정리 | PR CI run (T2/T3) |
| 머지 뒤 동작 | PR cleanup 실행, stale cleanup dry-run·실삭제, 첫 dev push export | 머지 뒤 run (T3) |

## 6. [ASSUMPTION] · [DECISION_NEEDED] · 열린 질문

- [ASSUMPTION] A1 — 기본 브랜치 `dev`(D1, D6). Task 0 F6 확인.
- [ASSUMPTION] A2 — `delete-merged` 는 runtime token 으로 동작해 Quality Gate 권한(`contents: read`)을 넓히지 않는다(D3). AC6 확인.
- [ASSUMPTION] A3 — gradle entry 가 사라져도 setup-gradle 은 miss 로 진행한다(D6).
- [ASSUMPTION] A4 — 복원 조회도 `last_accessed_at` 을 갱신한다(D6). 틀려도 상대 기준이 피해를 제한한다.
- [ASSUMPTION] A5 — merge 30~50s(§4). Task 6 실측.
- [DECISION_NEEDED] N1 — stale 기준점을 "dev cache 최근 접근 − 3일"(상대)로 둔다. 합의 ③-3 의 "최근 N일 미접근" 을 벽시계가
  아닌 활동 기준으로 해석한 것이다(D6 근거). 벽시계 기준을 원하면 바꾼다.
- [DECISION_NEEDED] N2 — dod AC2 의 RED 를 "mutation 사본이 기대 메시지로 실패" 로 대신한다(새 파일의 정적 계약은 구현 전에
  돌리면 "파일 없음" 실패만 나서 skill 정의상 RED 가 아니다). dod 참조.
- [DECISION_NEEDED] N3 — 머지 뒤에만 관찰 가능한 AC7·AC8 을 이 dod 에 둔다(판정은 머지 뒤까지 `AWAITING_HUMAN`). 대안은 후속 이슈로 분리.
- Q1 — F5 결과가 "missing blob = 빌드 실패" 면 D6 대안(blob 제외)으로 범위를 바꿀지.
- Q2 — required status check 를 쓰기 시작한다면 `7. Docker image build` 하나만 걸면 된다(D2). 현재는 branch protection 이 없다고
  기록돼 있다(`.ai/PROJECT_STATUS.md:32`).
- Q3 — 이미 닫힌 PR #106·#108·#109 의 buildkit cache(4.6GB)를 수동으로 지울지. 지우지 않으면 최장 7일(마지막 접근 기준) 동안
  저장소가 한도 근처에 머문다 — PR CI 실측(AC4) 중에도 dev 레이어 축출 위험이 남는다. 수동 삭제는 공유 상태 변경이라 사용자 승인 필요.
- Q4 — "Re-run all jobs" 시 최종 artifact 이름 충돌 가능성은 현재 구조와 같다(D3). 이번에 고치지 않는다.

## 7. Task 0 에서 확인할 외부 사실

| ID | 사실 | 확인 방법 | 다르면 |
|---|---|---|---|
| F1 | `actions/upload-artifact/merge@ea165f8…` 의 입력 `name`·`pattern`·`compression-level`·`retention-days`·`delete-merged`·`separate-directories`, 출력 `artifact-id` 존재. `overwrite` 입력 유무 | 그 SHA 의 `merge/action.yml` | 멈추고 보고(조립 방식 재설계) |
| F2 | `actions/upload-artifact@ea165f8…` 에 `overwrite` 입력 존재 | 그 SHA 의 `action.yml` | `overwrite` 를 빼고 rerun 위험을 dod 에 기록 |
| F3 | `docker/build-push-action@2634353…` 은 빈 `cache-to` 에서 `--cache-to` 를 넘기지 않는다 | 그 SHA 의 `src/context.ts`(getInputList → 인자 조립) | 멈추고 보고 |
| F4 | buildx/buildkit cache exporter 가 `ignore-error=true` 속성을 지원 | buildx 문서 `cache backends` | 속성 제거, export 실패가 gate 실패가 됨을 기록 |
| F5 | buildkit gha importer 가 없는 blob 을 cache miss 로 처리 | `moby/buildkit` `cache/remotecache/gha/gha.go`(`CheckDescriptor`·`loadEntry`) | D6 대안 + Q1 |
| F6 | 기본 브랜치 `dev` | `gh repo view --json defaultBranchRef` | D1·D6 재검토(schedule·main cache 읽기) |
| F7 | cache 목록·삭제 REST 가 `actions: write`(삭제)·`actions: read`(목록)로 충분 | GitHub REST 문서 "Delete a GitHub Actions cache for a repository (using a cache ID)" | 권한 조정 |
| F8 | `gh api` 가 GET 에서 `-f/-F` 를 query string 으로 보내고 `--paginate --slurp` 를 지원 | `gh api --help`(runner 의 gh 버전 기준) | 호출 형태 조정(D4 결합점 변경 → plan 동시 수정) |

## 8. 개정 이력

| 시점 | 변경 | 사유 |
|---|---|---|
| ④ 최초 작성 | — | — |
