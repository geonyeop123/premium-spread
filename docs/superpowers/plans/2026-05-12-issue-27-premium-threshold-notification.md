# 프리미엄 임계값 이메일 알림 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 회원이 등록한 (symbol, direction, threshold) 구독 조건이 충족되면 이메일 알림을 발송하는 기능을 추가한다.

**Architecture:** API에서 `notification_subscription` CRUD를 제공하고, 배치(`PremiumRealtimeJob`)가 프리미엄 갱신 후 `PremiumUpdatedEvent`를 publish하면 `@Async` 리스너가 활성 구독을 매칭해 Gmail SMTP로 이메일을 보낸다. 같은 구독은 Redis TTL 키로 60분 cooldown 적용. 운영자용 `AlertService`(monitoring)와는 분리.

**Tech Stack:** Kotlin 2.0, Spring Boot 3.4, Spring Mail (Gmail SMTP), JPA(MySQL 8), JdbcTemplate, Spring ApplicationEvent + `@Async`, Redis, Flyway, MockK, AssertJ, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-05-12-issue-27-premium-threshold-notification-design.md`

---

## File Structure

### supports/email (신규)
- `supports/email/build.gradle.kts`
- `supports/email/src/main/kotlin/io/premiumspread/email/EmailSender.kt` — interface
- `supports/email/src/main/kotlin/io/premiumspread/email/EmailMessage.kt` — data class
- `supports/email/src/main/kotlin/io/premiumspread/email/JavaMailEmailSender.kt` — impl
- `supports/email/src/main/kotlin/io/premiumspread/email/EmailAutoConfiguration.kt`
- `supports/email/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `supports/email/src/test/kotlin/io/premiumspread/email/JavaMailEmailSenderTest.kt`

### apps/api
- `apps/api/src/main/resources/db/migration/V11__create_notification_subscription.sql`
- `apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscription.kt`
- `apps/api/src/main/kotlin/io/premiumspread/domain/notification/ThresholdDirection.kt`
- `apps/api/src/main/kotlin/io/premiumspread/domain/notification/SubscriptionStatus.kt`
- `apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionCommand.kt`
- `apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionRepository.kt`
- `apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionService.kt`
- `apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionExceptions.kt`
- `apps/api/src/main/kotlin/io/premiumspread/application/notification/NotificationSubscriptionDtos.kt`
- `apps/api/src/main/kotlin/io/premiumspread/application/notification/NotificationSubscriptionFacade.kt`
- `apps/api/src/main/kotlin/io/premiumspread/infrastructure/notification/NotificationSubscriptionJpaRepository.kt`
- `apps/api/src/main/kotlin/io/premiumspread/infrastructure/notification/NotificationSubscriptionRepositoryImpl.kt`
- `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/notification/NotificationSubscriptionRequest.kt`
- `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/notification/NotificationSubscriptionResponse.kt`
- `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/notification/NotificationSubscriptionController.kt`
- `apps/api/src/test/...` (각 클래스별 테스트)
- `http/api/notification.http`

### apps/batch
- `apps/batch/build.gradle.kts` (수정: supports:email 추가)
- `apps/batch/src/main/kotlin/io/premiumspread/application/notification/PremiumUpdatedEvent.kt`
- `apps/batch/src/main/kotlin/io/premiumspread/application/notification/PremiumThresholdNotificationListener.kt`
- `apps/batch/src/main/kotlin/io/premiumspread/application/notification/PremiumThresholdNotificationService.kt`
- `apps/batch/src/main/kotlin/io/premiumspread/repository/ActiveSubscriptionReadRepository.kt`
- `apps/batch/src/main/kotlin/io/premiumspread/cache/NotificationCooldownStore.kt`
- `apps/batch/src/main/kotlin/io/premiumspread/config/NotificationAsyncConfig.kt`
- `apps/batch/src/main/kotlin/io/premiumspread/application/job/premium/PremiumRealtimeJob.kt` (수정)
- `apps/batch/src/test/...`

### 설정
- `settings.gradle.kts` (수정)
- `apps/batch/src/main/resources/application-prd.yml` (수정)
- `apps/batch/src/main/resources/application.yml` (확인 — local profile은 mail 비활성)
- `.env.example` (수정)
- `docker/batch-compose.yml` (수정)

---

## Task 1: supports/email 모듈 셋업

**Files:**
- Create: `supports/email/build.gradle.kts`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Create build.gradle.kts**

`supports/email/build.gradle.kts`:
```kotlin
dependencies {
    // Spring Mail
    api("org.springframework.boot:spring-boot-starter-mail")

    // Spring Boot autoconfigure (compileOnly: 런타임에는 앱이 제공)
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.ninja-squad:springmockk:${project.properties["springMockkVersion"]}")
}
```

- [ ] **Step 2: Add module to settings.gradle.kts**

Modify `settings.gradle.kts` — find the `supports:logging`, `supports:monitoring` includes and add:
```kotlin
include("supports:email")
```

- [ ] **Step 3: Verify gradle picks up the module**

Run: `./gradlew :supports:email:dependencies --configuration compileClasspath | head -5`
Expected: shows `spring-boot-starter-mail` resolved without errors.

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts supports/email/build.gradle.kts
git commit -m "chore: supports:email 모듈 스캐폴드"
```

---

## Task 2: EmailSender 인터페이스 + EmailMessage

**Files:**
- Create: `supports/email/src/main/kotlin/io/premiumspread/email/EmailSender.kt`
- Create: `supports/email/src/main/kotlin/io/premiumspread/email/EmailMessage.kt`

- [ ] **Step 1: Create EmailMessage data class**

`supports/email/src/main/kotlin/io/premiumspread/email/EmailMessage.kt`:
```kotlin
package io.premiumspread.email

data class EmailMessage(
    val to: String,
    val subject: String,
    val text: String,
)
```

- [ ] **Step 2: Create EmailSender interface**

`supports/email/src/main/kotlin/io/premiumspread/email/EmailSender.kt`:
```kotlin
package io.premiumspread.email

interface EmailSender {
    fun send(message: EmailMessage)
}
```

- [ ] **Step 3: Compile check**

Run: `./gradlew :supports:email:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add supports/email/src/main/kotlin/io/premiumspread/email/EmailSender.kt supports/email/src/main/kotlin/io/premiumspread/email/EmailMessage.kt
git commit -m "feat: EmailSender 인터페이스와 EmailMessage 추가"
```

---

## Task 3: JavaMailEmailSender 구현 (TDD)

**Files:**
- Create: `supports/email/src/main/kotlin/io/premiumspread/email/EmailDeliveryException.kt`
- Create: `supports/email/src/test/kotlin/io/premiumspread/email/JavaMailEmailSenderTest.kt`
- Create: `supports/email/src/main/kotlin/io/premiumspread/email/JavaMailEmailSender.kt`

- [ ] **Step 1: Create EmailDeliveryException + update interface**

`supports/email/src/main/kotlin/io/premiumspread/email/EmailDeliveryException.kt`:
```kotlin
package io.premiumspread.email

class EmailDeliveryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

`EmailSender.kt` — 인터페이스에 throws 명세 추가:
```kotlin
package io.premiumspread.email

interface EmailSender {
    /** 발송 실패 시 [EmailDeliveryException]을 던진다. */
    @Throws(EmailDeliveryException::class)
    fun send(message: EmailMessage)
}
```

- [ ] **Step 2: Write failing test**

`supports/email/src/test/kotlin/io/premiumspread/email/JavaMailEmailSenderTest.kt`:
```kotlin
package io.premiumspread.email

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

class JavaMailEmailSenderTest {

    private val mailSender = mockk<JavaMailSender>(relaxed = true)
    private val sut = JavaMailEmailSender(mailSender, from = "alert@example.com")

    @Test
    fun `이메일 발송 시 SimpleMailMessage에 from, to, subject, text를 채워 호출한다`() {
        val captured = slot<SimpleMailMessage>()
        every { mailSender.send(capture(captured)) } returns Unit

        sut.send(EmailMessage(to = "user@example.com", subject = "제목", text = "본문"))

        val msg = captured.captured
        assertThat(msg.from).isEqualTo("alert@example.com")
        assertThat(msg.to).containsExactly("user@example.com")
        assertThat(msg.subject).isEqualTo("제목")
        assertThat(msg.text).isEqualTo("본문")
        verify(exactly = 1) { mailSender.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `JavaMailSender가 예외를 던지면 EmailDeliveryException으로 wrap하여 던진다`() {
        every { mailSender.send(any<SimpleMailMessage>()) } throws RuntimeException("SMTP down")

        assertThatThrownBy {
            sut.send(EmailMessage(to = "user@example.com", subject = "s", text = "t"))
        }
            .isInstanceOf(EmailDeliveryException::class.java)
            .hasMessageContaining("user@example.com")
    }
}
```

- [ ] **Step 2: Run test to verify it fails (no implementation)**

Run: `./gradlew :supports:email:test --tests JavaMailEmailSenderTest`
Expected: compile error or test failure due to missing `JavaMailEmailSender`.

- [ ] **Step 3: Implement JavaMailEmailSender**

`supports/email/src/main/kotlin/io/premiumspread/email/JavaMailEmailSender.kt`:
```kotlin
package io.premiumspread.email

import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

class JavaMailEmailSender(
    private val mailSender: JavaMailSender,
    private val from: String,
) : EmailSender {

    override fun send(message: EmailMessage) {
        try {
            val mail = SimpleMailMessage().apply {
                from = this@JavaMailEmailSender.from
                setTo(message.to)
                subject = message.subject
                text = message.text
            }
            mailSender.send(mail)
        } catch (e: Exception) {
            throw EmailDeliveryException("이메일 발송 실패 to=${message.to}", e)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :supports:email:test --tests JavaMailEmailSenderTest`
Expected: BUILD SUCCESSFUL, 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add supports/email/src/main/kotlin/io/premiumspread/email/EmailSender.kt supports/email/src/main/kotlin/io/premiumspread/email/EmailDeliveryException.kt supports/email/src/main/kotlin/io/premiumspread/email/JavaMailEmailSender.kt supports/email/src/test/kotlin/io/premiumspread/email/JavaMailEmailSenderTest.kt
git commit -m "feat: JavaMailEmailSender 구현 — 발송 실패는 EmailDeliveryException으로 wrap 후 전파"
```

---

## Task 4: EmailAutoConfiguration

**Files:**
- Create: `supports/email/src/main/kotlin/io/premiumspread/email/EmailAutoConfiguration.kt`
- Create: `supports/email/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: Create AutoConfiguration**

