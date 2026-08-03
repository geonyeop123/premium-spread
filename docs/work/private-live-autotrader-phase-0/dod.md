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
- 동결 산출물 변경 — **아래 7개 파일에 한정한다.** 디렉터리 전체가 아니다:
  `docs/work/private-live-autotrader/{design,plan,dod,understanding}.md`,
  `.ai/planning/private-live-autotrader/phase-minus-1-{design,plan}.md`,
  `docs/dod/private-live-autotrader-phase-minus-1.dod.md`
  (`docs/work/private-live-autotrader/README.md`는 **동결 대상이 아니며** `design.md` §5.7과 `plan.md` T7이
  As-Is 역참조 추가를 요구한다. `AC17`의 검사 목록이 이 7개와 정확히 같다)

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 원문 | 티어 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|
| AC1 | 실행 소스와 HTTP 샘플에 `positions` REST 경로가 남아 있지 않다. 인용 부호 종류와 무관하게 검사한다. | `P0-O1`, `SEM-1`, D1 | T1 | 아래 `AC1 command` | exit 0, `leftover=[…0…] new_route=1` |
| AC2 | 실행 소스의 Kotlin 타입·패키지에 `Position` 식별자가 남아 있지 않다 (`@Table(name = "position")`과 V12 동결 guard 제외). | D1, `SEM-2` | T1 | 아래 `AC2 command` | exit 0, `leftover=[0 hits]` |
| AC3 | **실제 HTTP 응답**이 `pnlBasis`·`priceBasis`·`observedAt`과 분모를 드러낸 필드명을 갖고, 옛 필드명이 응답에 없다. 파일 텍스트가 아니라 응답 body를 검증한다. | `P0-O2`, `SEM-4`, D3 | T2 | 아래 `AC3 command` | exit 0 |
| AC4 | 목록·상세 화면 **렌더 결과**에 비주문 고지, gross 각주(수수료·펀딩비·슬리피지·환전 스프레드 제외, 계정 손익 아님), 레버리지 무관성 각주, 프리미엄 방향 설명, 분모 라벨이 나타난다. 문자열 존재가 아니라 DOM 출현을 검증한다. | `SEM-1`, `SEM-3`, `SEM-4` | T2 | 아래 `AC4 command` | exit 0 |
| AC5 | `design.md` §5.3.2 "요청·응답 계약"이 실제 응답으로 성립하고, 잠금 경로가 soft-delete 필터와 소유권 검증을 유지한다. 특히 **`archive`는 snapshot 부재·stale에도 `200`을 반환하고**, `409`는 그 추적의 `gross-pnl` 조회에서만 나온다. 동시 archive 중 정확히 하나만 확정하고 나머지는 `400 INVALID_TRACKING`을 받는다. | §3.3 실제 결함, D2, §5.3.2, §5.3.5 | T2 | 아래 `AC5 command` | exit 0, 케이스 1~8 전부 통과 |
| AC6 | identity 판정이 4개 누락 항목(양 leg의 instrument class·quote currency), 기존 자산의 유효 범위, 확장 담당 Phase를 모두 명시한다. | `P0-O3`, `ARCH-9`, D5 | T1 | 아래 `AC6 command` | exit 0, `missing=[]` |
| AC7 | dead·미연결 계약 4건이 각각 유지/수정/제거로 판정되고, 제거 판정 항목이 실행 소스에서 사라졌다. | `P0-O4` | T1 | 아래 `AC7 command` | exit 0, `undecided=[] not_removed=[]` |
| AC8 | As-Is 문서에 `Planned capability` 절과 Planned 문서 링크가 있고, Planned 문서에서 As-Is 문서로의 역참조가 있다. | `P0-O5`, `ARCH-7` | T1 | 아래 `AC8 command` | exit 0, `missing=[]` |
| AC9 | 추적 endpoint 8개가 모두 인증을 요구한다. `PublicEndpointPolicy`에 추적 경로가 추가되지 않았다. | 범위 제외 "인증 경계 변경", `.ai/rules/http.md` | T2 | 아래 `AC9 command` | exit 0, 미인증 요청 전부 401 |
| AC10 | `V15`가 빈 DB latest 경로와 `V14`→`V15` 경로에서 모두 적용되고, 기존 종료 행이 `LEGACY_UNKNOWN`을 가지며, `status` 컬럼 값이 `OPEN`/`CLOSED`로 **보존**된다. | D2, D4, `.ai/rules/testing.md` migration 검증 | T2 | `./gradlew :infrastructure:common:integrationTest --tests '*V15*' --offline --no-daemon` | exit 0 |
| AC25 | 확정 판정 규칙의 6개 필드 중 **어느 하나라도 `NULL`인 행**이 전부 fail-closed로 읽힌다. 이전 image가 종료시킨 전부-`NULL` 행과 `MARKET_SNAPSHOT` 부분 행 모두 `gross-pnl` `409`이며 예외로 죽지 않는다. | §5.3.2 확정 판정 규칙, §5.8, codex 2R high-1·3R high-1 | T2 | 아래 `AC25 command` | exit 0, L1~L8 전부 통과 |
| AC23 | `V15`의 `ALTER` 연산이 **`ADD COLUMN`뿐**이고 모든 `UPDATE`의 모든 `SET` 대상이 같은 migration이 추가한 컬럼이다(fail-closed allowlist). 검사기는 우회 표본 11종(다중 SET·별칭·따옴표·복수 문장·`MODIFY`/`DROP`/`CHANGE`/`RENAME`/`ALTER COLUMN`·`COLUMN` 생략형·`DELETE FROM`)을 실제로 잡는지 self-test로 먼저 증명한다. | `docs/runbooks/deployment.md` Rollback 제약, D4, §5.8, codex 5R medium-2·6R high-1 | T1 | 아래 `AC23 command` | exit 0, `self_test=ok rewrites_existing=[] disallowed=[] destructive=[]` |
| AC26 | 범위 **제외** 선언이 실제로 지켜졌다. `MarketPair`, `modules/redis`, Redis 계약 runbook, premium·notification 도메인이 이 브랜치에서 변경되지 않았고 `@Table(name = "position")`이 정확히 1개 유지된다. | 범위 제외 절, codex 6R medium-2 | T1 | 아래 `AC26 command` | exit 0, `changed=[] table=1` |
| AC24 | 상태 변환 경계가 converter 한 곳에 모인다. 저장값 리터럴이 **실행 소스 전체와 SQL resource** 어디에도 없고(converter와 Flyway migration만 예외), converter를 우회하는 native query와 `position` 테이블 raw SQL이 **모든 실행 모듈**에 없다. | D4, §5.8, codex 3R medium-3·4R high-2 | T1 | 아래 `AC24 command` | exit 0, `missing=[] leaked=[] native=[] rawsql=[]` |
| AC11 | Flyway version uniqueness와 destructive SQL gate를 통과한다. | 기존 repository gate | T2 | `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` | exit 0 |
| AC12 | unit·contract test와 architecture 경계 test가 통과한다. | 기존 repository gate, `.ai/rules/architecture.md` | T2 | `./gradlew test architectureTest --offline --no-daemon` | exit 0 |
| AC13 | API·batch·infrastructure 통합 test가 통과한다. | 기존 repository gate | T2 | `./gradlew :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --offline --no-daemon` | exit 0 |
| AC14 | 웹 lint와 production build가 통과한다. | `apps/web` 동시 수정 (D1) | T2 | `cd apps/web && npm ci && npm run lint && npm run build` | exit 0 |
| AC15 | 저장소 문서 계약과 whitespace 계약이 유지된다. **브랜치가 base에 대해 도입한** whitespace 결함을 본다. | 기존 repository gate, codex 4R medium-3 | T1 | 아래 `AC15 command` | exit 0, `documentation check passed` |
| AC16 | `docs/work/private-live-autotrader-phase-0/`에 workflow 산출물 4종이 존재하고 상대 링크가 모두 실재 파일을 가리킨다. | `feature-workflow` ④⑤⑪-b | T1 | 아래 `AC16 command` | exit 0, `missing=[] broken_links=[]` |
| AC17 | 동결 산출물(마스터 spec 4종, Phase -1 3종)이 이 브랜치에서 변경되지 않았다. | 범위 제외 "동결 산출물 변경" | T1 | 아래 `AC17 command` | exit 0, `modified=[]` |
| AC18 | §7 outcome 추적표가 상위 동결 spec이 Phase 0에 배정한 **11개 ID와 정확히 일치**하고(누락·초과 0), 각 행이 근거 절과 검증 AC를 가지며, 참조된 AC가 이 계약서에 실재한다. | 상위 spec §8 Phase 0 배정, codex 6R medium-3 | T1 | 아래 `AC18 command` | exit 0, `missing=[] extra=[] empty_cells=[] dangling_ac=[]` |
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

