# Redis Fixture Baseline

Redis key/TTL/payload의 실행 가능한 기준 fixture는 다음 테스트에 있다.

| 영역 | fixture/test 경로 | 검증 내용 |
|---|---|---|
| ticker latest/time-series | `apps/batch/src/test/kotlin/io/premiumspread/cache/TickerCacheServiceTest.kt` | Hash payload, seconds ZSet, TTL, aggregation parse |
| ticker score regression | `apps/batch/src/test/kotlin/io/premiumspread/cache/TickerCacheServiceScoreTest.kt` | 명시 score, flat price distinct entry |
| premium latest/time-series | `apps/batch/src/test/kotlin/io/premiumspread/cache/PremiumCacheServiceTest.kt` | premium payload, fx_rate, history |
| FX latest | `apps/batch/src/test/kotlin/io/premiumspread/cache/FxCacheServiceTest.kt` | currency pair payload와 TTL |
| notification cooldown | `apps/batch/src/test/kotlin/io/premiumspread/cache/NotificationCooldownStoreTest.kt` | 현재 cooldown key/TTL; Phase 7에서 제거 예정 |
| Redis 공통 설정 | `modules/redis/src/test` | serializer와 connection fixture |

기준 key와 payload 표는 `baseline.md` 6절에 고정한다. Testcontainers 실행에는 현재 Docker Engine과의
호환을 위해 임시 `JAVA_TOOL_OPTIONS=-Dapi.version=1.44`가 필요하며 Phase 2에서 test JVM 단일 설정으로
대체한다.