`supports/email/src/main/kotlin/io/premiumspread/email/EmailAutoConfiguration.kt`:
```kotlin
package io.premiumspread.email

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.mail.javamail.JavaMailSender

@AutoConfiguration
class EmailAutoConfiguration {

    @Bean
    @ConditionalOnBean(JavaMailSender::class)
    @ConditionalOnProperty(name = ["alert.email.from"])
    @ConditionalOnMissingBean(EmailSender::class)
    fun emailSender(
        mailSender: JavaMailSender,
        @Value("\${alert.email.from}") from: String,
    ): EmailSender = JavaMailEmailSender(mailSender, from)
}
```

- [ ] **Step 2: Register AutoConfiguration**

`supports/email/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
io.premiumspread.email.EmailAutoConfiguration
```

- [ ] **Step 3: Compile check**

Run: `./gradlew :supports:email:build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add supports/email/src/main/kotlin/io/premiumspread/email/EmailAutoConfiguration.kt supports/email/src/main/resources/META-INF/
git commit -m "feat: EmailAutoConfiguration 추가 — alert.email.from + JavaMailSender 존재 시 활성화"
```

---

## Task 5: Flyway V11 마이그레이션

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V11__create_notification_subscription.sql`

- [ ] **Step 1: Create migration**

`apps/api/src/main/resources/db/migration/V11__create_notification_subscription.sql`:
```sql
-- BaseEntity와 컨벤션 일치: DATETIME(6), deleted_at (soft delete), utf8mb4
CREATE TABLE notification_subscription (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id  BIGINT         NOT NULL,
    symbol     VARCHAR(20)    NOT NULL,
    direction  VARCHAR(10)    NOT NULL,   -- ABOVE | BELOW
    threshold  DECIMAL(10, 4) NOT NULL,   -- 단위: %
    status     VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6)    NOT NULL,
    updated_at DATETIME(6)    NOT NULL,
    deleted_at DATETIME(6)    NULL,
    INDEX idx_notification_subscription_status_symbol (status, symbol),
    INDEX idx_notification_subscription_member_id (member_id),
    CONSTRAINT fk_notification_subscription_member FOREIGN KEY (member_id) REFERENCES member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] **Step 2: Validate via integration test bootup (later) — for now compile check**

Run: `./gradlew :apps:api:compileKotlin`
Expected: BUILD SUCCESSFUL (migration is only validated at runtime).

- [ ] **Step 3: Commit**

```bash
git add apps/api/src/main/resources/db/migration/V11__create_notification_subscription.sql
git commit -m "feat: notification_subscription 테이블 마이그레이션 추가 (V11)"
```

---

## Task 6: 도메인 enum + Exceptions

**Files:**
- Create: `apps/api/src/main/kotlin/io/premiumspread/domain/notification/ThresholdDirection.kt`
- Create: `apps/api/src/main/kotlin/io/premiumspread/domain/notification/SubscriptionStatus.kt`
- Create: `apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionExceptions.kt`

- [ ] **Step 1: Create ThresholdDirection**

`apps/api/src/main/kotlin/io/premiumspread/domain/notification/ThresholdDirection.kt`:
```kotlin
package io.premiumspread.domain.notification

enum class ThresholdDirection {
    ABOVE,
    BELOW,
}
```

- [ ] **Step 2: Create SubscriptionStatus**

`apps/api/src/main/kotlin/io/premiumspread/domain/notification/SubscriptionStatus.kt`:
```kotlin
package io.premiumspread.domain.notification

enum class SubscriptionStatus {
    ACTIVE,
    INACTIVE,
}
```

- [ ] **Step 3: Create exceptions**

`apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionExceptions.kt`:
```kotlin
package io.premiumspread.domain.notification

import io.premiumspread.domain.DomainException

class NotificationSubscriptionNotFoundException(message: String) : DomainException(message)
```

- [ ] **Step 4: Compile check + commit**

Run: `./gradlew :apps:api:compileKotlin`
```bash
git add apps/api/src/main/kotlin/io/premiumspread/domain/notification/
git commit -m "feat: NotificationSubscription 도메인 enum과 예외 추가"
```

---

## Task 7: NotificationSubscription Entity (TDD)

**Files:**
- Create: `apps/api/src/test/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionTest.kt`
- Create: `apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscription.kt`

- [ ] **Step 1: Write failing test**

`apps/api/src/test/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionTest.kt`:
```kotlin
package io.premiumspread.domain.notification

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class NotificationSubscriptionTest {

    @Test
    fun `create는 ACTIVE 상태로 구독을 생성한다`() {
        val sub = NotificationSubscription.create(
            memberId = 1L,
            symbol = "BTC",
            direction = ThresholdDirection.ABOVE,
            threshold = BigDecimal("5.00"),
        )
        assertThat(sub.memberId).isEqualTo(1L)
        assertThat(sub.symbol).isEqualTo("BTC")
        assertThat(sub.direction).isEqualTo(ThresholdDirection.ABOVE)
        assertThat(sub.threshold).isEqualByComparingTo("5.00")
        assertThat(sub.status).isEqualTo(SubscriptionStatus.ACTIVE)
    }

    @Test
    fun `create는 symbol을 uppercase로 정규화한다`() {
        val sub = NotificationSubscription.create(1L, "btc", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        assertThat(sub.symbol).isEqualTo("BTC")
    }

    @Test
    fun `changeStatus는 동일 인스턴스의 status만 바꾼다 (id 보존)`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        sub.changeStatus(SubscriptionStatus.INACTIVE)
        assertThat(sub.status).isEqualTo(SubscriptionStatus.INACTIVE)
    }

    @Test
    fun `changeThreshold는 임계값만 바꾼다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        sub.changeThreshold(BigDecimal("7.50"))
        assertThat(sub.threshold).isEqualByComparingTo("7.50")
    }

    @Test
    fun `changeDirection은 방향만 바꾼다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        sub.changeDirection(ThresholdDirection.BELOW)
        assertThat(sub.direction).isEqualTo(ThresholdDirection.BELOW)
    }
}
```

- [ ] **Step 2: Run test → expect fail**

Run: `./gradlew :apps:api:test --tests NotificationSubscriptionTest`
Expected: compile error (NotificationSubscription not defined).

- [ ] **Step 3: Implement entity (mutable fields with `protected set` + change* methods)**

⚠️ **id 보존 원칙**: `withX`처럼 새 인스턴스를 만들어 save하면 `BaseEntity.id = 0`이라 JPA가 INSERT를 발행해 새 row가 생긴다 (PATCH가 중복 row를 만드는 버그). 변경 가능 컬럼은 동일 인스턴스에서 mutate.

`apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscription.kt`:
```kotlin
package io.premiumspread.domain.notification

import io.premiumspread.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(
    name = "notification_subscription",
    indexes = [
        Index(name = "idx_notification_subscription_status_symbol", columnList = "status,symbol"),
        Index(name = "idx_notification_subscription_member_id", columnList = "member_id"),
    ],
)
class NotificationSubscription protected constructor(
    @Column(name = "member_id", nullable = false, updatable = false)
    val memberId: Long,
    @Column(nullable = false, length = 20, updatable = false)
    val symbol: String,
    direction: ThresholdDirection,
    threshold: BigDecimal,
    status: SubscriptionStatus,
) : BaseEntity() {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var direction: ThresholdDirection = direction
        protected set

    @Column(nullable = false, precision = 10, scale = 4)
    var threshold: BigDecimal = threshold
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: SubscriptionStatus = status
        protected set

    fun changeStatus(newStatus: SubscriptionStatus) {
        this.status = newStatus
    }

    fun changeThreshold(newThreshold: BigDecimal) {
        this.threshold = newThreshold
    }

    fun changeDirection(newDirection: ThresholdDirection) {
        this.direction = newDirection
    }

    companion object {
        fun create(
            memberId: Long,
            symbol: String,
            direction: ThresholdDirection,
            threshold: BigDecimal,
        ): NotificationSubscription = NotificationSubscription(
            memberId = memberId,
            symbol = symbol.uppercase(),
            direction = direction,
            threshold = threshold,
            status = SubscriptionStatus.ACTIVE,
        )
    }
}
```

- [ ] **Step 4: Run test → expect pass**

Run: `./gradlew :apps:api:test --tests NotificationSubscriptionTest`
Expected: BUILD SUCCESSFUL, 5 tests passed.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscription.kt apps/api/src/test/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionTest.kt
git commit -m "feat: NotificationSubscription Entity 추가 — 변경 컬럼은 protected set + change*()"
```

---

## Task 8: Command + Repository interface

**Files:**
- Create: `apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionCommand.kt`
- Create: `apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionRepository.kt`

- [ ] **Step 1: Create Command**

`apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionCommand.kt`:
```kotlin
package io.premiumspread.domain.notification

import java.math.BigDecimal

class NotificationSubscriptionCommand private constructor() {

    data class Create(
        val memberId: Long,
        val symbol: String,
        val direction: ThresholdDirection,
        val threshold: BigDecimal,
    )

    data class Update(
        val id: Long,
        val memberId: Long,
        val status: SubscriptionStatus?,
        val direction: ThresholdDirection?,
        val threshold: BigDecimal?,
    )
}
```

- [ ] **Step 2: Create Repository interface**

`apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionRepository.kt`:
```kotlin
package io.premiumspread.domain.notification

