---
feature: NOGO-0 상한·재평가 시점 승인을 owner가 결정 가능한 형태로 만든다
slug: private-live-autotrader-nogo-0
status: DRAFT
frozen_at:
verdict_commit:
source: docs/work/private-live-autotrader-phase-0/review-findings.md §1 critical("`NOGO-0` 에 상한값도 소유 실행 단위도 없다") · docs/work/private-live-autotrader/design.md §9.4 `NOGO-0` · docs/work/private-live-autotrader-nogo-0/design.md
---

## 범위

**포함**

- `NOGO-0` owner 결정 입력 8개를 `remaining-effort-estimate.md` §7과 1:1로 대응하는 결정 입력표로 고정
  (design §4)
- 각 입력의 권고 범위와 **근거 문서·절**, 유도 불가한 값의 사유 기록
- "왜 지금인가" 판정 — §4.5·§9.4의 "결과를 보기 전에" 규칙과 evidence clock 미시작의 결합, Phase 1 진입
  차단 판정 (design §2)
- "프로그램 개시 시" 문면과 현실의 불일치에 대한 판정 기록 (design §3, C안)
- 8개 값의 의존 구조와 재평가 시점의 반복 gate 성격 (design §5·§6)
- 종료 판정을 `progress.md` append로 고정 — 새 상태축 없음 (design §7)
- 상위 `plan.md` §1 gate 표에 `N0` 행, §3에 위임 한 줄 추가 (내용 계약은 design §8)
- 이 단위의 검증 러너 `verify.sh` 작성

**제외** *(명시적으로 하지 않는 것 — scope creep 차단선)*

- **8개 값 자체** — owner 결정이다. 이 계약서는 값의 존재를 요구하지 않는다
- 프로그램 총 소요 재계산 — `remaining-effort-estimate.md` §0이 산출 불가를 선언했고 §8이 이유를 적었다
- `C`(canary)·`L`(LIMITED) 기간의 범위 제시 — 상위 `design.md` §5 Phase 3이 비워 둔 값이다
- **허용 손실의 소속 판정** — 두 산출물이 갈린다. owner가 ⑦에서 정한다 (design §4 각주 ④)
- `remaining-effort-estimate.md` §7 목록의 수정
- `ACT-1` 평가 시점 확정 — `act1-evaluation-timing.md` §7의 owner 결정 3건이 소유한다
- 상위 `design.md` §9.4 문면 수정과 모체 `FROZEN` 산출물(`design.md`·`dod.md`) 변경
- 환경·보존 비용의 절대 금액과 host·storage 선택 — `ECG-1`~`ECG-3`(`plan.md` §1 `C` 행)이 소유한다
- 저장소 전역 게이트 신설과 CI workflow 변경
- 코드·schema·endpoint 변경 (design §0)

## 기존 gate 조사

> Gate 1 필수. 수용기준을 쓰기 전에 저장소가 이미 가진 검증 수단을 실제로 찾았다.

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있다 — `./gradlew architectureTest` (`architecture-tests/` 독립 source set) | 아니오. 대상이 JVM 바이트코드·모듈 의존성이라 마크다운을 보지 않는다. 이 단위는 코드 변경이 0이다 |
| lint 규칙 | 있다 — ktlint·detekt (CI job 5). **`./gradlew lint` 태스크는 이 저장소에 없다** | 아니오. Kotlin 소스 대상 |
| custom gradle task | 있다 — `unitTest`·`architectureTest`·`harnessCheck`·`harnessCheckTest`·`verifyTestIsolationPolicy`·`verifyCoverageExclusions`·`verifySecurityDependencyVersions`·`verifyMigrations`·`*:integrationTest` | 회귀 방어선으로만. 문서 대상은 `harnessCheck`뿐이고 그 범위는 `CLAUDE.md`·`.claude/**`다 |
| CI job | 있다 — 7개. `.github/workflows/quality-gate.yml` 57~59행이 `ci/quality-gate-contract-test.sh`·`ci/local-offline-contract-test.sh`·`docs/check-documentation.sh`를 실행 | **부분적.** `check-documentation.sh`는 정본 문서(`AGENTS.md`·`.ai/*`·`docs/runbooks/*`)만, `check-harness.sh`는 `CLAUDE.md`·`.claude/**`만 본다. **`docs/work/` 아래는 어느 쪽 대상도 아니다** |
| 단위 검증 러너 선례 | **있다** — `docs/work/private-live-autotrader-phase-0/verify.sh`. dod 표의 AC를 전수 열거하고, 명령이 없는 AC를 `MISSING`으로 실패시키며, T4는 `MANUAL`로 표시해 GREEN으로 세지 않는다 | **이 형태를 그대로 따른다** |
| 모체 링크 검사 | 있다 — 모체 `dod.md` `AC4`가 `docs/work/private-live-autotrader/*.md`의 상대 링크 실재를 검사한다 | 회귀 방어선. `N0` 행이 이 디렉터리를 링크하므로 다시 통과시켜야 한다 |

