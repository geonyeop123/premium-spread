# `NOGO-0` 상한·재평가 시점 승인 — 실행 계획

| 항목 | 값 |
|---|---|
| 문서 역할 | `feature-workflow` ⑤ plan 문서 |
| slug | `private-live-autotrader-nogo-0` |
| 스펙 | [`design.md`](design.md) |
| 완료 기준 계약서 | [`dod.md`](dod.md) |
| base branch | `dev` |
| branch | `docs/private-live-autotrader-nogo-0` |

## 0. 이 계획의 밀도 계약

이 단위는 **코드 산출물이 0이다**([`design.md`](design.md) §0). 따라서 ⑤가 요구하는 "전문으로 쓸 코드
계약"을 아래로 사상한다.

| 전문으로 쓴다 (태스크 간 결합점) | 요건 목록까지만 쓴다 |
|---|---|
| 파일 경로 | 산문 문단의 문장 표현 |
| 표의 행·열 머리글과 삽입 위치(어느 표, 어느 행 앞) | 검증 러너의 awk·sed 구현부 |
| 식별자 — `N0`, `AC1`~`AC6`, check 이름 | |
| 근거 절 참조 문자열 | |
| 러너가 파싱할 표 행 형식과 awk 필드 인덱스 | |
| 러너의 출력 형식과 종료 코드 | |
| 상위 문서에 삽입할 행·문단·불릿의 **전문** | |

**이웃 태스크가 참조하는 이름은 어느 태스크에도 정의돼 있어야 한다.** 이 문서에서 정의되지 않은 표 열,
check 이름, 파일 경로를 참조하지 않는다.

## 1. 태스크 개요

| # | 태스크 | 산출물 | 의존 | 상태 |
|---|---|---|---|---|
| T1 | 스펙·계약서·README 작성 | `design.md` · `dod.md` · `README.md` | — | **완료** |
| T2 | 검증 러너 작성 | `verify.sh` | T1 | 대기 |
| T3 | RED 기록 | `dod.md` 증거 로그 | T2 | 대기 |
| T4 | 위반 표본으로 검출 증명 | `dod.md` 증거 로그 | T2 | 대기 |
| T5 | 상위 `plan.md` 편집 | `../private-live-autotrader/plan.md` | T3 | 대기 |
| T6 | GREEN 기록과 회귀 방어선 실행 | `dod.md` 증거 로그 | T4 · T5 | 대기 |
| T7 | 최종 판정 기입 | `dod.md` 최종 판정 블록 | T6 | 대기 |

T3이 T5보다 앞인 것이 핵심이다. `owner-row` check의 **진짜 RED**는 `N0` 행이 없는 지금만 관찰할 수 있다.
T5를 먼저 하면 그 RED를 영영 기록할 수 없고 `AC3`은 `definition-of-done`의 RED-first 규칙에 따라 미충족이 된다.

---

## 2. 태스크

### T1 — 스펙·계약서·README 작성 **[완료]**

**파일**

- `docs/work/private-live-autotrader-nogo-0/design.md`
- `docs/work/private-live-autotrader-nogo-0/dod.md`
- `docs/work/private-live-autotrader-nogo-0/README.md`

**산출 요약** — `design.md` §4가 owner 결정 입력 8개를 `remaining-effort-estimate.md` §7과 1:1로 고정하고,
§8이 상위 `plan.md`에 넣을 내용 계약을 고정했다. `dod.md`는 `status: DRAFT`이며 수용기준 6개(T1 4 · T4 2)를 갖는다.

---

### T2 — 검증 러너 작성

**파일**: `docs/work/private-live-autotrader-nogo-0/verify.sh` (신규, 실행 권한 불필요 — `bash`로 호출한다)

#### 2-1. 상수 (전문)

```bash
ROOT=$(git rev-parse --show-toplevel)          # --root <dir> 로 덮어쓴다
UNIT="$ROOT/docs/work/private-live-autotrader-nogo-0"
MASTER="$ROOT/docs/work/private-live-autotrader"
DOD="$UNIT/dod.md"
SPEC="$UNIT/design.md"
EST="$MASTER/remaining-effort-estimate.md"
MPLAN="$MASTER/plan.md"
CORPUS=(
  "$MASTER/design.md"                                         # 프로그램 상태축
  "$ROOT/.claude/skills/definition-of-done/SKILL.md"          # 계약서 lifecycle
  "$ROOT/.claude/skills/definition-of-done/template.dod.md"   # 계약서 초기 상태
  "$ROOT/docs/work/private-live-autotrader-phase-0/verify.sh" # 러너 판정값
)
```

