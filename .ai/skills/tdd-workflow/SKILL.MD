| **name**     | **description**                                                                                                                                                             |
|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| tdd-workflow | Use this skill when writing new features, fixing bugs, or refactoring code. Enforces test-driven development with 80%+ coverage including unit, integration, and E2E tests. |

# Test-Driven Development Workflow

This skill ensures all code development follows TDD principles with comprehensive test coverage.

---

## When to Activate

다음 작업 시 반드시 본 워크플로우를 적용합니다.

- 신규 기능 개발
- 버그 수정
- 도메인 리팩토링
- API 엔드포인트 추가
- 비즈니스 규칙 변경

---

## Core Principles

### 1. Tests First (Red → Green → Refactor)

- 구현 코드 작성 **이전**에 테스트를 먼저 작성합니다.
- 테스트 실패(Red)를 확인한 뒤 구현합니다.
- 테스트 통과(Green) 후 리팩토링합니다.

### 2. Coverage Requirements

- 전체 테스트 커버리지 **80% 이상**
    - Unit Test
    - Integration Test
- 모든 경계 조건과 예외 시나리오를 테스트합니다.

### 3. Domain-Centric Testing

- 로직은 Controller가 아닌 **Domain / Application Layer**에 위치합니다.
- 테스트는 HTTP가 아닌 **도메인 행위**를 기준으로 작성합니다.

---

## Test Types

### Unit Tests

- 도메인 엔티티
- 값 객체(Value Object)
- 도메인 서비스
- 정책 / 계산 로직

### Integration Tests

- Application Service
- Repository (JPA)
- Spring Context 연동
- 트랜잭션 검증

---

## TDD Workflow Steps

## Step 1: Define User / Domain Scenario

Given 환율 기준 통화가 존재하고

When 특정 통화로 환율을 조회하면

Then 기준 통화와 대상 통화가 올바르게 반환된다

## Step2: Write Test (Fail First)

```

import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PremiumTest {
    @Test
    fun `한국 외국 환율 티커로 프리미엄을 계산한다`() {
			// Test implementation
    }

    @Test
    fun `심볼이 다르면 예외를 던진다`() {
			// Test edge case
    }

    @Test
    fun `redis가 동작하지 않을 때 fallback 코드가 동작한다`() {
	    // Test fallback behavior
    }

    @Test
    fun `들어온 일자 순으로 프리미엄이 정렬된다`() {
	    // Test sorting logic
    }
}
```

> ❗ assertEquals 대신 AssertJ fluent assertion 사용을 표준으로 합니다.
>

---

### Step 3: Run Tests (Red)

```bash
./gradlew test

```

- 반드시 실패 상태를 확인합니다.

---

### Step 4: Implement Minimal Code (Green)

```kotlin
data class ExchangeQuote(
    val base: Currency,
    val currency: Currency
)
```

---

### Step 5: Run Tests Again

```bash
./gradlew test
```

---

### Step 6: Refactor

- 중복 제거
- 네이밍 개선
- 책임 분리
- 도메인 불변성 강화

---

## Unit Test Pattern

```kotlin
class ExampleModelTest {
    @DisplayName("예시 모델을 생성할 때, ")
    @Nested
    inner class Create {
        @DisplayName("제목과 설명이 모두 주어지면, 정상적으로 생성된다.")
        @Test
        fun createsExampleModel_whenNameAndDescriptionAreProvided() {
            // arrange
            val name = "제목"
            val description = "설명"

            // act
            val exampleModel = ExampleModel(name = name, description = description)

            // assert
            assertAll(
                { assertThat(exampleModel.id).isNotNull() },
                { assertThat(exampleModel.name).isEqualTo(name) },
                { assertThat(exampleModel.description).isEqualTo(description) },
            )
        }

        @DisplayName("제목이 빈칸으로만 이루어져 있으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        fun throwsBadRequestException_whenTitleIsBlank() {
            // arrange
            val name = "   "

            // act
            val result = assertThrows<CoreException> {
                ExampleModel(name = name, description = "설명")
            }

            // assert
            assertThat(result.errorType).isEqualTo(ErrorType.BAD_REQUEST)
        }

        @DisplayName("설명이 비어있으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        fun throwsBadRequestException_whenDescriptionIsEmpty() {
            // arrange
            val description = ""

            // act
            val result = assertThrows<CoreException> {
                ExampleModel(name = "제목", description = description)
            }

            // assert
            assertThat(result.errorType).isEqualTo(ErrorType.BAD_REQUEST)
        }
    }
}

```

