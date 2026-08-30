---
name: dto-pattern
description: "DTO 작성 스킬. Request/Response·Criteria/Result·Command·Snapshot의 계층별 네이밍과 inner class 패턴, 변환 메서드, MarketPair·시간·범위 계약을 가이드한다. DTO 생성, Request/Response 작성, Criteria/Result 추가, 응답 스키마 결정, DTO 네이밍 확인 시 반드시 이 스킬을 사용할 것."
---

# DTO 패턴

## 계층별 컨테이너

| Layer | 컨테이너 | Inner class | 예시 |
|-------|---------|------------|------|
| interfaces | `*Request`, `*Response` | 동작명 | `PositionRequest.OpenAuto`, `PremiumResponse.Detail` |
| application | `*Criteria`, `*Result` | 동작명 | `PositionCriteria.OpenManual`, `PositionResult.Detail` |
| domain | `*Command` | 동작명 | `PositionCommand.Create` |
| domain | `*Snapshot` (read model) | — | `PremiumSnapshot` (조회 전용 단독 `data class`) |

## 파일 구조

```kotlin
class PositionCriteria private constructor() {
    data class OpenAuto(val pair: MarketPair, val quantity: BigDecimal)
    data class OpenManual(val pair: MarketPair, val entryPremium: BigDecimal)
}

class PositionResult private constructor() {
    data class Detail(val id: Long, val pair: MarketPair) {
        companion object {
            fun from(entity: Position): Detail = Detail(entity.id, entity.pair)
        }
    }
}
```

- 컨테이너는 `private constructor()`로 인스턴스화를 막는다.
- 변환 방향은 한쪽으로만 흐른다: `Request.toCriteria()` → `Criteria.toCommand()` → Entity →
  `Result.from(entity)` → `Response.from(result)`.
- Kotlin 불변 우선 — `val`, `data class`. (예외: JPA Entity는 `data class`가 아니다. `jpa-entity-pattern` 참조)

## 변환 흐름

```text
Request → Criteria → Command → (Entity | Snapshot) → Result → Response
```

여러 저장소를 조합해야 하는 조회는 Domain `*Snapshot`을 반환한다. 조합과 fallback은 infrastructure가
수행하고 application은 hit/miss를 알지 않는다.

## 데이터 계약 (지키지 않으면 계약 위반이다)

- **MarketPair를 명시한다.** premium/position/notification 요청·응답은 가능한 경우 pair를 실어 나른다.
  symbol-only 호환은 BITHUMB/BINANCE default pair에만 적용하고, non-default pair를 symbol-only로
  fallback하지 않는다.
- 요청 pair와 payload/row pair가 다르면 **다른 pair 데이터로 보정하지 않고** miss/error로 처리한다.
- 시간은 timezone이 명확한 **ISO-8601 Instant**로 주고받는다.
- 범위의 끝 시각은 exclusive다 — `[from, to)`. pagination도 같다.
- Controller는 Domain/Infrastructure 타입을 직접 반환하지 않는다. Entity가 Response로 새어 나가면 위반이다.

## Request 검증의 위치

| 위치 | 무엇을 |
|------|-------|
| Request | 존재·형식 (`@NotNull`, `@NotBlank`, 포맷) |
| Domain Entity/Value | 불변식, 상태 전이, 도메인 규칙 |
| Service | Repository 조회가 필요한 검증(존재·중복)과 유스케이스 트랜잭션 |

## 읽을 것

- `.ai/rules/naming.md` — DTO·Entity 네이밍의 정본
- `.ai/rules/http.md` — 요청/응답 데이터 계약과 상태 코드 매핑
