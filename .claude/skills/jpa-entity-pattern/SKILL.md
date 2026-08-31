---
name: jpa-entity-pattern
description: "JPA 엔티티 작성 스킬. Entity 선언 규칙(data class 금지·identity equality·protected mutation), Enum 매핑, Aggregate 참조, MarketPair 보존, 캐시-DB 정합과 Flyway 정합을 가이드한다. 새 엔티티 생성, 컬럼·연관관계 추가, Entity 수정, 스키마 변경 시 반드시 이 스킬을 사용할 것."
---

# JPA Entity 패턴

## 선언 규칙

```kotlin
@Entity
@Table(name = "position")
class Position protected constructor(
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: PositionStatus,
) : BaseEntity() {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    fun close(closedAt: Instant) { /* 상태 전이는 의미 있는 메서드로 */ }

    companion object {
        fun open(command: PositionCommand.Create): Position = Position(PositionStatus.OPEN)
    }
}
```

- **JPA Entity는 `data class`가 아니다.** `equals`/`hashCode`/`copy` 자동 생성이 영속 identity와
  충돌한다. 동등성은 영속 identity(non-null ID)를 기준으로 구현한다.
- 상태 변경은 setter가 아니라 **의미 있는 비즈니스 메서드**로 한다. 생성자는 `protected`, 생성은 정적
  팩토리로 한다.
- `@Enumerated(EnumType.STRING)` 필수 — ORDINAL 금지.
- `@Column`에 nullable·length를 명시한다.
- Entity 이름에 prefix/suffix를 붙이지 않는다: `Position`(O), `PositionEntity`(X).
- 도메인 계산은 순수 함수로 두고 부작용을 최소화한다.

## Aggregate와 참조

- 다른 Aggregate는 **FK 식별자 값**만 보유한다. 같은 Aggregate 내부 연관만 LAZY 연관을 허용한다.
- `fetch = FetchType.LAZY`를 명시한다. 목록 조회에서 연관을 순회하면 N+1을 의심한다.

## MarketPair 보존

- premium/position/notification의 identity는 `MarketPair`를 포함한다. Entity·쿼리·캐시 키 어디서도
  pair를 떨어뜨리지 않는다.
- non-default pair를 symbol-only row로 fallback하지 않는다. 요청 pair와 row pair가 다르면 miss/error다.

## 시간과 범위

- 저장·조회 모두 UTC 기준 `Instant`를 쓴다. minute/hour 버킷은 UTC, day 버킷과 cron은 `aggregation.zone`이다.
- 모든 범위는 `[from, to)`다.
- 현재 시각은 주입한 `Clock`에서 얻는다. `Instant.now()`를 직접 부르지 않는다.

## 저장소와 캐시

- durable DB가 정본이다. DB 성공 후 캐시를 갱신하는 **DB-first 또는 after-commit**만 쓴다.
- cache→DB fallback은 infrastructure adapter 안에 숨긴다. application은 hit/miss를 알지 않는다.
- 손상된 row가 하나라도 있으면 **부분 캐시 결과를 반환하지 않는다.**
- Redis key/TTL/payload를 바꾸면 `modules:redis`와 `docs/runbooks/redis-contract.md`를 같이 바꾼다.

## 스키마 변경

- 위치: `infrastructure/common/src/main/resources/db/migration/`
- 다음 번호는 `ls infrastructure/common/src/main/resources/db/migration/ | sort -V | tail -1` 결과 +1이다.
  문서나 스킬에 번호를 상수로 박지 않는다.
- migration은 **append-only**다. 이미 배포된 migration(예: V12)을 수정하지 않는다.
- Flyway는 API만 실행하고 Batch는 비활성이다.
- Entity 필드 ↔ 컬럼 타입/길이/nullable 정합을 반드시 확인한다. 불일치는 런타임에야 드러난다.

## 읽을 것

- `.ai/rules/naming.md`, `.ai/rules/architecture.md` — Entity·persistence 계약의 정본
- `docs/runbooks/redis-contract.md` — Redis key/TTL/payload 계약
