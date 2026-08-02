---
feature: PRIVATE LIVE autotrader Phase 0 — Foundation Alignment
slug: private-live-autotrader-phase-0
status: DRAFT
frozen_at:
source: docs/work/private-live-autotrader/design.md §5 Phase 0 (P0-O1~P0-O5, SEM-1~SEM-4, ARCH-7, ARCH-9)와 사용자 합의(feature-workflow ③, 2026-08-02)
---

## 범위

**포함**

- 추적 record가 실제 주문이 아님을 REST 계약·도메인 타입·화면 문구가 일관되게 드러내도록 정렬
- gross 손익의 보장 범위(제외 항목·백분율 분모·레버리지 무관성)를 응답 스키마와 화면이 명시
- 종료된 추적의 손익이 현재 시세로 변동하는 결함을 청산 스냅샷 확정으로 수정 (Flyway `V15`)
- `ARCH-9` 정본 identity 격차 판정과 확장 담당 Phase 배정 (문서)
- dead·미연결 계약의 유지·수정·제거 결정과 실행
- As-Is 아키텍처 문서와 Planned capability 문서의 상호 참조

**제외** *(scope creep 차단선)*

- `MarketPair` 타입 확장, Redis key 포맷 변경, premium/ticker/notification 도메인 변경
- 수수료·펀딩비·슬리피지를 반영한 순손익 모델 — Phase 1 `P1-O5`
- 계정 ROI, 실제 체결 손익, 외부 계정 대조, 주문 실행 경로
- `position` DB 테이블명 변경
- 인증·보안 경계 변경
- 동결 산출물 변경: `docs/work/private-live-autotrader/**`,
  `.ai/planning/private-live-autotrader/phase-minus-1-*.md`,
  `docs/dod/private-live-autotrader-phase-minus-1.dod.md`

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 원문 | 티어 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|
| AC1 | 실행 소스와 HTTP 샘플에 `positions` REST 경로가 남아 있지 않다. | `P0-O1`, `SEM-1`, D1 | T1 | 아래 `AC1 command` | exit 0, `leftover=[]` |
| AC2 | 실행 소스의 Kotlin 타입·패키지에 `Position` 식별자가 남아 있지 않다 (`@Table(name = "position")`과 V12 동결 guard 제외). | D1, `SEM-2` | T1 | 아래 `AC2 command` | exit 0, `leftover=[]` |
| AC3 | `gross-pnl` 응답이 `pnlBasis`·`priceBasis`·`observedAt`을 갖고, 분모를 감춘 옛 필드명(`totalPnlPercent`, `isProfit`, `koreaCurrentValue`)이 없다. | `P0-O2`, `SEM-4`, D3 | T1 | 아래 `AC3 command` | exit 0, `missing=[] leftover=[]` |
| AC4 | 화면이 비주문 고지와 gross 각주(수수료·펀딩비·슬리피지·환전 스프레드 제외, 계정 손익 아님)와 레버리지 무관성 각주와 프리미엄 방향 설명을 모두 표시한다. | `SEM-1`, `SEM-3`, `SEM-4` | T1 | 아래 `AC4 command` | exit 0, `missing=[]` |
| AC5 | 종료된 추적의 gross 손익이 이후 시세 변동에 영향받지 않는다. 시세를 확정하지 못한 종료는 손익 대신 `409 TRACKING_CLOSE_SNAPSHOT_UNAVAILABLE`을 반환한다. | §3.3 실제 결함, D2 | T2 | `./gradlew :apps:api:integrationTest --tests '*TrackingArchive*' --offline --no-daemon` | exit 0, 확정성·409 케이스 모두 통과 |
| AC6 | identity 판정이 4개 누락 항목(양 leg의 instrument class·quote currency), 기존 자산의 유효 범위, 확장 담당 Phase를 모두 명시한다. | `P0-O3`, `ARCH-9`, D5 | T1 | 아래 `AC6 command` | exit 0, `missing=[]` |
| AC7 | dead·미연결 계약 4건이 각각 유지/수정/제거로 판정되고, 제거 판정 항목이 실행 소스에서 사라졌다. | `P0-O4` | T1 | 아래 `AC7 command` | exit 0, `undecided=[] not_removed=[]` |
| AC8 | As-Is 문서에 `Planned capability` 절과 Planned 문서 링크가 있고, Planned 문서에서 As-Is 문서로의 역참조가 있다. | `P0-O5`, `ARCH-7` | T1 | 아래 `AC8 command` | exit 0, `missing=[]` |
| AC9 | 추적 endpoint 8개가 모두 인증을 요구한다. `PublicEndpointPolicy`에 추적 경로가 추가되지 않았다. | 범위 제외 "인증 경계 변경", `.ai/rules/http.md` | T2 | 아래 `AC9 command` | exit 0, 미인증 요청 전부 401 |
| AC10 | `V15`가 빈 DB latest 경로와 `V14`→`V15` 경로에서 모두 적용되고, 기존 `OPEN`/`CLOSED` 행이 `ACTIVE`/`ARCHIVED`로 전이하며 기존 종료 행이 `LEGACY_UNKNOWN`을 갖는다. | D2, D4, `.ai/rules/testing.md` migration 검증 | T2 | `./gradlew :infrastructure:common:integrationTest --tests '*V15*' --offline --no-daemon` | exit 0 |
| AC11 | Flyway version uniqueness와 destructive SQL gate를 통과한다. | 기존 repository gate | T2 | `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` | exit 0 |
| AC12 | unit·contract test와 architecture 경계 test가 통과한다. | 기존 repository gate, `.ai/rules/architecture.md` | T2 | `./gradlew test architectureTest --offline --no-daemon` | exit 0 |
| AC13 | API·batch·infrastructure 통합 test가 통과한다. | 기존 repository gate | T2 | `./gradlew :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --offline --no-daemon` | exit 0 |
| AC14 | 웹 lint와 production build가 통과한다. | `apps/web` 동시 수정 (D1) | T2 | `cd apps/web && npm ci && npm run lint && npm run build` | exit 0 |
| AC15 | 저장소 문서 계약과 whitespace 계약이 유지된다. | 기존 repository gate | T1 | `bash docs/check-documentation.sh && git diff --check` | exit 0, `documentation check passed` |
| AC16 | `docs/work/private-live-autotrader-phase-0/`에 workflow 산출물 4종이 존재하고 상대 링크가 모두 실재 파일을 가리킨다. | `feature-workflow` ④⑤⑪-b | T1 | 아래 `AC16 command` | exit 0, `missing=[] broken_links=[]` |
| AC17 | 동결 산출물(마스터 spec 4종, Phase -1 3종)이 이 브랜치에서 변경되지 않았다. | 범위 제외 "동결 산출물 변경" | T1 | 아래 `AC17 command` | exit 0, `modified=[]` |
| AC18 | `design.md` §7 outcome 추적표의 모든 계약이 근거 절과 검증 AC를 갖고, 참조된 AC가 이 계약서에 실재한다. | 상위 spec `P0-O1`~`ARCH-9` 배정 | T1 | 아래 `AC18 command` | exit 0, `empty_cells=[] dangling_ac=[]` |
| AC19 | 미해결 결정(§6)이 모두 이월 대상 Phase를 갖고, Phase 1 진입을 차단하지 않음이 명시된다. | 상위 spec §5 Phase 1 진입 조건 | T1 | 아래 `AC19 command` | exit 0, `unassigned=[]` |
| AC20 | 외부 관점 스펙 리뷰가 수렴한다. 동일 렌즈 재검토에서 critical·high가 0이다. | `feature-workflow` ⑥ | T4 | `codex-spec-review` 재실행 후 verdict 기록 | 재검토 critical·high 0 |
| AC21 | 사용자가 `design.md`·`plan.md`·`dod.md`를 승인하고 이 계약서가 `FROZEN`으로 전이한다. §5.2 도메인 rename 포함 여부를 명시적으로 확인받는다. | `feature-workflow` ⑦, `design.md` D1 단서 | T4 | 사용자 승인 기록 | `status: FROZEN` + `frozen_at` 기입 |
| AC22 | 사용자가 구현 결과를 확인하고 `progress.md`에 `FOUNDATION_ALIGNED`가 append된다. | `feature-workflow` ⑩, 상위 spec 종료 판정 | T4 | 사용자 승인 기록 + `progress.md` diff | `FOUNDATION_ALIGNED` 기록 |

