# Testing Rules

## 테스트 실행

```bash
./gradlew test                           # Unit tests
./gradlew :apps:api:integrationTest      # Integration (Docker 필요)
```

## 테스트 규칙

- **도구**: AssertJ 필수!!
- **Unit Test**: Mock Repository 사용
- **Integration Test**: `@Tag("integration")`, TestConfig Import

```kotlin
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, TestConfig::class)
class RepositoryTest { ... }
```