**결론.** `docs/work/` 아래의 문서 계약을 검사하는 **저장소 전역** gate는 없고, **만들지 않는다.** 대신
slug-local `docs/work/private-live-autotrader-nogo-0/verify.sh`를 만든다. 단위의 계약은 단위가 닫히면
끝나는데 전역 게이트로 두면 영구 유지 대상이 된다 — 단위 계약은 단위와 함께 산다. phase-0가 같은 판단으로
같은 위치에 러너를 뒀다. `AC1`~`AC3`의 티어는 **T1을 유지한다.**

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | 결정 입력표가 8행이고 `remaining-effort-estimate.md` §7과 번호·값 이름이 1:1로 같은 순서이며, 각 행의 단위·권고 범위 칸이 비어 있지 않고, 권고 범위에 수치가 있는 행은 같은 행에 근거 문서·절을, "권고 없음"인 행은 유도 불가 사유를 갖는다 | design §4. `NOGO-0`이 요구하는 것은 값이 아니라 "결과를 보기 전에 승인할 수 있는 형태"다 | `상위` | T1 | `신규스크립트` `verify.sh` | `bash docs/work/private-live-autotrader-nogo-0/verify.sh check decision-inputs` | exit 0, `decision-inputs: 8/8 rows, unmatched=[] unsourced=[]` |
| AC2 | 이 디렉터리 문서가 쓰는 대문자 상태 토큰이 전부 참조 코퍼스에 이미 있는 이름이다. 즉 새 상태 이름을 도입하지 않는다 | design §7. 종료 판정을 새 축 없이 `progress.md` append로 처리한다는 결정이 문서에서 깨지지 않아야 한다 | `요구` (③ 사용자 합의 — "새 상태축을 만들지 않는다") | T1 | `신규스크립트` `verify.sh` | `bash docs/work/private-live-autotrader-nogo-0/verify.sh check state-vocabulary` | exit 0, `state-vocabulary: unknown=[]` |
| AC3 | 상위 `plan.md` §1 gate 표에 `N0` 행이 있고 `C` 행보다 앞에 있으며, 그 행 안에 반복 gate 표기와 `progress.md` 종료 판정이 있다. §3에도 `NOGO-0` 위임 항목이 있다 | design §8. `Z2`가 이미 `NOGO-0` 재평가 결과를 참조하는데 소유 행이 없고, `review-findings.md` §1이 §1·§3 둘 다를 지적했다 | `리뷰` | T1 | `신규스크립트` `verify.sh` | `bash docs/work/private-live-autotrader-nogo-0/verify.sh check owner-row` | exit 0, `owner-row: present, before C, repeating, delegated` |
| AC4 | 모체 `FROZEN` 산출물 2종(`design.md`·`dod.md`)이 이 브랜치에서 변경되지 않았다 | 모체 `dod.md` `status: FROZEN`, `definition-of-done` 절대규칙 3 | `상위` | T1 | `기존` `git diff --quiet` | `git diff --quiet origin/dev -- docs/work/private-live-autotrader/design.md docs/work/private-live-autotrader/dod.md` | exit 0 |
| AC5 | owner가 design §2(Phase 1 진입 차단)·§3(C안)·§7(새 축 없음) 세 판정과 `[DECISION_NEEDED-4]`(허용 손실 소속)를 판정하고 이 계약서가 `FROZEN`으로 전이한다 | `feature-workflow` ⑦. 네 판정 모두 모체 동결 계약의 해석을 바꾸므로 AI 단독 확정 대상이 아니다 | `요구` | T4 | `사람` owner 승인 기록 | — | `status: FROZEN` + `frozen_at` 기입, 앵커는 승인 커밋 SHA |
| AC6 | owner가 8개 값(또는 회차 판정)과 승인 시각을 `progress.md`에 append한다 | 상위 `design.md` §9.4 "상한값과 재평가 시점은 결과를 보기 전에 승인" · §4.2 "승인 근거는 `progress.md`가 소유한다" | `상위` | T4 | `사람` `progress.md` append | — | append 커밋 SHA. **이 항목은 `AWAITING_HUMAN`으로 남는다** |