> **정적 검사의 한계:** 문자열 존재 검사는 "그 문자열이 파일에 있다"만 증명한다. 주석·미사용 컴포넌트·도달
> 불가 분기·응답에 실리지 않는 DTO 필드로도 통과할 수 있다. 따라서 **사용자가 보는 것**(AC4)과 **소비자가
> 받는 것**(AC3)은 정적 검사가 아니라 렌더 테스트와 응답 계약 테스트로 검증한다. 정적 검사는 "옛 이름이
> 남아 있지 않다"처럼 부재를 주장하는 데만 쓴다.

`verify.sh`는 `dod.md`의 `#### ACn command` 블록을 그대로 추출해 실행한다. 인자 없이 실행하면 블록이 있는
모든 기준을 돌리므로 `gradlew`·`npm`이 포함된 AC3·AC4·AC9도 함께 실행된다. 빠른 정적 확인만 필요하면
기준을 명시한다: `bash docs/work/private-live-autotrader-phase-0/verify.sh AC1 AC2 AC23 AC24`.

#### AC1 command

```bash
# 인용 부호에 의존하지 않는다. 초안의 "'/positions" 패턴은 백틱 템플릿 리터럴
# (`/positions/${id}` 등 3건)을 놓쳐 구현 전에도 GREEN을 낼 수 있었다.
api_http=$(grep -rn --exclude-dir=build '/api/v1/positions' apps/api/src http 2>/dev/null | grep -c . || true)
web=$(grep -rn --exclude-dir=node_modules --exclude-dir=.next '/positions' apps/web/src 2>/dev/null | grep -c . || true)
oldroute=$([ -d apps/web/src/app/positions ] && echo 1 || echo 0)
newroute=$([ -d apps/web/src/app/trackings ] && echo 1 || echo 0)
echo "leftover=[api_http=$api_http web=$web old_route=$oldroute] new_route=$newroute"
[ "$api_http" -eq 0 ] && [ "$web" -eq 0 ] && [ "$oldroute" -eq 0 ] && [ "$newroute" -eq 1 ]
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

파일 텍스트가 아니라 **실제 응답 body**를 검증한다. 필드가 DTO에 선언돼 있어도 응답에 실리지 않을 수 있고,
컨트롤러가 다른 DTO를 반환할 수도 있으므로 정적 검사만으로는 주장을 지지하지 못한다.

`TrackingGrossPnlContractTest`가 `ACTIVE`·`ARCHIVED` 두 경우의 응답 JSON 키 집합을 정확히 대조한다.

```bash
./gradlew :apps:api:integrationTest --tests '*TrackingGrossPnlContract*' --offline --no-daemon
```

#### AC4 command

`apps/web`에는 테스트 인프라가 없었다. 이 Phase가 vitest + Testing Library를 도입한다 (`plan.md` T6). 문자열
grep은 주석·미사용 컴포넌트·도달 불가 분기로도 통과하므로 렌더 결과를 검증한다.

```bash
cd apps/web && npm ci && npm run test
```

`TrackingList.test.tsx`와 `trackings/[id]/page.test.tsx`가 각각 다음이 DOM에 나타남을 확인한다.

| 확인 대상 | 목록 | 상세 |
|---|---|---|
| 비주문 고지 | O | O |
| gross 각주 (제외 항목) | O | O |
| 계정 손익 아님 | O | O |
| 분모 라벨 | O | O |
| 레버리지 무관성 각주 | — | O |
| 프리미엄 방향 설명 | — | O |
| `ARCHIVED` 확정값 배지 | O | O |
| 409 시 손익 미제공 안내 | — | O |

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

#### AC5 command

`TrackingArchiveIntegrationTest`가 `design.md` §5.3.2 요청·응답 계약 표의 6행을 각각 검증한다. 표가 단일
출처이고 이 테스트가 그 표를 그대로 옮긴다 — 세 문서가 `409`의 위치를 다르게 서술하던 문제(codex 2R high-2)의
재발 차단선이다.

| 케이스 | 요청 | 기대 |
|---|---|---|
| 1 | `POST /archive` (snapshot 신선) | `200`, `closePriceSource=MARKET_SNAPSHOT` |
| 2 | `POST /archive` (snapshot 부재·stale) | **`200`**, `closePriceSource=SNAPSHOT_UNAVAILABLE` |
| 3 | `POST /archive` 재호출 | `400 INVALID_TRACKING` |
| 4 | 케이스 1 뒤 시세를 바꾸고 `GET /gross-pnl` | `200`, `priceBasis=ARCHIVED_SNAPSHOT`, 금액이 archive 시점과 **동일** |
| 5 | 케이스 2 뒤 `GET /gross-pnl` | `409 TRACKING_CLOSE_SNAPSHOT_UNAVAILABLE` |
| 6 | 동시 `POST /archive` × N | 정확히 1건 `200`, 나머지 `400 INVALID_TRACKING`, DB의 확정값은 성공한 1건과 일치 |
| 7 | soft-deleted 추적에 `POST /archive` | `TRACKING_NOT_FOUND`, DB의 `status`·`close_*`가 **변하지 않음** |
| 8 | 타인 소유 추적에 `POST /archive` | `TRACKING_NOT_FOUND`, DB 무변경 (잠금 뒤에도 소유권을 검증한다) |

```bash
./gradlew :apps:api:integrationTest --tests '*TrackingArchive*' --offline --no-daemon
```

#### AC25 command

`TrackingLegacyRowIntegrationTest`가 SQL로 행을 직접 심어 새 code로 읽는다. **전부 `NULL`인 행만으로는
부족하다** — `MARKET_SNAPSHOT`이면서 한 컬럼만 `NULL`인 부분 행이 fail-open으로 통과할 수 있기 때문이다.

| 케이스 | 행 상태 | 기대 |
|---|---|---|
| L1 | `status='CLOSED'`, 신규 컬럼 전부 `NULL` (이전 image가 종료) | 조회 `200`·`ARCHIVED`, `closedAt=null`, `gross-pnl` `409` |
| L2 | `close_price_source='LEGACY_UNKNOWN'` | `gross-pnl` `409` |
| L3~L8 | `close_price_source='MARKET_SNAPSHOT'` + 나머지 6개 중 **정확히 하나만 `NULL`** (`closed_at`, `close_observed_at`, `close_korea_price`, `close_foreign_price`, `close_fx_rate`, `close_premium_rate`) | 각각 `gross-pnl` `409` |

L3~L8은 §5.3.2 확정 판정 규칙의 6개 필드에 1:1 대응하는 parameterized test다. 규칙에 필드를 추가하면 케이스도
함께 늘어난다.

```bash
./gradlew :apps:api:integrationTest --tests '*TrackingLegacyRow*' --offline --no-daemon
```

어떤 케이스에서도 `NullPointerException`·`IllegalStateException`이 발생하지 않는다.

#### AC23 command

금지 토큰 나열(blacklist)은 문법 변형에 뚫린다. MySQL은 `DROP status`·`CHANGE status ...`처럼 `COLUMN`을
생략할 수 있고, `SET "status"`·``SET `status` ``처럼 식별자를 감쌀 수 있다. 그래서 **allowlist로 뒤집는다**:
`ALTER` 연산은 `ADD COLUMN`만 허용하고, `UPDATE`의 `SET` 대상은 같은 migration이 추가한 컬럼만 허용한다.
허용 목록에 없으면 무조건 실패다(fail-closed).

검사기가 우회 표본을 실제로 잡는지 **self-test로 먼저 증명한 뒤** V15를 본다. self-test가 실패하면 본 검사
결과를 신뢰하지 않는다.

```bash
unquote() { sed -E 's/[`"'"'"']//g'; }

