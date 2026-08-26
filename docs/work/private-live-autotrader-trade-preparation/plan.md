# 거래 준비 — 실행 계획

| 항목 | 값 |
|---|---|
| 문서 역할 | `feature-workflow` ⑤ plan 문서 |
| slug | `private-live-autotrader-trade-preparation` |
| spec | [`design.md`](design.md) |
| 완료 기준 계약서 | [`dod.md`](dod.md) |
| base branch | `dev` |
| branch | `feat/trade-preparation` |

## 0. 실행 원칙

- **모든 태스크는 끝났을 때 컴파일되고 테스트가 통과하는 상태로 남는다.**
- `dod.md` 계약이 `FROZEN` 되기 전에 구현 코드를 쓰지 않는다 (DoD 절대 규칙 1).
- T1 기준은 테스트를 먼저 쓰고 실패를 확인한다. RED 로그가 없는 T1은 미충족이다.
- 로컬 gradle은 `--offline --no-daemon`을 붙인다 (`.ai/rules/testing.md`).
- Docker 부재로 실행하지 못한 통합 test는 GREEN으로 적지 않는다.

**사전 확인된 사실** — 계획 수립 중 실측했다. 구현자가 다시 확인할 필요 없다.

| 확인 | 결과 |
|---|---|
| Domain port 파일 관례 | `MarketPorts.kt`·`PremiumPorts.kt`·`AggregationPorts.kt` — 도메인별 **묶음 파일**에 여러 port를 담는다. 개별 `*Port.kt` 파일은 없다 |
| 계약 테스트 관례 | `apps/api/src/integrationTest/.../TrackingRouteContractTest`·`TrackingGrossPnlContractTest` — 실행 중 앱에 실제 HTTP 요청을 보내 응답 키 집합을 대조한다 |
| architecture test | `architecture-tests/` 독립 source set. `ArchitectureTarget` enum이 검사 대상 모듈을 열거하고 `KotlinSourceDependencyScanner`가 import를 훑는다 |
| 기존 tracking 도메인 | `Tracking`·`TrackingStatus`·`TrackingRepository`·`TrackingService`·`TrackingCloseSnapshot` 존재. **직전 종료 포지션 조회에 재사용한다** |
| DTO 관례 | inner class 패턴. interfaces `*Request`/`*Response`, application `*Criteria`/`*Result`, domain `*Command`, 읽기 전용은 `*Snapshot` (`.ai/rules/naming.md`) |
| 인증 경계 | `PublicEndpointPolicy`가 Spring Security matcher와 contract test의 유일한 목록. 추적 endpoint는 전부 인증 필요 |
| migration 필요 여부 | **필요하다.** durable 계획(D5)을 저장해야 한다. 최신 버전은 `V15`이므로 `V16`이다 |

## 1. 태스크

### T1. Domain — 잔고 port와 사이징

**신설** `domain/src/main/kotlin/io/premiumspread/domain/tradeprep/`

| 파일 | 내용 |
|---|---|
| `TradePrepPorts.kt` | `BalanceReadPort` — 표시용·판정용 두 계약 (D2). 관례상 묶음 파일 |
| `BalanceSnapshot.kt` | 잔고 스냅샷 read model. `balanceBasis`·`observedAt`·스냅샷 id 보존 |
| `BalanceBasis.kt` | `FRESH` / `STALE` / `UNAVAILABLE` |
| `TradePrepSizing.kt` | `R` → `L` → `Q` 순수 함수 (`ECO-5` §2) |
| `CapVerdict.kt` | 레버 캡·효율 캡·청산 거리 판정 결과 |
| `TradePrepPolicy.kt` | 캡 값을 설정으로 받아 판정. 값을 하드코딩하지 않는다 |

**판정용 계약을 타입으로 분리한다.** 표시용이 `BalanceSnapshot`을 반환하고, 판정용은
별도 타입(`VerifiedBalance`)을 반환해 캐시 경로에서 만들 수 없게 한다 — `dod.md` AC5.

