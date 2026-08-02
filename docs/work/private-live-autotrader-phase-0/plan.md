# Phase 0 — 실행 계획

| 항목 | 값 |
|---|---|
| 문서 역할 | `feature-workflow` ⑤ plan 문서 |
| slug | `private-live-autotrader-phase-0` |
| spec | [`design.md`](design.md) |
| 완료 기준 계약서 | [`dod.md`](dod.md) |
| base branch | `dev` (`5319a2d`) |
| branch | `refactor/private-live-autotrader-phase-0` |
| 검증 러너 | `bash docs/work/private-live-autotrader-phase-0/verify.sh` |

## 0. 실행 원칙

- **모든 태스크는 끝났을 때 컴파일되고 테스트가 통과하는 상태로 남는다.** rename은 전 계층에 걸치므로
  계층별로 쪼개면 중간 상태가 깨진다. T1은 한 번에 전 계층을 옮긴다.
- 태스크마다 관련 gate를 실행하고 결과를 기록한다. Docker 부재로 실행하지 못한 항목은 GREEN으로 적지 않는다.
- 명령은 `grep`·`awk`·`sed`·`find`·`git`·`gradlew`·`npm`만 쓴다. `rg`는 비대화형 셸과 CI runner의 PATH에
  없어 조용히 통과시킨다 (`dod.md` 도구 제약).
- 로컬 gradle은 `--offline --no-daemon`을 붙인다 (`.ai/rules/testing.md`).

## 1. 태스크

### T1. 도메인·인프라·애플리케이션·인터페이스 타입 rename

HTTP 경로·응답 필드명·상태값은 **건드리지 않는다.** 타입·패키지 이름만 옮겨 컴파일러가 전수 검증하게 한다.

**이동 (`git mv` 후 패키지 선언·import 수정)**

| from | to |
|---|---|
| `domain/src/main/kotlin/io/premiumspread/domain/position/` | `.../domain/tracking/` |
| `domain/src/test/kotlin/io/premiumspread/domain/position/` | `.../domain/tracking/` |
| `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jpa/position/` | `.../jpa/tracking/` |
| `apps/api/src/main/kotlin/io/premiumspread/application/position/` | `.../application/tracking/` |
| `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/position/` | `.../interfaces/api/tracking/` |
| `apps/api/src/test/kotlin/io/premiumspread/application/position/` | `.../application/tracking/` |
| `apps/api/src/test/kotlin/io/premiumspread/interfaces/api/position/` | `.../interfaces/api/tracking/` |
| `apps/api/src/integrationTest/kotlin/io/premiumspread/infrastructure/position/` | `.../infrastructure/tracking/` |
| `apps/api/src/integrationTest/kotlin/io/premiumspread/interfaces/api/position/` | `.../interfaces/api/tracking/` |

**타입 rename**

| from | to |
|---|---|
| `Position` (Entity) | `Tracking` |
| `PositionOpenSpec` | `TrackingRecordSpec` |
| `PositionPnl` | `TrackingGrossPnl` |
| `PositionStatus` | `TrackingStatus` |
| `PositionCommand` | `TrackingCommand` |
| `PositionRepository` | `TrackingRepository` |
| `PositionService` | `TrackingService` |
| `InvalidPositionException` | `InvalidTrackingException` |
| `JpaPositionRepositoryAdapter` | `JpaTrackingRepositoryAdapter` |
| `SpringDataPositionRepository` | `SpringDataTrackingRepository` |
| `PositionFacade` | `TrackingFacade` |
| `PositionCriteria` / `PositionResult` | `TrackingCriteria` / `TrackingResult` |
| `PositionController` | `TrackingController` |
| `PositionRequest` / `PositionResponse` | `TrackingRequest` / `TrackingResponse` |

**유지**

- `@Table(name = "position")` — DB 테이블명 불변
- `infrastructure/common/.../migration/V12MigrationSafety*` — V12 전용 동결 guard
- `.ai/planning/**`, `docs/superpowers/**`, `docs/plans/**`, `docs/runbooks/v12-migration.md` — 역사 기록
- `CommonInfrastructureAutoConfiguration`의 entity scan 대상 패키지는 새 경로로 갱신

**검증**

```bash
./gradlew compileKotlin --offline --no-daemon
./gradlew test architectureTest --offline --no-daemon
bash docs/work/private-live-autotrader-phase-0/verify.sh AC2
```

예상: 전부 exit 0, `AC2 GREEN leftover=[0 hits]`. AC1은 아직 RED다 (T2가 처리).

