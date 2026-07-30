---
feature: PRIVATE LIVE master specification 재작성과 workflow 산출물 정렬
slug: private-live-autotrader-master-spec
status: DRAFT
frozen_at: null
source: 사용자 지시("상위 specification으로 재작성", "반영본 초안 작성", "feature-workflow 산출물처럼 재구성")와 docs/work/private-live-autotrader/design.md
---

## 범위

**포함**

- 프로그램 master specification을 상세 구현 계획이 아닌 상위 spec으로 재작성
- Claude 독립 Review A·B·C finding 반영과 requirement ID 체계 정합성 확보
- 상태축 현재값의 단일 소유(SSOT) 확정
- 프로그램 문서를 `feature-workflow` 산출물 계약(`docs/work/{slug}/design·plan·dod`)으로 정렬

**제외** *(명시적으로 하지 않는 것 — scope creep 차단선)*

- Phase 0~3의 구현, 코드 변경, migration과 API 변경
- Phase별 상세 design/plan/dod 작성 — 각 Phase 진입 시 별도 workflow 실행 단위가 소유
- 완료된 Phase -1 산출물(`docs/dod/private-live-autotrader-phase-minus-1.dod.md`,
  `.ai/planning/private-live-autotrader/phase-minus-1-*.md`)의 내용 변경
- 장기 증거 수집 시작, 환경·비용 선택, credential·activation 관련 실제 조치

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 원문 | 티어 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|
| AC1 | specification의 모든 requirement ID 참조가 정의를 갖는다 (dangling reference 0건). | 사용자: "문제점 및 개선사항 체크" | T1 | 아래 `AC1 command` | exit 0, `dangling=[]` |
| AC2 | Review C blocker·major 반영으로 신설된 8개 ID가 문서에 정의돼 있다. | 사용자: "반영본 초안 작성해봐" | T1 | 아래 `AC2 command` | exit 0, `review C ids present` |
| AC3 | 상태축 현재값을 spec이 중복 기록하지 않고 `progress.md`가 단독 소유한다. | Review C major(M5) 상태 SSOT | T1 | 아래 `AC3 command` | exit 0, `state ssot ok` |
| AC4 | `docs/work/{slug}/`에 workflow 산출물 5종이 존재하고 상대 링크가 모두 실재 파일을 가리킨다. | 사용자: "feature-workflow에서 생성하는 문서처럼 재구성" | T1 | 아래 `AC4 command` | exit 0, `missing=[] broken_links=[]` |
| AC5 | 동결된 Phase -1 DoD가 변경되지 않고 그 검증 명령이 참조하는 경로가 유지된다. | Phase -1 DoD "Evidence 기록 소유권" | T1 | 아래 `AC5 command` | exit 0, `frozen artifacts intact` |
| AC6 | 저장소 문서 계약과 whitespace 계약이 유지된다. | 기존 repository gate | T1 | `bash docs/check-documentation.sh && git diff --check` | exit 0, `documentation check passed` |
| AC7 | 외부 관점 스펙 리뷰가 수렴한다. 동일 렌즈 재검토에서 critical·high가 0이고, 제품·아키텍처 / 추적성 / 코드 대조 / 실행 안전 네 렌즈를 각각 1회 이상 통과했다. | `feature-workflow` ⑥, 사용자: "2번으로 가자" | T4 | `codex-spec-review` 재실행 후 verdict 기록 | 재검토 critical·high 0 |
| AC8 | 사용자가 `design.md`·`plan.md`·`dod.md`를 승인하고 이 계약서가 `FROZEN`으로 전이한다. | `feature-workflow` ⑦ | T4 | 사용자 승인 기록 | `status: FROZEN` + `frozen_at` 기입 |
| AC9 | §4.2가 정의한 모든 activation 상태가 §4.3 권한 표에 행으로 존재하고, 권한 표에 상태축 밖의 상태가 없다. | 반복 발생한 권한 모순(codex ISSUE-2)의 재발 차단 | T1 | 아래 `AC9 command` | exit 0, `missing=[] undefined_in_axis=[]` |
| AC10 | §8이 계약을 배정한 모든 범위(Phase 0~3, Gate)가 §8.1 정직성 표에 판정 행을 갖는다. | 반복 발생한 배정 정직성 결함(codex ISSUE-8)의 재발 차단 | T1 | 아래 `AC10 command` | exit 0, `missing=[]` |
| AC11 | §4.3 권한 표의 복구 열이 모두 `LIVE-11`의 집합 이름(`RECOVERY-0`/`RECOVERY-A`/`RECOVERY-B`/`RECOVERY-C`)을 참조하고 조건을 자유 서술한 행이 없다. | 복구 권한 의미가 라운드마다 재발(codex 3·4·5R)한 원인 차단 | T1 | 아래 `AC11 command` | exit 0, `freeform_recovery_rows=[]` |
| AC12 | 신규 제출을 차단하는 §4.3의 모든 상태 행이 진입 시 `FENCE`를 참조한다. | 전이별 fence 누락 재발(codex 6R)의 차단 | T1 | 아래 `AC12 command` | exit 0, `unfenced_blocking_rows=[]` |
| AC13 | §4.3 권한 회수 트리거 표의 모든 행이 `FENCE` 필수이고, 표 밖 guard(`SAFE-3`·`SAFE-7`·`SAFE-9`·`SAFE-10`)와 Phase outcome 경로(`P3-O17`)를 포함한다. | 표 밖 guard 경로가 fence 없이 권한을 회수하던 문제(codex 7R) 차단 | T1 | 아래 `AC13 command` | exit 0, `without_fence=[] missing_sources=[]` |
| AC14 | 권한 회수 트리거 표의 모든 행이 지속·주기 감시와 재시작 재평가를 포함한 탐지 경로를 갖는다. | 시점 검사만 있고 지속 감시가 없어 생기는 TOCTOU 부류(codex 10R) 차단 | T1 | 아래 `AC14 command` | exit 0, `incomplete_detection=[]` |
| AC15 | 신규 제출이 차단된 모든 상태가 재개 조건(`RESUME` 또는 최초 gate)을 명시한다. | 회수만 정의하고 재개를 비워 두는 부류(codex 11R) 차단 | T1 | 아래 `AC15 command` | exit 0, `missing_resume=[]` |
| AC16 | 문서에 상태축 밖의 비정형 상태 이름이 남아 있지 않다. | 이름만 있고 등재되지 않은 상태 부류(codex 12R) 차단 | T1 | 아래 `AC16 command` | exit 0, `informal=[]` |
| AC17 | 권한 회수 트리거 표의 모든 행이 진입 상태와 `FENCE` 완료 후 목적 상태를 activation·latch 두 축으로 명시한다. | 한 축만 지정하고 다른 축을 미정으로 두는 부류(codex 13R) 차단 | T1 | 아래 `AC17 command` | exit 0, `incomplete_target=[]` |
| AC19 | 권한 회수 전이가 승인·알림을 기다리지 않고 runtime이 즉시 수행한다는 규칙이 §4.2에 있고, 트리거 표가 승인을 요구하지 않는다. | 안전 회수가 승인 대기로 지연될 수 있던 부류(codex 16R) 차단 | T1 | 아래 `AC19 command` | exit 0, `violations=[]` |
| AC25 | 외부·수동 기원 변화의 귀속 절차가 `SAFE-5`에 정의되고 `LIVE-13`·`SAFE-7`·`RESUME`이 이를 참조한다. | 수동·외부 개입 결과의 귀속 경계 부재 부류(codex 23R) 차단 | T1 | 아래 `AC25 command` | exit 0, `violations=[]` |
| AC24 | `FENCE-2`가 exposure-reducing working 주문까지 다루고, owner fallback이 `LIVE-13`으로 정의돼 참조된다. | 참조만 되고 정의되지 않은 절차와 fence 범위 누락 부류(codex 22R) 차단 | T1 | 아래 `AC24 command` | exit 0, `violations=[]` |
| AC23 | 상태 의존 복구 집합이 입력 신뢰(`SAFE-3` 비활성·reconcile 완료)를 전제로 하고, 불신 시 취소·owner fallback만 남는 규칙이 있다. | 검사를 요구하면서 그 입력의 신뢰를 전제하지 않는 부류(codex 21R) 차단 | T1 | 아래 `AC23 command` | exit 0, `violations=[]` |
| AC22 | `RECOVERY-C`가 정적 승인 한도뿐 아니라 현재 headroom·risk budget 기준 실현 가능성 검사에 결속된다. | 정적 한도만 보고 현재 상태를 보지 않는 부류(codex 20R) 차단 | T1 | 아래 `AC22 command` | exit 0, `violations=[]` |
| AC21 | 복구 집합을 열거하는 어떤 위치도 `RECOVERY-A`·`RECOVERY-B`만 적고 `RECOVERY-C`를 빠뜨리지 않는다. | 단일 정의를 여러 곳에서 열거하다 전파를 빠뜨리는 부류(codex 19R) 차단 | T1 | 아래 `AC21 command` | exit 0, `inconsistent_enumerations=[]` |
| AC20 | `FENCE` 완료 기준이 `FENCE-1`~`FENCE-3`의 terminal 결과로만 정의되고, 원인 해소는 `RESUME` 선행조건이며, owner의 외부 트리거 발행 역할이 명시된다. | 완료 기준 모호로 `FENCE_PENDING`에 갇히는 부류와 owner 역할 모순(codex 17R) 차단 | T1 | 아래 `AC20 command` | exit 0, `violations=[]` |
| AC18 | §4.2의 모든 상태축(software 포함)이 초기값을 갖고 그 값이 해당 축의 등록 상태다. | 축을 추가하고 초기값 목록을 갱신하지 않는 부류(codex 14R) 차단 | T1 | 아래 `AC18 command` | exit 0, `missing_initial=[]` |