interface NotificationSubscriptionRepository {
    fun save(subscription: NotificationSubscription): NotificationSubscription
    fun findById(id: Long): NotificationSubscription?
    fun findAllByMemberId(memberId: Long): List<NotificationSubscription>
    fun delete(subscription: NotificationSubscription)
}
```

- [ ] **Step 3: Compile + commit**

Run: `./gradlew :apps:api:compileKotlin`
```bash
git add apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionCommand.kt apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionRepository.kt
git commit -m "feat: NotificationSubscription Command/Repository interface 추가"
```

---

## Task 9: NotificationSubscriptionService (TDD)

**Files:**
- Create: `apps/api/src/test/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionServiceTest.kt`
- Create: `apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionService.kt`

- [ ] **Step 1: Write failing tests**

`apps/api/src/test/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionServiceTest.kt`:
```kotlin
package io.premiumspread.domain.notification

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class NotificationSubscriptionServiceTest {

    private val repository = mockk<NotificationSubscriptionRepository>(relaxed = true)
    private val sut = NotificationSubscriptionService(repository)

    @Test
    fun `create는 ACTIVE 상태로 저장한다`() {
        val saved = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        every { repository.save(any()) } returns saved

        val result = sut.create(
            NotificationSubscriptionCommand.Create(
                memberId = 1L,
                symbol = "btc",
                direction = ThresholdDirection.ABOVE,
                threshold = BigDecimal("5.00"),
            ),
        )

        assertThat(result.status).isEqualTo(SubscriptionStatus.ACTIVE)
        verify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `findByIdAndMemberId는 본인 구독을 반환한다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        every { repository.findById(10L) } returns sub

        val result = sut.findByIdAndMemberId(10L, 1L)
        assertThat(result).isSameAs(sub)
    }

    @Test
    fun `findByIdAndMemberId는 다른 회원 구독이면 null을 반환한다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        every { repository.findById(10L) } returns sub

        val result = sut.findByIdAndMemberId(10L, 2L)
        assertThat(result).isNull()
    }

    @Test
    fun `findByIdAndMemberId는 존재하지 않으면 null을 반환한다`() {
        every { repository.findById(10L) } returns null
        val result = sut.findByIdAndMemberId(10L, 1L)
        assertThat(result).isNull()
    }

    @Test
    fun `update는 동일 인스턴스의 status, direction, threshold를 부분 갱신한다 (새 인스턴스 생성 금지)`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        every { repository.findById(10L) } returns sub
        every { repository.save(sub) } returns sub

        val result = sut.update(
            NotificationSubscriptionCommand.Update(
                id = 10L,
                memberId = 1L,
                status = SubscriptionStatus.INACTIVE,
                direction = ThresholdDirection.BELOW,
                threshold = BigDecimal("-2.00"),
            ),
        )

        // 반환된 인스턴스가 입력 인스턴스와 동일 (id 보존)
        assertThat(result).isSameAs(sub)
        assertThat(result.status).isEqualTo(SubscriptionStatus.INACTIVE)
        assertThat(result.direction).isEqualTo(ThresholdDirection.BELOW)
        assertThat(result.threshold).isEqualByComparingTo("-2.00")
        verify(exactly = 1) { repository.save(sub) }
    }

    @Test
    fun `update는 본인 구독이 아니면 NotFound 예외를 던진다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        every { repository.findById(10L) } returns sub

        assertThatThrownBy {
            sut.update(NotificationSubscriptionCommand.Update(10L, memberId = 2L, null, null, null))
        }.isInstanceOf(NotificationSubscriptionNotFoundException::class.java)
    }

    @Test
    fun `delete는 본인 구독을 삭제한다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        every { repository.findById(10L) } returns sub

        sut.delete(10L, memberId = 1L)
        verify(exactly = 1) { repository.delete(sub) }
    }

    @Test
    fun `delete는 본인 구독이 아니면 NotFound를 던진다`() {
        val sub = NotificationSubscription.create(1L, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00"))
        every { repository.findById(10L) } returns sub

        assertThatThrownBy { sut.delete(10L, memberId = 2L) }
            .isInstanceOf(NotificationSubscriptionNotFoundException::class.java)
    }
}
```

- [ ] **Step 2: Run → expect fail**

Run: `./gradlew :apps:api:test --tests NotificationSubscriptionServiceTest`
Expected: compile error / fail.

- [ ] **Step 3: Implement Service**

`apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionService.kt`:
```kotlin
package io.premiumspread.domain.notification

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NotificationSubscriptionService(
    private val repository: NotificationSubscriptionRepository,
) {

    @Transactional
    fun create(command: NotificationSubscriptionCommand.Create): NotificationSubscription {
        val sub = NotificationSubscription.create(
            memberId = command.memberId,
            symbol = command.symbol,
            direction = command.direction,
            threshold = command.threshold,
        )
        return repository.save(sub)
    }

    @Transactional(readOnly = true)
    fun findByIdAndMemberId(id: Long, memberId: Long): NotificationSubscription? {
        val sub = repository.findById(id) ?: return null
        return if (sub.memberId == memberId) sub else null
    }

    @Transactional(readOnly = true)
    fun findAllByMemberId(memberId: Long): List<NotificationSubscription> =
        repository.findAllByMemberId(memberId)

    @Transactional
    fun update(command: NotificationSubscriptionCommand.Update): NotificationSubscription {
        val sub = findByIdAndMemberId(command.id, command.memberId)
            ?: throw NotificationSubscriptionNotFoundException("구독을 찾을 수 없습니다: id=${command.id}")

        // id 보존: 동일 인스턴스의 필드를 mutate. save 호출은 명시적(이미 영속 상태면 dirty checking으로도 가능).
        command.status?.let { sub.changeStatus(it) }
        command.direction?.let { sub.changeDirection(it) }
        command.threshold?.let { sub.changeThreshold(it) }

        return repository.save(sub)
    }

    @Transactional
    fun delete(id: Long, memberId: Long) {
        val sub = findByIdAndMemberId(id, memberId)
            ?: throw NotificationSubscriptionNotFoundException("구독을 찾을 수 없습니다: id=$id")
        repository.delete(sub)
    }
}
```

- [ ] **Step 4: Run → expect pass**

Run: `./gradlew :apps:api:test --tests NotificationSubscriptionServiceTest`
Expected: BUILD SUCCESSFUL, 8 tests passed.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionService.kt apps/api/src/test/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionServiceTest.kt
git commit -m "feat: NotificationSubscriptionService 구현 — 본인 검증 + 부분 갱신"
```

---

## Task 10: Infrastructure Repository (JPA + Integration)

**Files:**
- Create: `apps/api/src/main/kotlin/io/premiumspread/infrastructure/notification/NotificationSubscriptionJpaRepository.kt`
- Create: `apps/api/src/main/kotlin/io/premiumspread/infrastructure/notification/NotificationSubscriptionRepositoryImpl.kt`
- Create: `apps/api/src/test/kotlin/io/premiumspread/infrastructure/notification/NotificationSubscriptionRepositoryImplTest.kt`

- [ ] **Step 1: Create JpaRepository**

`apps/api/src/main/kotlin/io/premiumspread/infrastructure/notification/NotificationSubscriptionJpaRepository.kt`:
```kotlin
package io.premiumspread.infrastructure.notification

import io.premiumspread.domain.notification.NotificationSubscription
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationSubscriptionJpaRepository : JpaRepository<NotificationSubscription, Long> {
    fun findAllByMemberIdAndDeletedAtIsNull(memberId: Long): List<NotificationSubscription>
}
```

- [ ] **Step 2: Create RepositoryImpl**

`apps/api/src/main/kotlin/io/premiumspread/infrastructure/notification/NotificationSubscriptionRepositoryImpl.kt`:
```kotlin
package io.premiumspread.infrastructure.notification

import io.premiumspread.domain.notification.NotificationSubscription
import io.premiumspread.domain.notification.NotificationSubscriptionRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

@Repository
class NotificationSubscriptionRepositoryImpl(
    private val jpaRepository: NotificationSubscriptionJpaRepository,
) : NotificationSubscriptionRepository {

    override fun save(subscription: NotificationSubscription): NotificationSubscription =
        jpaRepository.save(subscription)

    override fun findById(id: Long): NotificationSubscription? =
        jpaRepository.findByIdOrNull(id)?.takeIf { it.deletedAt == null }

    override fun findAllByMemberId(memberId: Long): List<NotificationSubscription> =
        jpaRepository.findAllByMemberIdAndDeletedAtIsNull(memberId)

    override fun delete(subscription: NotificationSubscription) {
        subscription.delete()
        jpaRepository.save(subscription)
    }
}
```

- [ ] **Step 3: Write integration test (follows existing Repository Integration patterns)**

`apps/api/src/test/kotlin/io/premiumspread/infrastructure/notification/NotificationSubscriptionRepositoryImplTest.kt`:
```kotlin
package io.premiumspread.infrastructure.notification

import io.premiumspread.config.MySqlTestContainersConfig
import io.premiumspread.config.RedisTestContainersConfig
import io.premiumspread.config.TestConfig
import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.MemberRepository
import io.premiumspread.domain.notification.NotificationSubscription
import io.premiumspread.domain.notification.SubscriptionStatus
import io.premiumspread.domain.notification.ThresholdDirection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, TestConfig::class)
class NotificationSubscriptionRepositoryImplTest @Autowired constructor(
    private val sut: NotificationSubscriptionRepositoryImpl,
    private val memberRepository: MemberRepository,
) {

    @Test
    fun `저장 후 ID로 조회 가능`() {
        val member = memberRepository.save(Member.create(email = "a@a.com", encodedPassword = "x"))
        val saved = sut.save(NotificationSubscription.create(member.id, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00")))
        val found = sut.findById(saved.id)
        assertThat(found).isNotNull
        assertThat(found?.symbol).isEqualTo("BTC")
    }

    @Test
    fun `findAllByMemberId는 멤버의 활성 구독을 반환한다`() {
        val member = memberRepository.save(Member.create(email = "b@b.com", encodedPassword = "x"))
        sut.save(NotificationSubscription.create(member.id, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00")))
        sut.save(NotificationSubscription.create(member.id, "ETH", ThresholdDirection.BELOW, BigDecimal("-1.00")))

        val list = sut.findAllByMemberId(member.id)
        assertThat(list).hasSize(2)
    }

    @Test
    fun `delete는 soft delete로 처리되어 조회되지 않는다`() {
        val member = memberRepository.save(Member.create(email = "c@c.com", encodedPassword = "x"))
        val sub = sut.save(NotificationSubscription.create(member.id, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00")))
        sut.delete(sub)
        assertThat(sut.findById(sub.id)).isNull()
        assertThat(sut.findAllByMemberId(member.id)).isEmpty()
    }

    @Test
    fun `change 후 save는 새 row를 만들지 않는다 (UPDATE)`() {
        // PATCH 회귀 방지: id 보존 패턴이 깨지면 row가 2개로 늘어남
        val member = memberRepository.save(Member.create(email = "d@d.com", encodedPassword = "x"))
        val sub = sut.save(NotificationSubscription.create(member.id, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00")))
        val originalId = sub.id

        sub.changeStatus(SubscriptionStatus.INACTIVE)
        sut.save(sub)

        val all = sut.findAllByMemberId(member.id)
        // INACTIVE는 findAll 결과에 어떻게 포함되는지에 따라 다름 — JpaRepository는 status 무관 조회
        // 핵심: row count가 1이고 id가 보존됨
        assertThat(all).hasSizeLessThanOrEqualTo(1)  // 보수적
        assertThat(sut.findById(originalId)).isNotNull
    }
}
```

- [ ] **Step 4: Run integration test**

