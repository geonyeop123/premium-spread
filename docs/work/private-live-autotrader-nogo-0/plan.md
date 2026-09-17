# `NOGO-0` 상한·재평가 시점 승인 — 실행 계획

| 항목 | 값 |
|---|---|
| 문서 역할 | `feature-workflow` ⑤ plan 문서 |
| slug | `private-live-autotrader-nogo-0` |
| 스펙 | [`design.md`](design.md) |
| 완료 기준 계약서 | [`dod.md`](dod.md) |
| base branch | `dev` |
| branch | `docs/private-live-autotrader-nogo-0` |
| 상태 | ⑥ 스펙 리뷰 R2 반영본 |

## 0. 이 계획의 밀도 계약

이 단위는 **코드 산출물이 0이다**([`design.md`](design.md) §0). 따라서 ⑤가 요구하는 "전문으로 쓸 코드
계약"을 아래로 사상한다.

| 전문으로 쓴다 (태스크 간 결합점) | 요건 목록까지만 쓴다 |
|---|---|
| 파일 경로 | 산문 문단의 문장 표현 |
| 표의 행·열 머리글과 삽입 위치(어느 표, 어느 행 앞) | 검증 러너의 awk·sed 구현부 |
| 식별자 — `N0`, `AC1`~`AC6`, check 이름, 단정 번호 | |
| 근거 절 참조 문자열 | |
| 러너가 파싱할 표 행 형식과 awk 필드 인덱스 | |
| 러너의 출력 형식·실패 전략·종료 코드 | |
| 상위 문서에 삽입·수정할 행·문단·불릿의 **전문** | |

**이웃 태스크가 참조하는 이름은 어느 태스크에도 정의돼 있어야 한다.** 이 문서에서 정의되지 않은 표 열,
check 이름, 단정 번호, 파일 경로를 참조하지 않는다.

**단정 번호의 정본은 이 문서 §2-4다.** [`dod.md`](dod.md)는 그 번호를 그대로 인용한다.

## 1. 태스크 개요

| # | 태스크 | 산출물 | 의존 | 상태 |
|---|---|---|---|---|
| T1 | 스펙·계약서·README 작성 | `design.md` · `dod.md` · `README.md` | — | **완료** |
| T2 | 검증 러너 작성 | `verify.sh` | T1 | 대기 |
| T3 | RED 기록 | `dod.md` 증거 로그 | T2 | 대기 |
| T4a | 위반 표본 검출 증명 — 표본 1~4·9 | `dod.md` 증거 로그 | T2 | 대기 |
| T5 | 상위 `plan.md` 세 곳 편집 | `../private-live-autotrader/plan.md` | T3 · T4a | 대기 |
| T4b | 위반 표본 검출 증명 — 표본 5~8c | `dod.md` 증거 로그 | T5 | 대기 |
| T6 | GREEN 기록과 회귀 방어선 실행 | `dod.md` 증거 로그 | T4b | 대기 |
| T7 | 최종 판정 기입 | `dod.md` 최종 판정 블록 | T6 | 대기 |

**실행 순서는 T1 → T2 → T3 → T4a → T5 → T4b → T6 → T7이다.** 표의 나열 순서가 곧 실행 순서다.

두 가지가 순서를 고정한다.

- **T3이 T5보다 앞이다.** `owner-row`의 진짜 RED(`missing N0 row`)는 상위 `plan.md`를 고치기 전에만 관찰할
  수 있다. T5를 먼저 하면 그 RED를 영영 기록하지 못하고 `AC3`은 `definition-of-done`의 RED-first 규칙에
  따라 미충족이 된다.
- **T4가 둘로 갈린다.** 표본 5~8c는 `N0` 행·문단·§3 항목·Phase 1 행이 있어야 "지웠을 때 잡히는지"를 볼 수
  있으므로 T5 뒤다. 표본 1~4·9는 T2 직후에 가능하다. 표본 9는 상위 `plan.md`에 다른 미커밋 변경이 없어야
  하므로 **T5 앞**이어야 한다(`dod.md` `## 검사 산출물`의 주석).

