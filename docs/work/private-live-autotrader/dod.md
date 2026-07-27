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
| AC7 | 외부 관점 스펙 리뷰(`codex-spec-review`)의 open finding이 0이다. | `feature-workflow` ⑥ | T4 | `codex-spec-review` 실행 후 verdict 기록 | open finding 0 또는 반영 완료 |
| AC8 | 사용자가 `design.md`·`plan.md`·`dod.md`를 승인하고 이 계약서가 `FROZEN`으로 전이한다. | `feature-workflow` ⑦ | T4 | 사용자 승인 기록 | `status: FROZEN` + `frozen_at` 기입 |

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

### AC7 — 대기 (1라운드 완료)

- 2026-07-27 codex `adversarial-review` 1라운드: `needs-attention`, critical 1 · high 5 · medium 2
- REBUT 0건, 8건 전부 ACCEPT하고 `design.md`·`plan.md`에 반영 (상세는 `progress.md` "Codex 외부 스펙 리뷰")
- Codex가 동일 adversarial 시나리오의 재검토를 권고했고 그 2라운드는 미실행이므로 open finding 0을 아직 선언하지 않는다.

### AC8 — 대기

- 사용자 승인 미수령. 승인 전까지 `status: DRAFT`를 유지하고 Phase 0으로 진행하지 않는다.

## 최종 판정

```text
DoD VERDICT: private-live-autotrader-master-spec
  T1/T2 자동:      6/6 PASS
  T3 기록 제출:    0건
  T4 사람 확인:    2건 대기 (AC7 외부 스펙 리뷰, AC8 사용자 승인)
  => AWAITING_HUMAN
```

**사람 확인이 필요한 항목**

- AC7 — `codex-spec-review` 실행과 ACCEPT/REBUT 루프 종료
- AC8 — 사용자 승인 후 `status: FROZEN`, `frozen_at` 기입

## Evidence 기록 소유권

수용기준 문장은 동결 이후 변경하지 않는다. 증거 로그는 각 검증을 실행한 직후 해당 AC 절에 append하고, `최종 판정`
블록의 숫자와 상태만 갱신한다. 기준 자체를 바꿔야 하면 `## 변경 요청` 절을 추가해 사용자 재승인을 받는다.
