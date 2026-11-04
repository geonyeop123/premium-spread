# Quality Gate Docker image build 시간 단축 (issue #107) — 설계

> dod: `docs/work/docker-build-time/dod.md` · plan: `docs/work/docker-build-time/plan.md`
> 브랜치 `chore/issue-107-docker-build-time` (base `origin/dev` b1141a8)
> **이미지 내용·Dockerfile·Gradle 빌드 로직·배포 파이프라인 변경 없음.** 바뀌는 것은 `.github/workflows/` 와
> `ci/` 계약 스크립트, 그리고 배포 런북의 artifact 조립·cache 복구 설명뿐이다.

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

**포함** — 사용자 합의 ③ 그대로, 단 S3 은 F5 에 따른 컨트롤러 판정으로 gradle 만 대상이다(§1.1).

| ID | 내용 | 결정 |
|---|---|---|
| S1 | Docker `cache-to` 를 dev push 에서만, PR 은 `cache-from` 만 | D1 |
| S2 | PR closed 시 그 PR merge ref 의 모든 Actions cache 삭제 workflow | D4, D5 |
| S3 | 하루 1회 + 수동 실행, dev 의 **gradle** cache 중 오래 미접근 entry 삭제 (buildkit 은 제외 — §1.1) | D4, D6 |
| S4 | API·Batch·Web matrix 병렬 빌드 → 마지막 job 이 기존 `docker-images-${{ github.sha }}` artifact 하나로 조립 + provenance | D2, D3 |
| S5 | `ci/quality-gate-contract-test.sh` 의 Docker·업로드 단정 개정, 새 workflow 계약 단정 추가 | D7 |
| S6 | 계약 테스트 통과 + PR CI 실측 기록 | D8 |