---

## 2. 태스크

### T1 — 스펙·계약서·README 작성 **[완료]**

**파일**

- `docs/work/private-live-autotrader-nogo-0/design.md`
- `docs/work/private-live-autotrader-nogo-0/dod.md`
- `docs/work/private-live-autotrader-nogo-0/README.md`

**산출 요약** — `design.md` §4가 owner 결정 입력 8개를 `remaining-effort-estimate.md` §7과 1:1로 고정하고,
§8이 상위 `plan.md`에 가할 세 편집의 내용 계약을 고정했다. `dod.md`는 `status: DRAFT`이며 수용기준
6개(T1 4 · T4 2)를 갖는다.

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
재평가 회차마다 직접 돌리는 것이고(`design.md` §8.4) 의존이 적을수록 그 약속이 지켜진다.

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

[상위 plan §1 첫 표 Phase 1 행]
  행 정규식   ^\| 2 \| Phase 1
  awk -F'|'   $5 = 진입 조건
  결속 대상   # | 실행 단위 | slug 후보 | 진입 조건 | 산출 문서 | 종료 판정

[상위 plan §1 gate 표 행]
  행 정규식   ^\| N0 \|   와   ^\| C \|
  판정        grep -n 으로 얻은 행 번호를 비교한다

[상위 plan §1 절 전체]  — '## 1. 실행 단위 분해' ~ 다음 '## ' 구간으로 한정한다
  용도        단정 ⑧ 의 거짓-서술 방지 문장 검사
```

**절 한정이 필수다.** phase-0 러너 주석이 같은 함정을 기록했다 — 구간을 한정하지 않으면 수용기준 표의
`| ACn |` 행이 사람 확인 행으로 오인돼 **모든 T4가 "기록됨"으로 오판된다.** 같은 이유로 `^\| [0-9] \|`도
두 파일의 다른 표를 집을 수 있다. Phase 1 행 정규식이 `^\| 2 \|`로 끝나지 않고 `Phase 1`까지 포함하는
것도 같은 이유다.

#### 2-3. 서브커맨드 (전문)

```text
verify.sh                      최종 판정. T4 미해결이면 RED
verify.sh --pre-approval       T4 미해결을 PENDING 으로 허용 (최종 판정으로 쓸 수 없다)
verify.sh --static             T1 만 실행
verify.sh AC1 AC3              지정한 AC 만. 표에 없는 id 를 주면 exit 2
verify.sh check <name>         개별 check. **표를 파싱하지 않는다** (자기 재귀 방지)
verify.sh --root <dir> check <name>

<name> ∈ decision-inputs | state-vocabulary | owner-row | doc-links
알 수 없는 <name> 은 exit 2
```

#### 2-4. check 단정과 출력 (전문)

**단정은 fail-fast다.** 각 check는 **첫 실패에서 멈추고 그 사유 한 줄만 출력한다.** 누적 보고하지 않는다.
이 결정의 귀결은 T3에 적는다 — 단정 하나의 RED만 관찰되므로 나머지 단정의 RED는 T4b 표본이 각각 증명한다.

```text
decision-inputs
  ① SPEC §4 행이 8개,  EST §7 행이 8개
  ② 양쪽 $2 가 1..8 순서
  ③ 같은 번호 행끼리 trim($3) 문자열 일치            → 불일치는 unmatched 에 번호 누적
  ④ SPEC 행의 $4·$5 가 공백이 아님                   → 위반은 unsourced 에 번호 누적
  ⑤ 두 조건은 배타가 아니다. 각각 독립으로 검사한다
       $5 에 [0-9] 또는 % 또는 ~ 가 있으면  →  $6 이 공백이 아님
       $5 에 '권고 없음' 이 있으면          →  $7 이 공백이 아님
       둘 다 해당하면 둘 다 요구한다        →  위반은 unsourced 에 번호 누적
  출력  decision-inputs: 8/8 rows, unmatched=[] unsourced=[]

  주: ③④⑤ 의 '누적' 은 한 check 안에서 위반 행 번호를 모으는 것이고, 위 fail-fast 는
      check 사이가 아니라 단정 사이의 규칙이다. ①② 가 실패하면 거기서 멈춘다.

