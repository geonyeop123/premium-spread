# Plan: Position AUTO/MANUAL 엔드포인트 분기

- Issue: #42
- Spec: `docs/superpowers/specs/2026-05-18-issue-42-position-auto-manual-endpoints-design.md`
- Branch: `feat/issue-42-position-auto-manual-endpoints`
- Date: 2026-05-18

## 태스크 개요

1. T1 — `PremiumSnapshotNotAvailableException` 신규 추가 (`application/position/`)
2. T2 — `GlobalExceptionHandler`에 새 예외 핸들러 + 메시지 추가 (409 응답)
3. T3 — `PositionCriteria.Open` 제거 → `PositionCriteria.OpenAuto` + `PositionCriteria.OpenManual` 추가
4. T4 — `PositionFacade.openPosition` 제거 → `openAutoPosition` + `openManualPosition` 추가
5. T5 — `PositionRequest.Open` 제거 → `PositionRequest.OpenAuto` + `PositionRequest.OpenManual` 추가
6. T6 — `PositionController.open` 제거 → `openAuto` + `openManual` 핸들러 추가
7. T7 — `http/api/positions.http` AUTO/MANUAL 샘플로 교체
8. T8 — `PositionFacadeTest` AUTO/MANUAL 케이스 재작성
9. T9 — `PositionControllerTest` AUTO/MANUAL 테스트 재작성
10. T10 — `PositionControllerE2ETest` AUTO/MANUAL 통합 테스트 재작성
11. T11 — 빌드 + 테스트 검증

각 태스크는 컴파일 가능한 상태로 묶어 한 단위(T1~T10)로 진행하고, T11에서 일괄 빌드한다.

---

## T1. 예외 클래스 2개 추가 (PositionFacade.kt 하단)

**파일:** `apps/api/src/main/kotlin/io/premiumspread/application/position/PositionFacade.kt` (하단)

기존 `PositionNotFoundException`, `PremiumNotFoundException` 옆에 같은 패턴으로 추가:

```kotlin
class PremiumSnapshotNotAvailableException(message: String) : RuntimeException(message)
class StalePremiumSnapshotException(message: String) : RuntimeException(message)
```

별도 클래스로 분리하는 이유: 클라이언트가 retry 전략을 다르게 가져갈 수 있음 (없음 vs stale).

---

## T2. GlobalExceptionHandler에 핸들러 2개 + 메시지 2개 추가

**파일:** `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/GlobalExceptionHandler.kt`

추가 사항:

```kotlin
// import 추가
import io.premiumspread.application.position.PremiumSnapshotNotAvailableException
import io.premiumspread.application.position.StalePremiumSnapshotException

// 핸들러 1
@ExceptionHandler(PremiumSnapshotNotAvailableException::class)
fun handlePremiumSnapshotNotAvailable(
    ex: PremiumSnapshotNotAvailableException,
): ResponseEntity<ErrorResponse> {
    return ResponseEntity
        .status(HttpStatus.CONFLICT)
        .body(
            ErrorResponse(
                code = "PREMIUM_SNAPSHOT_NOT_AVAILABLE",
                message = ERROR_MESSAGES["PREMIUM_SNAPSHOT_NOT_AVAILABLE"]!!,
            ),
        )
}

// 핸들러 2
@ExceptionHandler(StalePremiumSnapshotException::class)
fun handleStalePremiumSnapshot(
    ex: StalePremiumSnapshotException,
): ResponseEntity<ErrorResponse> {
    return ResponseEntity
        .status(HttpStatus.CONFLICT)
        .body(
            ErrorResponse(
                code = "STALE_PREMIUM_SNAPSHOT",
                message = ERROR_MESSAGES["STALE_PREMIUM_SNAPSHOT"]!!,
            ),
        )
}

// ERROR_MESSAGES 맵에 추가
"PREMIUM_SNAPSHOT_NOT_AVAILABLE" to "해당 종목의 최신 프리미엄 스냅샷이 없습니다.",
"STALE_PREMIUM_SNAPSHOT" to "프리미엄 스냅샷이 오래되어 사용할 수 없습니다. 잠시 후 다시 시도해주세요.",
```

---

## T3. PositionCriteria 변경

**파일:** `apps/api/src/main/kotlin/io/premiumspread/application/position/PositionDtos.kt`

`PositionCriteria.Open`을 제거하고 두 개로 분리:

