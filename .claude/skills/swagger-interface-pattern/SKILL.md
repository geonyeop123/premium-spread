---
name: swagger-interface-pattern
description: "API 문서 계약 스킬. 이 저장소의 실효 계약인 http/api/{domain}.http + contract test 갱신 의무와, ControllerDocs(문서 어노테이션 분리) 패턴을 도입할 경우의 규약을 가이드한다. endpoint 추가·변경, API 문서 갱신, Controller 작성, Swagger 도입 논의 시 반드시 이 스킬을 사용할 것."
---

# API 문서 계약

## 현재 상태를 먼저 알 것

이 저장소에는 `*ControllerDocs` 인터페이스도, `@Operation`·`@Tag` 같은 Swagger 어노테이션을 쓰는
Kotlin 코드도 **없다.** springdoc 의존만 `apps/api/build.gradle.kts`에 선언돼 있고 OpenAPI 설정
클래스는 없다.

따라서 **이 저장소의 실효 API 계약은 `http/api/{domain}.http` 파일과 contract/integration 테스트**다.
이 스킬은 ControllerDocs 도입을 강제하지 않는다. 도입 여부는 별도 결정이며, 도입 전까지는 아래 §1이
정본이다.

## 1. endpoint를 추가·변경할 때 (지금 지켜야 하는 것)

- `http/api/{domain}.http`를 함께 갱신한다. 현재 파일: `auth`, `members`, `notification`, `premiums`,
  `tickers`, `trackings`. 형식은 `http/README.md`를 따른다.
- contract/integration 테스트를 함께 갱신한다.
- 공개 여부는 **method + path 조합**이다. path만 공개하지 않는다. 공개 목록의 단일 출처는
  `PublicEndpointPolicy`이며, 이것이 Spring Security matcher와 contract test가 함께 보는 목록이다.
- 공개 목록을 바꾸면 `docs/runbooks/auth-security.md`와 management endpoint contract test를 같이 바꾼다.
- 안정된 application error code를 `GlobalExceptionHandler`에서 HTTP status로 매핑한다. Controller가
  상태 코드를 임의로 만들지 않는다.
- Actuator health/Prometheus는 management network 전용이다. application ingress로 노출하지 않는다.

## 2. Controller의 책임

| 위치 | 담당 |
|------|------|
| Controller | Request validation, Criteria 변환, Result→Response/HTTP status 매핑 |
| Facade | 유스케이스 조합과 application error 변환 |

Controller는 Facade **하나만** 주입하고, Domain/Infrastructure 타입을 직접 반환하지 않는다.

## 3. ControllerDocs를 도입한다면 (아직 도입 전)

도입을 결정했을 때의 규약을 미리 못 박아 둔다. 두 어노테이션 묶음이 한 파일에 섞이면 라우팅 변경과
문서 변경이 같은 diff에서 충돌한다.

| 위치 | 포함 | 제외 |
|------|------|------|
| `{Domain}ControllerDocs` (interface) | `@Tag`, `@Operation`, `@ApiResponse`, `@Parameter` | 라우팅 어노테이션 |
| `{Domain}Controller` (class) | `@RestController`, `@RequestMapping`, `@GetMapping` 등, `@Valid` | 문서 어노테이션 |

- Controller는 Docs 인터페이스를 구현하고 메서드에 `override`를 단다.
- 스키마 이름은 앱 안에서 전역 유일해야 한다.
- 도입 시 이 스킬의 §"현재 상태"와 `.ai/rules/http.md`를 같은 MR에서 갱신한다. 코드에 없는 패턴을
  규칙으로 서술해 두면 다음 사람이 문서를 믿고 틀린 구조를 만든다.

## 읽을 것

- `.ai/rules/http.md` — HTTP/API 규칙의 정본
- `http/README.md` — `.http` 샘플 형식
- `docs/runbooks/auth-security.md` — 공개 endpoint 정책