**`출처`** — `요구`(사용자 요구 원문) / `상위`(상위 동결 문서의 ID) / `리뷰`(리뷰에서 파생) / `추론`(근거 없음).

> **이 표의 어떤 칸에도 `|` 를 쓰지 않는다.** `verify.sh`가 `awk -F'|'`로 파싱하며 `$2`=AC id, `$6`=티어,
> `$8`=검증 명령에 결속된다. 칸에 파이프를 넣으면 필드가 밀려 러너가 엉뚱한 칸을 명령으로 실행한다.
> 검증 명령 칸은 **백틱 쌍이 정확히 하나**여야 한다.

> **AC1은 승인값을 요구하지 않는다.** 결정 입력표의 `값` 칸은 결정 대상의 **이름**이고(design §4 각주 ①),
> 검사는 그 이름이 `remaining-effort-estimate.md` §7과 일치하는지만 본다. 승인값은 그 표가 아니라
> `progress.md` append가 담는다.

> **AC2의 참조 코퍼스는 네 파일이다.**
>
> | 파일 | 소유하는 어휘 |
> |---|---|
> | `docs/work/private-live-autotrader/design.md` | 프로그램 상태축 (`COLLECTION_NOT_READY` 등) |
> | `.claude/skills/definition-of-done/SKILL.md` | 계약서 lifecycle (`FROZEN`·`VERIFIED`·`AWAITING_HUMAN`) |
> | `.claude/skills/definition-of-done/template.dod.md` | 계약서 초기 상태 (`DRAFT`) |
> | `docs/work/private-live-autotrader-phase-0/verify.sh` | 러너 판정값 (`MISSING`·`MANUAL` 등) |
>
> 코퍼스를 모체 `design.md` 하나로 두면 계약서 lifecycle과 러너 판정 어휘가 전부 오탐이 된다. 넷 다 이
> 저장소가 이미 쓰는 어휘이고, 이 단위가 **새로 만든** 이름이 아니라는 것이 AC2가 보려는 것이다.

> **AC4의 baseline은 `origin/dev`다.** 두 파일은 `FROZEN`이므로 다른 작업자도 건드리지 않는 것이 전제다.
> `origin/dev`가 이 branch 분기점보다 앞서 있고 누군가 그 파일을 바꿨다면 이 명령은 그 변경까지 잡는다.
> 그것은 오탐이 아니라 **동결 위반의 탐지**이므로 baseline을 느슨하게 바꾸지 않는다.

> **AC6이 이 단위를 막지 않는다.** 나머지 5개가 통과하면 판정은 `AWAITING_HUMAN`이고, 그 상태에서
> "완료"라는 단어를 쓰지 않는다. AC6을 "값이 채워졌다"로 쓰지 않은 이유는 그렇게 쓰면 owner 응답 없이
> 이 단위가 영원히 닫히지 않기 때문이다 — 나머지 기준은 전부 **형태**만 본다.

**기준 개수**: 6개

**분할하지 않는 이유**: 해당 없음 (6 ≤ 8)

**티어 강등 사유** *(T1이 아닌 항목만. Gate 1에서 사람이 함께 승인한다)*

- AC5·AC6 → T4: 강등이 아니라 본래 티어다. 상위 `design.md` §4.2가 위험을 늘리는 방향의 선언을
  **사용자 승인으로만** 하도록 규정하고 §9.4가 "승인"을 명시한다. 기계가 대신 판정하면 그 규정이 무의미해진다.
  앵커는 이 파일 밖(승인 커밋 SHA, `progress.md` append 커밋 SHA)에 둔다.
