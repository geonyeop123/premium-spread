# Naming Conventions

## DTO (Inner Class Pattern)

모든 레이어의 DTO는 컨테이너 클래스 내 inner class 패턴을 사용:

| Layer | 컨테이너 | Inner Class | 예시 |
|-------|---------|------------|------|
| interfaces | `*Request`, `*Response` | 동작 | `PositionRequest.Open` |
| application | `*Criteria`, `*Result` | 동작 | `PositionCriteria.Open`, `PositionResult.Detail` |
| domain | `*Command` | 동작 | `PositionCommand.Create` |
| domain | `*Snapshot` (Read Model) | — | `PremiumSnapshot` (조회 전용, 단독 data class) |

## DTO 파일 구조

```kotlin
class PositionCriteria private constructor() {
    data class Open(val symbol: String, ...)
}

class PositionResult private constructor() {
    data class Detail(val id: Long, ...) {
        companion object {
            fun from(entity: Position): Detail = ...
        }
    }
    data class Pnl(val positionId: Long, ...)
}
```

## Entity

- prefix/suffix 없음: `Position`, `Ticker`, `Premium`
- `@Enumerated(EnumType.STRING)` 필수