```kotlin
class PositionCriteria private constructor() {
    data class OpenAuto(
        val memberId: Long,
        val symbol: String,
        val koreaExchange: Exchange,
        val koreaQuantity: BigDecimal,
        val foreignExchange: Exchange,
        val foreignQuantity: BigDecimal,
        val foreignLeverage: Int,
    )

    data class OpenManual(
        val memberId: Long,
        val symbol: String,
        val koreaExchange: Exchange,
        val koreaQuantity: BigDecimal,
        val koreaEntryPrice: BigDecimal,
        val foreignExchange: Exchange,
        val foreignQuantity: BigDecimal,
        val foreignEntryPrice: BigDecimal,
        val foreignLeverage: Int,
        val entryFxRate: BigDecimal,
        val entryObservedAt: Instant,
    )
}
```

`PositionResult.Detail` / `.Pnl` / `.Summary`는 변경 없음.

---

## T4. PositionFacade 메서드 분리

**파일:** `apps/api/src/main/kotlin/io/premiumspread/application/position/PositionFacade.kt`

`openPosition` 제거, `openAutoPosition` + `openManualPosition` 추가. 다른 메서드(`findById`, `findAllOpen*`, `findAllClosedByMemberId`, `calculatePnl`, `getSummary`, `closePosition`)는 그대로.

상수 추가 (companion object):
```kotlin
companion object {
    private const val SNAPSHOT_MAX_AGE_SECONDS = 60L
}
```

```kotlin
@Transactional
fun openAutoPosition(criteria: PositionCriteria.OpenAuto): PositionResult.Detail {
    val snapshot = premiumService.findLatestSnapshotBySymbol(Symbol(criteria.symbol))
        ?: throw PremiumSnapshotNotAvailableException(
            "Premium snapshot not available for symbol: ${criteria.symbol}",
        )

    val ageSeconds = Duration.between(snapshot.observedAt, Instant.now()).seconds
    if (ageSeconds > SNAPSHOT_MAX_AGE_SECONDS) {
        throw StalePremiumSnapshotException(
            "Premium snapshot is stale (age=${ageSeconds}s, max=${SNAPSHOT_MAX_AGE_SECONDS}s) for symbol: ${criteria.symbol}",
        )
    }

    val command = PositionCommand.Create(
        memberId = criteria.memberId,
        symbol = criteria.symbol,
        koreaExchange = criteria.koreaExchange,
        koreaQuantity = criteria.koreaQuantity,
        koreaEntryPrice = snapshot.koreaPrice,
        foreignExchange = criteria.foreignExchange,
        foreignQuantity = criteria.foreignQuantity,
        foreignEntryPrice = snapshot.foreignPrice,
        foreignLeverage = criteria.foreignLeverage,
        entryFxRate = snapshot.fxRate,
        entryObservedAt = snapshot.observedAt,
    )
    val position = positionService.create(command)
    return PositionResult.Detail.from(position)
}

@Transactional
fun openManualPosition(criteria: PositionCriteria.OpenManual): PositionResult.Detail {
    val command = PositionCommand.Create(
        memberId = criteria.memberId,
        symbol = criteria.symbol,
        koreaExchange = criteria.koreaExchange,
        koreaQuantity = criteria.koreaQuantity,
        koreaEntryPrice = criteria.koreaEntryPrice,
        foreignExchange = criteria.foreignExchange,
        foreignQuantity = criteria.foreignQuantity,
        foreignEntryPrice = criteria.foreignEntryPrice,
        foreignLeverage = criteria.foreignLeverage,
        entryFxRate = criteria.entryFxRate,
        entryObservedAt = criteria.entryObservedAt,
    )
    val position = positionService.create(command)
    return PositionResult.Detail.from(position)
}
```

파일 하단의 예외 선언에 두 줄 추가:

```kotlin
class PositionNotFoundException(message: String) : RuntimeException(message)
class PremiumNotFoundException(message: String) : RuntimeException(message)
class PremiumSnapshotNotAvailableException(message: String) : RuntimeException(message)
class StalePremiumSnapshotException(message: String) : RuntimeException(message)
```

import 추가:
- `java.time.Duration`
- `java.time.Instant` (이미 있다면 생략)

`Symbol`은 이미 import됨.

---

## T5. PositionRequest 변경

**파일:** `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/position/PositionDtos.kt`

`PositionRequest.Open`을 두 개로 분리:

```kotlin
class PositionRequest private constructor() {
    data class OpenAuto(
        val symbol: String,
        val koreaExchange: String,
        val koreaQuantity: BigDecimal,
        val foreignExchange: String,
        val foreignQuantity: BigDecimal,
        val foreignLeverage: Int,
    )

    data class OpenManual(
        val symbol: String,
        val koreaExchange: String,
        val koreaQuantity: BigDecimal,
        val koreaEntryPrice: BigDecimal,
        val foreignExchange: String,
        val foreignQuantity: BigDecimal,
        val foreignEntryPrice: BigDecimal,
        val foreignLeverage: Int,
        val entryFxRate: BigDecimal,
        val entryObservedAt: Instant,
    )
}
```