참조 코퍼스의 네 번째 항목이 phase-0 러너인 이유는 이 단위의 문서가 그 러너의 판정값을 인용하기 때문이다.
그것들은 이 단위가 새로 만든 이름이 아니다 — `dod.md` `AC2` 주석의 표가 파일별 소유 어휘를 적는다.

**도구 제약**: `grep` · `awk` · `sed` · `git` 만 쓴다. `python3`에 의존하지 않는다 — 이 러너는 owner가
재평가 회차마다 직접 돌리는 것이고(`design.md` §8.3) 의존이 적을수록 그 약속이 지켜진다.

#### 2-2. 표 파싱 계약 (전문)

```text
[dod 수용기준 행]
  행 정규식   ^\| AC[0-9]+ \|
  awk -F'|'   $2 = AC id      $6 = 티어      $8 = 검증 명령
  결속 대상   dod.md 수용기준 표의 열 순서
              # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건

[dod 사람 확인 행]  — '## 사람 확인 (T4)' 절 안으로 한정한다
  행 정규식   ^\| AC[0-9]+ \|
  awk -F'|'   $2 = AC id      $6 = 앵커
  결속 대상   # | 확인 사항 | 확인자 | 날짜 | 앵커
  판정        앵커가 공백이 아니면 해결

[design §4 결정 입력 행]  — '## 4. owner 결정 입력표' ~ 다음 '## ' 구간으로 한정한다
  행 정규식   ^\| [0-9] \|
  awk -F'|'   $2 = #   $3 = 값   $4 = 단위   $5 = 권고 범위   $6 = 근거 문서·절   $7 = 유도 불가 시 사유

[remaining-effort-estimate §7 행]  — '## 7. ' ~ 다음 '## ' 구간으로 한정한다
  행 정규식   ^\| [0-9] \|
  awk -F'|'   $2 = #   $3 = 값   $4 = 영향
```

**절 한정이 필수다.** phase-0 러너 주석이 같은 함정을 기록했다 — 구간을 한정하지 않으면 수용기준 표의
`| ACn |` 행이 사람 확인 행으로 오인돼 **모든 T4가 "기록됨"으로 오판된다.** 같은 이유로 `^\| [0-9] \|`도
두 파일의 다른 표를 집을 수 있다.

#### 2-3. 서브커맨드 (전문)

```text
verify.sh                      최종 판정. T4 미해결이면 RED
verify.sh --pre-approval       T4 미해결을 PENDING 으로 허용 (최종 판정으로 쓸 수 없다)
verify.sh --static             T1 만 실행
verify.sh AC1 AC3              지정한 AC 만. 표에 없는 id 를 주면 exit 2
verify.sh check <name>         개별 check. **표를 파싱하지 않는다** (자기 재귀 방지)
verify.sh --root <dir> check <name>

<name> ∈ decision-inputs | state-vocabulary | owner-row | master-links
알 수 없는 <name> 은 exit 2
```

#### 2-4. check 단정과 출력 (전문)