alter_ops() {   # ALTER TABLE 문의 연산을 콤마 단위로 하나씩 출력
  sed -E 's/--.*$//' | tr '\n' ' ' | tr ';' '\n' \
  | grep -iE '^[[:space:]]*ALTER[[:space:]]+TABLE' \
  | sed -E 's/^[[:space:]]*[Aa][Ll][Tt][Ee][Rr][[:space:]]+[Tt][Aa][Bb][Ll][Ee][[:space:]]+[^[:space:]]+[[:space:]]+//' \
  | tr ',' '\n' | sed -E 's/^[[:space:]]*//; s/[[:space:]]+$//' | grep -E '.'
}

set_targets() {   # UPDATE 문의 SET 대상 컬럼 전부 (다중 SET·별칭·따옴표·복수 문장·주석 대응)
  sed -E 's/--.*$//' | tr '\n' ' ' | tr ';' '\n' \
  | grep -iE '^[[:space:]]*UPDATE' \
  | sed -E 's/[[:space:]][Ww][Hh][Ee][Rr][Ee][[:space:]].*$//' \
  | sed -E 's/^.*[[:space:]][Ss][Ee][Tt][[:space:]]//' \
  | tr ',' '\n' | sed -E 's/^[[:space:]]*//; s/[[:space:]]*=.*$//' | unquote \
  | sed -E 's/^[A-Za-z_][A-Za-z0-9_]*\.//' | tr '[:upper:]' '[:lower:]' \
  | grep -E '^[a-z_][a-z0-9_]*$' | sort -u
}