### T2. REST 경로·에러코드 rename

**`TrackingController`** — `@RequestMapping("/api/v1/trackings")`

| 메서드 | 경로 |
|---|---|
| `recordFromMarket` | `POST /from-market` |
| `record` | `POST` (root) |
| `findAllActive` | `GET` (root) |
| `findAllArchived` | `GET /archived` |
| `getSummary` | `GET /summary` |
| `getById` | `GET /{id}` |
| `getGrossPnl` | `GET /{id}/gross-pnl` |
| `archive` | `POST /{id}/archive` |

**`ApplicationError`** (`apps/api/src/main/kotlin/io/premiumspread/application/common/ApplicationException.kt`)

- `POSITION_NOT_FOUND` → `TRACKING_NOT_FOUND`
- `INVALID_POSITION` → `INVALID_TRACKING`
- `TRACKING_CLOSE_SNAPSHOT_UNAVAILABLE` 신설 (HTTP 409). `GlobalExceptionHandler` 매핑 추가
- `PREMIUM_SNAPSHOT_NOT_AVAILABLE`·`STALE_PREMIUM_SNAPSHOT`·`PREMIUM_NOT_FOUND`은 premium 도메인 소유이므로 불변

**`http/api/positions.http` → `http/api/trackings.http`**

모든 요청을 새 경로로 갱신하고 파일 머리말에 다음을 넣는다.

```text
### 이 API는 실제 주문을 생성하지 않는다. 다른 곳에서 체결한 포지션을 손으로 기록해 두는 추적 record다.
```

`http/README.md`의 파일 목록도 갱신한다.

**테스트 갱신**: `TrackingControllerTest`, `TrackingControllerE2ETest`의 경로와 에러 코드 기대값.

**검증**

```bash
./gradlew test --offline --no-daemon
./gradlew :apps:api:integrationTest --tests '*Tracking*' --offline --no-daemon
bash docs/work/private-live-autotrader-phase-0/verify.sh AC1
```

예상: exit 0. AC1은 `web=6 web_route_dir=1`이 남아 여전히 RED다 (T6이 처리).

### T3. 상태 표현 정렬 + `V15` + 청산 스냅샷 + 확정 단일성

**`TrackingStatus`**: 값은 `ACTIVE`/`ARCHIVED`. **DB 저장값은 `OPEN`/`CLOSED`를 유지한다** (`design.md` D4).

```kotlin
// domain/tracking/TrackingStatusConverter.kt
@Converter(autoApply = false)
class TrackingStatusConverter : AttributeConverter<TrackingStatus, String> {
    override fun convertToDatabaseColumn(attribute: TrackingStatus): String = when (attribute) {
        TrackingStatus.ACTIVE -> "OPEN"
        TrackingStatus.ARCHIVED -> "CLOSED"
    }
    override fun convertToEntityAttribute(dbData: String): TrackingStatus = when (dbData) {
        "OPEN" -> TrackingStatus.ACTIVE
        "CLOSED" -> TrackingStatus.ARCHIVED
        else -> throw IllegalStateException("Unknown tracking status in database: $dbData")
    }
}
```

`Tracking.status`는 `@Enumerated`를 떼고 `@Convert(converter = TrackingStatusConverter::class)`를 쓴다.
저장값 리터럴 `"OPEN"`·`"CLOSED"`는 이 파일 밖에 나타나지 않는다 (`dod.md` AC24가 기계 검사).

**`infrastructure/common/src/main/resources/db/migration/V15__add_tracking_close_snapshot.sql`**

```sql
-- V15: 추적 종료 시점의 시세를 확정 저장한다.
-- 기존 컬럼의 값을 재작성하지 않는다 — 이전 application image 롤백 호환을 유지해야 한다
-- (docs/runbooks/deployment.md "Rollback 제약", design.md D4·§5.8).
ALTER TABLE position
    ADD COLUMN closed_at           DATETIME(6)     NULL AFTER status,
    ADD COLUMN close_price_source  VARCHAR(30)     NULL AFTER closed_at,
    ADD COLUMN close_observed_at   DATETIME(6)     NULL AFTER close_price_source,
    ADD COLUMN close_korea_price   DECIMAL(30, 10) NULL AFTER close_observed_at,
    ADD COLUMN close_foreign_price DECIMAL(30, 10) NULL AFTER close_korea_price,
    ADD COLUMN close_fx_rate       DECIMAL(20, 6)  NULL AFTER close_foreign_price,
    ADD COLUMN close_premium_rate  DECIMAL(10, 2)  NULL AFTER close_fx_rate;

-- 신규 컬럼만 채운다. 이전 image는 이 컬럼을 무시하므로 롤백 호환 범위 안이다.
UPDATE position SET close_price_source = 'LEGACY_UNKNOWN' WHERE status = 'CLOSED';
```