### 검증 명령

모든 명령은 저장소 루트에서 `bash`로 실행한다.

> **도구 제약 (필수):** 검증 명령은 `grep`·`awk`·`sed`·`find`·`git`·`gradlew`·`npm`만 사용한다. `rg`처럼
> 개발자 환경에만 설치된 도구는 **비대화형 셸과 CI runner의 PATH에 없어 조용히 0건을 반환하고 검사를
> 통과시킨다.** 이 계약서 작성 중 실제로 `rg` 기반 AC1·AC2·AC7이 구현 전인데도 GREEN을 반환하는 것을
> 확인했다. 같은 부류를 Phase -1이 이미 겪었다(`f40d4b2 fix: CI 계약 검사의 runner 도구 의존성 제거`).

#### AC1 command

```bash
api_http=$(grep -rn --exclude-dir=build '/api/v1/positions' apps/api/src http 2>/dev/null | grep -c . || true)
web=$(grep -rn --exclude-dir=node_modules --exclude-dir=.next "'/positions" apps/web/src 2>/dev/null | grep -c . || true)
webpath=$([ -d apps/web/src/app/positions ] && echo 1 || echo 0)
echo "leftover=[api_http=$api_http web=$web web_route_dir=$webpath]"
[ "$api_http" -eq 0 ] && [ "$web" -eq 0 ] && [ "$webpath" -eq 0 ]
```