added_cols() {
  alter_ops | grep -iE '^ADD[[:space:]]+COLUMN[[:space:]]' \
  | awk '{print $3}' | unquote | tr '[:upper:]' '[:lower:]' | sort -u
}

judge() {   # 0 = 안전, 1 = 위반
  local sql="$1" added targets bad="" disallowed="" destructive=""
  disallowed=$(printf '%s' "$sql" | alter_ops | grep -ivE '^ADD[[:space:]]+COLUMN[[:space:]]' | cut -c1-50 || true)
  added=$(printf '%s' "$sql" | added_cols)
  targets=$(printf '%s' "$sql" | set_targets)
  for tgt in $targets; do printf '%s\n' "$added" | grep -qx "$tgt" || bad="$bad $tgt"; done
  destructive=$(printf '%s' "$sql" | sed -E 's/--.*$//' \
    | grep -inE 'TRUNCATE|DROP[[:space:]]+TABLE|RENAME[[:space:]]+TABLE|DELETE[[:space:]]+FROM' | cut -c1-50 || true)
  JB="$bad"; JD=$(printf '%s' "$disallowed" | tr '\n' ';'); JX=$(printf '%s' "$destructive" | tr '\n' ';')
  [ -z "$bad" ] && [ -z "$disallowed" ] && [ -z "$destructive" ]
}