### AC1 command

```bash
python3 - <<'PY'
import re,sys
d=open('docs/work/private-live-autotrader/design.md',encoding='utf-8').read()
defined=set(re.findall(r'^\s*[-\d.]+\s+`([A-Z0-9]+-O?\d+)`',d,re.M))|set(re.findall(r'^`([A-Z0-9]+-O?\d+)`\s',d,re.M))
dangling=sorted(set(re.findall(r'`([A-Z]{3,5}-O?\d+)`',d))-defined)
print(f'defined={len(defined)} dangling={dangling}')
sys.exit(1 if dangling else 0)
PY
```

### AC2 command

```bash
for id in ECO-5 SAFE-9 ECG-5 P1-O8 P2-O10 P3-O15 P3-O16 NOGO-0; do
  grep -q -- "- \`$id\`" docs/work/private-live-autotrader/design.md || { echo "missing $id"; exit 1; }
done
echo 'review C ids present'
```

### AC3 command

```bash
! grep -q '현재 프로그램 상태' docs/work/private-live-autotrader/design.md &&
grep -q '상태축 현재값은 이 문서가 단독으로 소유한다' .ai/planning/private-live-autotrader/progress.md &&
grep -q '| software | `SOFTWARE_BASELINE` |' .ai/planning/private-live-autotrader/progress.md &&
echo 'state ssot ok'
```

### AC4 command

```bash
python3 - <<'PY'
import re,pathlib,sys
base=pathlib.Path('docs/work/private-live-autotrader')
req=['design.md','plan.md','dod.md','understanding.md','README.md']
missing=[f for f in req if not (base/f).exists()]
bad=[]
for md in sorted(base.glob('*.md')):
    for link in re.findall(r'\]\(([^)#]+?)(?:#[^)]*)?\)', md.read_text(encoding='utf-8')):
        if link.startswith(('http','mailto')): continue
        if not (md.parent/link).resolve().exists(): bad.append(f'{md.name} -> {link}')
print(f'missing={missing} broken_links={bad}')
sys.exit(1 if missing or bad else 0)
PY
```

### AC5 command

```bash
git diff --quiet HEAD -- docs/dod/private-live-autotrader-phase-minus-1.dod.md &&
test -f .ai/planning/private-live-autotrader/progress.md &&
test -f .ai/planning/private-live-autotrader/phase-minus-1-plan.md &&
echo 'frozen artifacts intact'
```

### AC9 command

§4.2의 모든 activation 상태가 §4.3 권한 표에 존재하고, 권한 표의 모든 행이 §4.2에 정의된 상태인지 검사한다.
activation 축은 전건 필수이며, 다른 축(program 등)은 표에 올릴 수 있지만 §4.2에 없는 상태를 만들 수는 없다.

```bash
python3 - <<'CHECK'
import re, sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
axes = d.split('### 4.2 독립 상태축')[1].split('### 4.3')[0]
all_states, activation = set(), set()
for line in axes.splitlines():
    m = re.match(r'\| (\w[\w /]*) \| `([A-Z_]+)`', line)
    if not m:
        continue
    all_states.add(m.group(2))
    if m.group(1).strip() == 'activation':
        activation.add(m.group(2))
auth = d.split('### 4.3 상태별 허용 행위')[1].split('### 4.4')[0]
covered = set(re.findall(r'^\| `([A-Z_]+)`', auth, re.M))
missing, extra = sorted(activation - covered), sorted(covered - all_states)
print(f'activation={len(activation)} covered={len(covered)} missing={missing} undefined_in_axis={extra}')
sys.exit(1 if missing or extra else 0)
CHECK
```

### AC10 command

§8 traceability 표가 배정한 모든 범위가 §8.1 정직성 표에 판정 행을 갖는지 검사한다.