**검증**

```bash
./gradlew test --tests '*TradePreparationSizing*' --tests '*TradePreparationCap*' --offline --no-daemon
./gradlew architectureTest --offline --no-daemon
```

예상: `AC2`·`AC3`·`AC8` GREEN. 나머지는 아직 RED다.

### T2. Domain — 계획 엔티티와 상태 기계

**신설** `TradePreparation` (JPA Entity, `data class` 아님), `TradePreparationStatus`

```
DRAFT ──(희망 프리미엄 등록)──> WATCHING ──(조건 충족)──> ARMED
  │                                │                        │
  └────────────(무효화)─────────────┴────────────────────────┘
                                                             ↓
                                                        INVALIDATED
```

무효화 트리거는 셋이다 (D4). 시간 경과는 트리거가 아니다.

| 트리거 | 조건 |
|---|---|
| 체결 | 이 owner의 tracking이 생성·종료됨 |
| owner refresh | 명시 요청 |
| reconcile 불일치 | 판정용 잔고가 결속 스냅샷과 다름 |

`ARMED`에서 owner 확인이 없을 때의 거동은 `design.md` `TP-OPEN-3`이며 **이 태스크에서 결정한다.**

**검증**

```bash
./gradlew test --tests '*TradePreparationInvalidation*' --tests '*TradePreparationSnapshotBinding*' --offline --no-daemon
```

예상: `AC5`·`AC6` GREEN.

### T3. Migration `V16`

`infrastructure/common/src/main/resources/db/migration/V16__create_trade_preparation.sql`

`ALTER` 없이 `CREATE TABLE` 하나다. 기존 테이블을 건드리지 않으므로 append-only 계약과 무충돌이다.
컬럼은 계획 식별자, owner, 결속 스냅샷 id, 잔고 두 값, 산출 물량·레버, 희망 프리미엄, 상태,
무효화 사유, `BaseEntity` 공통 컬럼.

**검증**

```bash
./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon
./gradlew :infrastructure:common:integrationTest --tests '*V16*' --offline --no-daemon
```

예상: exit 0. 빈 DB latest와 `V15`→`V16` 경로 둘 다.

### T4. Infrastructure — declared 어댑터와 저장소

| 파일 | 내용 |
|---|---|
| `infrastructure/common/.../tradeprep/DeclaredBalanceAdapter.kt` | 요청에 담긴 신고값을 `BalanceSnapshot`으로 (D1) |
| `infrastructure/common/.../persistence/jpa/tradeprep/JpaTradePreparationRepositoryAdapter.kt` | 계획 저장 |
| `.../SpringDataTradePreparationRepository.kt` | Spring Data |

**실거래소 어댑터는 만들지 않는다** (`design.md` §1.3). `ExchangeBalanceAdapter`는 `ACT-2` 이후다.

**검증**

```bash
./gradlew :infrastructure:common:integrationTest --tests '*TradePreparation*' --offline --no-daemon
./gradlew architectureTest --offline --no-daemon
```

### T5. Application — Facade

`apps/api/src/main/kotlin/io/premiumspread/application/tradeprep/`

| 파일 | 내용 |
|---|---|
| `TradePreparationFacade.kt` | 유스케이스 조합. Domain port만 주입 |
| `TradePreparationCriteria.kt` | inner class 패턴 |
| `TradePreparationResult.kt` | 〃 |

유스케이스 넷이다.

1. `prepare` — 잔고 조회 → 사이징 → 캡 판정 → 계획 생성. 직전 종료 포지션 참조값 포함 (D8)
2. `registerTarget` — 희망 프리미엄 등록 → `WATCHING`
3. `invalidate` — 사건 기반 무효화
4. `findById` — 계획 조회

`prepare`는 **표시용** 잔고를, `registerTarget`은 **판정용** 잔고를 쓴다 (D2). exposure를 늘리는
쪽이 후자다. `STALE`이면 전자는 라벨과 함께 반환하고 후자는 거절한다 (D3).

