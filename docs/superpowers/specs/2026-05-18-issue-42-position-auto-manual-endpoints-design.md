# Position AUTO/MANUAL 엔드포인트 분기

- Issue: #42 (Parent: #40)
- Branch: `feat/issue-42-position-auto-manual-endpoints`
- Date: 2026-05-18
- Depends on: #41 (이미 dev 머지됨, commit 4f4b7f2)

## 배경

#41에서 `Position` 엔티티가 한국 long + 해외 short 페어 모델로 재구조화되었다. 그러나 포지션 생성은 여전히 단일 `POST /api/v1/positions` 엔드포인트만 존재하며, 클라이언트가 모든 진입 정보(price, fxRate, observedAt)를 직접 입력하는 형태이다.

본 이슈는 포지션 생성을 **두 경로로 분리**한다.

- **AUTO**: 서버가 수집 중인 `PremiumSnapshot` 최신값으로 진입가/환율/관측시각을 자동 채움. "지금 이 시세로 포지션 잡기"
- **MANUAL**: 사용자가 외부에서 잡아둔 포지션을 수동 입력. 진입가/환율/관측시각까지 모두 입력 받음

## 목표 / 스코프

### 포함

- `POST /api/v1/positions/auto` — 자동 시세 충전 엔드포인트
- `POST /api/v1/positions/manual` — 수동 입력 엔드포인트
- 기존 `POST /api/v1/positions` **제거**
- `PositionFacade`에 `openAutoPosition`, `openManualPosition` 2개 메서드
- `PositionCriteria.OpenAuto`, `PositionCriteria.OpenManual` 2개 DTO
- `PositionRequest.OpenAuto`, `PositionRequest.OpenManual` 2개 DTO
- `PositionCommand.Create` (도메인)는 그대로 유지 — 어차피 입력 전 시점부터는 동일
- AUTO 실패 케이스: `PremiumSnapshot` 없음 → 409 응답
- `http/api/positions.http` AUTO/MANUAL 샘플 갱신
- 통합 테스트로 두 경로 모두 검증

### 제외 (후속 이슈)

- KRW PnL 수식 확장 → #43
- 프론트엔드 폼 분리/PnL 표시 → #44

## API 계약

### AUTO 엔드포인트

```http
POST /api/v1/positions/auto
Content-Type: application/json
Authorization: Bearer {{token}}

{
  "symbol": "BTC",
  "koreaExchange": "BITHUMB",
  "koreaQuantity": "0.157",
  "foreignExchange": "BINANCE",
  "foreignQuantity": "0.15",
  "foreignLeverage": 5
}
```

**서버 자동 채움 (PremiumSnapshot에서):**
- `koreaEntryPrice` ← `snapshot.koreaPrice`
- `foreignEntryPrice` ← `snapshot.foreignPrice`
- `entryFxRate` ← `snapshot.fxRate`
- `entryObservedAt` ← `snapshot.observedAt`
- `entryPremiumRate` ← Entity 팩토리에서 페어 가격 기준 재계산 (snapshot.premiumRate를 그대로 신뢰하지 않고 서버 계산 일관성 유지)

**신선도 검증:**
- `snapshot.observedAt`이 현재 시각으로부터 **60초 이상 오래되면** stale로 간주하고 거절
- 신선도 임계값(`SNAPSHOT_MAX_AGE_SECONDS = 60`)은 `PositionFacade` 내부 상수로 관리
- WebSocket 수집(1~5초 갱신) 기준 충분한 안전 마진 + 일시적 수집 누락에도 관대

**실패:**
- `symbol`에 대한 `PremiumSnapshot` 없음 → 409 Conflict + `PremiumSnapshotNotAvailableException` (code: `PREMIUM_SNAPSHOT_NOT_AVAILABLE`)
- `PremiumSnapshot`은 있으나 60초 이상 오래됨 → 409 Conflict + `StalePremiumSnapshotException` (code: `STALE_PREMIUM_SNAPSHOT`)
- region / positive / leverage 검증 위반 → 400 (`InvalidPositionException`)

