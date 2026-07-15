# Infrastructure Boundary Refactoring Baseline

> 확인일: 2026-07-14 KST
> 기준 커밋: `a0a59ee` (`origin/dev`)
> 작업 브랜치: `refactor/infrastructure-boundary`
> Worktree: `/mnt/c/Users/yeop/IdeaProjects/premium-spread-infrastructure-boundary`

## 1. 실행 환경

| 항목 | 값 |
|---|---|
| JDK | OpenJDK 21.0.11 |
| Gradle Wrapper | 8.14.3 |
| Kotlin Gradle Plugin | 2.0.20 (`gradlew --version` 내장 Kotlin은 2.0.21) |
| Node.js | 20.20.0 (`~/.nvm/versions/node/v20.20.0`) |
| npm | 10.8.2 |
| Docker Engine | 29.3.1, Linux/WSL2 |
| Testcontainers | 1.20.6 |

최초 계획 문서 SHA-256은
`e64a931603def7e4c2ab184b6869ff605c8dafda1b69a51f01731f0fd38709f0`이고, 스펙 리뷰와 사용자 승인을
반영한 실행본 SHA-256은 `fdc53e7dc50c015c02a651552809d56fa9855638c0fc4594c714903967eee772`이다.

## 2. 기본 테스트 기준선

실행 명령:

```bash
bash gradlew test --rerun-tasks --offline --no-daemon
```

결과: **성공**, 6분 23초, 총 443개, failure/error/skipped 0.

| 모듈 | 테스트 | 실패 | 오류 | skip |
|---|---:|---:|---:|---:|
| `apps/api` | 255 | 0 | 0 | 0 |
| `apps/batch` | 172 | 0 | 0 | 0 |
| `modules/redis` | 7 | 0 | 0 | 0 |
| `supports/email` | 2 | 0 | 0 | 0 |
| `supports/monitoring` | 7 | 0 | 0 | 0 |

컴파일 경고 기준선:

- KAPT가 Kotlin 2.0을 지원하지 않아 1.9로 fallback한다.
- `Member`, `Quote`, `Symbol`, `Ticker`의 private/non-public data class constructor가 생성 `copy()`로 노출된다.
- Batch 테스트 8곳에서 nullable Java type을 non-null Kotlin type으로 사용하는 mismatch 경고가 발생한다.
- Mockito inline mock maker의 동적 agent attach가 향후 JDK에서 차단될 예정이라는 경고가 있다.

## 3. 통합 테스트 기준선

### 3.1 Docker API 호환성

Docker Engine 29.3.1은 최소 API 1.40을 요구한다. Testcontainers 1.20.6에 포함된 docker-java의
기본 `api.version`은 1.32이므로 일반 실행은 모든 context에서 아래 오류로 실패했다.

```text
Could not find a valid Docker environment
client version 1.32 is too old. Minimum supported API version is 1.40
```

기준선 재현 시 다음 임시 환경 보정이 필요했다.

```bash
JAVA_TOOL_OPTIONS='-Dapi.version=1.44' \
DOCKER_HOST=unix:///var/run/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
bash gradlew <integration task> --offline --no-daemon
```

### 3.2 API

`JAVA_TOOL_OPTIONS=-Dapi.version=1.44` 보정 후 `:apps:api:integrationTest --rerun-tasks`는
4분 42초에 성공했다. 총 88개 중 failure/error 0, skip 1이다.

### 3.3 Batch

보정 전 전체 64개는 Docker environment 초기화 실패로 제품 로직까지 도달하지 못했다.
보정 후 전체를 `--rerun-tasks`로 재실행한 결과는 **64개 중 25개 실패, 39개 성공**이다.

실패는 두 원인으로 분류된다.

1. `TickerCacheServiceScoreTest` 2건: 시간/retention 회귀
2. Repository 16건과 하위 aggregation E2E 7건: `batch-schema.sql`에 현재 Repository SQL이 요구하는
   ticker `currency`, premium `fx_rate` 컬럼이 없어 발생한 test fixture schema drift

- 기대: 지정 score로 저장한 ZSet entry 1개/5개 조회
- 실제: entry 0개
- 원인: fixture score는 `2026-05-12`, 실행일은 `2026-07-14`인데
  `TickerCacheService.saveToSecondsWithScore()`가 직접 `Instant.now()`를 호출해 5분 retention보다
  오래된 것으로 판단하고 방금 저장한 entry를 즉시 삭제한다.
- 영향: Clock 주입 없이 과거/재처리 시각을 저장하면 동일하게 삭제될 수 있으며 O-01 범위의 실제 결함이다.