## Integration Test Pattern

```kotlin
@SpringBootTest
class ExampleServiceIntegrationTest @Autowired constructor(
    private val exampleService: ExampleService,
    private val exampleJpaRepository: ExampleJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("예시를 조회할 때,")
    @Nested
    inner class Get {
        @DisplayName("존재하는 예시 ID를 주면, 해당 예시 정보를 반환한다.")
        @Test
        fun returnsExampleInfo_whenValidIdIsProvided() {
            // arrange
            val exampleModel =
                exampleJpaRepository.save(ExampleModel(name = "예시 제목", description = "예시 설명"))

            // act
            val result = exampleService.getExample(exampleModel.id)

            // assert
            assertAll(
                { assertThat(result).isNotNull() },
                { assertThat(result.id).isEqualTo(exampleModel.id) },
                { assertThat(result.name).isEqualTo(exampleModel.name) },
                { assertThat(result.description).isEqualTo(exampleModel.description) },
            )
        }

        @DisplayName("존재하지 않는 예시 ID를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        fun throwsException_whenInvalidIdIsProvided() {
            // arrange
            val invalidId = 999L // Assuming this ID does not exist

            // act
            val exception = assertThrows<CoreException> {
                exampleService.getExample(invalidId)
            }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.NOT_FOUND)
        }
    }
}

```

## E2E Test Pattern

```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExampleV1ApiE2ETest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val exampleJpaRepository: ExampleJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private val ENDPOINT_GET: (Long) -> String = { id: Long -> "/api/v1/examples/$id" }
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("GET /api/v1/examples/{id}")
    @Nested
    inner class Get {
        @DisplayName("존재하는 예시 ID를 주면, 해당 예시 정보를 반환한다.")
        @Test
        fun returnsExampleInfo_whenValidIdIsProvided() {
            // arrange
            val exampleModel =
                exampleJpaRepository.save(ExampleModel(name = "예시 제목", description = "예시 설명"))
            val requestUrl = ENDPOINT_GET(exampleModel.id)

            // act
            val responseType =
                object : ParameterizedTypeReference<ApiResponse<ExampleV1Dto.ExampleResponse>>() {}
            val response = testRestTemplate.exchange(
                requestUrl,
                HttpMethod.GET,
                HttpEntity<Any>(Unit),
                responseType
            )

            // assert
            assertAll(
                { assertThat(response.statusCode.is2xxSuccessful).isTrue() },
                { assertThat(response.body?.data?.id).isEqualTo(exampleModel.id) },
                { assertThat(response.body?.data?.name).isEqualTo(exampleModel.name) },
                { assertThat(response.body?.data?.description).isEqualTo(exampleModel.description) },
            )
        }

        @DisplayName("숫자가 아닌 ID 로 요청하면, 400 BAD_REQUEST 응답을 받는다.")
        @Test
        fun throwsBadRequest_whenIdIsNotProvided() {
            // arrange
            val requestUrl = "/api/v1/examples/나나"

            // act
            val responseType =
                object : ParameterizedTypeReference<ApiResponse<ExampleV1Dto.ExampleResponse>>() {}
            val response = testRestTemplate.exchange(
                requestUrl,
                HttpMethod.GET,
                HttpEntity<Any>(Unit),
                responseType
            )

            // assert
            assertAll(
                { assertThat(response.statusCode.is4xxClientError).isTrue },
                { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
            )
        }

        @DisplayName("존재하지 않는 예시 ID를 주면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        fun throwsException_whenInvalidIdIsProvided() {
            // arrange
            val invalidId = -1L
            val requestUrl = ENDPOINT_GET(invalidId)

            // act
            val responseType =
                object : ParameterizedTypeReference<ApiResponse<ExampleV1Dto.ExampleResponse>>() {}
            val response = testRestTemplate.exchange(
                requestUrl,
                HttpMethod.GET,
                HttpEntity<Any>(Unit),
                responseType
            )

            // assert
            assertAll(
                { assert(response.statusCode.is4xxClientError) },
                { assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND) },
            )
        }
    }
}
```