```bash
python3 - <<'CHECK'
import re, sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
table = d.split('## 8. 요구사항 Traceability')[1].split('### 8.1')[0]
assigned = set()
for line in table.splitlines():
    if line.startswith('|') and '`' in line:
        scope = line.split('|')[2]
        for ph in re.findall(r'Phase (\d)', scope):
            assigned.add(f'Phase {ph}')
        if re.search(r'[Gg]ate', scope):
            assigned.add('Gate')
honesty = d.split('### 8.1 Phase 정직성 검사')[1].split('SaaS와 다중')[0]
rows = {m.group(1) for line in honesty.splitlines()
        for m in [re.match(r'\| (Phase \d|Gate)', line)] if m}
missing = sorted(assigned - rows)
print(f'assigned={sorted(assigned)} rows={sorted(rows)} missing={missing}')
sys.exit(1 if missing else 0)
CHECK
```

### AC11 command

§4.3 권한 표의 복구 열이 `LIVE-11`의 집합 이름을 참조하는지 검사한다. 상태 행이 조건을 자유 서술하면 실패한다.

```bash
python3 - <<'CHECK'
import re, sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
sec = d.split('### 4.3 상태별 허용 행위')[1].split('### 4.4')[0]
state_table = sec.split('| 상태 | 신규 exposure 제출 |')[1].split('\n\n')[0]
bad = []
for line in state_table.splitlines():
    if not line.startswith('|') or line.startswith('|---'):
        continue
    cols = [c.strip() for c in line.strip('|').split('|')]
    if len(cols) < 5:
        continue
    if not (re.search(r'RECOVERY-[0ABC]', cols[2]) or cols[2].startswith('해당 없음')):
        bad.append(f'{cols[0]} -> {cols[2][:30]}')
print(f'freeform_recovery_rows={bad}')
sys.exit(1 if bad else 0)
CHECK
```

### AC12 command

§4.3에서 신규 제출이 `불가`인 모든 행이 진입 시 `FENCE`를 수행하는지 검사한다. 특정 전이만 더 약한 절차를 쓰면 실패한다.

```bash
python3 - <<'CHECK'
import re, sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
sec = d.split('### 4.3 상태별 허용 행위')[1].split('### 4.4')[0]
state_table = sec.split('| 상태 | 신규 exposure 제출 |')[1].split('\n\n')[0]
bad = []
for line in state_table.splitlines():
    if not line.startswith('|') or line.startswith('|---'):
        continue
    cols = [c.strip() for c in line.strip('|').split('|')]
    if len(cols) < 5:
        continue
    if '불가' in cols[1] and not (cols[3].startswith('`FENCE`') or cols[3].startswith('해당 없음')):
        bad.append(f'{cols[0]} -> {cols[3][:30]}')
print(f'unfenced_blocking_rows={bad}')
sys.exit(1 if bad else 0)
CHECK
```

### AC13 command

권한 회수 트리거 표가 모든 트리거에 `FENCE`를 요구하고, §4.3 표 밖에서 권한을 회수하는 guard까지 포함하는지 검사한다.

```bash
python3 - <<'CHECK'
import re, sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
sec = d.split('### 4.3 상태별 허용 행위')[1].split('### 4.4')[0]
block = sec.split('| 권한 회수 트리거 |')[1].split('\n\n')[0]
rows = [l for l in block.splitlines() if l.startswith('|') and not l.startswith('|---')]
bad = [l.split('|')[1].strip() for l in rows if '필수' not in l.split('|')[4]]
missing = [r for r in ['SAFE-6', 'LIVE-10', 'SAFE-7', 'SAFE-9', 'SAFE-3', 'SAFE-10', 'P3-O17'] if f'`{r}`' not in block]
print(f'rows={len(rows)} without_fence={bad} missing_sources={missing}')
sys.exit(1 if bad or missing else 0)
CHECK
```

### AC14 command

트리거마다 지속·주기 감시와 재시작 재평가가 명시됐는지 검사한다. 제출 시점 일회성 점검만 남으면 실패한다.

```bash
python3 - <<'CHECK'
import sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
sec = d.split('### 4.3 상태별 허용 행위')[1].split('### 4.4')[0]
block = sec.split('| 권한 회수 트리거 |')[1].split('\n\n')[0]
rows = [l for l in block.splitlines() if l.startswith('|') and not l.startswith('|---')]
bad = []
for l in rows:
    cols = [c.strip() for c in l.strip('|').split('|')]
    if len(cols) < 5:
        bad.append((cols[0], 'no-detect-column')); continue
    det = cols[4]
    if not (('지속' in det or '주기' in det or '즉시' in det or 'latch' in det) and '재시작' in det):
        bad.append((cols[0], det[:25]))
print(f'rows={len(rows)} incomplete_detection={bad}')
sys.exit(1 if bad else 0)
CHECK
```

### AC15 command

권한이 회수된 상태가 되돌아오는 경로를 명시하는지 검사한다. 회수 조건만 있고 재개 조건이 비면 실패한다.

```bash
python3 - <<'CHECK'
import sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
sec = d.split('### 4.3 상태별 허용 행위')[1].split('### 4.4')[0]
st = sec.split('| 상태 | 신규 exposure 제출 |')[1].split('\n\n')[0]
bad = []
for line in st.splitlines():
    if not line.startswith('|') or line.startswith('|---'):
        continue
    c = [x.strip() for x in line.strip('|').split('|')]
    if len(c) < 6:
        bad.append((c[0], 'no-resume-column')); continue
    if '불가' in c[1] and not ('RESUME' in c[5] or 'ACT-' in c[5]):
        bad.append((c[0], c[5][:25]))
print(f'missing_resume={bad}')
sys.exit(1 if bad else 0)
CHECK
```

### AC16 command

상태처럼 쓰이지만 §4.2에 등재되지 않은 비정형 이름이 남아 있는지 검사한다.

```bash
python3 - <<'CHECK'
import re, sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
informal = re.findall(r'recovery-required|halt latch 활성', d)
print(f'informal={informal}')
sys.exit(1 if informal else 0)
CHECK
```

### AC17 command

트리거마다 진입 상태와 `FENCE` 완료 후 목적 상태가 두 축으로 명시됐는지 검사한다. 한 축만 적으면 실패한다.

```bash
python3 - <<'CHECK'
import sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
sec = d.split('### 4.3 상태별 허용 행위')[1].split('### 4.4')[0]
trig = sec.split('| 권한 회수 트리거 |')[1].split('\n\n')[0]
bad = []
for l in trig.splitlines():
    if not l.startswith('|') or l.startswith('|---'):
        continue
    c = [x.strip() for x in l.strip('|').split('|')]
    if len(c) < 5:
        bad.append((c[0], 'no-column')); continue
    if 'ACTIVATION_FENCE_PENDING' not in c[2] or '→' not in c[2] or '/' not in c[2]:
        bad.append((c[0][:20], c[2][:30]))