```text
decision-inputs
  ① SPEC §4 행이 8개,  EST §7 행이 8개
  ② 양쪽 $2 가 1..8 순서
  ③ 같은 번호 행끼리 trim($3) 문자열 일치            → 불일치는 unmatched 에 번호 누적
  ④ SPEC 행의 $4·$5 가 공백이 아님                   → 위반은 unsourced 에 번호 누적
  ⑤ $5 가 '권고 없음' 을 포함하면 $7 이, 포함하지 않으면 $6 이 공백이 아님
                                                     → 위반은 unsourced 에 번호 누적
  출력  decision-inputs: 8/8 rows, unmatched=[] unsourced=[]

state-vocabulary
  ① UNIT 의 *.md 전 줄에서 백틱 쌍 안의 토큰을 뽑는다
  ② 그중 ^[A-Z][A-Z_]{3,}$ 인 것만 남긴다
  ③ 각 토큰이 참조 코퍼스 네 파일 중 하나에 grep -qF 로 존재하면 통과
  출력  state-vocabulary: unknown=[]

owner-row
  ① MPLAN 에 ^\| N0 \| 행이 있다                      없으면 missing N0 row
  ② MPLAN 에 ^\| C \| 행이 있다                       없으면 missing C row
  ③ N0 행 번호 < C 행 번호                            아니면 N0 not before C
  ④ N0 행이 '반복' 을 포함한다                        아니면 no repeating marker
  ⑤ N0 행이 'progress.md' 를 포함한다                 아니면 no progress.md closure
  ⑥ '## 3. 이 plan이 결정하지 않는 것' 절에 'NOGO-0' 을 포함한 불릿이 있다
                                                      아니면 not delegated in section 3
  출력  owner-row: present, before C, repeating, delegated

master-links
  ① MASTER/*.md 의 모든 ](target) 에서 target 추출
  ② http:// https:// mailto: 로 시작하면 건너뛴다
  ③ '#' 이후를 잘라낸다
  ④ 그 파일의 디렉터리 기준으로 실재하는지 test -e
  출력  master-links: 0 broken
```

#### 2-5. 러너 모드의 계상 규칙 (전문 — 이 계약이 이 태스크의 핵심이다)

```text
AC 행마다 한 줄:   printf '%-6s %-7s %s\n' <id> <판정> <요약>

판정값
  GREEN    비 T4 이고 명령이 exit 0
  RED      비 T4 이고 명령이 exit != 0,  또는 T4 인데 앵커가 비어 있고 --pre-approval 이 아님
  MISSING  비 T4 인데 $8 에서 실행 가능한 명령을 뽑지 못함
  MANUAL   T4 이고 앵커가 채워짐
  PENDING  T4 이고 앵커가 비었으나 --pre-approval
  SKIP     --static 이고 티어가 T1 이 아님

요약 블록
  GREEN=<n> RED=<n> MISSING=<n> MANUAL=<n> SKIP=<n>
  표의 AC 총 <total>개 중 <accounted>개 계상

종료 코드
  0  RED=0 이고 MISSING=0
  1  RED 또는 MISSING 이 1 이상
  2  사용법 오류 · 파일 부재 · 표에 없는 AC id · 알 수 없는 check 이름
  3  accounted != total   (계상 누락 — 러너가 표의 AC 를 전부 다루지 못했다)
```

**`MANUAL`과 `PENDING`을 GREEN으로 세지 않는다.** 이 단위는 `AC6`이 `AWAITING_HUMAN`으로 남는 것이
정상이므로(`dod.md`), T4를 GREEN에 섞으면 러너가 "완료"라고 거짓말한다.

**`MISSING`을 조용히 건너뛰지 않는다.** phase-0 초안이 명령 블록 없는 AC 9개를 건너뛰고 GREEN을 보고한
전례가 있다. 총수 대조(exit 3)가 그 재발을 막는 장치다.

**명령 추출**: `$8` 칸에서 백틱 쌍 안의 내용을 그대로 실행한다. `dod.md`의 검증 명령 칸은 백틱 쌍이 정확히
하나라고 계약돼 있다.

**실행 명령**

```bash
bash docs/work/private-live-autotrader-nogo-0/verify.sh --help
```

**예상 결과** — 사용법 출력, `exit 2`. (`--help`는 사용법 오류와 같은 경로로 처리한다.)

---

### T3 — RED 기록

**파일**: `docs/work/private-live-autotrader-nogo-0/dod.md` `## 증거 로그` 절

**전제**: 상위 `plan.md`를 **아직 편집하지 않았다.** T5보다 먼저 수행한다.

**실행 명령**

```bash
bash docs/work/private-live-autotrader-nogo-0/verify.sh check owner-row
```

**예상 결과**

```text
owner-row: missing N0 row
```

`exit 1`. 이것이 `AC3`의 RED다 — 파일 부재나 문법 오류가 아니라 **기대한 단정이 기대한 이유로 틀린** 실패다.

**기록 형식** — `## 증거 로그` `### AC3` 블록에 아래 형식으로 append한다.

```text
[RED] <날짜> <commit>
$ bash docs/work/private-live-autotrader-nogo-0/verify.sh check owner-row
owner-row: missing N0 row
(exit 1)
```

---