- AC1~AC3: 강등 없음. T1을 유지한다.

## 검사 산출물

> `신규스크립트`를 검증 수단으로 쓴다. 스크립트는 이 계약서가 아니라 **저장소에 있고, 자기 수용기준을 갖는다.**
> 형태는 `docs/work/private-live-autotrader-phase-0/verify.sh`를 따른다 — 표의 AC 전수 열거, 명령 없는 AC는
> `MISSING` 실패, T4는 `MANUAL`로 GREEN에서 제외.

**경로**: `docs/work/private-live-autotrader-nogo-0/verify.sh`

**인터페이스**

```text
bash docs/work/private-live-autotrader-nogo-0/verify.sh                  # 최종 판정
bash docs/work/private-live-autotrader-nogo-0/verify.sh --pre-approval   # T4 미해결 허용
bash docs/work/private-live-autotrader-nogo-0/verify.sh --static         # T1 만
bash docs/work/private-live-autotrader-nogo-0/verify.sh AC1 AC3          # 지정 AC 만
bash docs/work/private-live-autotrader-nogo-0/verify.sh check <name>     # 개별 check. 표를 파싱하지 않는다
bash docs/work/private-live-autotrader-nogo-0/verify.sh --root <dir> check <name>
```

`<name>` ∈ `decision-inputs` · `state-vocabulary` · `owner-row` · `master-links`

**각 check가 보는 것**

| check | 대상 | 단정 |
|---|---|---|
| `decision-inputs` | 이 디렉터리 `design.md` §4 표 ↔ `docs/work/private-live-autotrader/remaining-effort-estimate.md` §7 표 | ① 양쪽 8행 ② 번호가 1~8 순서 ③ 값 이름이 양쪽에서 문자열 일치 ④ 단위·권고 범위 칸이 비지 않음 ⑤ 권고 범위가 "권고 없음"을 포함하면 유도 불가 사유 칸이, 포함하지 않으면 근거 문서·절 칸이 비지 않음 |
| `state-vocabulary` | 이 디렉터리 `*.md`의 백틱 토큰 중 대문자·밑줄 4자 이상 | 각 토큰이 참조 코퍼스 **네 파일** 중 하나에 문자열로 존재한다 |
| `owner-row` | `docs/work/private-live-autotrader/plan.md` | ① `N0` 행 존재 ② `N0` 행이 `C` 행보다 위 ③ `N0` 행에 "반복" ④ `N0` 행에 `progress.md` ⑤ §3에 `NOGO-0` 위임 항목 |
| `master-links` | `docs/work/private-live-autotrader/*.md`의 상대 링크 | 모든 링크 대상이 실재한다 (모체 `dod.md` `AC4`의 회귀 방어) |

**위반 검출 증명**

| check | 정상 표본 (통과해야 함) | 위반 표본 (잡아야 함) | 증명 |
|---|---|---|---|
| `decision-inputs` | 현재 트리의 `design.md` §4 표 | ① 한 행의 근거 문서·절과 유도 불가 사유를 모두 비운 `--root` 사본 ② 8행을 7행으로 줄인 사본 ③ 2행과 3행의 값 이름을 맞바꾼 사본 | 증거 로그 AC1 |
| `state-vocabulary` | 현재 트리의 이 디렉터리 `*.md` | 참조 코퍼스에 없는 상태 이름을 백틱으로 감싸 본문에 넣은 `--root` 사본 | 증거 로그 AC2 |
| `owner-row` | `N0` 행과 §3 항목이 추가된 상위 `plan.md` | ① `N0` 행을 지운 `--root` 사본 ② `N0` 행을 `Z2` 뒤로 옮긴 사본 ③ `N0` 행에서 "반복" 표기를 지운 사본 ④ §3 항목을 지운 사본 | 증거 로그 AC3 |
| `master-links` | 현재 트리 | 상위 `plan.md`에 실재하지 않는 대상을 가리키는 링크를 넣은 `--root` 사본 | 증거 로그 회귀 방어선 |