print(f'incomplete_target={bad}')
sys.exit(1 if bad else 0)
CHECK
```

### AC18 command

모든 상태축이 초기값을 갖고, 그 초기값이 해당 축에 등록된 상태인지 검사한다. 서술형 초기값은 실패한다.

```bash
python3 - <<'CHECK'
import re, sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
sec = d.split('### 4.2 독립 상태축')[1].split('### 4.3')[0]
registered = {}
for line in sec.splitlines():
    m = re.match(r'\| ([a-z][a-z /]*) \| `([A-Z_]+)`', line)
    if m:
        registered.setdefault(m.group(1).strip(), set()).add(m.group(2))
soft = d.split('### 4.1 Software 상태')[1].split('### 4.2')[0]
registered['software'] = set(re.findall(r'\| `([A-Z_]+)`', soft))
init = sec.split('프로그램 개시 시점의 초기값은')[1].split('축을 새로 만들면')[0]
bad = []
for axis, states in registered.items():
    m = re.search(re.escape(axis) + r'\s+`([A-Z_]+)`', init)
    if not m:
        bad.append((axis, 'no-initial')); continue
    if m.group(1) not in states:
        bad.append((axis, f'{m.group(1)} not registered'))
print(f'axes={len(registered)} invalid_initial={bad}')
sys.exit(1 if bad else 0)
CHECK
```

### AC19 command

안전 트리거의 권한 회수 전이가 승인 대기 없이 즉시 수행되는 규칙을 유지하는지 검사한다.

```bash
python3 - <<'CHECK'
import sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
sec = d.split('### 4.2 독립 상태축')[1].split('### 4.3')[0]
fail = []
if '위험을 줄이는 방향' not in sec or '즉시 durable' not in sec:
    fail.append('no-direction-rule')
if '기다리지 않으며' not in sec:
    fail.append('no-nowait-rule')
trig = d.split('| 권한 회수 트리거 |')[1].split('\n\n')[0]
if '사용자 승인' in trig or 'owner 승인' in trig:
    fail.append('approval-in-trigger-table')
print(f'violations={fail}')
sys.exit(1 if fail else 0)
CHECK
```

### AC20 command

`FENCE` 완료 기준과 owner 역할 분리가 문서에 유지되는지 검사한다.

```bash
python3 - <<'CHECK'
import sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
fail = []
if '`FENCE` 완료 기준은 오직 `FENCE-1`~`FENCE-3`의 terminal 결과이며 원인 트리거의 해소가 아니다' not in d:
    fail.append('no-completion-criterion')
if '원인 해소는 `LIVE-12` `RESUME`의 선행조건으로만 요구한다' not in d:
    fail.append('cause-resolution-not-bound-to-resume')
if '외부 트리거를 발행' not in d:
    fail.append('no-owner-trigger-role')
print(f'violations={fail}')
sys.exit(1 if fail else 0)
CHECK
```

### AC21 command

복구 집합을 문서 여러 곳에서 열거하다 새 집합 전파를 빠뜨리는 회귀를 검사한다.

```bash
python3 - <<'CHECK'
import sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
bad = []
for i, line in enumerate(d.splitlines(), 1):
    if 'RECOVERY-A' in line and 'RECOVERY-B' in line and 'RECOVERY-C' not in line:
        if line.strip().startswith('- `RECOVERY') or '차단 조건:' in line:
            continue
        bad.append((i, line.strip()[:50]))
print(f'inconsistent_enumerations={bad}')
sys.exit(1 if bad else 0)
CHECK
```

### AC22 command

`RECOVERY-C`가 현재 상태 기준 검사에 결속되고 실패 시 분기가 정의됐는지 검사한다.

```bash
python3 - <<'CHECK'
import sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
fail = []
if '현재 headroom과 risk budget을 지키는지 검사' not in d:
    fail.append('no-dynamic-feasibility-check')
if '지키지 못하면 `RECOVERY-C`도 금지한다' not in d:
    fail.append('no-fallback-branch')
sec = d.split('### 4.3 상태별 허용 행위')[1].split('### 4.4')[0]
st = sec.split('| 상태 | 신규 exposure 제출 |')[1].split('\n\n')[0]
for line in st.splitlines():
    if 'RECOVERY-C' in line and '현재 headroom 검사' not in line:
        fail.append(line.split('|')[1].strip())
print(f'violations={fail}')
sys.exit(1 if fail else 0)
CHECK
```

### AC23 command

상태 의존 복구 집합의 입력 신뢰 전제와 불신 시 축소 규칙이 유지되는지 검사한다.

```bash
python3 - <<'CHECK'
import sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
fail = []
if '입력 신뢰 전제' not in d:
    fail.append('no-input-trust-rule')
if '`SAFE-3`이 활성이거나 필요한 reconcile이 끝나지 않았으면' not in ' '.join(d.split()):
    fail.append('no-safe3-binding')
if 'owner fallback만 허용한다' not in ' '.join(d.split()):
    fail.append('no-reduced-set')
print(f'violations={fail}')
sys.exit(1 if fail else 0)
CHECK
```

### AC24 command

`FENCE-2` 범위와 owner fallback 정의·용어 통일을 검사한다.

```bash
python3 - <<'CHECK'
import re, sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
n = ' '.join(d.split())
fail = []
if 'exposure-reducing 주문도 포함한다' not in n:
    fail.append('fence2-scope')
if '- `LIVE-13` owner fallback은' not in d:
    fail.append('no-fallback-definition')
if 'operator fallback' in n:
    fail.append('inconsistent-term')
print(f'violations={fail}')
sys.exit(1 if fail else 0)
CHECK
```

### AC25 command

외부·수동 기원 변화의 귀속 절차와 그 참조가 유지되는지 검사한다.

```bash
python3 - <<'CHECK'
import sys, pathlib
d = pathlib.Path('docs/work/private-live-autotrader/design.md').read_text(encoding='utf-8')
n = ' '.join(d.split())
fail = []
if '귀속 절차를 따른다' not in n:
    fail.append('no-attribution-rule')
if '`unmanaged`로 분류해' not in n:
    fail.append('no-unmanaged-class')
if '`unmanaged` 항목이 남아 있으면' not in n:
    fail.append('no-resume-block')
if '`SAFE-5`의 절차를 따르며' not in n:
    fail.append('fallback-not-linked')