Run: `./gradlew :apps:api:integrationTest --tests NotificationSubscriptionRepositoryImplTest`
Expected: BUILD SUCCESSFUL, 3 tests passed (Testcontainers spins MySQL).

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/kotlin/io/premiumspread/infrastructure/notification/ apps/api/src/test/kotlin/io/premiumspread/infrastructure/notification/
git commit -m "feat: NotificationSubscriptionRepositoryImpl + JpaRepository — soft delete 기반"
```

---

## Task 11: Application Facade + DTOs

**Files:**
- Create: `apps/api/src/main/kotlin/io/premiumspread/application/notification/NotificationSubscriptionDtos.kt`
- Create: `apps/api/src/main/kotlin/io/premiumspread/application/notification/NotificationSubscriptionFacade.kt`

- [ ] **Step 1: Create DTOs**

`apps/api/src/main/kotlin/io/premiumspread/application/notification/NotificationSubscriptionDtos.kt`:
```kotlin
package io.premiumspread.application.notification

import io.premiumspread.domain.notification.NotificationSubscription
import io.premiumspread.domain.notification.SubscriptionStatus
import io.premiumspread.domain.notification.ThresholdDirection
import java.math.BigDecimal

class NotificationSubscriptionCriteria private constructor() {
    data class Create(
        val memberId: Long,
        val symbol: String,
        val direction: ThresholdDirection,
        val threshold: BigDecimal,
    )

    data class Update(
        val id: Long,
        val memberId: Long,
        val status: SubscriptionStatus?,
        val direction: ThresholdDirection?,
        val threshold: BigDecimal?,
    )
}

class NotificationSubscriptionResult private constructor() {
    data class Detail(
        val id: Long,
        val memberId: Long,
        val symbol: String,
        val direction: ThresholdDirection,
        val threshold: BigDecimal,
        val status: SubscriptionStatus,
    ) {
        companion object {
            fun from(entity: NotificationSubscription): Detail = Detail(
                id = entity.id,
                memberId = entity.memberId,
                symbol = entity.symbol,
                direction = entity.direction,
                threshold = entity.threshold,
                status = entity.status,
            )
        }
    }
}
```

- [ ] **Step 2: Create Facade**

`apps/api/src/main/kotlin/io/premiumspread/application/notification/NotificationSubscriptionFacade.kt`:
```kotlin
package io.premiumspread.application.notification

import io.premiumspread.domain.notification.NotificationSubscriptionCommand
import io.premiumspread.domain.notification.NotificationSubscriptionNotFoundException
import io.premiumspread.domain.notification.NotificationSubscriptionService
import org.springframework.stereotype.Service

@Service
class NotificationSubscriptionFacade(
    private val service: NotificationSubscriptionService,
) {

    fun create(criteria: NotificationSubscriptionCriteria.Create): NotificationSubscriptionResult.Detail {
        val saved = service.create(
            NotificationSubscriptionCommand.Create(
                memberId = criteria.memberId,
                symbol = criteria.symbol,
                direction = criteria.direction,
                threshold = criteria.threshold,
            ),
        )
        return NotificationSubscriptionResult.Detail.from(saved)
    }

    fun findByIdAndMemberId(id: Long, memberId: Long): NotificationSubscriptionResult.Detail {
        val sub = service.findByIdAndMemberId(id, memberId)
            ?: throw NotificationSubscriptionNotFoundException("구독을 찾을 수 없습니다: id=$id")
        return NotificationSubscriptionResult.Detail.from(sub)
    }

    fun findAllByMemberId(memberId: Long): List<NotificationSubscriptionResult.Detail> =
        service.findAllByMemberId(memberId).map { NotificationSubscriptionResult.Detail.from(it) }

    fun update(criteria: NotificationSubscriptionCriteria.Update): NotificationSubscriptionResult.Detail {
        val updated = service.update(
            NotificationSubscriptionCommand.Update(
                id = criteria.id,
                memberId = criteria.memberId,
                status = criteria.status,
                direction = criteria.direction,
                threshold = criteria.threshold,
            ),
        )
        return NotificationSubscriptionResult.Detail.from(updated)
    }

    fun delete(id: Long, memberId: Long) {
        service.delete(id, memberId)
    }
}
```

- [ ] **Step 3: Compile + commit**

Run: `./gradlew :apps:api:compileKotlin`
```bash
git add apps/api/src/main/kotlin/io/premiumspread/application/notification/
git commit -m "feat: NotificationSubscriptionFacade와 Criteria/Result DTOs 추가"
```

---

## Task 12: Controller + Request/Response + WebMvc 테스트

**Files:**
- Create: `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/notification/NotificationSubscriptionRequest.kt`
- Create: `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/notification/NotificationSubscriptionResponse.kt`
- Create: `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/notification/NotificationSubscriptionController.kt`
- Create: `apps/api/src/test/kotlin/io/premiumspread/interfaces/api/notification/NotificationSubscriptionControllerTest.kt`

- [ ] **Step 1: Create Request**

`apps/api/src/main/kotlin/io/premiumspread/interfaces/api/notification/NotificationSubscriptionRequest.kt`:
```kotlin
package io.premiumspread.interfaces.api.notification

import io.premiumspread.domain.notification.SubscriptionStatus
import io.premiumspread.domain.notification.ThresholdDirection
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal

class NotificationSubscriptionRequest private constructor() {

    data class Create(
        @field:NotBlank val symbol: String,
        @field:NotNull val direction: ThresholdDirection,
        @field:NotNull val threshold: BigDecimal,
    )

    data class Update(
        val status: SubscriptionStatus? = null,
        val direction: ThresholdDirection? = null,
        val threshold: BigDecimal? = null,
    )
}
```

- [ ] **Step 2: Create Response**

`apps/api/src/main/kotlin/io/premiumspread/interfaces/api/notification/NotificationSubscriptionResponse.kt`:
```kotlin
package io.premiumspread.interfaces.api.notification

import io.premiumspread.application.notification.NotificationSubscriptionResult
import io.premiumspread.domain.notification.SubscriptionStatus
import io.premiumspread.domain.notification.ThresholdDirection
import java.math.BigDecimal

class NotificationSubscriptionResponse private constructor() {

    data class Detail(
        val id: Long,
        val symbol: String,
        val direction: ThresholdDirection,
        val threshold: BigDecimal,
        val status: SubscriptionStatus,
    ) {
        companion object {
            fun from(result: NotificationSubscriptionResult.Detail): Detail = Detail(
                id = result.id,
                symbol = result.symbol,
                direction = result.direction,
                threshold = result.threshold,
                status = result.status,
            )
        }
    }
}
```

- [ ] **Step 3: Create Controller**

`apps/api/src/main/kotlin/io/premiumspread/interfaces/api/notification/NotificationSubscriptionController.kt`:
```kotlin
package io.premiumspread.interfaces.api.notification

