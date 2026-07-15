# Architecture Rules

## 모듈 경계

```text
apps:api/interfaces ─┐
                    ├→ apps application → domain ← infrastructure adapters
apps:batch/interfaces┘                         ↑
                                 modules foundations / supports
```

- `apps:api`: REST interfaces와 Application Facade만 소유한다.
- `apps:batch`: scheduling interfaces와 Application Job만 소유한다.
- `domain`: Entity/Value/Policy/Service/Port/Snapshot을 소유한다.
- `infrastructure:common`: JPA/JDBC/Redis business adapter와 Flyway를 소유한다.
- `infrastructure:api`: Security/JWT/refresh-session adapter를 소유한다.
- `infrastructure:batch`: 거래소/FX/WebSocket/Redis job/SMTP adapter를 소유한다.
- `modules:*`와 `supports:*`는 재사용 가능한 foundation/auto-configuration만 제공한다.

앱 내부에 `infrastructure`, `cache`, `repository` 기술 구현 패키지를 만들지 않는다. 앱은 infrastructure를
`runtimeOnly`로 소비하며 infrastructure는 어떤 `apps:*`에도 의존하지 않는다.

## 계층 규칙

### API

```text
Controller → Application Facade → Domain Service/Port
```

- Controller는 Request validation, Criteria 변환, Result→Response/HTTP mapping만 수행한다.
- Controller 하나는 해당 유스케이스 Facade 하나만 주입한다.
- Facade는 유스케이스 조합과 안정된 Application error 변환을 담당한다.
- Facade는 infrastructure concrete type, Redis/JPA/JDBC 구현을 참조하지 않는다.

### Batch

```text
Scheduler → Application Job → Domain Port ← Infrastructure Adapter
```

- Scheduler는 trigger와 Job 한 번 호출만 담당한다.
- Job은 timeout/lock/result와 유스케이스 순서를 조합하되 기술 구현을 참조하지 않는다.
- 외부 API/WebSocket/Redis/JDBC/SMTP/Micrometer는 infrastructure가 담당한다.

## Domain 허용 경계

Domain은 JDBC, Redis/Redisson, HTTP/WebSocket, Security/JWT, SMTP, Micrometer, Jackson/Hibernate 구현에
의존하지 않는다. 현재 Entity/transaction/port 계약을 위해 아래 직접 외부 의존만 허용한다.

- `jakarta.persistence:jakarta.persistence-api`
- `org.springframework:spring-context`
- `org.springframework:spring-tx`
- `org.springframework.data:spring-data-commons`

새 framework dependency를 Domain에 추가하기 전에 Architecture Test allowlist 변경 근거를 문서화한다.
JPA Entity는 `data class`가 아니며 영속 identity equality와 protected mutation을 사용한다.

## Port와 Adapter

- 비즈니스가 필요로 하는 capability를 Domain port로 정의한다.
- 하나의 구현뿐이고 대체/경계 가치가 없는 타입을 습관적으로 interface로 만들지 않는다.
- 기술 구현은 `*Adapter`, Spring Data repository는 `SpringData*Repository`처럼 역할을 드러낸다.
- Application에서 infrastructure 타입이 필요해지면 import를 허용하지 말고 capability가 Domain port인지 먼저 판단한다.

## MarketPair와 Read Model

- Premium/Position/Notification identity는 `MarketPair`를 보존한다.
- non-default pair를 symbol-only cache/DB row로 fallback하지 않는다.
- 여러 저장소를 조합한 조회는 Domain `*Snapshot`을 반환하고 조합/fallback은 infrastructure가 수행한다.
- 요청 pair와 payload/row pair가 다르면 miss/error로 처리하고 다른 pair 데이터로 보정하지 않는다.

## Persistence와 Cache

- Flyway와 공통 JPA/JDBC adapter owner는 `infrastructure:common`이다.
- API만 Flyway를 실행하고 Batch는 비활성이다.
- migration은 append-only이며 immutable V12를 수정하지 않는다.
- DB와 cache를 함께 쓸 때 durable DB가 정본이다. DB-first 또는 after-commit만 사용한다.
- cache→DB fallback은 infrastructure 안에서 숨기고 Application은 hit/miss를 알지 않는다.
- range는 `[from,to)`다. 손상 row가 하나라도 있으면 부분 cache 결과를 반환하지 않는다.
- Redis key/TTL/payload 변경은 `modules:redis`와 `docs/runbooks/redis-contract.md`를 동시에 변경한다.

## 변경 체크리스트

- [ ] 앱 main에 기술 adapter/import를 추가하지 않았다.
- [ ] Controller→Facade, Scheduler→Job 단일 entry 경계를 지켰다.
- [ ] Domain에 새 framework 구현 dependency를 추가하지 않았다.
- [ ] MarketPair identity와 시간/범위 계약을 보존했다.
- [ ] DB/cache ordering과 transaction 경계를 테스트했다.
- [ ] root `architectureTest`와 관련 integration test가 통과했다.