두 분류는 사용자 승인된 `baseline-known-failures`다. schema fixture drift 23건은 Phase 2에서,
retention 2건은 Phase 3에서 해결하며 Phase 3 종료 시 Batch integration 전체를 green으로 만든다.

## 4. Web 기준선

Node 18.20.8은 Next 16.1.6의 최소 Node 20.9 요구와 맞지 않는다. Node 20.20.0으로 재실행했다.

| 명령 | 결과 |
|---|---|
| `npm --prefix apps/web ci` | 성공, 711 packages |
| `npm --prefix apps/web run lint` | 실패 1건 |
| `npm --prefix apps/web run build` | 성공 |
| npm audit 요약 | 17건: low 1, moderate 6, high 10 |

lint 실패는 `apps/web/src/components/PositionList.tsx:77`의 effect 내부 동기 `fetchPnls()` 호출로,
`react-hooks/set-state-in-effect` 규칙을 위반한다.

## 5. REST contract snapshot

| Method | Path |
|---|---|
| POST | `/api/v1/members/register` |
| POST | `/api/v1/members/login` (security filter) |
| GET | `/api/v1/members/me` |
| POST | `/api/v1/auth/refresh` |
| POST | `/api/v1/auth/logout` |
| POST | `/api/v1/tickers` |
| POST | `/api/v1/premiums/calculate/{symbol}` |
| GET | `/api/v1/premiums/current/{symbol}` |
| GET | `/api/v1/premiums/history/{symbol}` |
| GET | `/api/v1/premiums/aggregation/{symbol}` |
| POST | `/api/v1/positions/auto` |
| POST | `/api/v1/positions/manual` |
| GET | `/api/v1/positions`, `/summary`, `/history`, `/{id}`, `/{id}/pnl` |
| POST | `/api/v1/positions/{id}/close` |
| POST/GET/PATCH/DELETE | `/api/v1/notifications/subscriptions[/{id}]` |

실제 응답/인가 contract는 API integration 88개가 기준선이며, refresh cookie-only 공개 여부는 아직
테스트되지 않고 현재 Security matcher에도 누락되어 있다.

재현 artifact `rest-contract.md`에는 route/status/body type snapshot이 있으며 SHA-256은
`76a10c24e90106a873c2b6bbf75a5e7147c1fa48555378948c8b958f1da21abd`이다.

## 6. Redis key/TTL/payload 기준선

| Key | Type | TTL | 주요 payload |
|---|---|---|---|
| `ticker:{exchange}:{symbol}` | Hash | 5초 | exchange, symbol, currency, price, volume, timestamp |
| `fx:{base}:{quote}` | Hash | 31분 | base, quote, rate, timestamp |
| `premium:{symbol}` | Hash | 5초 | symbol, rate, korea_price, foreign_price, foreign_price_krw, fx_rate, observed_at |
| `premium:{symbol}:history` | ZSet | 1시간 | `rate:koreaPrice:foreignPrice` |
| `ticker:seconds:{exchange}:{symbol}` | ZSet | 5분 | `{epochMs}:{price}` |
| `premium:seconds:{symbol}` | ZSet | 5분 | `rate:koreaPrice:foreignPrice:fxRate` |
| `summary:{interval}:{symbol}` | Hash | 10초~5분 | interval aggregation |
| `position:open:{exists,count}` | String | 30초 | global open state/count, consumer 부재 |
| `batch:last_run:{job}` | String | 5분 | epoch millis |

현재 key는 premium/summary에 MarketPair가 없어 같은 symbol의 거래소 pair 간 충돌 가능성이 있다.
실행 가능한 fixture/test 경로는 `redis-fixtures.md`에 기록했으며 SHA-256은
`f60f0050278f55398eab6234256c51c50f1d7fe55d2ed2f5103c0d2c2e3f14fb`이다.

## 7. Flyway/V12 및 timestamp audit

로컬 persistent MySQL volume을 read-only query한 결과:

| 항목 | 결과 |
|---|---|
| 최고 Flyway version | 12 |
| V12 | APPLIED, checksum `-1352556376` |
| position row | 0 |
| MySQL global/session/system timezone | SYSTEM / SYSTEM / UTC |
| exchange_rate row | 15 |
| premium_minute row | 16,989 |
| ticker_minute row | 35,427 |

timestamp sample range:

- `exchange_rate.observed_at`: `2026-02-25 00:00:01` ~ `2026-05-21 00:00:01`
- `premium_minute.minute_at`: `2026-02-25 02:24:00` ~ `2026-05-21 00:21:00`
- `ticker_minute.minute_at`: `2026-02-25 02:24:00` ~ `2026-05-21 00:21:00`

DB server가 UTC인 사실만 확인되며, 과거 애플리케이션이 `Asia/Seoul`, system default, JDBC
`serverTimezone=Asia/Seoul`을 혼용했기 때문에 위 DATETIME 값의 실제 의미를 UTC/KST 중 하나로
단정할 외부 기준 이벤트가 없다. 기존 데이터 timezone audit는 **판별 불가**다.

운영/스테이징 환경은 존재하지 않음을 사용자가 확인했으므로 `NOT_DEPLOYED`로 분류한다. 로컬은
`APPLIED`다. 의미가 불명확한 기존 로컬 DATETIME은 변환하지 않고 volume을 보존하며, UTC 정책 적용 후
새 non-production volume/fixture를 재생성한다.

동일 조회를 재현하는 read-only SQL은 `v12-audit.sql`이며 SHA-256은
`9e9e9bc14e07ea57905bfc92d6a221ec3f6b226005c8237673cd18ab95ff5de7`이다. 실행 예시는
`docker exec -i <mysql-container> mysql -u<readonly-user> -p <database> < v12-audit.sql`이고,
volume/container 이름은 `docker compose -f docker/infra-compose.yml ps`와 `docker volume ls`로 확인한다.

## 8. 품질 도구/coverage 기준선

Gradle 신규 다운로드 금지 규칙을 지켜 모두 `--offline`으로 실행했다.

- JaCoCo report: `org.jacoco.ant:0.8.13`이 cache에 없어 report 생성 실패
- ktlint: `com.pinterest.ktlint` 1.0.1 engine/reporter artifact가 cache에 없어 실패
- detekt 및 OWASP Dependency-Check: plugin/artifact가 cache에 없어 실행 불가
- ArchUnit 1.3.0과 ktlint Gradle plugin 12.1.2 자체는 cache에 존재

따라서 module별 coverage 수치와 Phase 9의 lint/security gate는 현재 실행 환경에서 증명할 수 없다.
해당 검증은 artifact 다운로드가 허용된 격리 CI runner의 SHA 귀속 결과를 완료 증거로 사용한다.

### baseline-known-failures

| ID | 실패 테스트/명령 | 현재 증상 | owner | 해결 Phase | 완료 gate | 승인일 |
|---|---|---|---|---:|---|---|
| BKF-01 | `TickerCacheServiceScoreTest` | 고정 과거 score 2건이 직접 `Instant.now()` retention으로 삭제 | Phase 3 Domain/시간 담당 | 3 | Batch integration 전체 green | 2026-07-14 |
| BKF-05 | Batch integration Repository/E2E | `batch-schema.sql`의 `currency`/`fx_rate` 누락으로 23건 실패 | Phase 2 빌드/테스트 기반 담당 | 2 | retention 2건 외 Batch integration green | 2026-07-14 |
| BKF-02 | `npm run lint` | `PositionList.tsx:77` set-state-in-effect 1건 | Phase 5 Web 계약 담당 | 5 | Web lint/build green | 2026-07-14 |
| BKF-03 | JaCoCo/ktlint/detekt/OWASP | local offline artifact 부재 | Phase 9 CI/품질 담당 | 9 | 후보 SHA CI quality jobs green | 2026-07-14 |
| BKF-04 | `npm audit` | low 1/moderate 6/high 10 | Phase 9 CI/품질 담당 | 9 | `npm audit --audit-level=high` green 또는 owner/근거/만료 suppression | 2026-07-14 |

## 9. Phase 0 판정

**APPROVED_KNOWN_FAILURES / READY**.

- 운영/스테이징: `NOT_DEPLOYED`(사용자 확인)
- 로컬 V12: `APPLIED`, checksum 변경 금지
- 기존 로컬 timestamp: 자동 변환 금지, 기존 volume 보존 후 UTC 기반 새 volume/fixture 사용
- Batch schema fixture drift 23건: Phase 2 해결
- Batch retention 2건: Phase 3 해결
- Web lint 1건: Phase 5 해결
- offline quality tool 부재 및 npm 취약점: Phase 9의 격리 CI gate에서 해결·판정

default test 443개는 모두 green이며, 사용자가 2026-07-14에 위 기준선 예외와 계획 보정안을 승인했다.
따라서 Phase 1 구현, commit, push를 진행할 수 있다.