`PositionResponse.Detail` / `.Pnl` / `.Summary`는 변경 없음.

---

## T6. PositionController 핸들러 분리

**파일:** `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/position/PositionController.kt`

기존 `open` 메서드 제거하고 두 개 추가:

```kotlin
@PostMapping("/auto")
fun openAuto(
    @LoginMemberId memberId: Long,
    @RequestBody request: PositionRequest.OpenAuto,
): ResponseEntity<PositionResponse.Detail> {
    val criteria = PositionCriteria.OpenAuto(
        memberId = memberId,
        symbol = request.symbol,
        koreaExchange = Exchange.valueOf(request.koreaExchange),
        koreaQuantity = request.koreaQuantity,
        foreignExchange = Exchange.valueOf(request.foreignExchange),
        foreignQuantity = request.foreignQuantity,
        foreignLeverage = request.foreignLeverage,
    )
    val result = positionFacade.openAutoPosition(criteria)
    return ResponseEntity.status(HttpStatus.CREATED).body(PositionResponse.Detail.from(result))
}

@PostMapping("/manual")
fun openManual(
    @LoginMemberId memberId: Long,
    @RequestBody request: PositionRequest.OpenManual,
): ResponseEntity<PositionResponse.Detail> {
    val criteria = PositionCriteria.OpenManual(
        memberId = memberId,
        symbol = request.symbol,
        koreaExchange = Exchange.valueOf(request.koreaExchange),
        koreaQuantity = request.koreaQuantity,
        koreaEntryPrice = request.koreaEntryPrice,
        foreignExchange = Exchange.valueOf(request.foreignExchange),
        foreignQuantity = request.foreignQuantity,
        foreignEntryPrice = request.foreignEntryPrice,
        foreignLeverage = request.foreignLeverage,
        entryFxRate = request.entryFxRate,
        entryObservedAt = request.entryObservedAt,
    )
    val result = positionFacade.openManualPosition(criteria)
    return ResponseEntity.status(HttpStatus.CREATED).body(PositionResponse.Detail.from(result))
}
```

기존 `@PostMapping` (`/api/v1/positions` 루트로의 POST)은 완전히 제거. 다른 엔드포인트 (`/summary`, `/history`, `/{id}`, `/{id}/pnl`, `/{id}/close`)는 그대로.

---

## T7. http/api/positions.http 갱신

**파일:** `http/api/positions.http`

기존 단일 POST 샘플을 AUTO + MANUAL 두 샘플로 교체. 기타 GET/PATCH 샘플은 그대로.

```http
### 포지션 오픈 - AUTO (서버가 PremiumSnapshot에서 시세 자동 충전)
POST {{api_base}}/positions/auto
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "symbol": "BTC",
  "koreaExchange": "BITHUMB",
  "koreaQuantity": 0.157,
  "foreignExchange": "BINANCE",
  "foreignQuantity": 0.15,
  "foreignLeverage": 5
}

### 포지션 오픈 - MANUAL (사용자가 외부에서 잡은 포지션을 직접 입력)
POST {{api_base}}/positions/manual
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "symbol": "BTC",
  "koreaExchange": "BITHUMB",
  "koreaQuantity": 0.157,
  "koreaEntryPrice": 161493792,
  "foreignExchange": "BINANCE",
  "foreignQuantity": 0.15,
  "foreignEntryPrice": 118100,
  "foreignLeverage": 5,
  "entryFxRate": 1521.6,
  "entryObservedAt": "2026-04-01T10:30:00Z"
}
```

---

## T8. PositionFacadeTest 재작성

**파일:** `apps/api/src/test/kotlin/io/premiumspread/application/position/PositionFacadeTest.kt`

신규 케이스 (모두 필수):

