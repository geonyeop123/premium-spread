# Auth token, cookie, public endpoint 계약

## Token lifecycle

1. `POST /api/v1/members/login`이 Access Token을 body로, Refresh Token을 cookie로 발급한다.
2. Access Token은 `Authorization: Bearer {token}`으로 전송한다.
3. `POST /api/v1/auth/refresh`는 cookie를 검증하고 Access/Refresh를 모두 회전한다.
4. `POST /api/v1/auth/logout`은 확인 가능한 refresh session을 revoke하고 만료 cookie를 반환한다.

JWT는 HMAC 서명 외에 issuer, audience, `tokenType`, `jti`, expiry를 검증한다. Refresh에는
`familyId`, `generation`도 필요하다. Access와 Refresh TTL은 `jwt.*` typed property로 설정하며 Refresh TTL은
Access TTL보다 길어야 한다.

Redis에는 Refresh Token 원문을 저장하지 않는다. HMAC-SHA-256 hash, jti, member ID, expiry, family,
generation만 `auth:refresh:{memberId}`에 저장한다. 로그인은 회원의 active session을 교체하고, refresh는 Lua
CAS와 Redis `TIME`으로 회전한다.

- 동시 refresh의 winner만 회전하고 loser는 401이다.
- grace가 지난 old token 재사용은 해당 family를 revoke한다.
- 이전 login family 요청은 현재 family를 revoke하지 않는다.
- logout은 현재/직전 proof에 일치하는 family만 revoke한다.
- Access Token blacklist는 없다. logout 뒤에도 이미 발급된 Access Token은 expiry까지 유효하다.

## Refresh cookie

| 속성 | 계약 |
|---|---|
| name | 기본 `refresh_token` |
| path | 기본 `/api/v1/auth` |
| HttpOnly | 항상 `true` |
| Secure | local/test `false`, prd `true` |
| SameSite | 기본 `Strict`; `None`이면 Secure 필수 |
| Domain | 명시 설정이 없으면 host-only |

refresh/logout은 cookie endpoint이므로 CSRF token 대신 명시적 Origin 정책을 적용한다. 허용 목록 밖의
`Origin` 또는 Origin 없이 `Sec-Fetch-Site: cross-site`인 요청은 403이다. credential CORS에 wildcard
origin/header를 사용하지 않는다.

브라우저는 Access Token을 memory/sessionStorage에만 보관하고 Refresh cookie에는 접근하지 않는다.

## Public endpoint SSOT

`infrastructure:api`의 `PublicEndpointPolicy`가 Security matcher와 contract test가 공유하는 유일한 목록이다.
목록은 method까지 포함한다.

| Method | Pattern | 목적 |
|---|---|---|
| POST | `/api/v1/members/register` | 회원 가입 |
| POST | `/api/v1/members/login` | 로그인 |
| POST | `/api/v1/auth/refresh` | cookie refresh |
| POST | `/api/v1/auth/logout` | cookie logout |
| GET | `/api/v1/premiums/**` | 공개 프리미엄 조회 |
| GET | `/api/v1/tickers/**` | 공개 시세 조회 |
| GET | `/actuator/health/liveness` | 내부 management probe |
| GET | `/actuator/health/readiness` | 내부 management probe |
| GET | `/actuator/prometheus` | 내부 Prometheus scrape |
| GET | `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**` | local/test API 문서 |

그 밖의 요청과 같은 path의 다른 method는 인증이 필요하다. Swagger API docs는 prd profile에서 비활성이다.
Actuator 공개 matcher는 인증 면제만 의미한다. endpoint는 별도 management port/내부 Docker network/host
loopback으로 제한하고 nginx application ingress에는 노출하지 않는다.

## Production startup 정책

- JWT secret과 refresh HMAC key는 각각 32 bytes 이상이어야 하며 local 기본값을 prd에서 금지한다.
- issuer/audience/TTL/clock skew는 명시해야 한다.
- refresh cookie는 `secure=true`여야 한다.
- CORS origin은 한 개 이상 명시하며 wildcard를 금지한다.
- 비밀값은 GitHub Environment/secret manager에서 주입하고 repository/compose/server image에 저장하지 않는다.

## 변경 검증

- public endpoint는 policy와 contract test를 함께 바꾼다.
- login/refresh rotation/concurrent loser/reuse/logout/expired token을 E2E로 검증한다.
- logout 이후 refresh 거부와 기존 Access Token의 expiry 전 유효성을 모두 검증한다.
- cookie attribute, invalid Origin/cross-site, CORS preflight를 검증한다.
- management endpoint가 9080에서 열리고 8080에서 404인지 실제 HTTP로 검증한다.
