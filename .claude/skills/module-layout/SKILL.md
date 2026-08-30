---
name: module-layout
description: "멀티모듈 배치 스킬. apps/domain/infrastructure/modules/supports 구조, 의존 방향, 포트-어댑터 명명, 새 도메인 추가 시 파일 배치를 가이드한다. 새 도메인 추가, 새 모듈 추가, 파일을 어디에 둘지 결정, 의존성 설정, 패키지 이동 시 반드시 이 스킬을 사용할 것."
---

# 멀티모듈 배치

## 모듈 구조

```text
premium-spread/
├── apps/
│   ├── api/          # REST interfaces + Application Facade (8080, management 9080)
│   │   └── src/main/kotlin/io/premiumspread/
│   │       ├── interfaces/api/{domain}/   # Controller, Request, Response
│   │       ├── application/{domain}/      # Facade, Criteria, Result
│   │       └── config/                    # 앱 실행 설정
│   ├── batch/        # scheduling interfaces + Application Job (8081, management 9081)
│   │   └── src/main/kotlin/io/premiumspread/
│   │       ├── interfaces/scheduling/     # @Scheduled thin trigger
│   │       └── application/job/{domain}/  # Job (유스케이스 조합)
│   └── web/          # Next.js (Gradle 모듈 아님)
├── domain/                       # Entity, Value, Policy, Service, Port, Snapshot
├── infrastructure/
│   ├── common/       # JPA/JDBC/Redis business adapter + Flyway migration
│   ├── api/          # Security/JWT/refresh-session adapter
│   └── batch/        # 거래소/FX/WebSocket/Redis job/SMTP adapter
├── modules/{jpa,redis}           # 재사용 foundation/auto-configuration
├── supports/{logging,monitoring,email}
└── architecture-tests/           # 모듈 의존·import·바이트코드 경계 단정
```

Gradle 모듈 목록의 정본은 `settings.gradle.kts`다.

## 레이어와 모듈은 다른 개념이다

```text
apps:api/interfaces  ─┐
                      ├→ application → domain ← infrastructure adapters
apps:batch/interfaces ┘                      ↑
                              modules foundations / supports
```

앱은 infrastructure를 **`runtimeOnly`** 로 소비한다. 그래서 Controller·Facade·Job 코드는 adapter 구현
타입을 compile-time에 참조할 수 없다 — 의존 역전을 Gradle이 강제하는 방식이다. infrastructure는 어떤
`apps:*`에도 의존하지 않는다.

## 절대 하지 않는 것

- **앱 안에 `infrastructure`·`cache`·`repository`·`client` 패키지를 만들지 않는다.** 기술 구현은
  `infrastructure:{common,api,batch}`가 소유한다.
- Flyway migration을 `apps/`에 두지 않는다. `infrastructure/common/src/main/resources/db/migration/`이다.
- Domain에 새 framework 의존을 추가하지 않는다. 현재 허용은 `jakarta.persistence-api`,
  `spring-context`, `spring-tx`, `spring-data-commons` 넷뿐이며, 추가하려면 architecture test allowlist
  변경 근거를 문서로 남긴다.
- Controller에 Facade를 둘 이상 주입하지 않는다. Scheduler가 Job을 둘 이상 호출하지 않는다.
- 하나뿐인 구현을 습관적으로 interface로 감싸지 않는다.

## 명명

| 역할 | 이름 | 위치 |
|------|------|------|
| Domain port | `{Domain}Repository`, `{X}ReadPort`, `{X}WritePort` | `domain/{domain}/` |
| JPA adapter | `{Domain}JpaAdapter` 같은 `*Adapter` | `infrastructure/common/.../{domain}/` |
| Spring Data interface | `SpringData{Domain}Repository` | adapter와 같은 패키지 |
| Redis adapter | `Redis{X}Adapter` | `infrastructure/common` 또는 `infrastructure/batch` |
| application 진입점 | `{Domain}Facade` | `apps/api/.../application/{domain}/` |
| 배치 유스케이스 | `{목적}Job` | `apps/batch/.../application/job/{domain}/` |

구현 이름을 `{X}RepositoryImpl`로 고정하지 않는다. **기술과 역할이 이름에서 드러나야 한다.**

## 새 도메인 추가 파일 목록

1. `domain/src/main/kotlin/io/premiumspread/domain/{domain}/`
   — Entity, Value, `{Domain}Repository`(port), `{Domain}Service`, `{Domain}Command`, 필요 시 `{X}Snapshot`
2. `infrastructure/common/src/main/kotlin/.../{domain}/`
   — `*Adapter`, `SpringData{Domain}Repository`. 캐시를 쓰면 cache→DB fallback을 **adapter 안에** 숨긴다
3. `apps/api/.../application/{domain}/` — `{Domain}Facade`, `{Domain}Criteria`, `{Domain}Result`
4. `apps/api/.../interfaces/api/{domain}/` — `{Domain}Controller`, `{Domain}Request`, `{Domain}Response`
5. 스키마가 바뀌면 `infrastructure/common/src/main/resources/db/migration/V{다음}__{설명}.sql`
   — 다음 번호는 `ls infrastructure/common/src/main/resources/db/migration/ | sort -V | tail -1`로 확인해
   +1 한다. append-only이며 이미 적용된 migration을 수정하지 않는다
6. `http/api/{domain}.http` 갱신 + contract/integration 테스트
7. 테스트: Domain 단위 → adapter integration → Facade/Controller → contract/E2E → `architectureTest`

배치 유스케이스면 3·4 대신 `apps/batch/.../application/job/{domain}/`(Job)과
`interfaces/scheduling/`(thin trigger), 외부 연동은 `infrastructure/batch/`에 둔다.

## 읽을 것

- `.ai/rules/architecture.md` — 모듈 경계·계층·포트/어댑터의 정본
- `.ai/rules/batch.md` — 배치 구조와 Job 실행 계약
- `.ai/architecture/ARCHITECTURE_DESIGN.md` — 현재 구조와 데이터 흐름