1. `openAutoPosition` 정상 — snapshot mock이 신선한(observedAt = Instant.now()에 가까움) 가격 반환, Position 생성 후 페어 필드 검증, snapshot.koreaPrice ↔ position.koreaEntryPrice 매핑 확인
2. `openAutoPosition` snapshot 없음 — `findLatestSnapshotBySymbol` mock이 null 반환, `PremiumSnapshotNotAvailableException` 검증
3. `openAutoPosition` snapshot stale — snapshot.observedAt이 `Instant.now().minusSeconds(120)` 등 60초 초과로 mock, `StalePremiumSnapshotException` 검증
4. `openAutoPosition` region 위반 — koreaExchange=BINANCE 등 region 불일치 시 `InvalidPositionException` (Entity 검증으로 전파)
5. `openManualPosition` 정상 — 입력값이 그대로 Position에 반영, entryPremiumRate가 계산값으로 채워지는지 검증
6. `openManualPosition` region 위반 — koreaExchange=BINANCE 등 시 `InvalidPositionException`
7. 기타 메서드(`findById`, `closePosition`, `calculatePnl`, `getSummary`, `findAllOpenByMemberId`, `findAllClosedByMemberId`)는 기존 케이스 유지

PremiumService mock은 `findLatestSnapshotBySymbol` 메서드만 stub. PremiumSnapshot fixture는 테스트 내부 헬퍼로 작성 또는 기존 `PremiumFixtures` 활용.

---

## T9. PositionControllerTest 재작성

**파일:** `apps/api/src/test/kotlin/io/premiumspread/interfaces/api/position/PositionControllerTest.kt`

mockk 기반 controller slice test. 신규 케이스 (모두 필수):

1. `POST /api/v1/positions/auto` — 정상 (201), 응답 본문 페어 필드 확인
2. `POST /api/v1/positions/auto` — facade가 `PremiumSnapshotNotAvailableException` throw → 409 + code=PREMIUM_SNAPSHOT_NOT_AVAILABLE
3. `POST /api/v1/positions/auto` — facade가 `StalePremiumSnapshotException` throw → 409 + code=STALE_PREMIUM_SNAPSHOT
4. `POST /api/v1/positions/auto` — facade가 `InvalidPositionException` throw → 400 (region 위반 시나리오)
5. `POST /api/v1/positions/manual` — 정상 (201)
6. `POST /api/v1/positions/manual` — facade가 `InvalidPositionException` throw → 400 (region 위반 시나리오)
7. `POST /api/v1/positions` (루트 POST) — 라우트 제거 회귀: 404 또는 405 (Spring 기본은 405 Method Not Allowed)
8. 기타 GET 메서드 회귀

---

## T10. PositionControllerE2ETest 재작성

**파일:** `apps/api/src/test/kotlin/io/premiumspread/interfaces/api/position/PositionControllerE2ETest.kt`

실제 application context로 테스트. 신규 케이스 (모두 필수):

1. `POST /positions/auto` — 사전에 신선한 premium snapshot 삽입 (observedAt = now) → 201 정상 생성
2. `POST /positions/auto` — snapshot 없는 symbol → 409 + code=PREMIUM_SNAPSHOT_NOT_AVAILABLE
3. `POST /positions/auto` — snapshot stale (observedAt = now.minusSeconds(120)) → 409 + code=STALE_PREMIUM_SNAPSHOT
4. `POST /positions/auto` — region 위반 (예: koreaExchange=BINANCE) → 400 + code=INVALID_POSITION
5. `POST /positions/manual` — 정상 → 201
6. `POST /positions/manual` — region 위반 → 400
7. `POST /positions` (루트 POST) — 라우트 제거 회귀: 404 또는 405
8. 인증 헤더 누락 시 401 (기존 회귀)

테스트 데이터 셋업 방식은 기존 E2E 패턴 그대로 따른다. PremiumSnapshot 삽입은 직접 DB insert 또는 PremiumFixtures + repository.save 활용.

---

## T11. 빌드 + 테스트 검증

```bash
./gradlew :apps:api:compileKotlin :apps:api:compileTestKotlin
./gradlew :apps:api:test --tests "*Position*"
```

수용 기준:
- [ ] 컴파일 통과
- [ ] PositionFacadeTest 신규 4케이스 + 회귀 통과
- [ ] PositionControllerTest 신규 5케이스 통과
- [ ] PositionControllerE2ETest 신규 케이스 통과
- [ ] PositionTest, PositionServiceTest, PositionRepositoryTest, PositionCacheWriterTest 회귀 통과

---

## 진행 메모

- T1 ~ T10은 인터페이스 시그니처가 연쇄적으로 바뀌므로 한 단위로 진행하고 T11에서 일괄 검증한다.
- 본 이슈에서는 #43 (PnL KRW 확장)에 손대지 않는다.
- 본 이슈에서는 #44 (프론트엔드)에 손대지 않는다 — 프론트는 본 PR 머지 후 별도로 갱신되어야 함.
- AUTO에서 snapshot.premiumRate는 사용하지 않는다 (Entity가 페어 가격으로 자체 계산하여 단일 소스 유지).
