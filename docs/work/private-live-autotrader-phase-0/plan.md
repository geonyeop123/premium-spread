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

### T3. 상태값 정렬 + `V15` + 청산 스냅샷

**`TrackingStatus`**: `OPEN` → `ACTIVE`, `CLOSED` → `ARCHIVED`

**`infrastructure/common/src/main/resources/db/migration/V15__add_close_snapshot_and_align_tracking_status.sql`**

```sql
-- V15: 추적 종료 시점의 시세를 확정 저장하고 상태값을 추적 의미로 정렬한다.
ALTER TABLE position
    ADD COLUMN closed_at           DATETIME(6)     NULL AFTER status,
    ADD COLUMN close_price_source  VARCHAR(30)     NULL AFTER closed_at,
    ADD COLUMN close_observed_at   DATETIME(6)     NULL AFTER close_price_source,
    ADD COLUMN close_korea_price   DECIMAL(30, 10) NULL AFTER close_observed_at,
    ADD COLUMN close_foreign_price DECIMAL(30, 10) NULL AFTER close_korea_price,
    ADD COLUMN close_fx_rate       DECIMAL(20, 6)  NULL AFTER close_foreign_price,
    ADD COLUMN close_premium_rate  DECIMAL(10, 2)  NULL AFTER close_fx_rate;

UPDATE position SET close_price_source = 'LEGACY_UNKNOWN' WHERE status = 'CLOSED';
UPDATE position SET status = 'ARCHIVED' WHERE status = 'CLOSED';
UPDATE position SET status = 'ACTIVE'   WHERE status = 'OPEN';
```

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

**Facade `archive`**: 최신 premium snapshot을 조회해 60초 이내면 `TrackingCloseSnapshot`, 아니면 `null`을
넘긴다. **snapshot 부재·stale을 이유로 archive를 거절하지 않는다** (`design.md` §5.3.2).

**검증**

```bash
./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon
./gradlew test --offline --no-daemon
./gradlew :infrastructure:common:integrationTest --tests '*V15*' --offline --no-daemon
bash docs/work/private-live-autotrader-phase-0/verify.sh AC11
```

예상: exit 0. `verifyMigrations`는 `UPDATE`를 차단하지 않는다 (`infrastructure/common/build.gradle.kts:85`가
`TRUNCATE TABLE`·`DROP TABLE`만 검사).

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

```bash
./gradlew test --offline --no-daemon
./gradlew :apps:api:integrationTest --tests '*TrackingArchive*' --offline --no-daemon
bash docs/work/private-live-autotrader-phase-0/verify.sh AC3 AC5
```

예상: exit 0. `AC5`는 "archive 후 시세를 바꿔도 gross 손익이 동일" 케이스와 409 케이스를 모두 포함한다.

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

**검증**

```bash
cd apps/web && npm ci && npm run lint && npm run build
cd ../.. && bash docs/work/private-live-autotrader-phase-0/verify.sh AC1 AC4
```

예상: exit 0, `AC1 GREEN`, `AC4 GREEN missing=[]`.

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
cd apps/web && npm ci && npm run lint && npm run build && cd ../..
bash docs/work/private-live-autotrader-phase-0/verify.sh
```

`dod.md` 증거 로그의 GREEN 열을 실제 출력으로 채우고 최종 판정 블록을 갱신한다. Docker 부재로 실행하지 못한
통합 test는 GREEN으로 적지 않고 미실행으로 남긴다.

## 2. 태스크 체크리스트

- [ ] T1. 도메인·인프라·애플리케이션·인터페이스 타입 rename
- [ ] T2. REST 경로·에러코드 rename
- [ ] T3. 상태값 정렬 + `V15` + 청산 스냅샷
- [ ] T4. `gross-pnl` 응답 정직화
- [ ] T5. dead·미연결 계약 처리
- [ ] T6. `apps/web` 정렬
- [ ] T7. 문서 정렬
- [ ] T8. 전체 gate와 DoD 증거

## 3. 이 plan이 결정하지 않는 것

- `MarketPair` 확장 형태와 이행 경로 — `design.md` §6 `OPEN-1`이 Phase 1에 배정
- 순손익 모델과 비용 파라미터 — Phase 1 `P1-O5`
- `SNAPSHOT_UNAVAILABLE`·`LEGACY_UNKNOWN` 행의 사후 backfill 여부 — `OPEN-5`
- PR 분할 — `design.md` D6이 단일 PR로 고정