#### AC2 command

```bash
leftover=$(grep -rn --include='*.kt' --exclude-dir=build 'Position' \
  apps/api/src/main apps/batch/src/main domain/src/main \
  infrastructure/api/src/main infrastructure/batch/src/main infrastructure/common/src/main 2>/dev/null \
  | grep -v 'V12MigrationSafety' \
  | grep -v '@Table(name = "position")')
hits=$(printf '%s\n' "$leftover" | grep -c . || true)
echo "leftover=[$hits hits]"
[ "$hits" -eq 0 ]
```

#### AC3 command

```bash
dto=apps/api/src/main/kotlin/io/premiumspread/interfaces/api/tracking/TrackingDtos.kt
missing=""
for f in pnlBasis priceBasis observedAt grossPnlPercentOfKoreaNotional isGrossProfit \
         koreaLegGrossPnlKrw foreignLegGrossPnlKrw totalGrossPnlKrw koreaLegNotionalKrw; do
  grep -q "$f" "$dto" || missing="$missing $f"
done
leftover=""
for f in totalPnlPercent isProfit koreaCurrentValue premiumDiff positionId; do
  grep -qw "$f" "$dto" && leftover="$leftover $f"
done
echo "missing=[$missing] leftover=[$leftover]"
[ -z "$missing" ] && [ -z "$leftover" ]
```

#### AC4 command

```bash
missing=""
grep -rq "주문을 내지 않습니다" apps/web/src || missing="$missing non-order-notice"
grep -rq "수수료·펀딩비·슬리피지·환전 스프레드" apps/web/src || missing="$missing gross-footnote"
grep -rq "계정 손익이나 실제 체결 손익이 아닙니다" apps/web/src || missing="$missing not-account-pnl"
grep -rq "필요 증거금에만 영향" apps/web/src || missing="$missing leverage-footnote"
grep -rq "프리미엄이 축소될 때 이익" apps/web/src || missing="$missing premium-direction"
grep -rq "한국 leg 명목가 대비" apps/web/src || missing="$missing percent-denominator"
echo "missing=[$missing]"
[ -z "$missing" ]
```

#### AC6 command