**확정 단일성** (`design.md` §5.3.5)

```kotlin
// TrackingRepository
fun findByIdForUpdate(id: Long): Tracking?
```

`SpringDataTrackingRepository`에 `@Lock(LockModeType.PESSIMISTIC_WRITE)` 쿼리를 추가하고
`JpaTrackingRepositoryAdapter`가 노출한다. Facade의 `archive`만 이 경로를 쓴다. 조회 경로는 잠그지 않는다.
경쟁에서 진 요청은 `ARCHIVED`를 보고 `INVALID_TRACKING`을 받는다.

**도메인**

```kotlin
enum class TrackingClosePriceSource { MARKET_SNAPSHOT, SNAPSHOT_UNAVAILABLE, LEGACY_UNKNOWN }

data class TrackingCloseSnapshot(
    val koreaPrice: BigDecimal,
    val foreignPrice: BigDecimal,
    val fxRate: BigDecimal,
    val premiumRate: BigDecimal,
    val observedAt: Instant,
)

// Tracking
fun archive(snapshot: TrackingCloseSnapshot?, archivedAt: Instant)
```

- 이미 `ARCHIVED`면 `InvalidTrackingException` (기존 `close()` 동작 유지)
- `snapshot == null` → `closePriceSource = SNAPSHOT_UNAVAILABLE`, 가격 컬럼 null
- `closedAt`은 항상 기록, `closeObservedAt`은 `MARKET_SNAPSHOT`일 때만

**확정 판정은 한 곳에만 둔다** (`design.md` §5.3.2). 값별 분기를 흩뿌리지 않는다.

```kotlin
// Tracking
val hasConfirmedClose: Boolean
    get() = status == TrackingStatus.ARCHIVED &&
        closePriceSource == TrackingClosePriceSource.MARKET_SNAPSHOT &&
        closeKoreaPrice != null && closeForeignPrice != null &&
        closeFxRate != null && closePremiumRate != null
```

`closePriceSource`와 `closedAt`은 **nullable**이다. `V15` 적용 후 이전 application image가 종료시킨 행은
`status`만 바뀌고 신규 컬럼이 `NULL`로 남기 때문이다 (`design.md` §5.8). `NULL`은 `hasConfirmedClose == false`로
자연히 fail-closed 처리되고, `closedAt`이 `NULL`이면 화면이 "종료 시각 불명"을 표시한다. `updatedAt`으로
대체하지 않는다.

**Facade `archive`**: 행을 잠근 뒤 최신 premium snapshot을 조회해 60초 이내면 `TrackingCloseSnapshot`,
아니면 `null`을 넘긴다. **snapshot 부재·stale을 이유로 archive를 거절하지 않는다.** `409`는 archive가 아니라
그 추적의 `gross-pnl` 조회에서만 나온다 — 요청·응답 계약의 단일 출처는 `design.md` §5.3.2 표다.

**검증**

```bash
./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon
./gradlew test --offline --no-daemon
./gradlew :infrastructure:common:integrationTest --tests '*V15*' --offline --no-daemon
./gradlew :apps:api:integrationTest --tests '*TrackingArchive*' --tests '*TrackingLegacyRow*' --offline --no-daemon
bash docs/work/private-live-autotrader-phase-0/verify.sh AC11 AC23 AC24
```

예상: exit 0.

- `V15*` 통합 test — 빈 DB latest, `V14`→`V15` 경로, **`status` 값 보존**, `LEGACY_UNKNOWN` 백필 (`AC10`)
- `TrackingArchive*` — `design.md` §5.3.2 요청·응답 계약 표의 6행 (`AC5`). archive는 어떤 경우에도 시세를
  이유로 `409`를 내지 않는다
- `TrackingLegacyRow*` — 이전 image가 남긴 `NULL` 행을 SQL로 직접 심고 fail-closed 읽기를 검증 (`AC25`)

`verifyMigrations`의 destructive gate는 `TRUNCATE TABLE`·`DROP TABLE`만 검사하므로
(`infrastructure/common/build.gradle.kts:85`) 기존 컬럼 값 재작성을 잡지 못한다. 그 공백을 `AC23`이 메운다.

### T4. `gross-pnl` 응답 정직화

