# HTTP/API Rules

- Controller는 Request validation, Criteria 변환, Result→Response/HTTP status mapping만 수행한다.
- Controller마다 Application Facade 하나만 주입하며 Domain/Infrastructure 타입을 직접 반환하지 않는다.
- 안정된 Application error code를 `GlobalExceptionHandler`에서 HTTP status로 매핑한다.
- endpoint 추가/변경 시 `http/api/{domain}.http`와 contract/integration test를 함께 갱신한다.
- 자세한 HTTP sample 형식은 `http/README.md`를 따른다.

## 인증과 공개 endpoint

- 공개 여부는 method+path 조합이다. path만 공개하지 않는다.
- `PublicEndpointPolicy`가 Spring Security matcher와 contract test의 유일한 목록이다.
- Premium/Ticker 조회는 GET만 공개하며 mutation은 인증이 필요하다.
- refresh/logout은 cookie 기반 공개 POST이지만 Origin/Sec-Fetch-Site 검증을 통과해야 한다.
- Actuator health/Prometheus는 management network 전용이다. nginx application ingress로 노출하지 않는다.
- 공개 목록 변경 시 `docs/runbooks/auth-security.md`와 management endpoint contract test를 함께 갱신한다.

## 데이터 계약

- premium/position/notification 요청과 응답은 가능한 경우 MarketPair를 명시한다.
- 기존 symbol-only 호환은 BITHUMB/BINANCE default pair에만 적용한다.
- 날짜/시간은 timezone이 명확한 ISO-8601 Instant로 전달한다.
- pagination/range의 끝 시각은 exclusive인 `[from,to)`다.