# --- self-test: 아래 우회 표본을 하나라도 놓치면 즉시 실패 ---
A="ALTER TABLE position ADD COLUMN close_price_source VARCHAR(30) NULL;"
undetected=""
judge "$A UPDATE position SET close_price_source='X', status='ARCHIVED';"       && undetected="$undetected multi-set"
judge "$A UPDATE position p SET p.status='ACTIVE';"                             && undetected="$undetected alias-set"
judge "$A UPDATE position SET close_price_source='X'; UPDATE position SET status='A';" && undetected="$undetected second-update"
judge "$A UPDATE position SET \"status\" = 'ARCHIVED';"                         && undetected="$undetected quoted-set"
judge "$A ALTER TABLE position MODIFY COLUMN status VARCHAR(30) NOT NULL;"      && undetected="$undetected modify-column"
judge "$A ALTER TABLE position DROP COLUMN status;"                             && undetected="$undetected drop-column"
judge "$A ALTER TABLE position DROP status;"                                    && undetected="$undetected drop-no-keyword"
judge "$A ALTER TABLE position CHANGE status state VARCHAR(30);"                && undetected="$undetected change-no-keyword"
judge "$A ALTER TABLE position RENAME COLUMN status TO state;"                  && undetected="$undetected rename-column"
judge "$A ALTER TABLE position ALTER COLUMN status SET DEFAULT 'ACTIVE';"       && undetected="$undetected alter-column"
judge "$A DELETE FROM position WHERE status='CLOSED';"                          && undetected="$undetected delete-from"
judge "$A" || undetected="$undetected false-positive-on-clean"
if [ -n "$undetected" ]; then echo "self_test_failed=[$undetected]"; exit 1; fi