import io.premiumspread.application.notification.NotificationSubscriptionCriteria
import io.premiumspread.application.notification.NotificationSubscriptionFacade
import io.premiumspread.interfaces.api.auth.LoginMemberId
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/notifications/subscriptions")
class NotificationSubscriptionController(
    private val facade: NotificationSubscriptionFacade,
) {

    @PostMapping
    fun create(
        @LoginMemberId memberId: Long,
        @Valid @RequestBody request: NotificationSubscriptionRequest.Create,
    ): ResponseEntity<NotificationSubscriptionResponse.Detail> {
        val result = facade.create(
            NotificationSubscriptionCriteria.Create(
                memberId = memberId,
                symbol = request.symbol,
                direction = request.direction,
                threshold = request.threshold,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(NotificationSubscriptionResponse.Detail.from(result))
    }

    @GetMapping
    fun list(@LoginMemberId memberId: Long): ResponseEntity<List<NotificationSubscriptionResponse.Detail>> {
        val list = facade.findAllByMemberId(memberId).map { NotificationSubscriptionResponse.Detail.from(it) }
        return ResponseEntity.ok(list)
    }

    @GetMapping("/{id}")
    fun detail(
        @LoginMemberId memberId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<NotificationSubscriptionResponse.Detail> {
        val result = facade.findByIdAndMemberId(id, memberId)
        return ResponseEntity.ok(NotificationSubscriptionResponse.Detail.from(result))
    }

    @PatchMapping("/{id}")
    fun update(
        @LoginMemberId memberId: Long,
        @PathVariable id: Long,
        @RequestBody request: NotificationSubscriptionRequest.Update,
    ): ResponseEntity<NotificationSubscriptionResponse.Detail> {
        val result = facade.update(
            NotificationSubscriptionCriteria.Update(
                id = id,
                memberId = memberId,
                status = request.status,
                direction = request.direction,
                threshold = request.threshold,
            ),
        )
        return ResponseEntity.ok(NotificationSubscriptionResponse.Detail.from(result))
    }

    @DeleteMapping("/{id}")
    fun delete(
        @LoginMemberId memberId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        facade.delete(id, memberId)
        return ResponseEntity.noContent().build()
    }
}
```

- [ ] **Step 4: Write Controller test (WebMvc + Security 통합, PositionControllerTest 패턴 그대로)**

`apps/api/src/test/kotlin/io/premiumspread/interfaces/api/notification/NotificationSubscriptionControllerTest.kt`:
```kotlin
package io.premiumspread.interfaces.api.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import io.premiumspread.application.notification.NotificationSubscriptionFacade
import io.premiumspread.application.notification.NotificationSubscriptionResult
import io.premiumspread.domain.notification.NotificationSubscriptionNotFoundException
import io.premiumspread.domain.notification.SubscriptionStatus
import io.premiumspread.domain.notification.ThresholdDirection
import io.premiumspread.infrastructure.security.CustomUserDetails
import io.premiumspread.infrastructure.security.CustomUserDetailsService
import io.premiumspread.infrastructure.security.JwtTokenProvider
import io.premiumspread.infrastructure.security.JwtValidationResult
import io.premiumspread.infrastructure.security.SecurityConfig
import io.premiumspread.interfaces.api.config.WebMvcConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.math.BigDecimal

@WebMvcTest(NotificationSubscriptionController::class)
@Import(SecurityConfig::class, WebMvcConfig::class)
class NotificationSubscriptionControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper

    @MockkBean private lateinit var facade: NotificationSubscriptionFacade
    @MockkBean(relaxed = true) private lateinit var jwtTokenProvider: JwtTokenProvider
    @MockkBean(relaxed = true) private lateinit var userDetailsService: CustomUserDetailsService

    private val testUserDetails = CustomUserDetails(
        memberId = 1L, email = "u@x.com", nickname = "user", encodedPassword = "pw",
    )

    @BeforeEach
    fun setUp() {
        every { jwtTokenProvider.validateAndGetClaims(any()) } returns JwtValidationResult.Invalid
    }

    @Test
    fun `POST 정상 201`() {
        every { facade.create(any()) } returns NotificationSubscriptionResult.Detail(
            id = 100L, memberId = 1L, symbol = "BTC",
            direction = ThresholdDirection.ABOVE, threshold = BigDecimal("5.00"),
            status = SubscriptionStatus.ACTIVE,
        )
        mockMvc.post("/api/v1/notifications/subscriptions") {
            with(user(testUserDetails))
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"BTC","direction":"ABOVE","threshold":5.00}"""
        }.andExpect { status { isCreated() } }
        verify { facade.create(any()) }
    }

    @Test
    fun `POST 유효성 실패 400`() {
        mockMvc.post("/api/v1/notifications/subscriptions") {
            with(user(testUserDetails))
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"","direction":"ABOVE","threshold":5.00}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `POST 미인증 401`() {
        mockMvc.post("/api/v1/notifications/subscriptions") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"BTC","direction":"ABOVE","threshold":5.00}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `GET 목록 200`() {
        every { facade.findAllByMemberId(1L) } returns emptyList()
        mockMvc.get("/api/v1/notifications/subscriptions") {
            with(user(testUserDetails))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `GET 목록 미인증 401`() {
        mockMvc.get("/api/v1/notifications/subscriptions")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `GET 단건 본인 200`() {
        every { facade.findByIdAndMemberId(10L, 1L) } returns NotificationSubscriptionResult.Detail(
            id = 10L, memberId = 1L, symbol = "BTC",
            direction = ThresholdDirection.ABOVE, threshold = BigDecimal("5.00"),
            status = SubscriptionStatus.ACTIVE,
        )
        mockMvc.get("/api/v1/notifications/subscriptions/10") {
            with(user(testUserDetails))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `GET 단건 타인 또는 없음 404`() {
        every { facade.findByIdAndMemberId(10L, 1L) } throws NotificationSubscriptionNotFoundException("not found")
        mockMvc.get("/api/v1/notifications/subscriptions/10") {
            with(user(testUserDetails))
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `GET 단건 미인증 401`() {
        mockMvc.get("/api/v1/notifications/subscriptions/10")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `PATCH 본인 200`() {
        every { facade.update(any()) } returns NotificationSubscriptionResult.Detail(
            id = 10L, memberId = 1L, symbol = "BTC",
            direction = ThresholdDirection.BELOW, threshold = BigDecimal("-2.00"),
            status = SubscriptionStatus.INACTIVE,
        )
        mockMvc.patch("/api/v1/notifications/subscriptions/10") {
            with(user(testUserDetails))
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"INACTIVE","direction":"BELOW","threshold":-2.00}"""
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `PATCH 타인 404`() {
        every { facade.update(any()) } throws NotificationSubscriptionNotFoundException("not found")
        mockMvc.patch("/api/v1/notifications/subscriptions/10") {
            with(user(testUserDetails))
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"INACTIVE"}"""
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `PATCH 미인증 401`() {
        mockMvc.patch("/api/v1/notifications/subscriptions/10") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"INACTIVE"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `DELETE 본인 204`() {
        every { facade.delete(10L, 1L) } returns Unit
        mockMvc.delete("/api/v1/notifications/subscriptions/10") {
            with(user(testUserDetails))
        }.andExpect { status { isNoContent() } }
    }

    @Test
    fun `DELETE 타인 404`() {
        every { facade.delete(10L, 1L) } throws NotificationSubscriptionNotFoundException("not found")
        mockMvc.delete("/api/v1/notifications/subscriptions/10") {
            with(user(testUserDetails))
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `DELETE 미인증 401`() {
        mockMvc.delete("/api/v1/notifications/subscriptions/10")
            .andExpect { status { isUnauthorized() } }
    }
}
```

- [ ] **Step 5: Verify NotFoundException → 404 매핑 존재 여부 확인**

Run: `grep -rn "NotFoundException\|ResponseStatus\|ControllerAdvice" apps/api/src/main/kotlin --include="*.kt" | head -10`

만약 글로벌 ExceptionHandler가 DomainException → 404로 매핑하지 않으면, `NotificationSubscriptionNotFoundException`에 `@ResponseStatus(HttpStatus.NOT_FOUND)`를 추가하거나 기존 ControllerAdvice에 매핑을 추가한다.

추가하는 경우 — `NotificationSubscriptionExceptions.kt`를 다음으로 교체:
```kotlin
package io.premiumspread.domain.notification

import io.premiumspread.domain.DomainException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

@ResponseStatus(HttpStatus.NOT_FOUND)
class NotificationSubscriptionNotFoundException(message: String) : DomainException(message)
```

- [ ] **Step 6: Run all API unit tests**

Run: `./gradlew :apps:api:test`
Expected: BUILD SUCCESSFUL, all new + existing tests pass.

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/main/kotlin/io/premiumspread/interfaces/api/notification/ apps/api/src/test/kotlin/io/premiumspread/interfaces/api/notification/ apps/api/src/main/kotlin/io/premiumspread/domain/notification/NotificationSubscriptionExceptions.kt
git commit -m "feat: NotificationSubscription REST 컨트롤러 추가 (/api/v1/notifications/subscriptions)"
```

---

## Task 13: HTTP 샘플 파일

**Files:**
- Create: `http/api/notification.http`

- [ ] **Step 1: Create http file (follow existing http/api/*.http 패턴)**

`http/api/notification.http`:
```http
### Create subscription
POST {{host}}/api/v1/notifications/subscriptions
Content-Type: application/json
Authorization: Bearer {{token}}

{
  "symbol": "BTC",
  "direction": "ABOVE",
  "threshold": 5.00
}

### List my subscriptions
GET {{host}}/api/v1/notifications/subscriptions
Authorization: Bearer {{token}}

### Get subscription detail
GET {{host}}/api/v1/notifications/subscriptions/1
Authorization: Bearer {{token}}

### Update subscription
PATCH {{host}}/api/v1/notifications/subscriptions/1
Content-Type: application/json
Authorization: Bearer {{token}}

{
  "status": "INACTIVE"
}

### Delete subscription
DELETE {{host}}/api/v1/notifications/subscriptions/1
Authorization: Bearer {{token}}
```

- [ ] **Step 2: Commit**

```bash
git add http/api/notification.http
git commit -m "docs: notification API http 샘플 추가"
```

---

## Task 14: Batch — NotificationCooldownStore (TDD)

**Files:**
- Create: `apps/batch/src/test/kotlin/io/premiumspread/cache/NotificationCooldownStoreTest.kt`
- Create: `apps/batch/src/main/kotlin/io/premiumspread/cache/NotificationCooldownStore.kt`

- [ ] **Step 1: Write failing test**

`apps/batch/src/test/kotlin/io/premiumspread/cache/NotificationCooldownStoreTest.kt`:
```kotlin
package io.premiumspread.cache

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

class NotificationCooldownStoreTest {

    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
    private val redisTemplate = mockk<StringRedisTemplate>(relaxed = true) {
        every { opsForValue() } returns valueOps
    }
    private val sut = NotificationCooldownStore(redisTemplate)

    @Test
    fun `tryAcquireCooldown은 키가 없을 때 setIfAbsent로 60분 TTL을 설정하고 true를 반환한다`() {
        val key = slot<String>()
        val ttl = slot<Duration>()
        every { valueOps.setIfAbsent(capture(key), any(), capture(ttl)) } returns true

        val result = sut.tryAcquireCooldown(42L)

        assertThat(result).isTrue()
        assertThat(key.captured).isEqualTo("notification:cooldown:42")
        assertThat(ttl.captured).isEqualTo(Duration.ofMinutes(60))
        verify(exactly = 1) { valueOps.setIfAbsent(any(), any(), any<Duration>()) }
    }

    @Test
    fun `tryAcquireCooldown은 이미 키가 있으면 false를 반환한다`() {
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns false
        assertThat(sut.tryAcquireCooldown(42L)).isFalse()
    }

    @Test
    fun `tryAcquireCooldown은 setIfAbsent가 null을 반환해도 false로 처리한다`() {
        every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns null
        assertThat(sut.tryAcquireCooldown(42L)).isFalse()
    }

    @Test
    fun `release는 키를 삭제한다`() {
        val key = slot<String>()
        every { redisTemplate.delete(capture(key)) } returns true

        sut.release(42L)

        assertThat(key.captured).isEqualTo("notification:cooldown:42")
        verify(exactly = 1) { redisTemplate.delete("notification:cooldown:42") }
    }
}
```

- [ ] **Step 2: Run → expect fail**

Run: `./gradlew :apps:batch:test --tests NotificationCooldownStoreTest`
Expected: compile error.

- [ ] **Step 3: Implement NotificationCooldownStore**

`apps/batch/src/main/kotlin/io/premiumspread/cache/NotificationCooldownStore.kt`:
```kotlin
package io.premiumspread.cache

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class NotificationCooldownStore(
    private val redisTemplate: StringRedisTemplate,
) {

    /**
     * 키가 없을 때만 set (NX) + 60분 TTL.
     * 새로 set 됐으면 true, 이미 있어 set 실패면 false.
     * 원자 연산으로 동시 접근 시 중복 발송을 방지한다.
     */
    fun tryAcquireCooldown(subscriptionId: Long): Boolean {
        val acquired = redisTemplate.opsForValue()
            .setIfAbsent(key(subscriptionId), "1", COOLDOWN)
        return acquired == true
    }

    /** 발송 실패 시 reservation 해제. 다음 이벤트에서 즉시 재시도 허용. */
    fun release(subscriptionId: Long) {
        redisTemplate.delete(key(subscriptionId))
    }

    companion object {
        private val COOLDOWN: Duration = Duration.ofMinutes(60)
        private fun key(id: Long) = "notification:cooldown:$id"
    }
}
```

- [ ] **Step 4: Run → expect pass**

Run: `./gradlew :apps:batch:test --tests NotificationCooldownStoreTest`
Expected: BUILD SUCCESSFUL, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add apps/batch/src/main/kotlin/io/premiumspread/cache/NotificationCooldownStore.kt apps/batch/src/test/kotlin/io/premiumspread/cache/NotificationCooldownStoreTest.kt
git commit -m "feat: NotificationCooldownStore — 원자 SET NX 기반 tryAcquireCooldown/release"
```

---

## Task 15: Batch — ActiveSubscriptionReadRepository (JdbcTemplate + Integration)

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/repository/ActiveSubscriptionReadRepository.kt`
- Create: `apps/batch/src/test/kotlin/io/premiumspread/repository/ActiveSubscriptionReadRepositoryTest.kt`

- [ ] **Step 1: Create ActiveSubscriptionView + Repository**

`apps/batch/src/main/kotlin/io/premiumspread/repository/ActiveSubscriptionReadRepository.kt`:
```kotlin
package io.premiumspread.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal

enum class ThresholdDirectionView { ABOVE, BELOW }

data class ActiveSubscriptionView(
    val id: Long,
    val memberId: Long,
    val memberEmail: String,
    val memberNickname: String,
    val symbol: String,
    val direction: ThresholdDirectionView,
    val threshold: BigDecimal,
) {
    fun matches(rate: BigDecimal): Boolean = when (direction) {
        ThresholdDirectionView.ABOVE -> rate >= threshold
        ThresholdDirectionView.BELOW -> rate <= threshold
    }
}

@Repository
class ActiveSubscriptionReadRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findActiveBySymbol(symbol: String): List<ActiveSubscriptionView> {
        return jdbcTemplate.query(
            """
            SELECT ns.id, ns.member_id, m.email, m.nickname,
                   ns.symbol, ns.direction, ns.threshold
            FROM notification_subscription ns
            INNER JOIN member m ON m.id = ns.member_id
            WHERE ns.status = 'ACTIVE'
              AND ns.deleted_at IS NULL
              AND m.deleted_at IS NULL
              AND ns.symbol = ?
            """.trimIndent(),
            { rs, _ ->
                ActiveSubscriptionView(
                    id = rs.getLong("id"),
                    memberId = rs.getLong("member_id"),
                    memberEmail = rs.getString("email"),
                    memberNickname = rs.getString("nickname"),
                    symbol = rs.getString("symbol"),
                    direction = ThresholdDirectionView.valueOf(rs.getString("direction")),
                    threshold = rs.getBigDecimal("threshold"),
                )
            },
            symbol.uppercase(),
        )
    }
}
```

- [ ] **Step 2: Write integration test**

`apps/batch/src/test/kotlin/io/premiumspread/repository/ActiveSubscriptionReadRepositoryTest.kt`:
```kotlin
package io.premiumspread.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
// 프로젝트의 배치 통합테스트 컨피그가 있다면 import (없으면 MySqlTestContainersConfig만)
// import io.premiumspread.config.MySqlTestContainersConfig 등

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class ActiveSubscriptionReadRepositoryTest @Autowired constructor(
    private val sut: ActiveSubscriptionReadRepository,
    private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `활성 구독 + 회원 정보를 JOIN해서 반환한다`() {
        // given: member 1명 + ACTIVE BTC 구독 1건
        jdbcTemplate.update("INSERT INTO member (email, password, nickname, status, created_at, updated_at) VALUES ('u@x.com','p','user','ACTIVE', NOW(6), NOW(6))")
        val memberId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
        jdbcTemplate.update(
            """
            INSERT INTO notification_subscription (member_id, symbol, direction, threshold, status, created_at, updated_at)
            VALUES (?, 'BTC', 'ABOVE', 5.0, 'ACTIVE', NOW(6), NOW(6))
            """.trimIndent(),
            memberId,
        )

        val rows = sut.findActiveBySymbol("BTC")
        assertThat(rows).hasSize(1)
        assertThat(rows[0].memberEmail).isEqualTo("u@x.com")
        assertThat(rows[0].symbol).isEqualTo("BTC")
    }

    @Test
    fun `INACTIVE 구독은 반환되지 않는다`() {
        jdbcTemplate.update("INSERT INTO member (email, password, nickname, status, created_at, updated_at) VALUES ('y@x.com','p','user','ACTIVE', NOW(6), NOW(6))")
        val memberId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
        jdbcTemplate.update(
            "INSERT INTO notification_subscription (member_id, symbol, direction, threshold, status, created_at, updated_at) VALUES (?, 'BTC', 'ABOVE', 5.0, 'INACTIVE', NOW(6), NOW(6))",
            memberId,
        )

        assertThat(sut.findActiveBySymbol("BTC")).noneMatch { it.memberEmail == "y@x.com" }
    }

    @Test
    fun `다른 symbol은 반환되지 않는다`() {
        jdbcTemplate.update("INSERT INTO member (email, password, nickname, status, created_at, updated_at) VALUES ('z@x.com','p','user','ACTIVE', NOW(6), NOW(6))")
        val memberId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
        jdbcTemplate.update(
            "INSERT INTO notification_subscription (member_id, symbol, direction, threshold, status, created_at, updated_at) VALUES (?, 'ETH', 'ABOVE', 5.0, 'ACTIVE', NOW(6), NOW(6))",
            memberId,
        )

        assertThat(sut.findActiveBySymbol("BTC")).noneMatch { it.memberEmail == "z@x.com" }
    }
}
```

⚠️ 통합 테스트의 Spring 부트 컨피그/Testcontainers 셋업은 batch 모듈의 기존 통합 테스트(예: `PremiumSnapshotRepositoryTest`가 있다면 그것)와 동일하게 맞춘다. 없으면 `apps/batch/src/test/kotlin/io/premiumspread/config/`에 MySqlTestContainersConfig를 새로 추가해야 한다 — 이 경우 기존 `:apps:api`의 테스트 컨피그를 참고하여 동일 패턴으로 작성.

- [ ] **Step 3: Run integration test**

Run: `./gradlew :apps:batch:test --tests ActiveSubscriptionReadRepositoryTest`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 4: Commit**

```bash
git add apps/batch/src/main/kotlin/io/premiumspread/repository/ActiveSubscriptionReadRepository.kt apps/batch/src/test/kotlin/io/premiumspread/repository/ActiveSubscriptionReadRepositoryTest.kt
git commit -m "feat: ActiveSubscriptionReadRepository — JOIN member 활성 구독 조회"
```

---

## Task 16: Batch — PremiumThresholdNotificationService (TDD, 핵심 매칭 로직)

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/application/notification/PremiumUpdatedEvent.kt`
- Create: `apps/batch/src/test/kotlin/io/premiumspread/application/notification/PremiumThresholdNotificationServiceTest.kt`
- Create: `apps/batch/src/main/kotlin/io/premiumspread/application/notification/PremiumThresholdNotificationService.kt`

- [ ] **Step 1: Create PremiumUpdatedEvent**

`apps/batch/src/main/kotlin/io/premiumspread/application/notification/PremiumUpdatedEvent.kt`:
```kotlin
package io.premiumspread.application.notification

import java.math.BigDecimal

data class PremiumUpdatedEvent(
    val symbol: String,
    val premiumRate: BigDecimal,
)
```

- [ ] **Step 2: Write failing tests (매칭 경계값 + process 동작)**

`apps/batch/src/test/kotlin/io/premiumspread/application/notification/PremiumThresholdNotificationServiceTest.kt`:
```kotlin
package io.premiumspread.application.notification

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.cache.NotificationCooldownStore
import io.premiumspread.email.EmailMessage
import io.premiumspread.email.EmailSender
import io.premiumspread.repository.ActiveSubscriptionReadRepository
import io.premiumspread.repository.ActiveSubscriptionView
import io.premiumspread.repository.ThresholdDirectionView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PremiumThresholdNotificationServiceTest {

    private val readRepo = mockk<ActiveSubscriptionReadRepository>(relaxed = true)
    private val cooldownStore = mockk<NotificationCooldownStore>(relaxed = true)
    private val emailSender = mockk<EmailSender>(relaxed = true)
    private val sut = PremiumThresholdNotificationService(readRepo, cooldownStore, emailSender)

    private fun view(
        id: Long,
        direction: ThresholdDirectionView,
        threshold: String,
        email: String = "u$id@x.com",
    ) = ActiveSubscriptionView(
        id = id, memberId = id, memberEmail = email, memberNickname = "user$id",
        symbol = "BTC", direction = direction, threshold = BigDecimal(threshold),
    )

    @Test
    fun `매칭 - ABOVE 경계값 5,00 == 5,00 → match`() {
        val v = view(1L, ThresholdDirectionView.ABOVE, "5.00")
        assertThat(v.matches(BigDecimal("5.00"))).isTrue()
    }

    @Test
    fun `매칭 - ABOVE 5,00 vs 4,99 → no match`() {
        val v = view(1L, ThresholdDirectionView.ABOVE, "5.00")
        assertThat(v.matches(BigDecimal("4.99"))).isFalse()
    }

    @Test
    fun `매칭 - BELOW 경계값 -2,00 == -2,00 → match`() {
        val v = view(1L, ThresholdDirectionView.BELOW, "-2.00")
        assertThat(v.matches(BigDecimal("-2.00"))).isTrue()
    }

    @Test
    fun `매칭 - BELOW -2,00 vs -1,99 → no match`() {
        val v = view(1L, ThresholdDirectionView.BELOW, "-2.00")
        assertThat(v.matches(BigDecimal("-1.99"))).isFalse()
    }

    @Test
    fun `활성 구독 없음 - send 호출 안 함`() {
        every { readRepo.findActiveBySymbol("BTC") } returns emptyList()
        sut.process(PremiumUpdatedEvent("BTC", BigDecimal("5.20")))
        verify(exactly = 0) { emailSender.send(any()) }
    }

    @Test
    fun `매칭 1건 - tryAcquireCooldown 후 send 1회 (release는 안 함)`() {
        val v = view(1L, ThresholdDirectionView.ABOVE, "5.00")
        every { readRepo.findActiveBySymbol("BTC") } returns listOf(v)
        every { cooldownStore.tryAcquireCooldown(1L) } returns true
        every { emailSender.send(any()) } returns Unit

        sut.process(PremiumUpdatedEvent("BTC", BigDecimal("5.20")))

        verify(exactly = 1) { cooldownStore.tryAcquireCooldown(1L) }
        verify(exactly = 1) { emailSender.send(any()) }
        verify(exactly = 0) { cooldownStore.release(any()) }
    }

    @Test
    fun `매칭 안 되는 구독 - acquire 미호출, send 미호출`() {
        val v = view(1L, ThresholdDirectionView.ABOVE, "5.00")
        every { readRepo.findActiveBySymbol("BTC") } returns listOf(v)

        sut.process(PremiumUpdatedEvent("BTC", BigDecimal("4.99")))

        verify(exactly = 0) { cooldownStore.tryAcquireCooldown(any()) }
        verify(exactly = 0) { emailSender.send(any()) }
    }

    @Test
    fun `이미 reservation 있음 (acquire false) - send 미호출, release 미호출`() {
        val v = view(1L, ThresholdDirectionView.ABOVE, "5.00")
        every { readRepo.findActiveBySymbol("BTC") } returns listOf(v)
        every { cooldownStore.tryAcquireCooldown(1L) } returns false

        sut.process(PremiumUpdatedEvent("BTC", BigDecimal("5.20")))

        verify(exactly = 0) { emailSender.send(any()) }
        verify(exactly = 0) { cooldownStore.release(any()) }
    }

    @Test
    fun `SMTP 실패 시 release 호출되어 cooldown이 해제된다`() {
        val v = view(1L, ThresholdDirectionView.ABOVE, "5.00")
        every { readRepo.findActiveBySymbol("BTC") } returns listOf(v)
        every { cooldownStore.tryAcquireCooldown(1L) } returns true
        every { emailSender.send(any()) } throws EmailDeliveryException("SMTP down")

        sut.process(PremiumUpdatedEvent("BTC", BigDecimal("5.20")))

        verify(exactly = 1) { cooldownStore.tryAcquireCooldown(1L) }
        verify(exactly = 1) { emailSender.send(any()) }
        verify(exactly = 1) { cooldownStore.release(1L) }
    }

    @Test
    fun `여러 구독 중 한 건 발송 실패해도 나머지 구독은 계속 처리된다`() {
        val v1 = view(1L, ThresholdDirectionView.ABOVE, "5.00", email = "fail@x.com")
        val v2 = view(2L, ThresholdDirectionView.ABOVE, "4.00", email = "ok@x.com")
        every { readRepo.findActiveBySymbol("BTC") } returns listOf(v1, v2)
        every { cooldownStore.tryAcquireCooldown(any()) } returns true
        every { emailSender.send(match { it.to == "fail@x.com" }) } throws EmailDeliveryException("SMTP")
        every { emailSender.send(match { it.to == "ok@x.com" }) } returns Unit

        sut.process(PremiumUpdatedEvent("BTC", BigDecimal("5.20")))

        verify(exactly = 1) { emailSender.send(match { it.to == "fail@x.com" }) }
        verify(exactly = 1) { cooldownStore.release(1L) }
        verify(exactly = 1) { emailSender.send(match { it.to == "ok@x.com" }) }
        verify(exactly = 0) { cooldownStore.release(2L) }
    }
}
```

- [ ] **Step 3: Run → expect fail**

Run: `./gradlew :apps:batch:test --tests PremiumThresholdNotificationServiceTest`
Expected: compile error.

- [ ] **Step 4: Implement Service**

`apps/batch/src/main/kotlin/io/premiumspread/application/notification/PremiumThresholdNotificationService.kt`:
```kotlin
package io.premiumspread.application.notification

import io.premiumspread.cache.NotificationCooldownStore
import io.premiumspread.email.EmailDeliveryException
import io.premiumspread.email.EmailMessage
import io.premiumspread.email.EmailSender
import io.premiumspread.repository.ActiveSubscriptionReadRepository
import io.premiumspread.repository.ActiveSubscriptionView
import io.premiumspread.repository.ThresholdDirectionView
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
@ConditionalOnBean(EmailSender::class)
class PremiumThresholdNotificationService(
    private val readRepository: ActiveSubscriptionReadRepository,
    private val cooldownStore: NotificationCooldownStore,
    private val emailSender: EmailSender,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(event: PremiumUpdatedEvent) {
        val subscriptions = readRepository.findActiveBySymbol(event.symbol)
        for (sub in subscriptions) {
            if (!sub.matches(event.premiumRate)) continue

            // 원자 reservation: 다른 스레드/이벤트가 먼저 set 했으면 skip
            if (!cooldownStore.tryAcquireCooldown(sub.id)) continue

            try {
                emailSender.send(buildMessage(sub, event))
                // 성공: cooldown 유지 (60분간 재발송 차단)
            } catch (e: EmailDeliveryException) {
                // 실패: cooldown 해제하여 다음 이벤트에서 재시도 가능
                cooldownStore.release(sub.id)
                log.error(
                    "구독 알림 발송 실패 — subscriptionId={}, email={}: {}",
                    sub.id, sub.memberEmail, e.message, e,
                )
            }
        }
    }

    private fun buildMessage(sub: ActiveSubscriptionView, event: PremiumUpdatedEvent): EmailMessage {
        val directionText = when (sub.direction) {
            ThresholdDirectionView.ABOVE -> "${sub.threshold}% 이상 (ABOVE)"
            ThresholdDirectionView.BELOW -> "${sub.threshold}% 이하 (BELOW)"
        }
        val subject = "[premium-spread] ${event.symbol.uppercase()} 프리미엄 ${event.premiumRate}% 도달"
        val text = """
            안녕하세요, ${sub.memberNickname}님.

            설정하신 알림 조건이 충족되었습니다.

            심볼: ${sub.symbol.uppercase()}
            조건: $directionText
            현재 프리미엄: ${event.premiumRate}%
            발생 시각: ${LocalDateTime.now()}

            --
            premium-spread
        """.trimIndent()
        return EmailMessage(to = sub.memberEmail, subject = subject, text = text)
    }
}
```

- [ ] **Step 5: Run → expect pass**

Run: `./gradlew :apps:batch:test --tests PremiumThresholdNotificationServiceTest`
Expected: BUILD SUCCESSFUL, 10 tests passed.

- [ ] **Step 6: Commit**

```bash
git add apps/batch/src/main/kotlin/io/premiumspread/application/notification/ apps/batch/src/test/kotlin/io/premiumspread/application/notification/PremiumThresholdNotificationServiceTest.kt
git commit -m "feat: PremiumThresholdNotificationService — 원자 reservation + SMTP 실패 시 release"
```

---

## Task 17: Batch — Async Listener + Config

**Files:**
- Create: `apps/batch/src/main/kotlin/io/premiumspread/config/NotificationAsyncConfig.kt`
- Create: `apps/batch/src/main/kotlin/io/premiumspread/application/notification/PremiumThresholdNotificationListener.kt`
- Create: `apps/batch/src/test/kotlin/io/premiumspread/application/notification/PremiumThresholdNotificationListenerTest.kt`

- [ ] **Step 1: Create AsyncConfig**

`apps/batch/src/main/kotlin/io/premiumspread/config/NotificationAsyncConfig.kt`:
```kotlin
package io.premiumspread.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
@EnableAsync
class NotificationAsyncConfig {

    @Bean("notificationExecutor")
    fun notificationExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 4
        queueCapacity = 200
        setThreadNamePrefix("notif-")
        initialize()
    }
}
```

- [ ] **Step 2: Write failing listener test**

`apps/batch/src/test/kotlin/io/premiumspread/application/notification/PremiumThresholdNotificationListenerTest.kt`:
```kotlin
package io.premiumspread.application.notification

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PremiumThresholdNotificationListenerTest {

    private val service = mockk<PremiumThresholdNotificationService>(relaxed = true)
    private val sut = PremiumThresholdNotificationListener(service)

    @Test
    fun `이벤트 수신 시 service process를 호출한다`() {
        val event = PremiumUpdatedEvent("BTC", BigDecimal("5.20"))
        sut.on(event)
        verify(exactly = 1) { service.process(event) }
    }

    @Test
    fun `service가 예외를 던져도 외부로 전파하지 않는다`() {
        val event = PremiumUpdatedEvent("BTC", BigDecimal("5.20"))
        every { service.process(any()) } throws RuntimeException("downstream failure")
        assertThatCode { sut.on(event) }.doesNotThrowAnyException()
    }
}
```

- [ ] **Step 3: Run → expect fail**

Run: `./gradlew :apps:batch:test --tests PremiumThresholdNotificationListenerTest`
Expected: compile error.

- [ ] **Step 4: Implement Listener**

⚠️ `@ConditionalOnBean(EmailSender::class)`로 보호한다 — EmailSender 빈이 없는 환경(local, test, 메일 미설정 prd)에서 listener 빈도 등록 안 됨 → 부팅 정상.

`apps/batch/src/main/kotlin/io/premiumspread/application/notification/PremiumThresholdNotificationListener.kt`:
```kotlin
package io.premiumspread.application.notification

import io.premiumspread.email.EmailSender
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
@ConditionalOnBean(EmailSender::class)
class PremiumThresholdNotificationListener(
    private val service: PremiumThresholdNotificationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async("notificationExecutor")
    @EventListener
    fun on(event: PremiumUpdatedEvent) {
        try {
            service.process(event)
        } catch (e: Exception) {
            log.error(
                "알림 리스너 처리 실패 — symbol={}, rate={}: {}",
                event.symbol, event.premiumRate, e.message, e,
            )
        }
    }
}
```

- [ ] **Step 5: Run → expect pass**

Run: `./gradlew :apps:batch:test --tests PremiumThresholdNotificationListenerTest`
Expected: BUILD SUCCESSFUL, 2 tests passed.

- [ ] **Step 6: Commit**

```bash
git add apps/batch/src/main/kotlin/io/premiumspread/config/NotificationAsyncConfig.kt apps/batch/src/main/kotlin/io/premiumspread/application/notification/PremiumThresholdNotificationListener.kt apps/batch/src/test/kotlin/io/premiumspread/application/notification/PremiumThresholdNotificationListenerTest.kt
git commit -m "feat: @Async @EventListener — 잡 스레드와 격리된 알림 리스너"
```

---

## Task 18: Batch — PremiumRealtimeJob에 이벤트 publish 추가

**Files:**
- Modify: `apps/batch/src/main/kotlin/io/premiumspread/application/job/premium/PremiumRealtimeJob.kt`
- Modify/Create: `apps/batch/src/test/kotlin/io/premiumspread/application/job/premium/PremiumRealtimeJobTest.kt`
- Modify: `apps/batch/build.gradle.kts`

- [ ] **Step 1: Add supports:email dependency to batch**

Modify `apps/batch/build.gradle.kts` — find `supports` section and add:
```kotlin
implementation(project(":supports:email"))
```

- [ ] **Step 2: Update PremiumRealtimeJob — inject ApplicationEventPublisher and publish on success**

Find the original `PremiumRealtimeJob.kt` and change its declaration + add publish:

```kotlin
package io.premiumspread.application.job.premium

import io.premiumspread.application.common.JobResult
import io.premiumspread.application.notification.PremiumUpdatedEvent
import io.premiumspread.cache.FxCacheService
import io.premiumspread.cache.PremiumCacheService
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.calculator.PremiumCalculator
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class PremiumRealtimeJob(
    private val tickerCacheService: TickerCacheService,
    private val fxCacheService: FxCacheService,
    private val premiumCacheService: PremiumCacheService,
    private val premiumCalculator: PremiumCalculator,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val BITHUMB = "bithumb"
        private const val BINANCE = "binance"
        private const val BTC = "btc"
    }

    fun run(): JobResult {
        return try {
            val bithumbTicker = tickerCacheService.get(BITHUMB, BTC)
            val binanceTicker = tickerCacheService.get(BINANCE, BTC)
            val fxRate = fxCacheService.getUsdKrw()

            if (bithumbTicker == null || binanceTicker == null || fxRate == null) {
                log.warn(
                    "Missing data for premium calculation - Bithumb: {}, Binance: {}, FX: {}",
                    bithumbTicker != null,
                    binanceTicker != null,
                    fxRate != null,
                )
                return JobResult.Skipped("missing_data")
            }

            if (bithumbTicker.price <= BigDecimal.ZERO || binanceTicker.price <= BigDecimal.ZERO || fxRate <= BigDecimal.ZERO) {
                log.warn(
                    "Invalid price detected - Bithumb: {}, Binance: {}, FX: {}",
                    bithumbTicker.price,
                    binanceTicker.price,
                    fxRate,
                )
                return JobResult.Skipped("invalid_price")
            }

            val premium = premiumCalculator.calculate(
                koreaTicker = bithumbTicker,
                foreignTicker = binanceTicker,
                fxRate = fxRate,
            )

            premiumCacheService.save(premium)
            premiumCacheService.saveToSeconds(premium)

            runCatching {
                premiumCacheService.saveHistory(premium)
            }.onFailure { e ->
                log.warn("saveHistory failed (non-critical): {}", e.message)
            }

            eventPublisher.publishEvent(
                PremiumUpdatedEvent(symbol = premium.symbol, premiumRate = premium.premiumRate),
            )

            log.debug(
                "Calculated premium: {}% (Korea: {} KRW, Foreign: {} USDT = {} KRW)",
                premium.premiumRate,
                premium.koreaPrice,
                premium.foreignPrice,
                premium.foreignPriceInKrw,
            )

            JobResult.Success
        } catch (e: Exception) {
            log.error("Failed to calculate premium", e)
            JobResult.Failure(e)
        }
    }
}
```

- [ ] **Step 3: Update existing test — 셋업 수정 + 신규 케이스 2개**

기존 `apps/batch/src/test/kotlin/io/premiumspread/application/job/premium/PremiumRealtimeJobTest.kt`를 수정한다.

**3-a. import 추가** (파일 상단):
```kotlin
import io.premiumspread.application.notification.PremiumUpdatedEvent
import org.springframework.context.ApplicationEventPublisher
```

**3-b. 필드 추가** (다른 lateinit var들과 함께):
```kotlin
    private lateinit var eventPublisher: ApplicationEventPublisher
```

**3-c. setUp 수정** — `tickerCacheService = mockk()` 등 옆에 `eventPublisher = mockk(relaxed = true)`를 추가하고, `job = PremiumRealtimeJob(...)` 호출에 `eventPublisher = eventPublisher`를 추가:

```kotlin
    @BeforeEach
    fun setUp() {
        tickerCacheService = mockk()
        fxCacheService = mockk()
        premiumCacheService = mockk(relaxed = true)
        premiumCalculator = mockk()
        eventPublisher = mockk(relaxed = true)
        job = PremiumRealtimeJob(
            tickerCacheService = tickerCacheService,
            fxCacheService = fxCacheService,
            premiumCacheService = premiumCacheService,
            premiumCalculator = premiumCalculator,
            eventPublisher = eventPublisher,
        )
    }
```

**3-d. `Run` 클래스에 신규 테스트 2개 추가** (기존 `예외 발생 시 Failure를 반환한다` 테스트 아래에):

```kotlin
        @Test
        fun `성공 시 PremiumUpdatedEvent를 publish 한다`() {
            // given
            val bithumb = bithumbTicker()
            val binance = binanceTicker()
            val fxRate = BigDecimal("1432.6")
            val premium = premiumData()

            every { tickerCacheService.get("bithumb", "btc") } returns bithumb
            every { tickerCacheService.get("binance", "btc") } returns binance
            every { fxCacheService.getUsdKrw() } returns fxRate
            every { premiumCalculator.calculate(bithumb, binance, fxRate) } returns premium

            // when
            val result = job.run()

            // then
            assertThat(result).isEqualTo(JobResult.Success)
            verify(exactly = 1) {
                eventPublisher.publishEvent(match<PremiumUpdatedEvent> {
                    it.symbol == "btc" && it.premiumRate == BigDecimal("1.2800")
                })
            }
        }

        @Test
        fun `실패 시 PremiumUpdatedEvent를 publish 하지 않는다`() {
            // given
            every { tickerCacheService.get("bithumb", "btc") } throws RuntimeException("cache error")

            // when
            val result = job.run()

            // then
            assertThat(result).isInstanceOf(JobResult.Failure::class.java)
            verify(exactly = 0) { eventPublisher.publishEvent(any()) }
        }
```

- [ ] **Step 4: Run all batch tests**

Run: `./gradlew :apps:batch:test`
Expected: BUILD SUCCESSFUL, 모든 신규 + 기존 테스트 통과.

- [ ] **Step 5: Commit**

```bash
git add apps/batch/build.gradle.kts apps/batch/src/main/kotlin/io/premiumspread/application/job/premium/PremiumRealtimeJob.kt apps/batch/src/test/kotlin/io/premiumspread/application/job/premium/PremiumRealtimeJobTest.kt
git commit -m "feat: PremiumRealtimeJob — 성공 시 PremiumUpdatedEvent publish"
```

---

## Task 19: 설정 (yml, env, docker-compose)

**Files:**
- Modify: `apps/batch/src/main/resources/application-prd.yml`
- Modify: `.env.example`
- Modify: `docker/batch-compose.yml`

- [ ] **Step 1: Update application-prd.yml (batch)**

`apps/batch/src/main/resources/application-prd.yml` — 기존 파일 맨 아래에 다음 블록을 append (또는 적절한 위치에 병합):

```yaml
spring:
  mail:
    host: ${MAIL_HOST:smtp.gmail.com}
    port: ${MAIL_PORT:587}
    username: ${MAIL_USERNAME}
    password: ${MAIL_PASSWORD}
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true

alert:
  email:
    from: ${ALERT_EMAIL_FROM:${MAIL_USERNAME}}
```

⚠️ 기존 파일에 이미 `spring:` 루트가 있다면 그 아래 `mail:` 만 추가한다.

- [ ] **Step 2: Update .env.example**

`.env.example` — 맨 아래에 다음 블록 추가:
```
# ── Email (사용자 알림, batch만 필요) ──────────────────────
# Gmail SMTP 사용 시: https://myaccount.google.com/apppasswords 에서 앱 비밀번호 발급
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=your-gmail@gmail.com
MAIL_PASSWORD=your-gmail-app-password
ALERT_EMAIL_FROM=your-gmail@gmail.com
```

- [ ] **Step 3: Update docker/batch-compose.yml**

`docker/batch-compose.yml` — batch 컨테이너의 `environment:` 블록에 추가:
```yaml
      MAIL_HOST: ${MAIL_HOST:-smtp.gmail.com}
      MAIL_PORT: ${MAIL_PORT:-587}
      MAIL_USERNAME: ${MAIL_USERNAME}
      MAIL_PASSWORD: ${MAIL_PASSWORD}
      ALERT_EMAIL_FROM: ${ALERT_EMAIL_FROM}
```

- [ ] **Step 4: Validate batch boot in local (smoke)**

Run: `./gradlew :apps:batch:compileKotlin`
Expected: BUILD SUCCESSFUL. (실제 SMTP 실행은 사용자 환경에서 별도 확인)

- [ ] **Step 5: Commit**

```bash
git add apps/batch/src/main/resources/application-prd.yml .env.example docker/batch-compose.yml
git commit -m "chore: 알림 메일용 환경설정 추가 (spring.mail + alert.email.from)"
```

---

## Task 20: 전체 빌드 검증

**Files:** — 없음 (검증 단계)

- [ ] **Step 1: Run full build + all unit tests**

Run: `./gradlew clean build -x integrationTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run all integration tests**

Run: `./gradlew :apps:api:integrationTest :apps:batch:test --tests "*Repository*"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verification gate — final check**

다음 항목을 확인한다:
- [ ] `notification_subscription` 테이블이 생성되는 Flyway 마이그레이션이 V11에 존재 + `deleted_at` 컬럼 포함
- [ ] `NotificationSubscription` Entity는 변경 컬럼이 `protected set` + `change*()` 메서드 (새 인스턴스 생성 X)
- [ ] `EmailSender` 빈은 `alert.email.from` 설정 + `JavaMailSender` 빈이 있을 때만 등록
- [ ] `PremiumThresholdNotificationService`, `PremiumThresholdNotificationListener` 둘 다 `@ConditionalOnBean(EmailSender::class)` 가드
- [ ] `JavaMailEmailSender` 예외 → `EmailDeliveryException`으로 wrap
- [ ] `NotificationCooldownStore`는 `setIfAbsent`(NX) + `release` 사용 (체크-set 분리 패턴 없음)
- [ ] `apps/api`는 supports:email을 의존하지 않음
- [ ] `apps/batch`는 supports:email을 의존
- [ ] 운영 알람(monitoring) 코드는 손대지 않음 — `git diff dev --name-only` 결과에 `supports/monitoring`이 없는지 확인

- [ ] **Step 4: local 환경에서 boot 확인**

Run:
```bash
docker compose -f docker/infra-compose.yml up -d
SPRING_PROFILES_ACTIVE=local ./gradlew :apps:api:bootRun &
SPRING_PROFILES_ACTIVE=local ./gradlew :apps:batch:bootRun &
```
Expected: 두 앱 모두 정상 부팅. /actuator/health 200.

local profile은 `alert.email.from` 미설정 → EmailSender 빈 미등록 → `PremiumThresholdNotificationService`/`Listener` 둘 다 `@ConditionalOnBean(EmailSender::class)`로 가드되어 빈 등록 스킵 → 부팅 영향 없음 (Task 16/17에서 이미 가드 적용됨).

---

## Spec Coverage Check

| Spec 섹션 | 구현 Task |
|---|---|
| 1. 배경 / MVP 스코프 | 전체 |
| 2. 전체 흐름 (Async 격리) | Task 16, 17 |
| 3. 도메인 모델 / Migration | Task 5, 6, 7 |
| 4. 모듈/패키지 구조 | Task 1, 5–18 |
| 5. 핵심 코드 패턴 | Task 1–4 (Email), 16–18 (Event/Listener) |
| 6. API 엔드포인트 (CRUD) | Task 12 |
| 7. Email 포맷 | Task 16 (buildMessage) |
| 8. 설정 (yml, env, docker) | Task 19 |
| 9. 테스트 계획 | Task 3, 7, 9, 10, 12, 14, 15, 16, 17, 18 |
| 10. 미변경 영역 | Task 20 verification gate |
| 11. 후속 이슈 | — |
