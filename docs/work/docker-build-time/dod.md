---
feature: Quality Gate Docker image build 시간 단축 (issue #107)
slug: docker-build-time
status: DRAFT
frozen_at:
verdict_commit:
source: GitHub issue #107 · orchestrator ③ 합의 범위 · design.md
---

## 범위

**포함**

- Docker 3 이미지의 buildkit `cache-to` 를 dev push 에서만, PR 은 `cache-from` 만 (design D1)
- API·Batch·Web matrix 병렬 빌드 + 조립 job 이 기존 `docker-images-${{ github.sha }}` artifact 와 provenance 를 만든다 (D2, D3)
- PR closed 시 그 PR merge ref 의 모든 Actions cache 삭제 workflow (D4, D5)
- 하루 1회 + 수동 실행으로 dev 의 gradle·buildkit cache 중 활동 기준 3일 미접근 entry 삭제 workflow (D4, D6)
- `ci/quality-gate-contract-test.sh` 개정, cleanup workflow 정적 계약·삭제 스크립트 행동 계약 신규 (D7)
- PR CI 실측 기록과 머지 뒤 cleanup 동작 관찰 (D8)
- `docs/runbooks/deployment.md` 의 archive 조립 설명 갱신

**제외** *(scope creep 차단선)*

- 이미지 내용·Dockerfile 동작, 배포 파이프라인(`docker/deploy.sh`·compose), Gradle 빌드 로직, setup-gradle 설정
- Docker 외 Quality Gate job 의 구조·명령
- branch protection·required status check 설정 (저장소 밖)
- 이미 닫힌 PR #106·#108·#109 의 기존 cache 수동 삭제 (design Q3 — 별도 승인 시에만)
- `ci/quality-gate-contract-test.sh` 기존 단정 전체의 mutation 자가검증화

## 기존 gate 조사

> Gate 1 필수. 수용기준을 쓰기 전에 저장소가 이미 가진 검증 수단을 실제로 찾았다.

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있다 — `./gradlew architectureTest` | 무관 — Kotlin 소스를 건드리지 않는다 |
| lint 규칙 | 있다 — ktlint·detekt(CI `5.`), web lint(CI `6.`) | 무관. 셸 스크립트 lint(shellcheck·actionlint)는 저장소에 없다 |
| custom gradle/npm task | 있다 — `harnessCheck`(하네스 파일만), `verifyTestIsolationPolicy` 등 | 무관 — workflow·`ci/` 는 대상이 아니다 |
| CI job | 있다 — Quality Gate 8개 job, compile job 이 아래 계약 스크립트를 실행 | 회귀 방어선 + AC4~AC6 의 관찰 대상 |
| CI 계약 스크립트 | 있다 — `ci/quality-gate-contract-test.sh`(job id·순서·upload 7개·needs 0·Docker outputs 3·provenance·pinned SHA·build record 비활성 단정), `docker/deploy-contract-test.sh`, `ci/dependency-bootstrap-contract-test.sh`(fake 기반 행동 계약 선례), `ci/local-offline-contract-test.sh` | **`ci/quality-gate-contract-test.sh` 를 개정해 AC1 의 수단으로 쓴다.** 행동 계약은 `dependency-bootstrap-contract-test.sh` 의 형태를 따른다 |
| 계약 스크립트 자가검증 | 없다 — `quality-gate-contract-test.sh` 에 픽스처·negative case 없음. 현 build record 단정(:110)은 multi-line 문자열을 `grep -Fq` 로 검사해 줄 단위 OR 매치가 되는 약한 검사다 | 신규 두 스크립트는 자가검증(mutation·fake fixture)을 갖는다. :110 은 이번에 부분 문자열 비교로 강화한다 |
| workflow 정적 검사기 | 없다 — `actionlint` 미도입 | YAML 구문은 push 시 GitHub 가 거부한다(AC4 run 존재 자체가 증거) |

**결론.** 구조 계약은 개정한 `기존` 스크립트(AC1)와 `신규스크립트` 2개(AC2·AC3)로 T1 검증한다. 시간·GitHub 실제 동작은
PR·머지 뒤 run 에서만 생기는 관찰값이라 T2/T3 다.

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | Quality Gate workflow 가 (a) job id 9개를 `compile-architecture unit-coverage api-integration batch-integration static-analysis dependency-security docker-image docker-build common-integration` 순으로 갖고, (b) `docker-image` 가 `fail-fast: false`·`component: [api, batch, web]` matrix 로 build-push-action 을 정확히 1회 쓰며 file·tags·labels·outputs·cache-from 이 component·`github.sha` 로 결정되고, (c) `cache-to` 가 design D1 의 dev-push 조건식 한 줄뿐이고, (d) part 업로드가 `docker-image-part-${{ matrix.component }}-${{ github.sha }}`·retention 1·compression 0·overwrite·if-no-files-found error 이고, (e) `docker-build` 가 `needs: docker-image`·`if: ${{ !cancelled() }}`·결과 검사 step → `upload-artifact/merge`(같은 pinned SHA, name `docker-images-${{ github.sha }}`, pattern `docker-image-part-*-${{ github.sha }}`, compression 0, retention 14, delete-merged true) → provenance(run_id·commit·artifact_id → `GITHUB_STEP_SUMMARY`) 순서이며 checkout·buildx 를 쓰지 않고, (f) workflow 의 `needs:` 가 그 한 줄뿐이고, (g) `DOCKER_BUILD_RECORD_UPLOAD: 'false'` 가 `docker-image` env 에 연속 포함되고, (h) `actions/upload-artifact@` 7개·`actions/upload-artifact/merge@` 1개다. 그리고 이 스크립트가 AC2·AC3 스크립트를 실행한다 | design D1·D2·D3·D7 | `요구` 합의 ③-1·③-4·③-5 | T1 | `기존` `ci/quality-gate-contract-test.sh` (단정 개정) | `bash ci/quality-gate-contract-test.sh` | exit 0. RED: 개정 직후 현재 workflow 에 대해 `docker-image job must disable the default Docker build record artifact` 로 실패 |
| AC2 | cleanup workflow 2개가 design D5·D6 계약(트리거 블록 정확 일치, 최상위 permissions 가 `actions: write`·`contents: read` 뿐이고 job override 없음, `uses:` 는 Quality Gate 와 같은 SHA 의 checkout 1개, `persist-credentials: false`, `ref:` 없음, `github.event.pull_request.` 참조는 `.number` 뿐, `run:` 에 `${{` 없음, 스크립트 호출·env·concurrency 문자열 정확 일치, stale dispatch `dry_run` 기본값 true)을 지키고, 저장소의 `actions: write` 가 그 두 파일에만 있으며, 스크립트가 같은 실행 안에서 mutation 사본 9종(plan Task 3 M1~M9)을 각각 기대 메시지로 거부한다 | design D5·D6·D7 | `요구` 합의 ③-2·③-3·③-5·③-8 | T1 | `신규스크립트` `ci/actions-cache-cleanup-contract-test.sh` | `bash ci/actions-cache-cleanup-contract-test.sh` | exit 0, 마지막 줄 `actions cache cleanup contracts verified (mutations rejected: 9)` |
| AC3 | `ci/delete-actions-caches.sh` 가 fake `gh` 고정 fixture 에서 (a) stale 모드로 dev entry 중 rank 0~3·`last_accessed_at < 최근접근−3일` 만 rank→id 순으로 삭제하고 경계값·rank 4·타 ref entry 를 남기며, (b) dry-run 이면 DELETE 0회, (c) pr-ref 모드로 그 ref entry 전부를 rank 순으로 삭제하고 타 ref entry 는 남기며, (d) 잘못된 ref·days·DRY_RUN·GH_REPO 면 gh 호출 0회로 exit 2, (e) 404 는 already-gone 으로 exit 0, (f) 500 이면 그 자리에서 멈추고 exit 1, (g) 목록 실패면 DELETE 0회로 exit 1, (h) 출력에 `GH_TOKEN` 값이 없고 key 의 제어문자가 `?` 로 바뀌며, (i) 2 page 응답을 모두 읽는다 | design D4·D6 | `요구` 합의 ③-2·③-3 (삭제 기준·순서) + `리뷰` 로그 위생(h)은 architect 설계 검토(pull_request_target 로그 주입) | T1 | `신규스크립트` `ci/delete-actions-caches-test.sh` | `bash ci/delete-actions-caches-test.sh` | exit 0, 마지막 줄 `delete-actions-caches behavioral contract verified (cases: 11)`. RED: skeleton 스크립트에 대해 케이스 1 의 DELETE id 열 불일치로 실패 |
| AC4 | 최종 SHA 의 PR Quality Gate run 에서 모든 job 이 success 이고 `T_docker ≤ T_other` 다 (design D8 정의) | design D8 | `요구` issue #107 수용기준 "Docker 경로 최장이 테스트·검사 job 최장 이하" | T3 | `기존` CI workflow `Quality Gate` | `gh run view <run-id> --json jobs` | 전 job success, `T_docker ≤ T_other`. `T_leg` 3개·`T_merge` 를 함께 기록. 앵커 = run URL |
| AC5 | AC4 run 의 세 `7a.` leg 로그에 `exporting to GitHub Actions Cache` 가 없고, run 종료 뒤 그 PR merge ref 에 이 run 이 만든(`created_at` 이 run 시작 이후) key `index-`·`buildkit-blob-` cache 가 0개다 | design D1 | `요구` 합의 ③-1 | T2 | `기존` GitHub CLI | `gh cache list --ref refs/pull/<n>/merge --key buildkit-blob- --json id,createdAt` 와 `gh cache list --ref refs/pull/<n>/merge --key index- --json id,createdAt`, leg 별 `gh run view <run-id> --job <job-id> --log` | run 시작 이후 생성 entry 0개, 세 leg 로그에 해당 문구 0건 |
| AC6 | AC4 run 의 artifact 목록에 `docker-images-<sha>` 가 정확히 1개, `docker-image-part-` 로 시작하는 것은 0개이고, 그 archive 루트가 정확히 `api.tar`·`batch.tar`·`web.tar` 이며, 각 tar 를 load 한 이미지 tag 가 `ghcr.io/<repo>/<c>:<sha>`·`org.opencontainers.image.revision` label 이 `<sha>` 이고, `7. Docker image build` summary 의 `artifact_id` 가 그 artifact 의 id 와 같다 | design D3 | `요구` 합의 ③-4 (기존 artifact 계약 유지, 런북 의존) | T2 | `기존` GitHub CLI + docker | `gh api repos/<owner>/<repo>/actions/runs/<run-id>/artifacts`, `gh run download <run-id> -n docker-images-<sha> -D <dir>`, `docker load -i <dir>/<c>.tar`, `docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' ghcr.io/<repo>/<c>:<sha>` | 왼쪽 문장의 값과 각각 일치 |
| AC7 | 머지 뒤 PR 하나가 닫히면 `Actions cache PR cleanup` run 이 success 이고, 그 run 의 `cache-cleanup summary` 줄이 `mode=pr-ref` 이며, 종료 뒤 그 PR merge ref 의 cache 가 0개다 | design D5 | `요구` 합의 ③-2 | T3 | `기존` GitHub CLI | `gh run view <run-id> --log`, `gh cache list --ref refs/pull/<n>/merge --json id` | run success, summary 존재, 목록 `[]`. 앵커 = run URL |
| AC8 | 머지 뒤 `Actions cache stale cleanup` 을 `dry_run=true` 로 수동 실행하면 success 이고 `deleted=0`·`cutoff=` 가 찍힌 summary 를 내며, 이후 첫 schedule 실행이 success 이고, 그 다음 dev push 의 Quality Gate 세 leg 가 success 다 | design D6 | `요구` 합의 ③-3 | T3 | `기존` GitHub CLI | `gh workflow run actions-cache-stale-cleanup.yml -f dry_run=true`, `gh run view <run-id> --log`, `gh run view <dev-run-id> --json jobs` | 세 run 이 왼쪽 조건과 일치. 앵커 = run URL 3개 |

**`출처`** — `요구`(사용자 요구 원문) / `상위`(상위 동결 문서의 ID) /
`리뷰`(리뷰에서 파생) / `추론`(근거 없음).
`리뷰`와 `추론`은 Gate 1에서 같은 무게로 검토한다. — AC3 (h) 는 `리뷰` 다.

**기준 개수**: 8개

**분할하지 않는 이유**: 8개 이하라 해당 없음.

**티어 강등 사유** *(T1이 아닌 항목만. Gate 1에서 사람이 함께 승인한다)*

- AC4 → T3: GitHub-hosted runner 에서 PR 을 연 뒤에만 생기는 시간 관찰값이다. 로컬에서 재현할 수 없고 실행마다 변동한다.
  앵커 = run URL.
- AC5·AC6 → T2: 대상이 GitHub 의 cache·artifact 저장소와 run 로그라 저장소 스크립트로 고정할 수 없다. 명령은 재현 가능하다.
  run 마다 `<run-id>`·`<sha>` 가 달라 exit code 단일 명령으로 묶지 않는다.
- AC7·AC8 → T3: `pull_request_target`·`schedule` 은 기본 브랜치의 workflow 파일로만 돈다 — PR 머지 전에는 실행될 수 없다.
  이 dod 의 판정은 머지 뒤 관찰까지 `AWAITING_HUMAN` 에 머문다(design N3 — 후속 이슈 분리 대안을 Gate 1 에서 묻는다).

**RED 규칙 적용** *(design N2 — Gate 1 승인 필요)*

- AC1: 개정한 단정이 현재 workflow 에 대해 기대 메시지로 실패하는 것이 RED 다.
- AC2: 대상 workflow 파일이 이번에 새로 생긴다. 구현 전 실행은 "파일 없음" 실패만 내므로 skill 정의상 RED 가 아니다.
  대신 **스크립트가 매 실행마다 돌리는 mutation 9종이 각각 기대 메시지로 거부되는 것**을 위반 표본 증명이자 RED 로 기록한다.
- AC3: 인자 검증만 하고 삭제하지 않는 skeleton 스크립트에 대해 케이스 1 이 기대 메시지로 실패하는 것이 RED 다.

## 검사 산출물

| 스크립트 | 경로 | 정상 표본 (통과해야 함) | 위반 표본 (잡아야 함) | 증명 |
|---|---|---|---|---|
| cleanup workflow 정적 계약 | `ci/actions-cache-cleanup-contract-test.sh` | 저장소의 `.github/workflows/` (plan Task 3 의 두 파일 + quality-gate.yml) | 같은 디렉터리의 임시 사본에 mutation M1~M9(plan Task 3 표) 각각 적용 | 스크립트가 한 실행 안에서 정상 표본 통과 후 M1~M9 를 각각 실행해 실패 + 기대 메시지 포함을 확인한다. 하나라도 통과하면 스크립트 자체가 실패한다 |
| 삭제 스크립트 행동 계약 | `ci/delete-actions-caches-test.sh` | fixture F-stale·F-pr·F-paged 에 대한 기대 삭제 id 열(plan Task 2) | skeleton(삭제 안 함) 스크립트, 그리고 plan Task 2 Step 5 의 일시 변형 3종(id 만으로 정렬·ref 재확인 제거·경계 `<=`) | 변형은 커밋하지 않고 실행 결과만 증거 로그에 남긴다 |

## 회귀 방어선

이번 변경으로 깨질 수 있는 기존 동작. RED 면제 대상이다.

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 배포 계약(compose·deploy.sh·Quality Gate API/Batch/common job 명령) | `bash docker/deploy-contract-test.sh` |
| R2 | offline Gradle 설정 계약 | `bash ci/local-offline-contract-test.sh` |
| R3 | 정본 문서 검사(`docs/runbooks/deployment.md` 포함) | `bash docs/check-documentation.sh` |
| R4 | dependency bootstrap 행동 계약 | `bash ci/dependency-bootstrap-contract-test.sh` (AC1 명령이 실행) |
| R5 | Quality Gate 의 Docker 외 7개 job 이 success | AC4 run |
| R6 | 모든 workflow 의 action 이 40자리 SHA 고정 | AC1 명령(`ci/quality-gate-contract-test.sh:236-245`, 전 workflow 대상) |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

### Task 0 외부 사실 (design §7 F1~F8)

```
[PENDING]
```

### AC1

```
[PENDING]
```

### AC2

```
[PENDING]
```

### AC3

```
[PENDING]
```

### AC4

```
[AWAITING] PR Quality Gate run 이 생긴 뒤 기록한다.
```

### AC5

```
[AWAITING]
```

### AC6

```
[AWAITING]
```

### AC7

```
[AWAITING] 머지 뒤
```

### AC8

```
[AWAITING] 머지 뒤
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

## 최종 판정

> 아래 수는 전부 위 수용기준 표에서 센 값이다. 표와 어긋나면 판정을 낼 수 없다.
> 판정이 가리키는 SHA와 브랜치 최종 SHA가 다르면 그 판정은 만료다.

```
DoD VERDICT: docker-build-time @ <commit SHA>
  수용기준 표:     8개  (T1 3 · T2 2 · T3 3 · T4 0)
  T1/T2 자동:      5개 중 <p>개 PASS
  T3 기록 제출:    3개 중 <q>건
  T4 사람 확인:    0개 중 0건 완료, 0건 대기
  변경 요청:       0건
  =>
```

**사람 확인이 필요한 항목**

- Gate 1: design N1(상대 기준점), N2(AC2 RED 대체), N3(머지 뒤 AC7·AC8 포함), 티어 강등 사유