### MANUAL 엔드포인트

```http
POST /api/v1/positions/manual
Content-Type: application/json
Authorization: Bearer {{token}}

{
  "symbol": "BTC",
  "koreaExchange": "BITHUMB",
  "koreaQuantity": "0.157",
  "koreaEntryPrice": "161493792",
  "foreignExchange": "BINANCE",
  "foreignQuantity": "0.15",
  "foreignEntryPrice": "118100",
  "foreignLeverage": 5,
  "entryFxRate": "1521.6",
  "entryObservedAt": "2026-04-01T10:30:00Z"
}
```

서버가 모든 값을 그대로 사용. `entryPremiumRate`는 Entity 팩토리가 자동 계산.

**실패:**
- region / positive / leverage 검증 위반 → 400 (`InvalidPositionException`)

### 응답 (양쪽 동일)

```http
201 Created
{
  "id": 42,
  "symbol": "BTC",
  "koreaExchange": "BITHUMB",
  "koreaQuantity": "0.157",
  "koreaEntryPrice": "161493792",
  "foreignExchange": "BINANCE",
  "foreignQuantity": "0.15",
  "foreignEntryPrice": "118100",
  "foreignLeverage": 5,
  "entryFxRate": "1521.6",
  "entryPremiumRate": "-10.13",
  "entryObservedAt": "2026-04-01T10:30:00Z",
  "status": "OPEN"
}
```

`PositionResponse.Detail` 그대로. 별도 추가 필드 없음.

## 레이어 구성

```
interfaces/api/position/
  PositionController.kt
    POST /api/v1/positions/auto    → openAuto()
    POST /api/v1/positions/manual  → openManual()
    (기존 POST /api/v1/positions 제거)
  PositionDtos.kt
    PositionRequest.OpenAuto / .OpenManual
    PositionResponse.Detail (변경 없음)

application/position/
  PositionFacade.kt
    fun openAutoPosition(criteria: PositionCriteria.OpenAuto): PositionResult.Detail
    fun openManualPosition(criteria: PositionCriteria.OpenManual): PositionResult.Detail
    (기존 openPosition 제거)
  PositionDtos.kt
    PositionCriteria.OpenAuto / .OpenManual

application/position/
  (위 Facade + Criteria 외에)
  PositionFacade.kt 하단에 신규 예외 2개 추가 (기존 PositionNotFoundException/PremiumNotFoundException과 동일 패턴):
    - PremiumSnapshotNotAvailableException(message)
    - StalePremiumSnapshotException(message)

domain/position/
  (변경 없음 — PositionCommand.Create는 그대로, 두 경로 공통)

infrastructure/position/
  (변경 없음)
```

### PositionFacade 흐름

```kotlin
companion object {
    private const val SNAPSHOT_MAX_AGE_SECONDS = 60L
}

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
    return PositionResult.Detail.from(positionService.create(command))
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
    return PositionResult.Detail.from(positionService.create(command))
}
```

`entryPremiumRate`는 `Position.create()` 내부에서 페어 가격으로 자동 계산되므로 별도 전달 불필요.

### PositionCriteria DTO

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

### PositionRequest DTO

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

### PremiumSnapshotNotAvailableException / StalePremiumSnapshotException

`application/position/PositionFacade.kt` 하단에 기존 `PositionNotFoundException`/`PremiumNotFoundException`과 같은 패턴으로 추가. 둘 다 `RuntimeException` 상속. `GlobalExceptionHandler`에 두 예외 별도 매핑 추가:

```kotlin
// 기존 InvalidPositionException → 400
// PositionNotFoundException → 404
// 신규 1: PremiumSnapshotNotAvailableException → 409 Conflict / code: PREMIUM_SNAPSHOT_NOT_AVAILABLE
// 신규 2: StalePremiumSnapshotException        → 409 Conflict / code: STALE_PREMIUM_SNAPSHOT
```