state-vocabulary
  ① UNIT 의 *.md 전 줄에서 백틱 쌍 안의 토큰을 뽑는다
  ② 그중 ^[A-Z][A-Z_]{3,}$ 인 것만 남긴다
  ③ 각 토큰이 참조 코퍼스 네 파일 중 하나에 grep -qF 로 존재하면 통과
  출력  state-vocabulary: unknown=[]

owner-row   — 단정 8개. 번호는 이 목록이 정본이다
  ① MPLAN 에 ^\| N0 \| 행이 있다                      없으면 missing N0 row
  ② MPLAN 에 ^\| C \| 행이 있다                       없으면 missing C row
  ③ N0 행 번호 < C 행 번호                            아니면 N0 not before C
  ④ N0 행이 '반복' 을 포함한다                        아니면 no repeating marker
  ⑤ N0 행이 'progress.md' 를 포함한다                 아니면 no progress.md closure
  ⑥ '## 3. 이 plan이 결정하지 않는 것' 절에 'NOGO-0' 을 포함한 불릿이 있다
                                                      아니면 not delegated in section 3
  ⑦ 첫 표 Phase 1 행의 $5 가 'N0' 를 포함한다         아니면 phase 1 entry not gated
  ⑧ §1 절 안에 '재통과' 를 포함한 줄이 있다           아니면 missing repetition caveat
  출력  owner-row: present, before C, repeating, delegated, phase1-gated, caveat