`TrackingResponse.GrossPnl` 필드는 `design.md` §5.3.3 표를 그대로 따른다. `TrackingResult.GrossPnl`과
`TrackingGrossPnl`도 같은 이름 체계를 쓴다.

**계산 분기**

| 상태 | `priceBasis` | 입력 |
|---|---|---|
| `ACTIVE` | `CURRENT_MARKET` | 최신 premium snapshot |
| `ARCHIVED` + `MARKET_SNAPSHOT` | `ARCHIVED_SNAPSHOT` | 저장된 청산 스냅샷 |
| `ARCHIVED` + `SNAPSHOT_UNAVAILABLE`·`LEGACY_UNKNOWN` | — | `409 TRACKING_CLOSE_SNAPSHOT_UNAVAILABLE` |

`pnlBasis`는 상수 `GROSS_EXCLUDING_FEES_FUNDING_SLIPPAGE_FX_SPREAD`. **계산식 자체는 바꾸지 않는다** —
Phase 0은 손익 모델을 바꾸지 않는다 (`design.md` §1.3).

**검증**

신설 `TrackingGrossPnlContractTest` (`apps/api/src/integrationTest`)가 `ACTIVE`·`ARCHIVED` 두 경우의 응답
JSON **키 집합을 정확히 대조**한다. 옛 키(`totalPnlPercent`, `isProfit`, `koreaCurrentValue`, `premiumDiff`,
`positionId`)의 부재도 응답 기준으로 확인한다 — DTO 파일 텍스트 검사는 필드가 응답에 실리는지를 증명하지
못한다 (`dod.md` AC3).

```bash
./gradlew test --offline --no-daemon
./gradlew :apps:api:integrationTest --tests '*TrackingGrossPnlContract*' --tests '*TrackingArchive*' --offline --no-daemon
```

예상: exit 0.

### T5. dead·미연결 계약 처리

- **제거**: `TrackingRepository.findAllActive()`(기존 `findAllOpen`), `findAllByStatus`,
  `TrackingService.findAllActive()`와 이들만 검증하던 `TrackingServiceTest` 케이스
- **유지 + 주석**: `Exchange.UPBIT`에 수집·표시 경로가 없다는 KDoc 한 줄
- **유지 + 주석**: `Tracking.foreignLeverage`에 필요 증거금에만 영향을 주고 손익 금액에는 반영되지 않는다는
  KDoc 한 줄

**검증**

```bash
./gradlew test architectureTest --offline --no-daemon
bash docs/work/private-live-autotrader-phase-0/verify.sh AC7
```

예상: exit 0, `AC7 GREEN undecided=[] not_removed=[0 hits]`.

### T6. `apps/web` 정렬

**이동**

| from | to |
|---|---|
| `apps/web/src/app/positions/` | `apps/web/src/app/trackings/` |
| `apps/web/src/components/PositionList.tsx` | `apps/web/src/components/TrackingList.tsx` |
| `apps/web/src/components/OpenPositionForm.tsx` | `apps/web/src/components/RecordTrackingForm.tsx` |

**API 호출 경로**: `/positions*` → `/trackings*` (`GET /trackings`, `/trackings/archived`,
`/trackings/summary`, `/trackings/{id}`, `/trackings/{id}/gross-pnl`, `POST /trackings`,
`/trackings/from-market`, `/trackings/{id}/archive`)

**문구**: `design.md` §5.4.1·§5.4.2 표를 그대로 적용한다. `Header.tsx` nav 경로·라벨 포함.

**동작**

- `ARCHIVED` 항목은 `priceBasis: ARCHIVED_SNAPSHOT`일 때 `종료 시점(<closeObservedAt>) 확정값` 배지와 함께
  고정 표시한다. 목록·상세 모두 조회한다 (더 이상 `ACTIVE`만 조회하지 않는다)
- `409 TRACKING_CLOSE_SNAPSHOT_UNAVAILABLE`이면 손익 대신 `종료 시점 시세를 확정하지 못해 손익을 제공하지
  않습니다.`를 표시한다. 조용히 삼키지 않는다
- 타입 union `'OPEN' | 'CLOSED'` → `'ACTIVE' | 'ARCHIVED'`

**테스트 인프라 도입** — `apps/web`에는 테스트가 없었다 (`scripts`는 `dev`/`build`/`start`/`lint`뿐,
testing 관련 의존성 0개). 문구 grep은 주석·미사용 컴포넌트·도달 불가 분기로도 통과하므로 고지가 실제로
보이는지 증명하지 못한다 (`dod.md` AC4).