**검증**

```bash
./gradlew test --offline --no-daemon
./gradlew architectureTest --offline --no-daemon
```

### T6. Interfaces — REST

`apps/api/src/main/kotlin/io/premiumspread/interfaces/api/tradeprep/`

| 메서드 | 경로 |
|---|---|
| `prepare` | `POST /api/v1/trade-preparations` |
| `registerTarget` | `POST /api/v1/trade-preparations/{id}/target` |
| `getById` | `GET /api/v1/trade-preparations/{id}` |

`ApplicationError` 신설: `TRADE_PREPARATION_NOT_FOUND`(404), `STALE_BALANCE_FOR_EXPOSURE`(409),
`CAP_VIOLATED`(422). `GlobalExceptionHandler` 매핑 추가.

`PublicEndpointPolicy`에 **추가하지 않는다** — `dod.md` AC9.

`http/api/trade-preparations.http` 신설, `http/README.md` 파일 목록 갱신 (`.ai/rules/http.md`).

**검증**

```bash
./gradlew :apps:api:integrationTest --tests '*TradePreparationContract*' --tests '*TradePreparationAuth*' --offline --no-daemon
```

예상: `AC1`·`AC9` GREEN.

### T7. 조건 평가

프리미엄 스트림을 소비해 `WATCHING` 계획의 조건을 평가하고 `ARMED`로 전이한다.

**배치가 이미 프리미엄을 계산한다.** 새 수집을 만들지 않고 기존 계산 결과를 읽는다.
평가 주기와 조건 충족 판정의 신선도 계약은 `design.md` `TP-OPEN-4`이며 **이 태스크에서 결정한다.**

전이 시 **주문을 제출하지 않는다.** `ARMED`가 종점이다.

**검증**

```bash
./gradlew :apps:api:integrationTest --tests '*TradePreparationArming*' --offline --no-daemon
./gradlew :apps:api:integrationTest --tests '*TradePreparationStaleBalance*' --offline --no-daemon
```

예상: `AC4`·`AC7` GREEN.

### T8. 전체 gate와 DoD 증거

```bash
./gradlew test architectureTest --offline --no-daemon
./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon
./gradlew :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --offline --no-daemon
npm --prefix apps/web run lint
npm --prefix apps/web run test
npm --prefix apps/web run build
bash docs/check-documentation.sh
```

`dod.md` 증거 로그의 GREEN 열을 실제 출력으로 채우고 최종 판정 블록을 갱신한다.
`AC10`은 T4이므로 사람 확인 표에 앵커가 기록되기 전까지 `AWAITING_HUMAN`이다.

## 2. 태스크 체크리스트

- [ ] T1. Domain — 잔고 port와 사이징
- [ ] T2. Domain — 계획 엔티티와 상태 기계 (`TP-OPEN-3` 결정)
- [ ] T3. Migration `V16`
- [ ] T4. Infrastructure — declared 어댑터와 저장소
- [ ] T5. Application — Facade
- [ ] T6. Interfaces — REST
- [ ] T7. 조건 평가 (`TP-OPEN-4` 결정)
- [ ] T8. 전체 gate와 DoD 증거

## 3. 이 plan이 결정하지 않는 것

- **`TP-OPEN-1`** 빗썸 private API 한도·잔고 엔드포인트 — ⑥ 스펙 리뷰 이전 확인. declared 어댑터만
  만드는 이번 범위에서는 차단하지 않는다
- **`TP-OPEN-5`** `leverageBracket` 확인 — 실계정 조회 필요. 명목 구간별 최대 레버리지가 캡보다
  낮을 수 있으나 declared 단계에서는 검증 불가
- **`TP-OPEN-6`** 보유 중 포지션이 있을 때 거래 준비 요청의 거동 — T5 `prepare` 설계 중 결정
- 실거래소 어댑터의 형태와 credential 경계 — `design.md` §1.3이 제외
- 프론트엔드 화면 — 이번 범위는 API까지다