# --- 본 검사 ---
m=$(ls infrastructure/common/src/main/resources/db/migration/V15__*.sql 2>/dev/null | head -1)
[ -n "$m" ] || { echo "self_test=ok V15 없음"; exit 1; }
judge "$(cat "$m")"; rc=$?
echo "self_test=ok added=[$(added_cols < "$m" | tr '\n' ' ')] rewrites_existing=[$JB] disallowed=[$JD] destructive=[$JX]"
exit $rc
```

#### AC24 command

저장값 리터럴이 정당한 곳은 **converter와 Flyway migration 둘뿐**이다 (`V3__create_position_table.sql`의
`DEFAULT 'OPEN'`은 D4에서도 그대로 유효하다). 그 밖의 어디에 나타나든 converter 우회다. 검사 범위를
실행 소스 전체와 SQL resource까지 넓히고, converter를 건너뛰는 native query 자체를 금지한다.

```bash
conv=$(find domain/src/main infrastructure/common/src/main -name 'TrackingStatusConverter.kt' 2>/dev/null | head -1)
missing=""
[ -n "$conv" ] || missing="$missing converter-file"
if [ -n "$conv" ]; then
  for v in ACTIVE ARCHIVED OPEN CLOSED; do grep -q "$v" "$conv" || missing="$missing $v"; done
fi

# 1) 저장값 리터럴 유출 — 실행 소스 전체 + SQL resource. 허용: converter, Flyway migration, V12 동결 guard.
leaked=$(grep -rn --exclude-dir=build --exclude-dir=node_modules --exclude-dir=.next \
  --include='*.kt' --include='*.kts' --include='*.java' --include='*.sql' --include='*.yml' \
  -E "['\"](OPEN|CLOSED)['\"]" \
  apps/api/src/main apps/batch/src/main domain/src/main \
  infrastructure/api/src/main infrastructure/batch/src/main infrastructure/common/src/main \
  modules/jpa/src/main modules/redis/src/main 2>/dev/null \
  | grep -v 'TrackingStatusConverter' \
  | grep -v 'V12MigrationSafety' \
  | grep -v '/db/migration/' \
  | cut -c1-90 || true)