### T4 — 위반 표본으로 검출 증명

**파일**: `docs/work/private-live-autotrader-nogo-0/dod.md` `## 증거 로그` 절

`AC1`·`AC2`는 T1에서 이미 충족된 상태라 진짜 RED를 만들 수 없다. 대신 **검사기가 위반을 실제로 잡는지**를
증명한다. `dod.md` `## 검사 산출물`의 위반 표본 표가 이 태스크의 계약이다.

**표본 준비 (전문)**

```bash
SAMPLE=$(mktemp -d)
mkdir -p "$SAMPLE/docs/work/private-live-autotrader-nogo-0" \
         "$SAMPLE/docs/work/private-live-autotrader" \
         "$SAMPLE/docs/work/private-live-autotrader-phase-0" \
         "$SAMPLE/.claude/skills/definition-of-done"
cp docs/work/private-live-autotrader-nogo-0/*.md "$SAMPLE/docs/work/private-live-autotrader-nogo-0/"
cp docs/work/private-live-autotrader/*.md        "$SAMPLE/docs/work/private-live-autotrader/"
cp docs/work/private-live-autotrader-phase-0/verify.sh \
   "$SAMPLE/docs/work/private-live-autotrader-phase-0/"
cp .claude/skills/definition-of-done/SKILL.md .claude/skills/definition-of-done/template.dod.md \
   "$SAMPLE/.claude/skills/definition-of-done/"
```

`--root` 사본에도 참조 코퍼스 네 파일이 모두 있어야 한다. 하나라도 빠지면 `state-vocabulary`가 **표본
결함으로** 실패하고, 그 실패는 검출 증명이 아니다.

**각 표본의 편집과 기대 결과**

| # | check | 사본에 가할 편집 | 기대 출력 | 기대 exit |
|---|---|---|---|---|
| 1 | `decision-inputs` | `design.md` §4 표 5행의 `근거 문서·절`·`유도 불가 시 사유` 두 칸을 비운다 | `unsourced=[5]` | 1 |
| 2 | `decision-inputs` | `design.md` §4 표 8행을 삭제한다 | `7/8 rows` | 1 |
| 3 | `decision-inputs` | `design.md` §4 표 2행과 3행의 `값` 칸을 맞바꾼다 | `unmatched=[2,3]` | 1 |
| 4 | `state-vocabulary` | `design.md` 본문 아무 곳에 코퍼스에 없는 대문자 상태 이름을 백틱으로 감싸 넣는다 | `unknown=[...]` | 1 |
| 5 | `owner-row` | (T5 완료 후) `plan.md`의 `N0` 행을 삭제한다 | `missing N0 row` | 1 |
| 6 | `owner-row` | (T5 완료 후) `N0` 행을 `Z2` 행 뒤로 옮긴다 | `N0 not before C` | 1 |
| 7 | `owner-row` | (T5 완료 후) `N0` 행에서 "반복" 두 글자를 지운다 | `no repeating marker` | 1 |
| 8 | `owner-row` | (T5 완료 후) `plan.md` §3의 `NOGO-0` 불릿을 삭제한다 | `not delegated in section 3` | 1 |
| 9 | `master-links` | `plan.md`에 실재하지 않는 대상을 가리키는 마크다운 링크를 추가한다 | `1 broken` | 1 |

**실행 명령 (표본 1 예시)**

```bash
bash docs/work/private-live-autotrader-nogo-0/verify.sh --root "$SAMPLE" check decision-inputs
```

**정상 표본 대조** — 편집하지 않은 사본에서 같은 명령이 `exit 0`이어야 한다. 위반만 잡고 정상을 통과시키는
것까지가 증명이다.

표본 5~8은 T5 완료 뒤에 수행한다. 사본을 그때 다시 뜬다.

**정리**

```bash
rm -rf "$SAMPLE"
```

---

### T5 — 상위 `plan.md` 편집

**파일**: `docs/work/private-live-autotrader/plan.md`

상위 `plan.md`는 모체 `dod.md`가 동결한 대상이 **아니다**(`design.md` §8.5). 모체 `design.md`와 `dod.md`는
이 태스크에서도 **건드리지 않는다.**

#### 5-1. §1 gate 표에 `N0` 행 삽입