print(f'violations={fail}')
sys.exit(1 if fail else 0)
CHECK
```

## 증거 로그

### AC1 — 2026-07-27

- GREEN: `defined=115 dangling=[]`, exit 0
- 초안 작성 중 `ECG-4`가 산문에 남아 bullet 정의가 없던 상태를 이 검증으로 탐지해 bullet로 정렬했다.

### AC2 — 2026-07-27

- GREEN: `review C ids present`, exit 0
- 신설 ID: `ECO-5`, `SAFE-9`, `ECG-5`, `P1-O8`, `P2-O10`, `P3-O15`, `P3-O16`, `NOGO-0` (삭제된 기존 ID 0건)

### AC3 — 2026-07-27

- GREEN: `state ssot ok`, exit 0
- `design.md` §0.1에서 `현재 프로그램 상태` 줄을 제거하고 `progress.md`에 5축 현재값 표를 신설했다.

### AC4 — 2026-07-27

- RED: `broken_links=['understanding.md -> ../../../.ai/planning/private-live-autotrader/task_plan.md']`, exit 1
  — master specification을 `git mv`로 옮기면서 PR #63 이해문서의 링크가 끊겼다.
- GREEN: 링크 대상을 `design.md`로 수정한 뒤 `missing=[] broken_links=[]`, exit 0
- 이해문서 본문의 "Phase -1~10 계획"은 PR #63 시점의 사실이므로 역사 기록으로 두고 경로만 고쳤다.

### AC5 — 2026-07-27

- GREEN: `frozen artifacts intact`, exit 0
- `git mv`로 이동한 대상은 master specification 하나이며 Phase -1 동결 산출물은 건드리지 않았다.

### AC6 — 2026-07-27

- GREEN: `documentation check passed (20 files, 15 required paths)`, `git diff --check` exit 0

### AC7 — 대기 (1·2라운드 완료, 수렴 미확정)

- 2026-07-27 codex `adversarial-review` 1라운드: `needs-attention`, critical 1 · high 5 · medium 2
- REBUT 0건, 8건 전부 ACCEPT하고 `design.md`·`plan.md`에 반영 (상세는 `progress.md` "Codex 외부 스펙 리뷰")
- 2026-07-28 2라운드(job `review-ms4196fk-jpdfh6`, session `019fa68a-0aca-7693-b943-39f1f33b0c44`)는 **미완료**다.
  12분 44초 동안 문서·diff·문서계약 검사를 수행한 뒤 codex 사용 한도로 `Turn failed`했고 한도 해제 예정은 2026-08-03이다.
- **주의**: 실패한 turn의 출력에 `Verdict: approve`, `No material findings`가 찍혀 있으나 이는 fallback이다. summary가
  미래형 문장("재검토하겠습니다")으로 남아 있어 리뷰가 판정을 산출하지 못했음을 보여준다. 이 출력을 수렴 근거로
  인용하지 않는다.
- 2026-07-28 재시도(job `review-ms4f357a-2sbu1x`, session `019fa7ec-8e79-7750-bfae-02424aaa105f`)는 정상 완료했다.
  결과는 `needs-attention`, critical 1 · high 1로 1라운드의 8건에서 2건으로 감소했다.
  - critical: halt 완료 조건이 "전송 후 응답 불명·거래소 ID 미확정" intent를 fence하지 않음 → `SAFE-6`을 세 집합
    (미전송 / 거래소 잔존 / 응답 불명)으로 재작성, `P3-O19` 확장
  - high: §4.3 권한 표에 program 축이 없어 `PROGRAM_TERMINATION_PENDING`에서도 신규 진입이 허용됨 → program 축 행과
    원자적 전이 규칙 추가, §9.4 interlock 명시
- 두 지적 모두 REBUT 없이 반영했다.
- 2026-07-28 3라운드(job `review-ms4f9k83-ftwqh0`, session `019fa7f1-1c6d-7e12-bad4-5574ec6056b3`): `needs-attention`,
  critical 0 · high 2. 심각도는 내려갔으나 수렴 기준 미달이다.
  - high: `LIVE-11`이 gross·net·residual 전부의 단조 감소를 요구해 한 leg만 체결된 상태의 평탄화 hedge를 금지하는
    자기모순 → 위험 벡터별 기준으로 재정의(net·residual 증가 금지, gross 증가는 체결 leg 평탄화 hedge에 상한·예약 조건부 허용)
  - high: `SAFE-1`에 외부 제출 순서 계약이 남아 Phase 2 배정이 여전히 부정직 → `SAFE-11`로 분리해 Phase 3 전용화
- 2026-07-28 4라운드(job `review-ms4fekup-6zyy5k`, session `019fa7f4-affb-70d3-bf7a-64fe3e9b7133`): `needs-attention`,
  critical 0 · high 1.
  - high: 3라운드에서 연 `LIVE-11`의 gross 증가 hedge 예외가 `SAFE-7` breach·강제 감축보다 우선순위가 없어, 거래소가
    short leg를 강제 감축한 뒤 복구 로직이 short를 재진입해 margin 위기를 악화시킬 수 있음 → 해당 상태가 활성인 동안
    gross 증가 예외를 금지하고 bounded paired reduction 또는 owner fallback만 허용하도록 `LIVE-11`·§4.3·`P3-O6`·`P3-O14`를
    정렬하고 §7.2 failure matrix에 해당 경로를 추가
- 2026-07-28 5라운드(job `review-ms4fkbsf-...`, session `019fa7f8`): `needs-attention`, critical 0 · high 1.
  - high: §4.3의 `PROGRAM_TERMINATION_PENDING` 행이 "정리 범위"라는 자유 서술이어서 `LIVE-11`의 위험 벡터와 `SAFE-7`
    우선순위를 상속하지 않음 → 개별 행 패치 대신 **복구 권한을 `LIVE-11`에서 한 번만 정의**하고 모든 상태 행이
    `RECOVERY-A`/`RECOVERY-B` 집합 이름을 참조하도록 구조를 바꿨다.
- 2026-07-28 6라운드: `needs-attention`, critical 0 · high 1.
  - high: `LIVE-10` 강등(만료·candidate reject)이 halt·종결과 달리 거래소 대기 주문을 fence하지 않아, 강등 직후 기존
    진입 주문이 체결될 수 있음 → `SAFE-6`의 세 집합 종결을 `FENCE`(`FENCE-1`~`FENCE-3`)로 명명해 한 번만 정의하고
    `LIVE-10`·`P3-O14`·`P3-O19`·§4.3의 모든 차단 전이가 이를 참조하도록 구조화, `AC12`로 기계 검사
- 2026-07-28 7라운드: `needs-attention`, critical 0 · high 1.
  - high: `SAFE-7`·`SAFE-9` 같은 표 밖 guard가 권한을 회수하면서 `FENCE`를 거치지 않으며, `AC12`는 §4.3 표만 보므로
    탐지하지 못한다 → §4.3에 권한 회수 트리거 표를 신설해 중단·강등·종결·margin·자본·데이터 guard를 한 목록으로 모으고
    모두 `FENCE` 필수로 고정, `AC13`으로 기계 검사
- 2026-07-28 8라운드: `needs-attention`, critical 0 · high 1.
  - high: 7라운드에 만든 트리거 표가 스스로 "전체 목록"이라 선언했는데 `SAFE-10`(응답 불명·중복 의심)이 빠졌고 `AC13`도
    이를 요구하지 않았다 → 차단 계약을 전수 조사해 `SAFE-10`을 추가하고, 트리거 판별 기준(권한 회수 vs 범위 제약)과
    비트리거 예시(`LIVE-8`·`SAFE-2`)를 명시, `AC13` 필수 출처 확장
- 2026-07-28 9라운드: `needs-attention`, critical 0 · high 1.
  - high: `P3-O17`(configuration drift)도 권한 회수 경로인데 트리거 목록과 `AC13`에 없음 → 트리거로 등재하고 판별 기준에
    "Phase outcome과 gate 항목도 대상"을 추가, `P3-O17` 본문에 epoch 무효화·`FENCE`·전이를 명시, `AC13` 확장
- 2026-07-30 10라운드: `needs-attention`, critical 0 · high 1.
  - high: `P3-O17`의 drift 검사가 제출 직전에만 이뤄져 점검 후 변경이나 제출이 없는 구간의 변경을 탐지하지 못하는 TOCTOU
  - 부류 대응: 트리거 표에 탐지 경로 열을 신설해 8개 전부에 지속·주기 감시와 재시작 재평가를 요구하고, 점검-전송 사이
    변화를 막는 상태 version 결속 규칙을 추가. 스윕에서 `LIVE-10`·`SAFE-9`·`SAFE-3`의 같은 부류 결함 3건을 함께 수정.
    `AC14`로 기계 검사
- 2026-07-30 11라운드: `needs-attention`, critical 0 · high 1.
  - high: `ACTIVATION_RECOVERY_ONLY`에서 `ACTIVATION_IN_PROGRESS`로 돌아가는 재개 전이가 정의되지 않아 stale 근거로
    재활성화될 수 있음
  - 부류 대응: "회수만 정의하고 재개가 빈 상태" 부류로 보고 전 상태를 스윕한 결과 저하·종결 상태 전부에 복귀 조건이
    없었다. `LIVE-12`로 `RESUME`(FENCE terminal 확인·전체 reconcile·트리거 해소·evidence/configuration 재승인·owner
    재승인·새 epoch)을 단일 정의하고 상태 표에 재개 조건 열을 신설, `AC15`로 기계 검사
- 2026-07-30 12라운드: `needs-attention`, critical 0 · high 1.
  - high: `FENCE` 미완료 구간의 `recovery-required`가 §4.2·§4.3에 없어 허용 제출·재시도·재시작 복원·다음 전이가 미정의
  - 부류 대응: "이름만 있고 등재되지 않은 상태" 부류로 스윕해 `halt latch 활성`도 같은 상태임을 확인했다.
    `ACTIVATION_FENCE_PENDING`과 execution latch 축(`LATCH_ENGAGED`/`LATCH_CLEAR`)을 정식 등재하고 §4.3 행·재시작 복원·
    idempotent 재시도·완료 후 목적 상태를 정의, `AC16`으로 기계 검사
- 2026-07-30 13라운드: `needs-attention`, critical 0 · high 2 (둘 다 12라운드 상태 추가가 만든 모순).
  - high: 중단의 전이 대상이 latch 축만 지정해 `FENCE` 완료 후 activation 권한이 미정 → 트리거 표를 두 축 형식으로
    통일하고 목적 상태를 `FENCE` context에 durable 저장, `AC17`로 기계 검사
  - high: `ACTIVATION_FENCE_PENDING`이 `RECOVERY-A`(unwind 포함)를 허용해 "새 경제적 제출 금지"·`SAFE-10`과 충돌 →
    `RECOVERY-0`(FENCE 수행에 필요한 취소·조회만)을 `LIVE-11`에 신설하고 해당 행을 `RECOVERY-0` 전용으로 정정
- 2026-07-30 14라운드: `needs-attention`, critical 0 · high 2.
  - high: `ACTIVATION_FENCE_PENDING` 중 다른 트리거가 발생할 때 `FENCE` context의 병합·승격 규칙이 없어 나중 중단·종결
    요구가 유실될 수 있음 → context를 누적 전이 요청으로 정의하고 축별 최대 제한 병합, latch·program 우선순위, 모든
    요청 종결 시에만 완료를 명시
  - high: execution latch의 초기값 누락 → 부류 스윕으로 specification·candidate/evidence 축도 누락임을 확인해 함께 채우고,
    latch 값 누락·손상 시 `LATCH_ENGAGED` fail-closed 규칙과 `AC18` 기계 검사를 추가
- 2026-07-30 15라운드: `needs-attention`, **critical 0 · high 0 · medium 1**. 1라운드 이후 처음으로 high가 사라졌다.
  - medium: candidate/evidence 초기값이 등록 상태가 아닌 서술형이고 `AC18`이 축 이름 존재만 검사해 통과시킴 →
    `CANDIDATE_NOT_SELECTED`를 등재하고 candidate 생성·폐기·재시작 전이를 정의, `AC18`을 값 유효성까지 검사하도록 강화
- 2026-07-30 16라운드: `needs-attention`, critical 0 · high 1.
  - high: §4.2가 activation 축 전이를 사용자 승인으로 선언한다고 해서 안전 트리거의 즉시 회수가 승인 대기로 지연될 수
    있음 → 전이 선언 주체를 방향별로 분리(위험 증가는 승인, 위험 감소는 runtime 즉시 durable 수행)하고 `AC19`로 기계 검사
- 2026-07-30 17라운드: `needs-attention`, critical 0 · high 1 · medium 1.
  - high: `FENCE` 완료 기준이 "모든 요청 종결"로만 서술돼 원인 해소로 해석되면 `RECOVERY-0`만 허용되는
    `ACTIVATION_FENCE_PENDING`에 갇혀 노출을 줄이지 못함 → 완료를 `FENCE-1`~`FENCE-3` terminal 결과로만 정의하고 원인
    해소는 `RESUME` 선행조건으로 이동
  - medium: owner가 "사후 인지와 RESUME에서만 관여"한다는 규칙이 owner의 중단·NO_GO 선언과 충돌 → owner는 외부 트리거를
    발행하고 전이 실행은 runtime이 즉시 수행하는 역할 분리로 §4.2·§4.3·§9.4·plan Z2를 정렬
- 2026-07-30 18라운드: `needs-attention`, critical 0 · high 1.
  - high: `RECOVERY-A`가 fill마다 모든 지표 비증가를 요구해, 두 거래소의 비원자적 순차 청산에서 먼저 체결된 leg가
    residual을 일시 증가시키므로 완전 헤지 포지션의 청산을 시작조차 할 수 없음 → `RECOVERY-C`(bounded paired reduction)를
    신설해 사전 승인 한도·예약 안에서 일시적 증가를 허용하고 전체 위험 감소를 검증하도록 정의. `SAFE-7`의 breach 대응
    용어도 여기에 통일
- 2026-07-30 19라운드: `needs-attention`, critical 0 · high 1.
  - high: 18라운드에 신설한 `RECOVERY-C`가 `P3-O14`와 §9.4에 전파되지 않아 만료·종결 시 완전 헤지 pair 청산 권한이
    상태 표와 모순 → 부류 스윕으로 `LIVE-11` 차단 조건 문장의 누락 1건을 추가 발견하고 세 곳을 단일 정의 참조로 전환,
    `AC21`로 기계 검사
- 2026-07-30 20라운드: `needs-attention`, critical 0 · high 1.
  - high: `RECOVERY-C`가 margin breach 중에도 상시 허용이라 정적 승인 한도 안이어도 현재 margin이 worst-case 체결 경로를
    견디지 못해 강제 청산을 촉발할 수 있음 → 제출 직전 현재 account·margin 기준 실현 가능성 검사에 결속하고 실패 시
    `RECOVERY-A`·owner fallback으로 분기, `AC22`로 기계 검사
- 2026-07-30 21라운드: `needs-attention`, critical 0 · high 1.
  - high: `RECOVERY-C`의 동적 headroom 검사가 `SAFE-3`(상태 불신) 활성 중에도 허용돼, 신뢰할 수 없는 입력으로 계산한
    margin·수량으로 청산을 시작할 수 있었음 → 상태 의존 복구 집합 전체에 입력 신뢰 전제를 도입하고 불신 중에는 취소와
    owner fallback만 남기도록 정의, `AC23`으로 기계 검사
- 2026-07-30 22라운드: `needs-attention`, critical 0 · high 2.
  - high: `FENCE-2`가 exposure-increasing 주문만 취소해 진행 중인 unwind·hedge 주문이 살아남아 새 복구 결정과 경합 →
    복구 노출에 영향을 주는 모든 working 주문으로 범위 확대
  - high: 아홉 번 참조된 owner fallback이 정의된 적 없음 → `LIVE-13`으로 fenced 비상 절차 정의(허용 범위, outstanding
    주문 취소·확인, 수동 체결 durable 기록, reconcile과 새 epoch 전 자동 복구·`RESUME` 금지), 용어 통일, `AC24` 기계 검사
- 2026-07-30 23라운드: `needs-attention`, critical 0 · high 1.
  - high: `LIVE-13` fallback의 수동 조치가 전략 노출에 매핑되는지 여부가 정의되지 않아 `SAFE-5`(자동 흡수 금지)와 충돌 →
    `SAFE-5`를 귀속 절차의 단일 정의로 확장(owner 확인 매핑, `unmanaged` 분류, 매핑된 노출만 reconcile 종결,
    `unmanaged` 잔존 시 `RESUME` 차단)하고 `LIVE-13`·`SAFE-7`·`RESUME`이 참조, `AC25` 기계 검사
- 추이: 1R 8건(critical 1) → 2R 2건(critical 1) → 3~14R 각 1~2건 → 15R medium 1 → 16~23R 각 1~2건. critical은 2R 이후 0. 3~8라운드 지적은 모두
  "권한이 회수될 때 무엇이 허용되고 어떤 fence가 걸리는가"라는 한 매듭의 다른 표면이었고, 5·6·7라운드에서 각각 복구
  권한·전이 fence·회수 트리거를 단일 정의로 접은 뒤 8라운드에서 그 목록을 전수 조사로 닫았다.

### AC9 — 2026-07-28

- GREEN(초기): `activation_states=5 covered=5 missing=[] undefined_in_axis=[]`, exit 0
- 변형 검증(RED): §4.2에 `ACTIVATION_SUSPENDED`를 추가하고 §4.3 권한 표에는 넣지 않은 변형본에서
  `missing=['ACTIVATION_SUSPENDED']`, exit 1. 검사가 실제로 누락을 탐지한다.
- RED(실사용): Codex 2라운드 반영으로 §4.3에 program 축 행을 추가하자
  `undefined_in_axis=['PROGRAM_TERMINATED_NO_GO', 'PROGRAM_TERMINATION_PENDING']`, exit 1로 검사가 걸렸다.
- 검사 확장: activation 축은 전건 필수, 다른 축은 §4.2에 정의된 상태만 허용하도록 명령을 갱신했다.
- GREEN(확장 후): `activation=5 covered=7 missing=[] undefined_in_axis=[]`, exit 0
- 변형 재검증(RED): 존재하지 않는 `ACTIVATION_GHOST` 행을 넣은 변형본에서 `undefined_in_axis=['ACTIVATION_GHOST']`, exit 1

### AC10 — 2026-07-28

- GREEN: `assigned=['Gate', 'Phase 0', 'Phase 1', 'Phase 2', 'Phase 3'] missing=[]`, exit 0
- 변형 검증(RED): §8.1 정직성 표에서 Phase 2 행을 삭제한 변형본에서 `missing=['Phase 2']`, exit 1

### AC11 — 2026-07-28

- RED(최초): 구조 변경 직후 검사가 `ACTIVATION_IN_PROGRESS`와 `PRIVATE_LIVE_ACTIVE_COMPLETE` 두 행의 자유 서술을 탐지했다.
  codex 5라운드가 지적한 `PROGRAM_TERMINATION_PENDING` 외에 같은 부류가 2건 더 남아 있었다.
- GREEN: 두 행을 집합 참조로 통일한 뒤 `freeform_recovery_rows=[]`, exit 0
- 변형 검증(RED): `PROGRAM_TERMINATION_PENDING` 행을 "정리 범위만 가능"으로 되돌린 변형본에서 해당 행이 탐지됨, exit 1

### AC12 — 2026-07-28

- GREEN: `unfenced_blocking_rows=[]`, exit 0
- 변형 검증(RED): `ACTIVATION_RECOVERY_ONLY` 행의 fence를 "미전송 무효"로 되돌린 변형본에서 해당 행 탐지, exit 1

### AC13 — 2026-07-28

- GREEN: `rows=6 without_fence=[] missing_sources=[]`, exit 0
- 이 검사는 codex 7라운드가 지적한 `AC12`의 사각지대(표 밖 guard 경로)를 덮는다.

### AC11·AC12 파서 범위 수정 — 2026-07-30

- RED: §4.3에 트리거 표가 추가되자 `AC11` 파서가 두 표를 구분하지 못해 트리거 행 8개를 오탐(`freeform_recovery_rows` 9건)했다.
- 같은 부류(표 파싱 검사의 범위 미지정)로 `AC11`·`AC12`를 상태 표로 명시 범위 한정했다. `AC14`는 처음부터 트리거 표로
  한정돼 있어 영향이 없었다.
- GREEN: 두 검사 모두 `[]`, exit 0. 변형 검증에서 `ACTIVATION_RECOVERY_ONLY` 행을 자유 서술로 되돌리자 둘 다 탐지, exit 1.

### AC14 — 2026-07-30

- GREEN: `rows=8 incomplete_detection=[]`, exit 0
- 부류 스윕 결과: 10라운드 지적은 `P3-O17` 하나였지만 같은 부류를 훑자 `LIVE-10`과 `SAFE-9`는 탐지 시점 자체가 없었고
  `SAFE-3`도 지속 감시가 명시되지 않았다. 네 계약을 함께 고쳤다.

### AC15 — 2026-07-30

- GREEN: `missing_resume=[]`, exit 0
- 부류 스윕: 11라운드 지적은 `ACTIVATION_RECOVERY_ONLY` 하나였지만, 확인 결과 저하·종결 상태 전부에 복귀·해제 조건이
  없었다. halt latch 해제, `PROGRAM_TERMINATION_PENDING`의 재개 불가 명시, `CANDIDATE_REJECTED` 이후 `ACT-1` 재통과까지
  함께 정의했다.

### AC16 — 2026-07-30

- RED(최초): 부류 스윕에서 `recovery-required` 2회, `halt latch 활성` 2회가 상태축 밖 이름으로 사용 중임을 확인했다.
- 반영: `ACTIVATION_FENCE_PENDING`(activation 축)과 `LATCH_ENGAGED`·`LATCH_CLEAR`(execution latch 축 신설)로 정식 등재하고
  §4.3에 행을 추가했다. 잔여 사용처 2곳도 검사가 잡아 정리했다.
- GREEN: `informal=[]`, exit 0

### AC17 — 2026-07-30

- GREEN: `incomplete_target=[]`, exit 0
- 부류 스윕: 13라운드 지적은 정상·긴급 중단 1건이었으나 확인 결과 8개 트리거 전부가 한 축만 지정하고 있었다.
  전이 대상 열을 "진입 → `FENCE` 완료 후 / latch 축" 형식으로 통일하고 목적 상태의 durable 저장 규칙을 추가했다.

### AC18 — 2026-07-30

- GREEN(초기): `axes=7 missing_initial=[]`, exit 0
- 강화: 15라운드가 "축 이름 존재만 검사해 서술형 초기값을 통과시킨다"를 지적해, 초기값이 해당 축의 등록 상태인지까지
  검증하도록 명령을 교체했다. `candidate/evidence`의 "해당 candidate 없음"을 `CANDIDATE_NOT_SELECTED`로 정식 등재한 뒤
  `axes=7 invalid_initial=[]`, exit 0
- 부류 스윕: 14라운드 지적은 execution latch 1건이었으나 확인 결과 specification과 candidate/evidence 축도 초기값
  선언에 없었다. 세 축을 함께 채우고 축 신설 시 초기값 정의 의무를 규칙으로 명시했다.

### AC19 — 2026-07-30

- GREEN: `violations=[]`, exit 0
- §4.2의 전이 선언 주체를 방향별로 분리했다. 위험 증가 방향은 사용자 승인, 위험 감소 방향은 runtime 즉시 durable 수행이며
  승인·gate 기록·알림 전달을 기다리지 않는다.

### AC20 — 2026-07-30

- GREEN: `violations=[]`, exit 0
- `FENCE` 완료를 `FENCE-1`~`FENCE-3`의 terminal 결과로만 정의해, 원인이 지속되는 트리거에서도 목적 상태로 전이해
  `RECOVERY-A`/`RECOVERY-B` unwind가 가능하도록 했다. 원인 해소는 `RESUME` 선행조건으로 남는다.

### AC21 — 2026-07-30

- RED(스윕): 19라운드 지적은 `P3-O14`와 §9.4 두 곳이었으나 전수 확인 결과 `LIVE-11`의 `RECOVERY-B` 차단 조건 문장도
  `RECOVERY-C`를 빠뜨려 `SAFE-7` breach 대응과 모순이었다. 세 곳을 모두 고쳤다.
- 반영 방식: 열거 대신 §4.3과 `LIVE-11` 단일 정의를 참조하도록 바꿔 재발 여지를 줄였다.
- GREEN: `inconsistent_enumerations=[]`, exit 0

### AC22 — 2026-07-30

- GREEN: `violations=[]`, exit 0
- `RECOVERY-C`를 "상시 허용"에서 "현재 headroom 검사 통과 시"로 바꾸고, 검사 실패 시 `RECOVERY-A` 또는 owner fallback으로
  분기하도록 정의했다. §4.3의 다섯 행과 `P3-O6`, `LIVE-11` 차단 조건도 같은 표현으로 맞췄다.

### AC23 — 2026-07-30

- GREEN: `violations=[]`, exit 0
- `RECOVERY-A`의 unwind, `RECOVERY-B`, `RECOVERY-C`는 시장·account·order·position 입력이 신뢰 가능할 때만 허용하고,
  `SAFE-3` 활성 중에는 취소와 owner fallback만 남도록 `LIVE-11`과 §4.3에 우선 규칙을 넣었다.

### AC24 — 2026-07-30

- GREEN: `violations=[]`, exit 0
- `FENCE-2`를 복구 노출에 영향을 주는 모든 working 주문으로 넓히고, 유지할 경우 durable 복구 작업으로 직렬화하도록 했다.
- `LIVE-13`으로 owner fallback을 fenced 비상 절차로 정의하고, 부류 스윕에서 발견한 `DONE-5`의 `operator fallback`과
  `SAFE-6`의 "수동 거래소 fallback" 표현을 같은 용어로 통일했다.

### AC25 — 2026-07-30

- GREEN: `violations=[]`, exit 0
- `SAFE-5`를 귀속 절차의 단일 정의로 확장하고 `LIVE-13`·`SAFE-7`·`LIVE-12`가 참조하도록 연결했다. 부류 스윕에서 거래소
  강제 감축·ADL 결과도 같은 귀속 공백이 있었음을 확인해 함께 묶었다.

### AC8 — 대기

- 사용자 승인 미수령. 승인 전까지 `status: DRAFT`를 유지하고 Phase 0으로 진행하지 않는다.

## 최종 판정

```text
DoD VERDICT: private-live-autotrader-master-spec
  T1/T2 자동:      23/23 PASS
  T3 기록 제출:    0건
  T4 사람 확인:    2건 대기 (AC7 리뷰 수렴, AC8 사용자 승인)
  => AWAITING_HUMAN
```

**사람 확인이 필요한 항목**

- AC7 — `codex-spec-review` 재검토에서 critical·high 0 확인
- AC8 — 사용자 승인 후 `status: FROZEN`, `frozen_at` 기입

## Evidence 기록 소유권

수용기준 문장은 동결 이후 변경하지 않는다. 증거 로그는 각 검증을 실행한 직후 해당 AC 절에 append하고, `최종 판정`
블록의 숫자와 상태만 갱신한다. 기준 자체를 바꿔야 하면 `## 변경 요청` 절을 추가해 사용자 재승인을 받는다.