```bash
d=docs/work/private-live-autotrader-phase-0/design.md
missing=""
for k in "instrument class" "quote currency" "BITHUMB" "BINANCE" "단일 조합의 관측" "Phase 1"; do
  grep -q "$k" "$d" || missing="$missing ${k// /_}"
done
awk '/^### 5\.5/,/^### 5\.6/' "$d" | grep -q "암묵 전제" || missing="$missing implicit-premise-table"
echo "missing=[$missing]"
[ -z "$missing" ]
```

#### AC7 command

```bash
d=docs/work/private-live-autotrader-phase-0/design.md
undecided=""
for k in "Exchange.UPBIT" "foreignLeverage" "findAllActive" "상태 무가드"; do
  awk '/^### 5\.6/,/^### 5\.7/' "$d" | grep -q "$k" || undecided="$undecided ${k// /_}"
done
not_removed=$(grep -rn --include='*.kt' --exclude-dir=build -E 'findAllActive|findAllOpen|findAllByStatus' \
  domain/src/main infrastructure/common/src/main apps/api/src/main 2>/dev/null | grep -c . || true)
echo "undecided=[$undecided] not_removed=[$not_removed hits]"
[ -z "$undecided" ] && [ "$not_removed" -eq 0 ]
```

#### AC8 command

```bash
asis=.ai/architecture/ARCHITECTURE_DESIGN.md
landing=docs/work/private-live-autotrader/README.md
missing=""
grep -q "^## Planned capability" "$asis" || missing="$missing planned-section"
grep -q "docs/work/private-live-autotrader/design.md" "$asis" || missing="$missing forward-link"
grep -q "ARCHITECTURE_DESIGN.md" "$landing" || missing="$missing back-link"
echo "missing=[$missing]"
[ -z "$missing" ]
```

#### AC9 command

```bash
grep -qi "tracking" infrastructure/api/src/main/kotlin/io/premiumspread/infrastructure/api/security/PublicEndpointPolicy.kt \
  && { echo "tracking path leaked into public policy"; exit 1; }
./gradlew :apps:api:integrationTest --tests '*TrackingController*' --offline --no-daemon
```

#### AC16 command

```bash
dir=docs/work/private-live-autotrader-phase-0
missing=""
for f in design.md plan.md dod.md understanding.md; do
  [ -f "$dir/$f" ] || missing="$missing $f"
done
broken=""
while IFS= read -r target; do
  [ -e "$dir/$target" ] || broken="$broken $target"
done < <(grep -rhoE '\]\(([^)#]+\.md)' "$dir" | sed 's/^](//' | sort -u)
echo "missing=[$missing] broken_links=[$broken]"
[ -z "$missing" ] && [ -z "$broken" ]
```

#### AC17 command

```bash
modified=$(git diff --name-only origin/dev...HEAD -- \
  'docs/work/private-live-autotrader/design.md' \
  'docs/work/private-live-autotrader/dod.md' \
  'docs/work/private-live-autotrader/plan.md' \
  'docs/work/private-live-autotrader/understanding.md' \
  '.ai/planning/private-live-autotrader/phase-minus-1-design.md' \
  '.ai/planning/private-live-autotrader/phase-minus-1-plan.md' \
  'docs/dod/private-live-autotrader-phase-minus-1.dod.md')
echo "modified=[$modified]"
[ -z "$modified" ]
```

AC8의 As-Is 역참조는 동결 대상이 아닌 `docs/work/private-live-autotrader/README.md`가 소유하므로 이 검사와
충돌하지 않는다.

#### AC18 command

```bash
d=docs/work/private-live-autotrader-phase-0/design.md
dod=docs/work/private-live-autotrader-phase-0/dod.md
rows=$(awk '/^## 7\. Outcome 추적/,/^## 8\./' "$d" | grep -E '^\| `(P0-O|SEM-|ARCH-)')
empty=$(echo "$rows" | awk -F'|' '$3 ~ /^[[:space:]]*$/ || $4 ~ /^[[:space:]]*$/ {print $2}')
dangling=""
for ac in $(echo "$rows" | grep -oE 'AC[0-9]+' | sort -u); do
  grep -qE "^\| $ac \|" "$dod" || dangling="$dangling $ac"