---

## Test File Organization

```
src/
├── main/
│   └── kotlin/
│       └── io/example/exchange
│           ├── domain/
│           │   ├── ExchangeQuote.kt
│           │   └── Currency.kt
│           │   └── ExchangeService.kt
│           ├── application/
│           │   └── ExchangeFacade.kt
│           └── presentation/
│               └── ExchangeController.kt
└──test/
    └── kotlin/
        └── io/example/exchange
            ├── domain/
            │   └── ExchangeQuoteTest.kt
            │   └── ExchangeQueteIntegrationTest.kt
            └── application/
                └── ExchangeFacadeIntegrationTest.kt
            └── interfaces/
                └── ExchangeControllerE2ETest.kt

```

---

## Common Testing Mistakes

### ❌ WRONG: 구현 세부사항 테스트

```kotlin
assertThat(service.internalCache.size).isEqualTo(1)
```

### ✅ CORRECT: 도메인 결과 테스트

```kotlin
assertThat(result.currency).isEqualTo(Currency.KRW)
```

---

### ❌ WRONG:  테스트 이름이 요구사항/행위를 설명하지 못함

```kotlin
@Testfuntest1() {}
@TestfunshouldWork() {}

```

**문제점**

- 실패했을 때 무엇이 깨졌는지 파악이 어렵습니다.
- 테스트가 스펙이 아니라 잡동사니가 됩니다.

✅ **대안**

```kotlin
@Test
fun `환율 조회 시 base와 currency가 올바르게 설정된다`() {
}

```

---

### ❌ 과도한 Mocking으로 테스트가 “내가 짠 코드”만 통과시키는 형태

```kotlin
every { repo.save(any()) } returns saved
every { repo.findById(any()) } returns saved

```

**문제점**

- 실제 비즈니스 의미가 검증되지 않습니다.
- 구현이 바뀌면 mock 설정부터 깨지며 리팩토링 저항이 생깁니다.

✅ **대안**

- 도메인 로직은 **순수 객체 테스트(무 mock)** 를 우선
- Repository는 통합 테스트에서 검증
- Mock은 “경계(외부 시스템)”에서만 사용

---

---

### ❌ WRONG:  Assert가 “없거나 약함” (실제로 아무 것도 검증하지 않음)

```kotlin
service.doSomething()

```

**문제점**

- 통과해도 의미가 없습니다.
- 회귀 방지 효과가 없습니다.

✅ **대안**

- 결과/상태 변화/부수효과를 명시적으로 검증합니다.

```kotlin
val result = service.doSomething()
assertThat(result).isNotNull

```

---

## **Best Practices**

1. **Write Tests First**- Always TDD
2. **One Assert Per Test**- Focus on single behavior
3. **Descriptive Test Names**- Explain what's tested
4. **Arrange-Act-Assert**- Clear test structure
5. **Mock External Dependencies**- Isolate unit tests
6. **Test Edge Cases**- Null, undefined, empty, large
7. **Test Error Paths**- Not just happy paths
8. **Keep Tests Fast**- Unit tests < 50ms each
9. **Clean Up After Tests**- No side effects
10. **Review Coverage Reports**- Identify gaps

## **Success Metrics**

- 80%+ code coverage achieved
- All tests passing (green)
- No skipped or disabled tests
- Fast test execution (< 30s for unit tests)
- E2E tests cover critical user flows
- Tests catch bugs before production