**제외** — 이미지 내용·Dockerfile 동작, 배포 파이프라인, Gradle 빌드 로직, setup-gradle 설정, Docker 외 job, branch protection
설정(저장소 밖), 이미 닫힌 PR(#106·#108·#109)의 기존 cache 정리(7일 만료에 맡긴다 — §6 Q2), **dev buildkit index·blob 의
일정 삭제**(§1.1).

### 1.1 범위 조정 — F5 에 따른 컨트롤러 판정 (Gate 1 에서 사용자 승인 필요)

합의 ③-3 은 "dev 의 gradle-* 와 buildkit(index/blob) cache 중 N일 미접근 entry 삭제" 였다. F5(§7) 확인 결과 **buildkit gha
importer 는 index 가 가리키는 blob 이 없으면 cache miss 가 아니라 빌드를 실패시킨다**(moby/buildkit#7283, `BlobNotFound`/404).
blob key 는 scope 와 무관한 전역 sha256(`buildkit-blob-1-sha256:…`)이라 api·batch·web index 가 같은 blob entry 를 공유할 수
있고, 어떤 blob 이 어떤 살아 있는 index 에 참조되는지 cache 목록만으로 알 수 없다. 따라서 dev buildkit entry 의 부분 삭제는
안전하지 않다.

판정:

- 일정(stale) cleanup 은 **`gradle-*` entry(rank 2·3)만** 지운다.
- dev buildkit index·blob 은 어떤 일정 작업도 지우지 않는다. GitHub 의 7일 미사용 만료가 정리한다. PR 이 더 이상 buildkit 을
  쓰지 않으므로(D1) 증가량은 dev push 의 export 로 한정된다.
- buildkit index·blob 삭제는 **PR closed cleanup 의 `refs/pull/<n>/merge` 전체 삭제**에서만 일어난다. dev 빌드는 PR ref cache 를
  읽지 않으므로(cache 는 ref 단위 entry 다) 안전하다.

## 2. 설계 체크리스트 (architect 필수 항목)

| 항목 | 이번 작업 |
|---|---|
| Facade / Domain port / infrastructure 배치 | 해당 없음 — 애플리케이션 코드 무변경. CI 인프라 작업이다 |
| MarketPair identity | 해당 없음 — 데이터 경로를 건드리지 않는다 |
| 시간·범위 계약 | 해당 없음(애플리케이션). CI 쪽 시간 계약은 D6 의 stale 기준 — UTC ISO-8601 `last_accessed_at` 을 초 단위로 내림 비교, `last_accessed_at < cutoff` (strict) |
| DB·캐시 순서 | 애플리케이션 해당 없음. GHA cache 삭제 순서는 "참조하는 쪽 먼저"(index → blob → gradle-home → 나머지 gradle) — D4 |
| Flyway | 해당 없음 |
| 배치 lock/lease/JobResult | 해당 없음. 대신 workflow `concurrency` — D2·D5·D6 |
| 알림 event identity | 해당 없음 |
| 공개 endpoint | 해당 없음 |
| needs 그래프·실패 전파 | D2 |
| 권한 | D3·D5 — Quality Gate 최상위 `contents: read` 유지, 조립 job 하나만 `actions: read`. `actions: write` 는 cleanup workflow 2개에만 |
| pinned action SHA | 새 action 없음. 이미 고정한 4개 SHA 를 재사용한다(`upload-artifact/merge` 는 `upload-artifact` 와 **같은 저장소·같은 SHA** 의 sub-action) — D3 |
| 재실행 시 artifact 이름 충돌·part 만료 | D3 |
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
- PR(`refs/pull/N/merge`)·`main` push 는 식이 `''` 가 된다. `docker/build-push-action` v6.18.0 은 `cache-to` 를
  `getInputList('cache-to', {ignoreComma:true})` 로 읽어 원소마다 `--cache-to` 를 붙이므로 빈 목록이면 인자가 없다. 다만 빈
  문자열이 빈 목록이 되는지는 `@docker/actions-toolkit` 쪽 동작이라 소스로 확정하지 못했다(F3 = LIKELY). **PR run 의 build
  step 로그에 `--cache-to` 와 `exporting to GitHub Actions Cache` 가 없음으로 판정한다**(dod AC5).
- PR 의 cache 조회는 자기 ref → base(`dev`) → 기본 브랜치(`dev`, F6) 순이다. 자기 ref 에 더 이상 쓰지 않으므로 dev index 를 읽는다.
- `mode=max` 유지 — builder stage 의 113s 의존성 레이어를 cache 에 남기는 것이 이 작업의 핵심 이득이다. 비용(export
  20~88s)은 dev push 에만 든다.
- **`ignore-error=true` 추가**(F4 = TRUE: "Ignore errors caused by failed cache exports") — cache export 실패가 이미지 빌드
  gate 를 빨갛게 만들지 않게 한다. cache 는 최적화이고 정본이 아니다. 이 속성은 **export** 에만 적용된다. import 쪽 blob 부재는
  여전히 빌드를 실패시킨다(F5) — D6 의 수동 복구.

### D2. matrix 병렬 빌드와 조립 job (S4)

```text
docker-image (matrix: api | batch | web, fail-fast: false)  ──►  docker-build "7. Docker image build"  (permissions: actions: read)
   각 leg: buildx build → docker-image-part-<c>-<sha> 업로드          if: !cancelled()
                                                                    1) needs 결과가 success 가 아니면 실패
                                                                    2) 만료되지 않은 part 가 정확히 3개가 아니면 실패
                                                                    3) parts → docker-images-<sha> 로 merge
                                                                    4) provenance → step summary + 로그
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

### D3. 임시 artifact, 최종 artifact, part 개수 검사 (S4)

| 구분 | 이름 | 내용 | 설정 |
|---|---|---|---|
| leg 임시 | `docker-image-part-${{ matrix.component }}-${{ github.sha }}` | `<component>.tar` 1개 (`${{ runner.temp }}/docker-images/<component>.tar`) | `upload-artifact` pinned SHA, `if-no-files-found: error`, `compression-level: 0`, **`retention-days: 1`**, **`overwrite: true`** |
| 최종 | `docker-images-${{ github.sha }}` | 루트에 `api.tar`·`batch.tar`·`web.tar` | `upload-artifact/merge` 같은 SHA, `pattern: docker-image-part-*-${{ github.sha }}`, `compression-level: 0`, `retention-days: 14`, **`delete-merged: true`**, `separate-directories` 기본값(false) |

- `upload-artifact/merge` 는 `actions/upload-artifact` 저장소의 sub-action 이며 이미 고정한
  `ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2` 를 그대로 쓴다. 새 action·새 SHA 가 생기지 않는다. 입력 `name`·`pattern`·
  `separate-directories`(기본 false = 매치된 artifact 를 같은 디렉터리에 합친다)·`retention-days`·`compression-level`(기본 6 →
  0 으로 지정)·`delete-merged`(기본 false → true 로 지정)·`include-hidden-files`, 출력 `artifact-id`·`artifact-url`·
  `artifact-digest` 를 가진다(F1). 출력 `artifact-id` 가 기존 provenance 의 `steps.docker-archives.outputs.artifact-id` 자리를
  그대로 채운다.
- `separate-directories: false` 이므로 각 part 의 파일이 merge artifact 루트에 평탄화된다 → 기존과 같은 `api.tar` 등 3개.
- prefix 를 `docker-image-part-` 로 둔 이유: `docker-images-<sha>` 와 glob 이 겹치지 않는다(`docker-image-part-*` 는
  `docker-images-…` 와 매치 불가).
- 임시 artifact 정리: 정상 경로는 `delete-merged: true` 가 merge 직후 지운다. leg 실패로 merge 를 못 한 경우에만 남고
  `retention-days: 1`(최솟값)로 하루 뒤 만료된다. `delete-merged` 는 runner 의 artifact runtime token 으로 지운다는 것이
  전제다 — [ASSUMPTION] A2, PR CI run 에서 part 가 남지 않음으로 확인한다(dod AC6).
- **part 개수 검사(리뷰 M4)** — merge 는 pattern 에 매치되는 것만 합치므로, part 가 만료·누락되면 tar 1~2개짜리 archive 를
  만들고 초록으로 끝난다. 조립 job 은 merge **전에** 이번 run 의 만료되지 않은 part 이름 집합이 정확히
  `docker-image-part-{api,batch,web}-${GITHUB_SHA}` 3개인지 GitHub REST(`GET /repos/{owner}/{repo}/actions/runs/{run_id}/artifacts`,
  러너에 기본 설치된 `gh api` + 내장 `--jq`)로 확인하고 아니면 `exit 1` 한다.
  - 선택지 비교: 저장소는 `actions/download-artifact` 를 고정해 두지 않았다(grep 결과 0건) — 내려받아 `ls` 로 확인하는 방법은
    새 action·새 SHA 를 요구한다. REST 조회는 새 action 이 없고 읽기 권한 하나만 요구한다. **최소 권한 선택**: 조립 job 하나에만
    job 단위 `permissions: actions: read` 를 준다. job 단위 permissions 는 최상위를 대체하므로 이 job 의 token 은
    `actions: read` 만 갖는다(`contents` 없음 — checkout 을 하지 않으므로 필요 없다). 최상위 `contents: read` 는 그대로다.
  - 이 job 은 저장소 코드를 실행하지 않는다(checkout 없음, inline step 만). 읽기 권한이 노출되는 대상은 이 run 의 Actions
    메타데이터뿐이다.
  - merge 뒤 내용을 다시 내려받아 확인하지는 않는다 — 각 part 는 `path` 가 단일 tar 이고 `if-no-files-found: error` 이므로
    "만료되지 않은 part 3개" 가 "merge 결과 tar 3개" 를 함의한다. 최종 내용은 PR run 에서 AC6 로 한 번 더 관찰한다.
  - 계약 테스트의 "job 단위 permissions 금지"(`ci/quality-gate-contract-test.sh:98-100`)는 "job 단위 permissions 는 정확히
    1개, `docker-build` 의 `actions: read` 단독" 으로 바꾼다.
- **재실행(rerun)과 part 보존 창**:
  - "Re-run failed jobs": 실패한 leg 와 그에 의존하는 `docker-build` 만 다시 돈다. 성공한 leg 의 part 는 이전 attempt 의
    artifact 로 run 에 남아 있어 merge 가 집어간다. 실패 leg 가 upload 뒤(post step)에서 실패했던 경우에도
    `overwrite: true`(F2 — 기본 false 는 같은 이름이면 실패) 라 이름 충돌이 없다.
  - **rerun 창은 part 보존 기간 1일이다.** 원 run 뒤 1일이 지나 실패 job 만 다시 돌리면 성공했던 leg 의 part 가 만료돼 있다 —
    part 개수 검사가 `docker-build` 를 실패시킨다. 이때는 "Re-run all jobs" 로 세 part 를 다시 만든다. 런북에 적는다.
  - "Re-run all jobs": 세 part 를 다시 올린다(`overwrite: true`). 최종 `docker-images-<sha>` 는 이전 attempt 가 이미
    올렸다면 이름이 겹칠 수 있고, merge sub-action 에는 `overwrite` 입력이 **없다**(F1). 이 노출은 현재 단일 job 구조와 같다
    (현재도 같은 이름을 다시 올린다). 회귀가 아니므로 이번에 바꾸지 않는다 — §6 Q3.
- **provenance 를 로그에도 남긴다(리뷰 M3)** — step summary 는 `gh run view` 로 읽을 수 없다. provenance step 이 같은 세 줄
  (`run_id=`·`commit=`·`artifact_id=`)을 `tee -a "${GITHUB_STEP_SUMMARY}"` 로 summary 와 step 로그에 동시에 쓴다. 검증은
  `gh run view <run-id> --log --job <docker-build-job-id>`(dod AC6).
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
     1  GitHub API 실패(목록 조회 실패, 또는 404 가 아닌 삭제 실패 — 그 자리에서 중단),
        또는 stale 모드에서 해석할 수 없는 last_accessed_at (삭제 0건으로 중단)
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

GitHub 호출(정확히 이 두 형태만 — fake `gh` 와의 결합점. F8: gh 2.86.0 에서 `--paginate`·`--slurp`·GET 의 `-f`→query
string·`--method` 확인, `--slurp` 는 gh 2.48 부터):

```text
gh api --method GET --paginate --slurp "repos/${GH_REPO}/actions/caches" -f "ref=${ref}" -F per_page=100
gh api --method DELETE "repos/${GH_REPO}/actions/caches/${id}"
```

- 목록 응답은 `--slurp` 로 page 배열(`[{"total_count":…,"actions_caches":[…]}, …]`)이다. entry 필드 `id ref key version
  last_accessed_at created_at size_in_bytes`(F7). JSON 처리는 `python3` 로 한다 — 저장소 CI 스크립트가 이미 쓰는 도구
  (`ci/validate-dependency-bootstrap-output.sh:44`)이고 `jq` 의존을 새로 만들지 않는다.
- **방어적 ref 재확인**: API 의 `ref` 필터를 믿지 않고 응답 entry 의 `ref` 가 요청 ref 와 정확히 같은 것만 대상으로 한다.
- 삭제 응답이 `HTTP 404` 면 이미 없어진 것(경합·만료)으로 보고 `already-gone` 으로 센다. 그 외 실패는 즉시 exit 1.

순위(삭제 순서, 같은 순위 안에서는 `id` 오름차순):

| rank | key prefix | stale 대상 | pr-ref 대상 |
|---|---|---|---|
| 0 | `index-` (buildkit cache manifest) | **아니오** (§1.1) | 예 |
| 1 | `buildkit-blob-` | **아니오** (§1.1) | 예 |
| 2 | `gradle-home-` (setup-gradle 의 entry 목록을 담은 home entry) | 예 | 예 |
| 3 | 그 밖의 `gradle-` | 예 | 예 |
| 4 | 그 밖 전부(`quality-tools-*`, setup-node `node-cache-*` 등) | 아니오 | 예 |

pr-ref 모드는 닫힌 PR 의 ref 전체를 지우므로 순서가 정확성에 영향을 주지 않는다. 순서를 두는 것은 중간 실패 시에도 "참조하는
쪽(index·gradle-home)이 먼저 사라진" 상태만 남기기 위해서다.

로그 계약(stdout, 한 줄 한 사건):

```text
cache action=<delete|would-delete|already-gone> id=<id> rank=<0-4> key=<key> size_bytes=<n> last_accessed_at=<원문>
cache-cleanup summary mode=<pr-ref|stale> ref=<ref> listed=<n> selected=<n> deleted=<n> would_delete=<n> already_gone=<n> freed_bytes=<n> cutoff=<UTC ISO 초 단위|none>
```

오류는 stderr 에 `cache-cleanup error: <사유>` 한 줄. 담지 않는 값: `GH_TOKEN`, gh 의 원문 stderr·응답 본문, 요청 header.
**key 는 PR(포크 포함) 이 만든 임의 문자열**이므로 출력 전에 0x20~0x7E 밖 문자를 `?` 로 바꾼다 — 개행이 섞인 key 로
`::warning::` 같은 workflow command 를 새 줄 머리에 심는 것을 막는다(특권 컨텍스트인 `pull_request_target` job 의 로그다).

### D5. PR closed cleanup workflow 와 권한 (S2)

`.github/workflows/actions-cache-pr-cleanup.yml` — 전문은 plan Task 3.

- 트리거 `pull_request_target: types: [closed]` (merged 포함, merge 여부와 무관하게 지운다).
- **`pull_request` 가 아니라 `pull_request_target` 인 이유**: 포크 PR 의 `pull_request` 이벤트 token 은 `permissions` 선언과
  무관하게 읽기 전용이라 cache DELETE 가 실패한다. 저장소가 PUBLIC 이고(F6) 포크 PR 도 자기 merge ref 에 cache 를 쓸 수 있으므로
  지울 수 있어야 한다.
- **`pull_request_target` 보안 함의와 대응** — 이 이벤트는 base 브랜치의 workflow 로, 쓰기 token 을 가진 채 돈다.
  - PR 코드를 checkout 하지 않는다: `actions/checkout` 에 `ref:` 를 주지 않는다 → 기본값은 base 브랜치 최신 commit
    (`github.sha`)이라 실행되는 `ci/delete-actions-caches.sh` 는 이미 리뷰·머지된 코드다.
  - PR 이 통제하는 값(title·body·head ref·branch 이름)을 쓰지 않는다. 쓰는 값은 정수 `github.event.pull_request.number`
    하나이며, 그것도 `run:` 에 직접 보간하지 않고 `env` 로만 넘긴다.
  - `persist-credentials: false`, secrets 미사용, checkout 외 action 없음(제3자 action 이 쓰기 token 을 볼 일이 없다).
- 권한: 최상위 `permissions: { actions: write, contents: read }` 만, job 단위 override 없음. `contents: read` 는 checkout 용이다.
  GitHub REST 문서는 cache 목록(`ref`·`key` prefix 필터)과 id·key 기준 삭제 endpoint 를 확인했지만 `GITHUB_TOKEN` 권한 문구는
  그 페이지에서 확인하지 못했다(F7 = PARTIAL). `actions: write` 로 충분하다는 것은 **머지 뒤 첫 실행에서 `deleted>=1` 로
  확인**한다(dod AC7). 부족하면 403 으로 exit 1 이 나며 cache 는 그대로다(fail-closed).
- Quality Gate 의 최상위 `permissions: contents: read` 단일 블록은 그대로다. job 단위 override 는 조립 job 의 `actions: read`
  하나뿐이다(D3). **`.github/workflows/*.yml` 안에서 `actions: write` 는 cleanup workflow 2개에만** 있게 계약 테스트가 단정한다.
- `concurrency: group: actions-cache-pr-cleanup-${{ github.event.pull_request.number }}`, `cancel-in-progress: false`.
- 이 workflow 는 기본 브랜치에 머지된 뒤에야 동작한다(`pull_request_target` 은 base 의 workflow 파일을 쓴다).

### D6. stale cleanup workflow 와 삭제 기준 (S3)

`.github/workflows/actions-cache-stale-cleanup.yml` — 전문은 plan Task 3.

- 트리거 `schedule: cron '17 18 * * *'`(UTC 18:17 = KST 03:17, 정각 혼잡 회피·개발 시간대 밖) + `workflow_dispatch`
  (input `dry_run` boolean, **기본값 `true`**). schedule 실행은 input 이 없어 `DRY_RUN=false` 로 실제 삭제한다.
  `DRY_RUN: ${{ inputs.dry_run && 'true' || 'false' }}`.
- 대상: `refs/heads/dev` 의 **rank 2·3(`gradle-*`) entry 만**(§1.1). buildkit index·blob(rank 0·1)과 rank 4 는 건드리지 않는다.
- **N = 3일, 기준점은 벽시계가 아니라 "dev cache 의 가장 최근 접근"** 이다.
  `cutoff = max(dev entry 전체의 last_accessed_at) − 3일`, rank 2·3 중 `last_accessed_at < cutoff` 인 entry 삭제.
  - 기준점은 rank 와 무관하게 응답의 dev entry 전체(buildkit index 포함)에서 구한다 — 매 Quality Gate run 이 읽는 index 가
    "최근 활동" 을 가장 잘 대표한다.
  - 벽시계 기준이면 4일 연휴 동안 CI 가 한 번도 안 돌았을 때 살아 있는 cache 까지 전부 지운다. 상대 기준이면 가장 최근
    run 이 쓴 entry 는 언제나 남고(자기 자신이 기준점), "최근 활동 이후 3일 동안 아무 run 도 건드리지 않은 entry" 만 지운다.
  - 3일 근거: 사용자 기본 제안. dev push 가 거의 매일 있고(`ci-durations.txt`), 활동 기준 3일이면 한 번 대체된 gradle
    entry 는 GitHub 7일 만료보다 4일 일찍 빠진다.
  - 시간 비교: ISO-8601 `last_accessed_at` 의 소수 초를 자릿수와 무관하게 버리고 초 단위 UTC 로 비교한다. 경계값
    (`== cutoff`)은 남긴다.
- **경계 조건(리뷰 m9)**:

  | 상황 | 동작 |
  |---|---|
  | 응답에 요청 ref(dev) entry 가 0개 (타 ref entry 만 있거나 빈 목록) | DELETE 0회, summary `listed=0 selected=0 … cutoff=none`, exit 0 |
  | dev entry 중 하나라도 `last_accessed_at` 이 없거나 해석 불가 | 기준점을 믿을 수 없으므로 **삭제 0회**, stderr `cache-cleanup error: unparsable last_accessed_at id=<id>`, summary 없음, exit 1 (fail-closed). rank 와 무관하게 dev entry 전부를 검사한다(기준점 계산에 모두 쓰이므로) |
  | pr-ref 모드 | `last_accessed_at` 을 해석하지 않는다 — ref 전체 삭제라 시간이 판정에 쓰이지 않는다 |

- 삭제 순서: gradle-home(rank 2) → 나머지 gradle(rank 3). gradle-home 은 setup-gradle 이 복원할 나머지 entry key 목록을 가진다.
- gradle entry 가 사라졌을 때: setup-gradle 은 해당 entry 복원을 miss 로 기록하고 진행하며, Gradle 은
  `--dependency-verification strict` 로 의존성을 다시 받는다(느려질 뿐 실패하지 않음) — [ASSUMPTION] A3. AC8 의 schedule
  실행 뒤 dev push 성공으로 관찰한다.
- [ASSUMPTION] A4 — PR run 이 dev entry 를 복원(조회)할 때도 그 entry 의 `last_accessed_at` 이 갱신된다. 틀리더라도 상대 기준은
  dev push 가 3일 넘게 없는 기간에만 영향을 받고, 그때 지워지는 gradle entry 는 다음 dev push 가 다시 쓴다.
- `concurrency: group: actions-cache-stale-cleanup`, `cancel-in-progress: false`. 권한은 D5 와 같다.
- schedule 은 기본 브랜치(`dev`, F6)의 workflow 로만 돈다.

#### D6.1 남는 위험 — GitHub 자체 축출로 인한 BlobNotFound 와 수동 복구

일정 cleanup 이 buildkit 을 지우지 않아도, **GitHub 의 7일 미사용 만료나 저장소 한도 초과 LRU 축출**이 blob 하나를 지우고
그 blob 을 가리키는 index 는 남기는 경우가 생길 수 있다. 그러면 그 scope 를 읽는 빌드(dev push, 그리고 dev cache 를 읽는 PR)가
`BlobNotFound`/404 로 실패한다(F5).

- **rerun 으로 회복되지 않는다.** index 가 그대로 같은 blob 을 가리키므로 다시 돌려도 같은 import 가 같은 이유로 실패한다.
- 수동 복구(런북 `docs/runbooks/deployment.md` 에 절차로 남긴다 — plan Task 4):
  1. 실패한 leg 의 scope(`api`·`batch`·`web`)를 확인한다.
  2. `gh cache list --ref refs/heads/dev --key index- --json id,key` 로 dev index entry 를 찾고, key 에 그 scope 가 들어간 entry 를
     `gh cache delete <id>` 로 지운다. scope 를 특정할 수 없으면 dev `index-*` 전부를 지운다(세 scope 가 한 번 cold build 한다).
  3. 실패한 job 을 다시 실행한다. index 가 없으므로 cache 없이 빌드해 성공한다(api·batch 기준 +113s 수준).
  4. 다음 dev push 의 export 가 새 index 와 필요한 blob 을 다시 쓴다.
- 발생 가능성을 낮추는 것: PR buildkit 무기록(D1)·PR closed cleanup(D5)·gradle stale cleanup(D6)이 저장소 총량을 한도 아래로
  유지해 LRU 축출을 줄인다. 7일 만료는 같은 scope 의 index 와 blob 이 거의 같은 시점에 접근되므로(같은 빌드가 읽는다) 둘이
  어긋나는 경우가 드물다고 본다 — [ASSUMPTION] A6.

### D7. 계약 테스트 (S5)

세 스크립트. 셋 다 `ci/quality-gate-contract-test.sh` 가 부르므로 Quality Gate compile job 이 매 PR 에 돌린다
(`ci/dependency-bootstrap-contract-test.sh` 를 부르는 기존 방식과 같다).

| 스크립트 | 무엇을 재는가 |
|---|---|
| `ci/quality-gate-contract-test.sh` (개정) | Docker matrix·조립 job 구조, cache-to 조건식, part·최종 artifact 계약, part 개수 검사, provenance(summary+로그), needs 그래프, job 단위 permissions 1개. 기존의 pinned SHA·github.sha 이름·build record 비활성·provenance 단정은 새 위치에서 유지·강화 |
| `ci/actions-cache-cleanup-contract-test.sh` (신규) | cleanup workflow 2개의 트리거·권한·checkout 안전성·스크립트 호출·concurrency, `.github/workflows/*.yml` 안에서 `actions: write` 의 위치. **매 실행마다 변형(mutation) 사본으로 자기 단정이 위반을 잡는지 확인**한다 |
| `ci/delete-actions-caches-test.sh` (신규) | fake `gh` 로 `ci/delete-actions-caches.sh` 의 선별(stale 은 gradle 만)·순서·검증·404·실패 중단·dry-run·경계 조건·로그 위생 |

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
`T_merge`, leg 로그의 `--cache-to`·`exporting to GitHub Actions Cache` 부재, 머지 뒤 첫 dev push run 의 같은 값(기록만, 판정 아님).

## 4. 비용·시간 추정

| 경로 | 현재 | 변경 후 (추정) |
|---|---|---|
| PR, cache hit | 직렬 합 ≈ 150~200s + 3회 export 40s | leg 최장(API) ≈ setup 15s + 빌드 60~75s(export 없음) + part upload 10s ≈ **90~110s**, 조립 ≈ 대기 10s + part 검사 2s + merge 30~50s → `T_docker` ≈ **130~170s** |
| PR, api 의존성 레이어 miss | 277~283s(API) + Batch·Web | API leg ≈ 15 + 113 + 56 + 10 ≈ 195s, 조립 포함 ≈ **240s** (`T_other` 중앙값 370s 이하) |
| dev push | 위와 같음 + export | leg 마다 export 20~88s 추가(병렬) — PR 경로 아님 |
| runner 분 | 1 job | leg 3개의 setup 중복(+30s) + 조립 job(≈ 1분) → run 당 약 +1.5 runner-분 |
| cache 저장량 | 11.04GB | PR buildkit 0 + dev gradle 대체 entry 정리. dev buildkit 은 7일 만료에 맡긴다(§1.1) — dev push 당 변경 레이어만큼 7일치가 쌓인다 |

[ASSUMPTION] A5 — tar 3개 합이 수백 MB 라 merge(다운로드+재업로드)가 30~50s 다. Task 6 실측으로 확인하고, 크게 벗어나면 기록한다.

트레이드오프(수용된 범위의 결과): PR 이 자기 cache 를 쓰지 않으므로 의존성 파일(`gradle.lockfile`·`build.gradle.kts`)을 바꾼
PR 은 **push 마다** 의존성 레이어를 다시 만든다(+113s, 병렬이라 경로는 위 miss 행). 이전에는 그 PR 의 첫 push 만 냈다.

## 5. 테스트 전략

| 계층 | 무엇을 | 어디서 |
|---|---|---|
| 삭제 로직 | 선별(ref·rank·cutoff 경계, stale 에서 buildkit 생존)·순서·dry-run·404·실패 중단·입력 거부·경계 조건(dev 0개·해석 불가 시간)·로그 위생·pagination | `ci/delete-actions-caches-test.sh` (fake `gh`, 고정 fixture — 벽시계 무관) |
| cleanup workflow 정적 계약 | 트리거·권한·checkout 안전성·호출 문자열 + mutation 자가검증 | `ci/actions-cache-cleanup-contract-test.sh` |
| Quality Gate 구조 | Docker matrix·조립·part 검사·artifact·needs·permissions | `ci/quality-gate-contract-test.sh` (RED → GREEN) |
| 배포 계약 회귀 | 배포 스크립트·compose 계약 | `bash docker/deploy-contract-test.sh` |
| 실제 GitHub 동작 | 시간, PR 의 cache 무기록(`--cache-to` 부재 포함), 최종 artifact 내용(tar 3개·tag·OCI revision), part 정리, provenance 로그 | PR CI run (T2/T3) |
| 머지 뒤 동작 | PR cleanup 실행(`deleted>=1` 로 권한 확인), stale cleanup dry-run·실삭제, 첫 dev push export | 머지 뒤 run (T3) |

## 6. [ASSUMPTION] · [DECISION_NEEDED] · 열린 질문

- [ASSUMPTION] A2 — `delete-merged` 는 runtime token 으로 동작해 조립 job 권한(`actions: read`)을 넓히지 않아도 된다(D3). AC6 확인.
- [ASSUMPTION] A3 — gradle entry 가 사라져도 setup-gradle 은 miss 로 진행한다(D6). AC8 확인.
- [ASSUMPTION] A4 — 복원 조회도 `last_accessed_at` 을 갱신한다(D6). 틀려도 상대 기준이 피해를 제한한다.
- [ASSUMPTION] A5 — merge 30~50s(§4). Task 6 실측.
- [ASSUMPTION] A6 — GitHub 7일 만료로 같은 scope 의 index 와 blob 이 어긋나는 일은 드물다(D6.1). 발생하면 수동 복구.
- [DECIDED 2026-11-04: 상대 기준] N1 — stale 기준점을 "dev cache 최근 접근 − 3일"(상대)로 둔다. 합의 ③-3 의 "최근 N일 미접근" 을 벽시계가
  아닌 활동 기준으로 해석한 것이다(D6 근거). 벽시계 기준을 원하면 바꾼다.
- [컨트롤러 판단 — 설계안 유지] N2 — dod AC2 의 RED 를 "mutation 사본이 기대 메시지로 실패" 로 대신한다(새 파일의 정적 계약은 구현 전에
  돌리면 "파일 없음" 실패만 나서 skill 정의상 RED 가 아니다). dod 참조.
- [DECIDED 2026-11-04: 이 dod 에 포함] N3 — 머지 뒤에만 관찰 가능한 AC7·AC8 을 이 dod 에 둔다(판정은 머지 뒤까지 `AWAITING_HUMAN`). 대안은 후속 이슈로 분리.
- [DECIDED 2026-11-04: 승인] N4 — §1.1 범위 조정(F5): 일정 cleanup 은 gradle 만, dev buildkit 은 7일 만료에 맡기고 BlobNotFound 는
  수동 복구(D6.1). 합의 ③-3 의 buildkit 일정 삭제를 빼는 것이므로 사용자 승인이 필요하다.
- [DECIDED 2026-11-04: 승인] N5 — 조립 job 에 job 단위 `permissions: actions: read` 를 둔다(D3). 기존 정책 "job 단위 permissions 금지" 의
  예외 1건이다. 대안(새 action `actions/download-artifact` 고정 후 내려받아 확인)은 새 SHA 를 들이고 권한은 같거나 크다.
- Q1 — required status check 를 쓰기 시작한다면 `7. Docker image build` 하나만 걸면 된다(D2). 현재는 branch protection 이 없다고
  기록돼 있다(`.ai/PROJECT_STATUS.md:32`).
- Q2 — 이미 닫힌 PR #106·#108·#109 의 buildkit cache(4.6GB)를 수동으로 지울지. 지우지 않으면 최장 7일(마지막 접근 기준) 동안
  저장소가 한도 근처에 머문다 — PR CI 실측(AC4) 중에도 dev 레이어 축출 위험(그리고 D6.1 의 BlobNotFound 위험)이 남는다. 수동
  삭제는 공유 상태 변경이라 사용자 승인 필요. PR ref entry 는 dev 빌드가 읽지 않으므로 지워도 안전하다.
- Q3 — "Re-run all jobs" 시 최종 artifact 이름 충돌 가능성은 현재 구조와 같다(D3, merge 에 `overwrite` 없음 — F1). 이번에 고치지 않는다.

## 7. 외부 사실 (컨트롤러 확인 2026-11-03)

원본 기록은 로컬 전용 파일이라 커밋되지 않는다 — 결과와 출처를 여기에 옮긴다. dod `### Task 0 외부 사실` 에도 같은 내용을 남긴다.

| ID | 결과 | 내용 | 출처 | 설계 반영 |
|---|---|---|---|---|
| F1 | TRUE | `actions/upload-artifact/merge@ea165f8…` 입력: `name`(필수, 기본 merged-artifacts), `pattern`(기본 `*`), `separate-directories`(기본 false = 매치된 artifact 를 같은 디렉터리에 합침), `retention-days`, `compression-level`(기본 6), `delete-merged`(기본 false), `include-hidden-files`. 출력: `artifact-id`·`artifact-url`·`artifact-digest`. **`overwrite` 입력 없음** | 해당 SHA 의 `merge/action.yml` (raw.githubusercontent.com/actions/upload-artifact/ea165f8d65b6e75b540449e92b4886f43607fa02/merge/action.yml) | D3, Q3 |
| F2 | TRUE | `actions/upload-artifact@ea165f8…` 에 `overwrite`(기본 false — false 면 같은 이름이 있을 때 실패) | 해당 SHA 의 `action.yml` | D3 part `overwrite: true` |
| F3 | LIKELY | build-push-action 은 `Util.getInputList('cache-to', {ignoreComma:true})` 로 읽어 원소마다 `--cache-to` 를 붙인다. 빈 목록이면 인자 없음. 빈 문자열 → 빈 목록 여부는 `@docker/actions-toolkit` 동작이라 미확인 | `docker/build-push-action@263435318d21b8e681c14492fe198d362a7d2c83` `src/context.ts` | D1 — PR 로그로 판정(dod AC5) |
| F4 | TRUE | gha backend `ignore-error`(cache-to, Boolean, 기본 false) "Ignore errors caused by failed cache exports." | docs.docker.com/build/cache/backends/gha/ | D1 |
| F5 | **FALSE** | blob 부재는 cache miss 가 아니라 **빌드 실패**다. gha importer `loadScope` 는 index 를 읽을 때 blob 을 확인하지 않고, `ciProvider.loadEntry` 가 blob 이 없으면 ErrNotFound 를 돌려준다. 보고된 실패 `BlobNotFound`/404 — moby/buildkit#7283(open, buildkit v0.33.1). blob key 는 전역 sha256(`buildkit-blob-1-sha256:…`)이라 api·batch·web scope 가 blob 을 공유할 수 있다 | `moby/buildkit` `cache/remotecache/gha/gha.go`, issue #7283 | §1.1, D4 rank 표, D6, D6.1 |
| F6 | TRUE | 기본 브랜치 `dev`, 저장소 PUBLIC | `gh repo view` | D1, D5, D6 |
| F7 | PARTIAL | cache 목록은 `ref`·`key`(prefix)·`sort=last_accessed_at` 를 지원, 항목 필드 `id ref key version last_accessed_at created_at size_in_bytes`. key(+ref) 기준·id 기준 삭제 endpoint 존재. fine-grained/`GITHUB_TOKEN` 권한 문구는 페이지에서 미확인 | docs.github.com/en/rest/actions/cache | D4, D5 — 머지 뒤 첫 실행 `deleted>=1` 로 확인(dod AC7) |
| F8 | TRUE | gh 2.86.0(로컬): `--paginate`, `--slurp`, `-f`(GET → query string), `--method`. runner 의 gh 버전은 다를 수 있으며 `--slurp` 는 gh 2.48 부터 | `gh api --help` | D4 |

## 8. 개정 이력

| 시점 | 변경 | 사유 |
|---|---|---|
| ④ 최초 작성 | — | — |
| ④ 개정 1 (F1~F8 확인 + ⑥ spec-reviewer 1R) | §1.1 신설·stale 을 gradle 만으로·buildkit 삭제 대안 제거·D6.1 BlobNotFound 수동 복구(rerun 으로 회복 안 됨) / D3 part 개수 검사 + 조립 job `actions: read`·rerun 창 1일·provenance 로그 출력 / F1·F2 단정 문구, F3 LIKELY·PR 로그 판정, F7 PARTIAL·AC7 로 권한 확인 / D6 경계 조건 표 / `actions: write` 범위를 `.github/workflows/*.yml` 로 한정 / §7 을 확인 결과 표로 교체 | F5 = FALSE(missing blob 은 빌드 실패) 컨트롤러 판정, 리뷰 B1·M3·M4·m5~m10 |