두 코드를 분리하는 이유: 클라이언트가 retry 정책을 다르게 가져갈 수 있음 (snapshot 없음 = 수집 시작 후 잠시 후 재시도 / stale = 거의 즉시 신선해질 가능성).

## 검증 케이스

### Unit (Facade) — 모두 필수

- `openAutoPosition` 정상 — snapshot이 있고 신선할 때 페어 필드 + snapshot 가격으로 Position 생성
- `openAutoPosition` — snapshot 없을 때 `PremiumSnapshotNotAvailableException`
- `openAutoPosition` — snapshot.observedAt이 60초 이상 오래될 때 `StalePremiumSnapshotException`
- `openAutoPosition` — region 위반 시 `InvalidPositionException` (Entity 검증으로 전파)
- `openManualPosition` 정상 — 입력값 그대로 Position 생성, entryPremiumRate는 계산값
- `openManualPosition` — region 위반 시 `InvalidPositionException`

### Integration (Controller) — 모두 필수

- `POST /positions/auto` 정상 (201)
- `POST /positions/auto` snapshot 없음 (409, code=PREMIUM_SNAPSHOT_NOT_AVAILABLE)
- `POST /positions/auto` snapshot stale (409, code=STALE_PREMIUM_SNAPSHOT)
- `POST /positions/auto` region 위반 (400)
- `POST /positions/manual` 정상 (201)
- `POST /positions/manual` region 위반 (400)
- `POST /api/v1/positions` (루트 POST) → **404 또는 405** — 라우트 제거 회귀 검증 (필수, 옵션 아님)

## 영향 범위

- `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/position/PositionController.kt`
- `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/position/PositionDtos.kt` (Request 변경)
- `apps/api/src/main/kotlin/io/premiumspread/application/position/PositionFacade.kt`
- `apps/api/src/main/kotlin/io/premiumspread/application/position/PositionDtos.kt` (Criteria 변경)
- `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/GlobalExceptionHandler.kt` (예외 매핑 2개 신규 + ERROR_MESSAGES 2개 신규)
- `apps/api/src/test/kotlin/io/premiumspread/application/position/PositionFacadeTest.kt`
- `apps/api/src/test/kotlin/io/premiumspread/interfaces/api/position/PositionControllerTest.kt`
- `apps/api/src/test/kotlin/io/premiumspread/interfaces/api/position/PositionControllerE2ETest.kt`
- `http/api/positions.http`

## 위험 / 주의사항

- **Breaking API (이미 발생)**: 기존 `POST /api/v1/positions`는 이미 #41 머지 시점부터 프론트엔드(`apps/web/src/components/OpenPositionForm.tsx:61-72`)가 단일 거래소 필드를 보내고 있어 400 응답 상태. 본 PR이 라우트를 제거하면 404/405로 바뀔 뿐 사용자 영향 추가 없음. **#44에서 프론트엔드 폼을 AUTO/MANUAL로 갱신할 때 완전 해소**된다.
- **AUTO/MANUAL 응답 동일**: 둘 다 `PositionResponse.Detail` 반환. 프론트가 두 경로 구분할 필요 없음
- **snapshot.premiumRate를 무시하고 서버 재계산**: Entity 팩토리에서 `entryPremiumRate`를 페어 가격으로 재계산하므로, snapshot의 premiumRate와 미세한 차이 가능. 이는 의도된 단일 진실원천(SSOT) 정책 — Entity가 entry premium의 단일 소스
- **DTO 검증 (Bean Validation)**: 본 이슈에서는 `@NotNull` 등 Bean Validation 어노테이션 추가하지 않음 (기존 컨벤션 유지). 필수값 누락 시 Jackson 역직렬화 에러로 400 처리
- **시간 의존성**: `Instant.now()`를 facade에서 직접 호출. Clock 주입은 본 이슈 범위 외 — 테스트에서는 `mockkStatic(Instant::class)` 또는 충분히 과거/현재 fixture로 stale/fresh 케이스 구성

## Out of Scope (재확인)

- KRW PnL 수식 → #43
- 프론트엔드 폼 토글 + 응답 표시 → #44