doc-links
  ① MASTER/*.md 와 UNIT/*.md 의 모든 ](target) 에서 target 추출
  ② http:// https:// mailto: 로 시작하면 건너뛴다
  ③ '#' 이후를 잘라낸다
  ④ 그 파일의 디렉터리 기준으로 실재하는지 test -e
  출력  doc-links: 0 broken
```

**단정 ⑧이 `재통과`를 고른 이유.** 이 단정은 §8.4 둘째 항목(*"돌아오는 것 자체는 `N0`만의 성질이 아니다"*)이
살아 있다는 증거를 찾는다. `A1`이나 `CANDIDATE_REJECTED`를 찾으면 **그 문장이 없어도 통과한다** — 둘 다
모체 `plan.md` §1의 `A1` 행(`| A1 | Candidate gate | … | CANDIDATE_APPROVED 또는 CANDIDATE_REJECTED |`)에
이미 있기 때문이다. `재통과`는 모체 `plan.md`에 0회 등장하므로 5-3이 넣는 문단만이 이 단정을 통과시킨다.

**단정 ↔ 표본 대응.** 단정 하나가 표본 하나를 갖는다. 예외는 ②뿐이다.

| 단정 | 대응 표본 | 태스크 |
|---|---|---|
| ① `N0` 행 존재 | 5 | T4b |
| ② `C` 행 존재 | **표본 면제** — 모체 gate 표에서 `C` 행이 사라지는 것은 이 단위가 만들 수 있는 회귀가 아니다. 이 단정은 ③의 전제(행 번호 비교)를 성립시키기 위한 것이다 | — |
| ③ `N0` < `C` | 6 | T4b |
| ④ 반복 표기 | 7 | T4b |
| ⑤ `progress.md` | 7b | T4b |
| ⑥ §3 위임 | 8 | T4b |
| ⑦ Phase 1 진입 조건 | 8b | T4b |
| ⑧ 거짓-서술 방지 문장 | 8c | T4b |

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

**단정은 fail-fast이므로 이 실행은 단정 ①만 관찰한다.**(§2-4) `N0` 행이 없으면 ①③④⑤가 동시에 성립하지
않지만 출력은 ①의 사유 한 줄이다. 나머지 단정의 RED는 T4b 표본이 각각 증명한다 — 대응은 §2-4의
"단정 ↔ 표본" 표가 소유한다. 누적 보고로 바꾸는 것은 별도 결정이며, 그렇게 하면 이 태스크의 기대 출력과
표본 5의 기대 출력을 함께 고쳐야 한다.

**기록 형식** — `## 증거 로그` `### AC3` 블록에 아래 형식으로 append한다.

```text
[RED] <날짜> <commit>
$ bash docs/work/private-live-autotrader-nogo-0/verify.sh check owner-row
owner-row: missing N0 row
(exit 1)
```

---

### T4a — 위반 표본 검출 증명 (표본 1~4·9)

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

**`doc-links`(표본 9)는 이 사본을 쓰지 않는다.** 사본에는 상위 문서가 참조하는 `.ai/planning/**` 같은 대상이
없어 정상 표본조차 수십 건 broken으로 나온다. 실트리에서 한 줄을 넣었다 되돌린다 — 아래 표의 9번이다.

**각 표본의 편집과 기대 결과**

| # | check | 사본에 가할 편집 | 기대 출력 | 기대 exit |
|---|---|---|---|---|
| 1 | `decision-inputs` | `design.md` §4 표 5행의 `근거 문서·절`·`유도 불가 시 사유` 두 칸을 비운다 | `unsourced=[5]` | 1 |
| 2 | `decision-inputs` | `design.md` §4 표 8행을 삭제한다 | `7/8 rows` | 1 |
| 3 | `decision-inputs` | `design.md` §4 표 2행과 3행의 `값` 칸을 맞바꾼다 | `unmatched=[2,3]` | 1 |
| 4 | `state-vocabulary` | `design.md` 본문 아무 곳에 코퍼스에 없는 대문자 상태 이름을 백틱으로 감싸 넣는다 | `unknown=[<넣은 토큰>]` | 1 |
| 9 | `doc-links` | **실트리** 상위 `plan.md` 끝에 실재하지 않는 대상을 가리키는 마크다운 링크 한 줄을 추가 | `doc-links: 1 broken` | 1 |

**표본 4의 기대 출력에 리터럴을 적지 않는 이유.** 그 토큰을 이 문서에 백틱으로 적으면 `state-vocabulary`가
`plan.md` 자신을 위반으로 잡는다 — 검사 대상이 이 디렉터리의 `*.md` 전부이기 때문이다(`dod.md` `## 검사
산출물`의 같은 주석은 그 문서에 한정돼 있어 여기 다시 적는다). 실행자는 임의의 미등록 토큰을 쓰고 출력에
그 토큰이 그대로 나오는지 확인한다.

**실행 명령 (표본 1 예시)**

```bash
bash docs/work/private-live-autotrader-nogo-0/verify.sh --root "$SAMPLE" check decision-inputs
```

**실행 명령 (표본 9 — 실트리, 되돌림 포함)**

```bash
bash docs/work/private-live-autotrader-nogo-0/verify.sh check doc-links
git checkout -- docs/work/private-live-autotrader/plan.md
bash docs/work/private-live-autotrader-nogo-0/verify.sh check doc-links
```

첫 실행이 `doc-links: 1 broken` · exit 1, 되돌린 뒤 실행이 `doc-links: 0 broken` · exit 0이어야 한다.
**이 표본은 상위 `plan.md`에 다른 미커밋 변경이 없을 때만 수행한다** — `git checkout --`가 그 변경까지
지우기 때문이다. 그래서 T5보다 앞이다.

**정상 표본 대조** — 편집하지 않은 사본에서 표본 1~4의 명령이 `exit 0`이어야 한다. 위반만 잡고 정상을
통과시키는 것까지가 증명이다.

**정리**

```bash
rm -rf "$SAMPLE"
```

---

### T5 — 상위 `plan.md` 세 곳 편집

**파일**: `docs/work/private-live-autotrader/plan.md`

상위 `plan.md`는 모체 `dod.md`가 동결한 대상이 **아니다**(`design.md` §8.6). 모체 `design.md`와 `dod.md`는
이 태스크에서도 **건드리지 않는다.**

#### 5-1. §1 첫 표 — Phase 1 행의 진입 조건 (§2 판정의 강제 지점)

**위치** — §1의 **첫 번째** 표(`| # | 실행 단위 | slug 후보 | 진입 조건 | 산출 문서 | 종료 판정 |`)의
`| 2 | Phase 1 Market & Economics |` 로 시작하는 행. **진입 조건 칸만** 고치고 나머지 칸은 그대로 둔다.

**현재 행 (전문)**

```text
| 2 | Phase 1 Market & Economics | `private-live-autotrader-phase-1` | `FOUNDATION_ALIGNED` | 〃 | `MARKET_ECONOMICS_READY` |
```

**고친 뒤 (전문)**

```text
| 2 | Phase 1 Market & Economics | `private-live-autotrader-phase-1` | `FOUNDATION_ALIGNED` + `N0` 최초 승인 | 〃 | `MARKET_ECONOMICS_READY` |
```

근거는 `design.md` §2.3과 §8.2다 — gate 표의 순서는 가독성이고, `C`의 진입 조건인 `P1-O1`이 Phase 1
**안**의 산출물이라 표 순서만으로는 Phase 1 진입을 막지 못한다.

#### 5-2. §1 gate 표에 `N0` 행 삽입

**삽입 위치** — §1의 **두 번째** 표(`| # | 실행 단위 | 진입 상태 | owner | 산출물 | 종료 판정 |`)에서
구분선 `|---|---|---|---|---|---|` 바로 다음, 즉 **`| C | Collection 시작 |` 행 앞**이다.

**삽입할 행 (전문)**

```text
| N0 | Program 재평가 (`NOGO-0`) — **반복 gate** | 최초: Phase 1 진입 전이며 evidence collection이 `COLLECTION_NOT_READY`. 이후: 승인된 재평가 시점 도달마다 | owner | [결정 입력 8개](../private-live-autotrader-nogo-0/design.md)의 승인값·승인 시각, 회차의 계속·축소·종결 판정 | 상태축 전이 없음 — `progress.md` append로 종료. 종결이면 `Z2`로 넘긴다 |
```

실행 단위 칸이 `Program 재평가`를 포함하는 것은 모체 `design.md` §8 traceability가 `NOGO-0`의 책임을 그
문자열로 적었기 때문이다(`design.md` §8.3). 바꾸면 `review-findings.md`의 지적이 다시 열린다.

#### 5-3. gate 표 아래 문단 삽입

**삽입 위치** — 위 표 바로 다음, 기존 문단 *"각 gate 실행 단위는 진입 전에 자신의 판정 기준을 …"* **앞**에
빈 줄을 두고 넣는다.

**삽입할 문단 (전문)**

```text
`N0`는 최초 승인이 Phase 1 진입 조건을 해제한 뒤에도 승인된 재평가 시점마다 돌아오며, 그 회차의 승인은
사슬을 전진시키지 않고 자기 다음 회차의 진입 조건만 만든다. 돌아오는 것 자체가 `N0`만의 성질은 아니다 —
`A1`은 candidate가 교체되면 **재통과**하고(`CANDIDATE_REJECTED` 이후 새 candidate는 `ACT-1`부터 다시 통과한다)
`LIVE-12`의 `RESUME`은 해당 ACT gate 재평가를 요구한다. `Z2` 행이 진입 트리거로 참조하는
"`NOGO-0` 재평가 결과"의 소유 행이 `N0`이며, 각 회차는 `progress.md` append와 함께
`bash docs/work/private-live-autotrader-nogo-0/verify.sh` 실행 결과를 남긴다.
```

**"나머지 행은 1회성 전이"라고 쓰지 않는다.** 그 서술은 거짓이다 — 근거는 `design.md` §6.1이 소유한다.
모체 plan에 거짓 서술을 남기면 되돌릴 기회가 없다.

**"승인이 사슬을 전진시키는 대신"이라고 쓰지 않는다.** 5-1이 최초 승인을 Phase 1 진입 조건으로 만들므로
그 문장은 5-1과 모순된다. 위 전문처럼 **최초와 이후를 나눠** 적어야 한다.

**`재통과` 라는 낱말을 지우지 않는다.** 단정 ⑧이 그 낱말로 이 문단의 생존을 검사한다(§2-4). `A1`이나
`CANDIDATE_REJECTED`는 모체 `A1` 행에 이미 있어 판별력이 없다.

#### 5-4. §3에 위임 불릿 추가

**삽입 위치** — `## 3. 이 plan이 결정하지 않는 것`의 마지막 불릿
*"- activation 정책값(기간, 표본 수, 허용 손실) — `design.md` §4.5가 사용자 승인으로 고정한다"* **다음 줄**.

**삽입할 불릿 (전문)**

```text
- `NOGO-0` 비용·기간 상한값과 재평가 시점 — §1 `N0` 실행 단위가 owner 승인으로 고정한다
```

넣는 이유는 `design.md` §8.5가 소유한다 — §3은 미결정 목록이 아니라 **위임 등기부**이고,
`review-findings.md`가 §1과 §3 둘 다 비었다고 지적했으므로 한쪽만 채우면 지적의 절반이 남는다.

#### 5-5. 검증

**실행 명령**

```bash
bash docs/work/private-live-autotrader-nogo-0/verify.sh check owner-row
```

**예상 결과**

```text
owner-row: present, before C, repeating, delegated, phase1-gated, caveat
```

`exit 0`. 단정과 편집의 대응은 ⑦→5-1, ①③④⑤→5-2, ⑧→5-3, ⑥→5-4다. ②는 모체가 원래 갖고 있던 `C` 행을
확인하는 전제 단정이다.

**실행 명령**

```bash
bash docs/work/private-live-autotrader-nogo-0/verify.sh check doc-links
```

**예상 결과**

```text
doc-links: 0 broken
```

`exit 0`. 5-2가 넣은 `../private-live-autotrader-nogo-0/design.md` 링크가 실재하는지를 본다. 5-1·5-3·5-4는
새 링크를 만들지 않지만 같은 파일이라 같은 검사가 덮는다. 이것이 모체 `dod.md` `AC4`의 회귀 방어선 `R4`다.

---

### T4b — 위반 표본 검출 증명 (표본 5~8c)

**파일**: `docs/work/private-live-autotrader-nogo-0/dod.md` `## 증거 로그` 절

T5가 끝나 상위 `plan.md`에 세 편집이 들어간 뒤에만 가능하다. **사본을 이 시점에 새로 뜬다** — T4a의
`$SAMPLE`은 편집 전 상태다.

| # | 단정 | 사본에 가할 편집 | 기대 출력 | 기대 exit |
|---|---|---|---|---|
| 5 | ① | `plan.md`의 `N0` 행을 삭제한다 | `missing N0 row` | 1 |
| 6 | ③ | `N0` 행을 `Z2` 행 뒤로 옮긴다 | `N0 not before C` | 1 |
| 7 | ④ | `N0` 행에서 "반복" 두 글자를 지운다 | `no repeating marker` | 1 |
| 7b | ⑤ | `N0` 행에서 `progress.md` 를 지운다 | `no progress.md closure` | 1 |
| 8 | ⑥ | §3의 `NOGO-0` 불릿을 삭제한다 | `not delegated in section 3` | 1 |
| 8b | ⑦ | 첫 표 Phase 1 행의 진입 조건을 `FOUNDATION_ALIGNED` 로 되돌린다 | `phase 1 entry not gated` | 1 |
| 8c | ⑧ | 5-3 문단에서 `재통과` 를 포함한 문장을 지운다 | `missing repetition caveat` | 1 |

**단정은 fail-fast이므로 표본마다 앞선 단정이 성립해야 뒤 단정의 실패가 관찰된다.** 예를 들어 표본 8c는
①~⑦이 모두 통과한 사본이어야 ⑧의 사유가 출력된다. 각 표본은 **한 곳만** 고친다.

**실행 명령 (표본 5 예시)**

```bash
bash docs/work/private-live-autotrader-nogo-0/verify.sh --root "$SAMPLE" check owner-row
```

**정상 표본 대조** — 편집하지 않은 사본에서 같은 명령이 `exit 0`이어야 한다.

**정리**

```bash
rm -rf "$SAMPLE"
```

---

### T6 — GREEN 기록과 회귀 방어선 실행

**파일**: `docs/work/private-live-autotrader-nogo-0/dod.md` `## 증거 로그` 절

**실행 명령과 예상 결과**

| 대상 | 명령 | 예상 |
|---|---|---|
| AC1 | `bash docs/work/private-live-autotrader-nogo-0/verify.sh check decision-inputs` | `decision-inputs: 8/8 rows, unmatched=[] unsourced=[]` · exit 0 |
| AC2 | `bash docs/work/private-live-autotrader-nogo-0/verify.sh check state-vocabulary` | `state-vocabulary: unknown=[]` · exit 0 |
| AC3 | `bash docs/work/private-live-autotrader-nogo-0/verify.sh check owner-row` | `owner-row: present, before C, repeating, delegated, phase1-gated, caveat` · exit 0 |
| AC4 | `git diff --quiet origin/dev -- docs/work/private-live-autotrader/design.md docs/work/private-live-autotrader/dod.md` | 출력 없음 · exit 0 |
| R1 | `bash docs/check-documentation.sh` | `documentation check passed` · exit 0 |
| R2 | `bash docs/check-harness.sh` | `하네스 검사 통과: 파일 N개` · exit 0 |
| R3 | `./gradlew test architectureTest --offline --no-daemon` | `BUILD SUCCESSFUL` |
| R4 | `bash docs/work/private-live-autotrader-nogo-0/verify.sh check doc-links` | `doc-links: 0 broken` · exit 0 |

`AC4`는 ⑥ R1에서 orchestrator가 이미 한 번 실측해 증거 로그에 있다(exit 0). T5가 `plan.md`만 건드리므로
결과는 같아야 하며, 여기서 편집 후 상태로 한 번 더 실행해 append한다.

**러너 전체 실행**

```bash
bash docs/work/private-live-autotrader-nogo-0/verify.sh --pre-approval
```

**예상 결과**

```text
AC1    GREEN   decision-inputs: 8/8 rows, unmatched=[] unsourced=[]
AC2    GREEN   state-vocabulary: unknown=[]
AC3    GREEN   owner-row: present, before C, repeating, delegated, phase1-gated, caveat
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
- **`[DECISION_NEEDED-5]`의 승인 여부.** owner가 이미 범위 확대를 승인했다. ⑦에서 확인하는 것은 승인
  여부가 아니라 T5 5-1이 넣을 **문면**이다.
- **모체 `design.md`·`dod.md`의 어떤 변경도.** `FROZEN`이다.
- **Phase 1 행의 진입 조건 칸 외의 어떤 칸도.** 5-1은 한 칸만 고친다.
- **`verify.sh`의 CI 등록.** `dod.md` `## 검사 산출물`이 등록하지 않는 이유를 소유한다.
- **러너의 awk·sed 구현부.** §0 밀도 계약에서 요건 목록까지만 쓰는 쪽이다.

**결정된 것 하나를 명시한다.** `owner-row` 단정의 실패 전략은 **fail-fast**이며 §2-4가 소유한다. 누적
보고로 바꾸려면 T3와 표본 5의 기대 출력을 함께 고쳐야 하고, 그것은 이 계획이 아니라 새 결정이다.