- devDependencies 추가: `vitest`, `@vitejs/plugin-react`, `jsdom`, `@testing-library/react`,
  `@testing-library/jest-dom`
- `package.json` scripts에 `"test": "vitest run"` 추가, `vitest.config.ts` 신설
- 테스트: `src/components/TrackingList.test.tsx`, `src/app/trackings/[id]/page.test.tsx` —
  `dod.md` AC4 표의 확인 대상을 각각 검증
- `npm ci` 후 `npm audit --audit-level=high`가 여전히 exit 0인지 확인한다. 신규 advisory가 생기면
  `overrides`로 처리하고 그 근거를 `understanding.md`에 남긴다 (PR #65·#66에서 정리한 계약을 깨지 않는다)

**CI 반영** — `.github/workflows/quality-gate.yml`의 web job에 `npm run test`를 추가하고,
`ci/quality-gate-contract-test.sh`의 해당 job 단계 기대값을 함께 갱신한다. 계약 검사와 workflow를 따로
바꾸면 CI가 RED가 된다 (Phase -1에서 확인된 계약).

**검증**

```bash
cd apps/web && npm ci && npm run lint && npm run test && npm run build && npm audit --audit-level=high
cd ../.. && bash ci/quality-gate-contract-test.sh
bash docs/work/private-live-autotrader-phase-0/verify.sh AC1
```

예상: 전부 exit 0, `AC1 GREEN leftover=[api_http=0 web=0 old_route=0] new_route=1`.

### T7. 문서 정렬

- `.ai/architecture/ARCHITECTURE_DESIGN.md`
  - 머리말 As-Is 기준일 갱신
  - `## Planned capability` 절 추가: 자동매매 계획이 `docs/work/private-live-autotrader/design.md`에 있음,
    현재 문서가 다루지 않는 범위(전략 실행·private adapter·LIVE 계약), `ARCH-7` 경계
  - `## 핵심 식별자: MarketPair` 절에 `design.md` §5.5 identity 판정 링크
- `docs/work/private-live-autotrader/README.md` — As-Is 문서 역참조 추가 (동결된 마스터 `design.md`는 불변)
- `docs/work/README.md` — Phase 0 실행 단위 행 추가
- `README.md`, `.ai/context/project-overview.md`, `.ai/PROJECT_STATUS.md`,
  `apps/api/docs/instructions.md` — Position 서술을 추적 record 의미로 갱신
- `docs/work/private-live-autotrader-phase-0/README.md` 신설

**검증**

```bash
bash docs/check-documentation.sh && git diff --check
bash docs/work/private-live-autotrader-phase-0/verify.sh AC8 AC15 AC16 AC17
```

예상: exit 0, `documentation check passed`. `AC16`은 `understanding.md`가 아직 없어 RED다 (⑪-b가 생성).

### T8. 전체 gate와 DoD 증거

```bash
./gradlew test architectureTest --offline --no-daemon
./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon
./gradlew :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --offline --no-daemon
cd apps/web && npm ci && npm run lint && npm run test && npm run build && cd ../..
bash ci/quality-gate-contract-test.sh
bash docs/check-documentation.sh && git diff --check
bash docs/work/private-live-autotrader-phase-0/verify.sh
```

`dod.md` 증거 로그의 GREEN 열을 실제 출력으로 채우고 최종 판정 블록을 갱신한다. Docker 부재로 실행하지 못한
통합 test는 GREEN으로 적지 않고 미실행으로 남긴다.

## 2. 태스크 체크리스트

- [ ] T1. 도메인·인프라·애플리케이션·인터페이스 타입 rename
- [ ] T2. REST 경로·에러코드 rename
- [ ] T3. 상태 표현 정렬 + `V15` + 청산 스냅샷 + 확정 단일성
- [ ] T4. `gross-pnl` 응답 정직화
- [ ] T5. dead·미연결 계약 처리
- [ ] T6. `apps/web` 정렬 (테스트 인프라 도입 포함)
- [ ] T7. 문서 정렬
- [ ] T8. 전체 gate와 DoD 증거

## 3. 이 plan이 결정하지 않는 것

- `MarketPair` 확장 형태와 이행 경로 — `design.md` §6 `OPEN-1`이 Phase 1에 배정
- 순손익 모델과 비용 파라미터 — Phase 1 `P1-O5`
- `SNAPSHOT_UNAVAILABLE`·`LEGACY_UNKNOWN` 행의 사후 backfill 여부 — `OPEN-5`
- PR 분할 — `design.md` D6이 단일 PR로 고정