> 위 위반 표본의 예시 토큰을 이 문서 본문에 **백틱으로 적지 않는다.** 적으면 `state-vocabulary`가 이
> 계약서 자신을 위반으로 잡는다. 검사 대상에 이 디렉터리의 `*.md` 전부가 들어가기 때문이다.

**CI 등록은 하지 않는다.** 러너의 대상이 이 단위의 문서 계약이고 repository 빌드 산출물이 아니다. 전역
게이트로 올리면 무관한 PR 전부를 이 프로그램 문서 상태에 묶는다. 재실행 트리거는 `N0` 행이 소유한다 —
재평가 회차마다 `progress.md` append와 함께 러너 실행 결과를 남긴다(design §8.3).

## 회귀 방어선

이번 변경으로 깨질 수 있는 기존 동작. RED 면제 대상이다.

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 정본 문서 무결성 (`AGENTS.md`·`.ai/*`·`docs/runbooks/*`의 링크·placeholder·낡은 서술) | `bash docs/check-documentation.sh` |
| R2 | 하네스 정합성 (`CLAUDE.md`·`.claude/**`의 참조·frontmatter·계약 단정) | `bash docs/check-harness.sh` |
| R3 | 코드·아키텍처 경계가 이 문서 변경에 영향받지 않음 | `./gradlew test architectureTest --offline --no-daemon` |
| R4 | 상위 `plan.md` 편집 후에도 모체 `dod.md` `AC4`의 링크 실재 계약이 유지됨 | `bash docs/work/private-live-autotrader-nogo-0/verify.sh check master-links` |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.
>
> **AC1·AC2의 RED 처리.** 이 단위는 ④ 스펙 작성이 곧 산출물이라 러너를 만드는 시점에 이미 `design.md`
> §4 표와 본문이 존재한다. 따라서 AC1·AC2의 RED는 "구현 전 실패"가 아니라 **위반 표본의 exit 1**로
> 대체한다. 그 실행 없이는 AC1·AC2를 충족으로 처리하지 않는다.
> AC3은 `N0` 행이 아직 없으므로 **진짜 RED가 존재한다** — 러너 완성 직후 실행해 실패를 남긴다.

### AC1

```
(작성 예정)
```

### AC2

```
(작성 예정)
```

### AC3

```
(작성 예정)
```

### AC4

```
(작성 예정)
```

### 회귀 방어선

```
(작성 예정)
```

## 사람 확인 (T4)

> 판정 주체는 사람뿐이다. AI가 이 표를 채우지 않는다.
> 앵커는 이 파일 밖에 있어야 한다 — PR 코멘트 URL, 커밋 SHA, 이슈 링크.
> `verify.sh`는 이 절만 보고 T4 해결 여부를 판정한다. 앵커 칸이 비어 있으면 미해결이다.

| # | 확인 사항 | 확인자 | 날짜 | 앵커 |
|---|---|---|---|---|
| AC5 | design §2·§3·§7 세 판정과 `[DECISION_NEEDED-4]` 판정, `FROZEN` 전이 | | | |
| AC6 | 8개 값(또는 회차 판정)과 승인 시각의 `progress.md` append | | | |

## 변경 요청

> 동결 후 기준을 바꿔야 할 때만 작성. 사람이 승인하기 전까지 구현을 중단한다.

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|

## 최종 판정

> 아래 수는 전부 위 수용기준 표에서 센 값이다. 표와 어긋나면 판정을 낼 수 없다.
> 판정이 가리키는 SHA와 브랜치 최종 SHA가 다르면 그 판정은 만료다.

```
DoD VERDICT: private-live-autotrader-nogo-0 @ <commit SHA>
  수용기준 표:     6개  (T1 4 · T2 0 · T3 0 · T4 2)
  T1/T2 자동:      4개 중 <p>개 PASS
  T3 기록 제출:    0개 중 0건
  T4 사람 확인:    2개 중 <r>건 완료, <2-r>건 대기
  변경 요청:       0건
  =>
```

**사람 확인이 필요한 항목**

- AC5 — owner가 design §2·§3·§7 판정과 `[DECISION_NEEDED-4]`를 판정하고 이 계약서를 `FROZEN`으로 전이
- AC6 — owner가 8개 값과 승인 시각을 `progress.md`에 append (**이 단위는 여기서 `AWAITING_HUMAN`으로 남는다**)