# 2) converter를 우회하는 native query 금지 — 실행 모듈 전체의 Kotlin·Java.
#    테이블 참조 정규식은 JPQL의 "FROM Tracking t"를 대소문자 무시로 오탐하므로 쓰지 않는다.
#    native query 선언 자체를 차단하는 편이 정밀하고, 현재 실행 소스에는 0건이라 성립한다.
native=$(grep -rn --exclude-dir=build --include='*.kt' --include='*.java' \
  -E 'nativeQuery[[:space:]]*=|createNativeQuery' \
  apps/api/src/main apps/batch/src/main domain/src/main \
  infrastructure/api/src/main infrastructure/batch/src/main infrastructure/common/src/main \
  modules/jpa/src/main modules/redis/src/main \
  supports/logging/src/main supports/monitoring/src/main supports/email/src/main 2>/dev/null \
  | cut -c1-90 || true)

# 3) JdbcTemplate 등으로 position 테이블을 직접 다루는 raw SQL 금지.
#    대소문자를 구분한다 — JPQL은 엔티티명 "FROM Tracking t"를 쓰므로 오탐하지 않는다.
rawsql=$(grep -rn --exclude-dir=build --include='*.kt' --include='*.java' \
  -E '(FROM|JOIN|INTO|UPDATE|TABLE)[[:space:]]+`?position`?([[:space:]]|`|;|$)' \
  apps/api/src/main apps/batch/src/main domain/src/main \
  infrastructure/api/src/main infrastructure/batch/src/main infrastructure/common/src/main \
  modules/jpa/src/main modules/redis/src/main 2>/dev/null \
  | grep -v 'V12MigrationSafety' | cut -c1-90 || true)

echo "missing=[$missing] leaked=[$leaked] native=[$native] rawsql=[$rawsql]"
[ -z "$missing" ] && [ -z "$leaked" ] && [ -z "$native" ] && [ -z "$rawsql" ]
```

#### AC26 command

```bash
changed=$(git diff --name-only origin/dev...HEAD -- \
  domain/src/main/kotlin/io/premiumspread/domain/market/MarketPair.kt \
  domain/src/main/kotlin/io/premiumspread/domain/premium \
  domain/src/main/kotlin/io/premiumspread/domain/notification \
  modules/redis \
  docs/runbooks/redis-contract.md)
table=$(grep -rn '@Table(name = "position")' --include='*.kt' domain/src/main | grep -c . || true)
echo "changed=[$changed] table=$table"
[ -z "$changed" ] && [ "$table" -eq 1 ]
```

#### AC15 command

`git diff --check`는 **인자 없이 쓰면 working tree의 unstaged 변경만** 본다. 커밋 뒤에는 언제나 비어 있어
구조적으로 실패할 수 없는 검사였다. 브랜치가 base에 대해 도입한 whitespace 결함을 보려면 범위를 준다.

```bash
bash docs/check-documentation.sh && git diff --check origin/dev...HEAD && echo "whitespace ok"
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

기대 집합을 **검사 대상 자신에게서 유도하면 공허하게 통과한다.** 행을 지우면 `rows`가 줄어들고
`empty_cells`·`dangling_ac`가 비어 GREEN이 된다. 상위 동결 spec이 Phase 0에 배정한 ID 집합을 고정해 두고
누락·초과를 함께 본다. 이 집합의 출처는 `docs/work/private-live-autotrader/design.md` §8이며 그 문서는
`AC17`이 무변경을 보장한다.

```bash
d=docs/work/private-live-autotrader-phase-0/design.md
dod=docs/work/private-live-autotrader-phase-0/dod.md
required="ARCH-7 ARCH-9 P0-O1 P0-O2 P0-O3 P0-O4 P0-O5 SEM-1 SEM-2 SEM-3 SEM-4"

rows=$(awk '/^## 7\. Outcome 추적/,/^## 8\./' "$d" | grep -E '^\| `(P0-O|SEM-|ARCH-)')
present=$(printf '%s\n' "$rows" | grep -oE '(P0-O[0-9]+|SEM-[0-9]+|ARCH-[0-9]+)' \
  | awk '!seen[$0]++' | sort)