**삽입 위치** — §1의 **두 번째** 표(`| # | 실행 단위 | 진입 상태 | owner | 산출물 | 종료 판정 |`)에서
구분선 `|---|---|---|---|---|---|` 바로 다음, 즉 **`| C | Collection 시작 |` 행 앞**이다.

**삽입할 행 (전문)**

```text
| N0 | Program 재평가 (`NOGO-0`) — **반복 gate** | 최초: Phase 1 진입 전이며 evidence collection이 `COLLECTION_NOT_READY`. 이후: 승인된 재평가 시점 도달마다 | owner | [결정 입력 8개](../private-live-autotrader-nogo-0/design.md)의 승인값·승인 시각, 회차의 계속·축소·종결 판정 | 상태축 전이 없음 — `progress.md` append로 종료. 종결이면 `Z2`로 넘긴다 |
```

실행 단위 칸이 `Program 재평가`를 포함하는 것은 모체 `design.md` §8 traceability가 `NOGO-0`의 책임을 그
문자열로 적었기 때문이다(`design.md` §8.2). 바꾸면 `review-findings.md`의 지적이 다시 열린다.

#### 5-2. 표 아래 문단 삽입

**삽입 위치** — 위 표 바로 다음, 기존 문단 *"각 gate 실행 단위는 진입 전에 자신의 판정 기준을 …"* **앞**에
빈 줄을 두고 넣는다.

**삽입할 문단 (전문)**

```text
`N0`만 반복 gate다. 나머지 행은 1회성 전이여서 통과하면 상태축이 움직이지만, `N0`는 승인된 재평가 시점마다
반복되고 상태축을 움직이지 않는다. `Z2` 행이 진입 트리거로 참조하는 "`NOGO-0` 재평가 결과"의 소유 행이
`N0`다. 각 재평가 회차는 `progress.md` append와 함께
`bash docs/work/private-live-autotrader-nogo-0/verify.sh` 실행 결과를 남긴다.
```

#### 5-3. §3에 위임 불릿 추가

**삽입 위치** — `## 3. 이 plan이 결정하지 않는 것`의 마지막 불릿
*"- activation 정책값(기간, 표본 수, 허용 손실) — `design.md` §4.5가 사용자 승인으로 고정한다"* **다음 줄**.

**삽입할 불릿 (전문)**

```text
- `NOGO-0` 비용·기간 상한값과 재평가 시점 — §1 `N0` 실행 단위가 owner 승인으로 고정한다
```

넣는 이유는 `design.md` §8.4가 소유한다 — §3은 미결정 목록이 아니라 **위임 등기부**이고,
`review-findings.md`가 §1과 §3 둘 다 비었다고 지적했으므로 한쪽만 채우면 지적의 절반이 남는다.

#### 5-4. 검증

**실행 명령**

```bash
bash docs/work/private-live-autotrader-nogo-0/verify.sh check owner-row
```

**예상 결과**

```text
owner-row: present, before C, repeating, delegated
```

`exit 0`.

**실행 명령**

```bash
bash docs/work/private-live-autotrader-nogo-0/verify.sh check master-links
```

**예상 결과**

```text
master-links: 0 broken
```

`exit 0`. 새로 넣은 `../private-live-autotrader-nogo-0/design.md` 링크가 실재하는지를 본다. 이것이
모체 `dod.md` `AC4`(상위 디렉터리 상대 링크 실재)의 회귀 방어선 `R4`다.

---

### T6 — GREEN 기록과 회귀 방어선 실행

**파일**: `docs/work/private-live-autotrader-nogo-0/dod.md` `## 증거 로그` 절

**실행 명령과 예상 결과**

| 대상 | 명령 | 예상 |
|---|---|---|
| AC1 | `bash docs/work/private-live-autotrader-nogo-0/verify.sh check decision-inputs` | `decision-inputs: 8/8 rows, unmatched=[] unsourced=[]` · exit 0 |
| AC2 | `bash docs/work/private-live-autotrader-nogo-0/verify.sh check state-vocabulary` | `state-vocabulary: unknown=[]` · exit 0 |
| AC3 | `bash docs/work/private-live-autotrader-nogo-0/verify.sh check owner-row` | `owner-row: present, before C, repeating, delegated` · exit 0 |
| AC4 | `git diff --quiet origin/dev -- docs/work/private-live-autotrader/design.md docs/work/private-live-autotrader/dod.md` | 출력 없음 · exit 0 |
| R1 | `bash docs/check-documentation.sh` | `documentation check passed` · exit 0 |
| R2 | `bash docs/check-harness.sh` | `하네스 검사 통과: 파일 N개` · exit 0 |
| R3 | `./gradlew test architectureTest --offline --no-daemon` | `BUILD SUCCESSFUL` |
| R4 | `bash docs/work/private-live-autotrader-nogo-0/verify.sh check master-links` | `master-links: 0 broken` · exit 0 |