done
echo "empty_cells=[$empty] dangling_ac=[$dangling]"
[ -z "$empty" ] && [ -z "$dangling" ]
```

#### AC19 command

```bash
d=docs/work/private-live-autotrader-phase-0/design.md
rows=$(awk '/^## 6\. 미해결 결정/,/^## 7\./' "$d" | grep -E '^\| `OPEN-')
unassigned=$(echo "$rows" | awk -F'|' '$4 !~ /Phase/ {print $2}')
grep -q "차단 요소가 아니다" "$d" || unassigned="$unassigned no-nonblocking-statement"
echo "unassigned=[$unassigned]"
[ -z "$unassigned" ]
```

## 증거 로그

각 수용기준은 구현 전 RED와 구현 후 GREEN을 모두 기록한다. Docker·네트워크 부재로 실행하지 못한 항목은
GREEN으로 기록하지 않고 미실행으로 남긴다 (`.ai/rules/testing.md`).

일부 기준은 **기준선에서 이미 GREEN인 회귀 guard**다. 그런 항목을 RED로 위장하지 않고 그대로 적는다.
회귀 guard는 "구현이 이것을 깨뜨리지 않았다"를 증명하는 것이 역할이다.

RED 기준선 수집: `bash docs/work/private-live-autotrader-phase-0/verify.sh` (2026-08-02, `5319a2d` 기준
worktree, 구현 전) → `GREEN=4 RED=8`.

| # | RED (구현 전) | GREEN (구현 후) |
|---|---|---|
| AC1 | RED — `leftover=[api_http=35 web=6 web_route_dir=1]` | |
| AC2 | RED — `leftover=[131 hits]` | |
| AC3 | RED — `TrackingDtos.kt` 파일 부재 | |
| AC4 | RED — `missing=[non-order-notice gross-footnote not-account-pnl leverage-footnote premium-direction percent-denominator]` (6/6 전부 부재) | |
| AC5 | RED — `TrackingArchive*` 테스트 부재. 현재 `Position.close()`는 청산 시세를 저장하지 않고 `/pnl`에 상태 가드가 없다 (`design.md` §3.3) | |
| AC6 | GREEN — `missing=[]` (§5.5 판정이 `design.md`에 존재) | |
| AC7 | RED — `undecided=[] not_removed=[10 hits]` (판정은 완료, 제거 미실행) | |
| AC8 | RED — `missing=[planned-section forward-link back-link]` (3/3 전부 부재) | |
| AC9 | 회귀 guard — `PublicEndpointPolicy`에 추적 경로 없음(현재도 없음). 통합 test는 rename 후에만 실행 가능 | |
| AC10 | RED — `V15` 미존재 | |
| AC11 | 회귀 guard — 기준선 통과 | |
| AC12 | 회귀 guard — 기준선 통과 | |
| AC13 | 회귀 guard — 기준선 통과 | |
| AC14 | 회귀 guard — 기준선 통과 | |
| AC15 | 회귀 guard — 기준선 통과 | |
| AC16 | RED — `missing=[plan.md understanding.md] broken_links=[plan.md]` → 이후 `plan.md` 작성으로 부분 해소, `understanding.md`는 ⑪-b가 생성 | |
| AC17 | GREEN — `modified=[]` | |
| AC18 | GREEN — `rows=11 empty_cells=[] dangling_ac=[]` | |
| AC19 | GREEN — `rows=5 unassigned=[]` | |
| AC20 | 미실행 — `feature-workflow` ⑥ 대기 | |
| AC21 | 미실행 — `feature-workflow` ⑦ 대기 | |
| AC22 | 미실행 — `feature-workflow` ⑩ 대기 | |

## 최종 판정

```text
DoD VERDICT: private-live-autotrader-phase-0
  T1/T2 자동:      _/19
  T3 기록 제출:    0건
  T4 사람 확인:    _/3
  => (미판정)
```