missing=""; for id in $required; do printf '%s\n' "$present" | grep -qx "$id" || missing="$missing $id"; done
extra=""; for id in $present; do printf '%s\n' "$required" | tr ' ' '\n' | grep -qx "$id" || extra="$extra $id"; done

empty=$(printf '%s\n' "$rows" | awk -F'|' '$3 ~ /^[[:space:]]*$/ || $4 ~ /^[[:space:]]*$/ {print $2}')
dangling=""
for ac in $(printf '%s\n' "$rows" | grep -oE 'AC[0-9]+' | sort -u); do
  grep -qE "^\| $ac \|" "$dod" || dangling="$dangling $ac"
done

echo "required=$(printf '%s' "$required" | wc -w) present=$(printf '%s\n' "$present" | grep -c .) missing=[$missing] extra=[$extra] empty_cells=[$empty] dangling_ac=[$dangling]"
[ -z "$missing" ] && [ -z "$extra" ] && [ -z "$empty" ] && [ -z "$dangling" ]
```

#### AC19 command

```bash
d=docs/work/private-live-autotrader-phase-0/design.md
rows=$(awk '/^## 6\. 미해결 결정/,/^## 7\./' "$d" | grep -E '^\| `OPEN-')
unassigned=$(echo "$rows" | awk -F'|' '$4 !~ /Phase/ {print $2}')
grep -q "차단 요소가 아니다" "$d" || unassigned="$unassigned no-nonblocking-statement"
# 행이 전부 삭제되면 공허하게 통과하므로 최소 개수를 함께 본다 (현재 OPEN-1~OPEN-5).
n=$(printf '%s\n' "$rows" | grep -c . || true)
[ "$n" -ge 5 ] || unassigned="$unassigned too-few-rows($n)"
echo "rows=$n unassigned=[$unassigned]"
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
| AC1 | RED — 초안 패턴 기준 `leftover=[api_http=35 web=6 web_route_dir=1]`. 강화 후 web 실측 9건 (백틱 경로 3건 추가 검출) | |
| AC2 | RED — `leftover=[131 hits]` | |
| AC3 | RED — `TrackingGrossPnlContractTest` 부재. 현재 응답에 `pnlBasis`·`priceBasis`가 없다 | |
| AC4 | RED — `apps/web`에 테스트 인프라 자체가 없다 (`scripts`에 `test` 없음, testing 관련 의존성 0개). 고지 문구 6종도 전부 부재 | |
| AC5 | RED — `TrackingArchive*` 테스트 부재. 현재 `Position.close()`는 청산 시세를 저장하지 않고 `/pnl`에 상태 가드가 없다 (§3.3). 동시성: `BaseEntity`에 `@Version`이 없고 archive 경로에 행 잠금이 없어 마지막 쓰기가 앞선 확정을 덮어쓴다 (§5.3.5) | |
| AC6 | GREEN — `missing=[]` (§5.5 판정이 `design.md`에 존재) | |
| AC7 | RED — `undecided=[] not_removed=[10 hits]` (판정은 완료, 제거 미실행) | |
| AC8 | RED — `missing=[planned-section forward-link back-link]` (3/3 전부 부재) | |
| AC9 | 회귀 guard — `PublicEndpointPolicy`에 추적 경로 없음(현재도 없음). 통합 test는 rename 후에만 실행 가능 | |
| AC10 | RED — `V15` 미존재 | |
| AC25 | RED — `TrackingLegacyRow*` 테스트 부재. 현재 code에는 `close_price_source` 개념 자체가 없다 | |
| AC26 | 회귀 guard — 기준선 `changed=[] table=1` | |
| AC23 | RED — `V15` 미존재. 초안의 `V15`는 `status` 값을 재작성해 이 검사에 걸렸을 것이다 (codex 리뷰 1R high-1으로 D4 폐기) | |
| AC24 | RED — `TrackingStatusConverter` 미존재 | |
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
  T1/T2 자동:      _/23
  T3 기록 제출:    0건
  T4 사람 확인:    _/3
  => (미판정)
```