**러너 전체 실행**

```bash
bash docs/work/private-live-autotrader-nogo-0/verify.sh --pre-approval
```

**예상 결과**

```text
AC1    GREEN   decision-inputs: 8/8 rows, unmatched=[] unsourced=[]
AC2    GREEN   state-vocabulary: unknown=[]
AC3    GREEN   owner-row: present, before C, repeating, delegated
AC4    GREEN
AC5    PENDING 증거 미기록 — --pre-approval 이라 허용
AC6    PENDING 증거 미기록 — --pre-approval 이라 허용

모드: 승인 전 확인 (최종 판정으로 쓸 수 없다)
GREEN=4 RED=0 MISSING=0 MANUAL=2 SKIP=0
표의 AC 총 6개 중 6개 계상
```

`exit 0`. `--pre-approval` 없이 돌리면 `AC5`·`AC6`가 `RED`이고 `exit 1`이다 — 그것이 정상이다.

**R3이 실패하면 GREEN으로 기록하지 않는다.** Docker·네트워크 부재로 실행하지 못했다면 그 사실을 그대로
적는다(`.ai/rules/testing.md` "실행하지 못한 항목은 green으로 기록하지 않는다").

---

### T7 — 최종 판정 기입

**파일**: `docs/work/private-live-autotrader-nogo-0/dod.md` `## 최종 판정` 블록

**기입 규칙** — 수는 전부 수용기준 표에서 센다. 표와 어긋나면 판정을 낼 수 없다.

**예상 판정 (전문)**

```text
DoD VERDICT: private-live-autotrader-nogo-0 @ <commit SHA>
  수용기준 표:     6개  (T1 4 · T2 0 · T3 0 · T4 2)
  T1/T2 자동:      4개 중 4개 PASS
  T3 기록 제출:    0개 중 0건
  T4 사람 확인:    2개 중 0건 완료, 2건 대기
  변경 요청:       0건
  => AWAITING_HUMAN
```

**`AWAITING_HUMAN`일 때 "완료했습니다"라고 쓰지 않는다.** 남은 항목(`AC5`·`AC6`)을 나열한다.

`AC5`가 owner 승인으로 해결되면 `dod.md`의 `status`를 `FROZEN`으로, `frozen_at`을 승인 시각으로 바꾸고
`## 사람 확인 (T4)` 표의 `AC5` 행 앵커에 승인 커밋 SHA를 적는다. 그 뒤 판정은 `T4 2개 중 1건 완료, 1건 대기`가
되며 여전히 `AWAITING_HUMAN`이다. `AC6`은 owner가 `progress.md`에 append해야 닫힌다.

**커밋·PR은 이 계획의 범위가 아니다.** 호출한 오케스트레이터가 소유한다.

---

## 3. 이 계획이 결정하지 않는 것

- **8개 값 자체.** owner 결정이며 `progress.md` append가 담는다(`design.md` §7).
- **`[DECISION_NEEDED-1]`·`[DECISION_NEEDED-2]`·`[DECISION_NEEDED-4]`의 판정.** ⑦에서 owner가 정한다.
  `[DECISION_NEEDED-4]`가 "편입"으로 결정되면 `design.md` §4 표가 9행이 되고
  `remaining-effort-estimate.md` §7도 함께 고쳐야 한다 — 그때는 `dod.md` `## 변경 요청`을 거친다.
- **모체 `design.md`·`dod.md`의 어떤 변경도.** `FROZEN`이다.
- **`verify.sh`의 CI 등록.** `dod.md` `## 검사 산출물`이 등록하지 않는 이유를 소유한다.
- **러너의 awk·sed 구현부.** §0 밀도 계약에서 요건 목록까지만 쓰는 쪽이다.
