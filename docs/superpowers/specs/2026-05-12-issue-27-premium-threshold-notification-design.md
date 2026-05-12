# 프리미엄 임계값 도달 이메일 알림 (issue #27)

작성일: 2026-05-12
브랜치: `feat/issue-27-premium-threshold-notification`
이슈: https://github.com/geonyeop123/premium-spread/issues/27

## 1. 배경 / 목적

회원이 관심 코인의 김치 프리미엄이 특정 임계값에 도달했을 때 자신의 이메일로 알림을 받을 수 있도록 한다. 이는 `supports/monitoring`의 운영자용 `AlertService`와는 별개의 사용자 비즈니스 알림 영역으로 분리한다.

### MVP 스코프

- 프리미엄 임계값(ABOVE / BELOW) 도달 시 이메일 발송
- 회원이 (symbol, direction, threshold) 조합으로 구독 등록/조회/수정/삭제 (Full CRUD)
- Email 채널 단일 (Gmail SMTP)
- 같은 구독은 60분 cooldown 동안 재발송 안 함

### 비스코프

- 포지션 손절/익절/청산 이벤트 알림 → 별도 이슈
- SMS / 푸시 / 슬랙 채널 → 별도 이슈
- 사용자별 cooldown 설정, 폭주 방지(rate limit/dedup 고도화) → 별도 이슈
- 운영자용 `AlertService`(현 Slack 기반)의 Email 전환 → 별도 이슈

## 2. 전체 흐름

```
[회원]
   │ ① POST /api/notifications/subscriptions
   ▼
[API] domain/notification ──► MySQL: notification_subscription
                                          ▲
                                          │ ② 활성 구독 + member.email JOIN (JdbcTemplate)
                                          │
[Batch] PremiumRealtimeJob ── 1초마다 ──► PremiumUpdatedEvent (ApplicationEvent)
                                          │
                                          ▼ @Async
                                  PremiumThresholdNotificationListener
                                          │
                                          ├─ cooldownStore.isInCooldown? → skip
                                          ├─ direction/threshold 매칭?
                                          ▼
                                    EmailSender.send(...)
                                          ▼ SMTP
                                       [Gmail]
                                          ▼
                                    [회원 이메일함]
```

### 격리 원칙

- **Async 리스너**(`ThreadPoolTaskExecutor`)로 잡 스레드와 분리
- 리스너 내부 try-catch로 어떤 예외도 외부로 던지지 않음
- EmailSender 내부에서도 SMTP 예외를 흡수 (로그만)
- → 알림 인프라 장애가 시세 수집/저장 잡에 절대 영향 주지 않음

## 3. 도메인 모델

### Entity

```kotlin
@Entity
@Table(
    name = "notification_subscription",
    indexes = [Index(columnList = "status,symbol")],
)
class NotificationSubscription private constructor(
    @Column(nullable = false)
    val memberId: Long,
    @Column(nullable = false, length = 20)
    val symbol: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    val direction: ThresholdDirection,
    @Column(nullable = false, precision = 10, scale = 4)
    val threshold: BigDecimal,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    val status: SubscriptionStatus,
) : BaseEntity()

enum class ThresholdDirection { ABOVE, BELOW }
enum class SubscriptionStatus { ACTIVE, INACTIVE }
```

- 모든 필드 `val`. 상태 변경은 기존 Member 패턴과 동일하게 새 인스턴스 생성 (또는 named factory).
- `lastTriggeredAt` 등 가변 상태는 Entity에 두지 않고 Redis cooldown 키로 별도 관리.

### Flyway Migration

기존 마지막 버전: `V10__add_fx_rate_to_premium_aggregation_tables.sql`. 신규: `V11`.

```sql
-- V11__create_notification_subscription.sql
CREATE TABLE notification_subscription (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id    BIGINT       NOT NULL,
    symbol       VARCHAR(20)  NOT NULL,
    direction    VARCHAR(10)  NOT NULL,    -- ABOVE | BELOW
    threshold    DECIMAL(10, 4) NOT NULL,  -- 단위: %
    status       VARCHAR(20)  NOT NULL,    -- ACTIVE | INACTIVE
    created_at   DATETIME     NOT NULL,
    updated_at   DATETIME     NOT NULL,
    INDEX idx_status_symbol (status, symbol),
    CONSTRAINT fk_subscription_member FOREIGN KEY (member_id) REFERENCES member(id)
);
```

## 4. 모듈/패키지 구조

```
apps/api/src/main/kotlin/io/premiumspread/
├── domain/notification/
│   ├── NotificationSubscription.kt
│   ├── NotificationSubscriptionRepository.kt     (interface)
│   ├── NotificationSubscriptionService.kt
│   ├── NotificationSubscriptionCommand.kt
│   ├── ThresholdDirection.kt
│   ├── SubscriptionStatus.kt
│   └── NotificationSubscriptionExceptions.kt
├── application/notification/
│   ├── NotificationSubscriptionFacade.kt
│   └── NotificationSubscriptionDtos.kt           (Criteria, Result)
├── infrastructure/notification/
│   └── NotificationSubscriptionRepositoryImpl.kt
└── interfaces/api/notification/
    ├── NotificationSubscriptionController.kt
    ├── NotificationSubscriptionRequest.kt
    └── NotificationSubscriptionResponse.kt
+ apps/api/src/main/resources/db/migration/V11__create_notification_subscription.sql

apps/batch/src/main/kotlin/io/premiumspread/
├── application/job/premium/
│   └── PremiumRealtimeJob.kt                     (수정: event publish 추가)
├── application/notification/
│   ├── PremiumUpdatedEvent.kt
│   ├── PremiumThresholdNotificationListener.kt
│   └── PremiumThresholdNotificationService.kt
├── repository/
│   └── ActiveSubscriptionReadRepository.kt       (JdbcTemplate, JOIN member)
├── cache/
│   └── NotificationCooldownStore.kt
└── config/
    └── NotificationAsyncConfig.kt                (@EnableAsync + ThreadPoolTaskExecutor)

supports/email/ (신규 모듈)
├── build.gradle.kts                              (spring-boot-starter-mail)
└── src/main/kotlin/io/premiumspread/email/
    ├── EmailSender.kt                            (interface)
    ├── EmailMessage.kt                           (data class)
    ├── JavaMailEmailSender.kt
    └── EmailAutoConfiguration.kt
```

추가 변경:
- `settings.gradle.kts`: `supports:email` 모듈 등록
- `apps/batch/build.gradle.kts`: `supports:email` 의존성 추가 (api는 이메일 발송 사용처가 없으므로 의존성 미추가)
- `apps/batch/src/main/resources/application-prd.yml`: `spring.mail.*`, `alert.email.from`
- `docker/batch-compose.yml`: `MAIL_USERNAME`, `MAIL_PASSWORD`, `ALERT_EMAIL_FROM` env (api 컨테이너에는 불필요)
- `.env.example`: 신규 변수 템플릿
- `http/api/notification.http`: API 샘플

## 5. 핵심 코드 패턴

### supports/email

```kotlin
interface EmailSender {
    fun send(message: EmailMessage)
}

data class EmailMessage(
    val to: String,
    val subject: String,
    val text: String,
)

class JavaMailEmailSender(
    private val mailSender: JavaMailSender,
    private val from: String,
) : EmailSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(message: EmailMessage) {
        try {
            mailSender.send(
                SimpleMailMessage().apply {
                    from = this@JavaMailEmailSender.from
                    setTo(message.to)
                    subject = message.subject
                    text = message.text
                },
            )
        } catch (e: Exception) {
            log.error("이메일 발송 실패 to={}: {}", message.to, e.message, e)
        }
    }
}
```

### 이벤트 publish (PremiumRealtimeJob 수정)

```kotlin
@Component
class PremiumRealtimeJob(
    private val tickerCacheService: TickerCacheService,
    private val fxCacheService: FxCacheService,
    private val premiumCacheService: PremiumCacheService,
    private val premiumCalculator: PremiumCalculator,
    private val eventPublisher: ApplicationEventPublisher,  // 신규
) {
    fun run(): JobResult = try {
        // ... 기존 로직 ...
        premiumCacheService.save(premium)
        premiumCacheService.saveToSeconds(premium)
        runCatching { premiumCacheService.saveHistory(premium) }.onFailure { /* 기존 */ }

        eventPublisher.publishEvent(
            PremiumUpdatedEvent(symbol = premium.symbol, premiumRate = premium.premiumRate),
        )

        JobResult.Success
    } catch (e: Exception) {
        log.error("Failed to calculate premium", e)
        JobResult.Failure(e)
    }
}

data class PremiumUpdatedEvent(
    val symbol: String,
    val premiumRate: BigDecimal,
)
```

### Async Listener

```kotlin
@Component
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
                "알림 처리 실패: symbol={}, rate={}: {}",
                event.symbol, event.premiumRate, e.message, e,
            )
        }
    }
}

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

### 매칭 + 발송 서비스

```kotlin
@Service
class PremiumThresholdNotificationService(
    private val readRepository: ActiveSubscriptionReadRepository,
    private val cooldownStore: NotificationCooldownStore,
    private val emailSender: EmailSender,
) {
    fun process(event: PremiumUpdatedEvent) {
        val subscriptions = readRepository.findActiveBySymbol(event.symbol)
        for (sub in subscriptions) {
            if (!sub.matches(event.premiumRate)) continue
            if (cooldownStore.isInCooldown(sub.id)) continue

            emailSender.send(
                EmailMessage(
                    to = sub.memberEmail,
                    subject = "[premium-spread] ${event.symbol.uppercase()} 프리미엄 ${event.premiumRate}% 도달",
                    text = renderBody(sub, event),
                ),
            )
            cooldownStore.markTriggered(sub.id)
        }
    }
}

data class ActiveSubscriptionView(
    val id: Long,
    val memberId: Long,
    val memberEmail: String,
    val memberNickname: String,
    val symbol: String,
    val direction: ThresholdDirection,
    val threshold: BigDecimal,
) {
    fun matches(rate: BigDecimal): Boolean = when (direction) {
        ThresholdDirection.ABOVE -> rate >= threshold
        ThresholdDirection.BELOW -> rate <= threshold
    }
}
```

### Cooldown

```kotlin
@Component
class NotificationCooldownStore(
    private val redisTemplate: StringRedisTemplate,
) {
    fun isInCooldown(subscriptionId: Long): Boolean =
        redisTemplate.hasKey(key(subscriptionId))

    fun markTriggered(subscriptionId: Long) {
        redisTemplate.opsForValue().set(key(subscriptionId), "1", COOLDOWN)
    }

    companion object {
        private val COOLDOWN: Duration = Duration.ofMinutes(60)
        private fun key(id: Long) = "notification:cooldown:$id"
    }
}
```

## 6. API 엔드포인트

| Method | Path | Body / Query | 200/201 응답 | 권한 |
|---|---|---|---|---|
| GET | `/api/notifications/subscriptions` | — | `SubscriptionResponse.Detail[]` (내 구독) | 인증 필수 |
| GET | `/api/notifications/subscriptions/{id}` | — | `SubscriptionResponse.Detail` | 인증 + 본인 |
| POST | `/api/notifications/subscriptions` | `{symbol, direction, threshold}` | `SubscriptionResponse.Detail` (201) | 인증 |
| PATCH | `/api/notifications/subscriptions/{id}` | `{status?, direction?, threshold?}` | `SubscriptionResponse.Detail` | 인증 + 본인 |
| DELETE | `/api/notifications/subscriptions/{id}` | — | 204 | 인증 + 본인 |

- 미인증 요청 → 401
- 유효성 실패 (빈 symbol, null threshold, 미허용 direction 등) → 400
- 존재하지 않거나 본인 소유가 아닌 구독 접근 → **404** (정보 노출 방지 목적, 403 대신 404 통일)

## 7. Email 포맷

- **제목**: `[premium-spread] BTC 프리미엄 5.20% 도달`
- **본문 (평문)**:
  ```
  안녕하세요, {nickname}님

  설정하신 알림 조건이 충족되었습니다.

  심볼: BTC
  조건: 5.00% 이상 (ABOVE)
  현재 프리미엄: 5.20%
  발생 시각: 2026-05-12 10:30:45

  --
  premium-spread
  ```

## 8. 설정 (운영 환경)

### `application-prd.yml` (batch)

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

### `.env.example` 추가 항목

```
# ── Email (사용자 알림) ──────────────────────────────────
# Gmail SMTP 앱 비밀번호 발급 후 입력
MAIL_USERNAME=your-gmail@gmail.com
MAIL_PASSWORD=your-app-password
ALERT_EMAIL_FROM=your-gmail@gmail.com
```

### docker-compose

`api-compose.yml`, `batch-compose.yml`의 `environment:` 섹션에 `MAIL_USERNAME`, `MAIL_PASSWORD`, `ALERT_EMAIL_FROM` 주입 라인 추가.

## 9. 테스트 계획

### Unit Tests (AssertJ + MockK)

**`PremiumThresholdNotificationService` (가장 중요)**

매칭 로직 경계값 테이블:
| direction | threshold | rate | 결과 |
|---|---|---|---|
| ABOVE | 5.00 | 5.20 | match |
| ABOVE | 5.00 | 5.00 | match (경계) |
| ABOVE | 5.00 | 4.99 | no match |
| BELOW | -2.00 | -2.50 | match |
| BELOW | -2.00 | -2.00 | match (경계) |
| BELOW | -2.00 | -1.99 | no match |

process 동작:
- 활성 구독 없음 → `emailSender.send` 0회
- 매칭 1건 → send 1회 + cooldown 마킹 1회
- 매칭 N건 → send N회
- 매칭 안 되는 구독 → 해당 구독은 send 미호출
- cooldown 중 → send 미호출, 마킹 미호출
- 발송 도중 1건 예외 → 다음 구독 계속 처리됨

**`PremiumThresholdNotificationListener`**
- 이벤트 수신 시 `service.process` 1회 호출
- service 예외 throw 시 외부 전파 안 됨

**`NotificationCooldownStore`**
- `markTriggered` 시 set + TTL 60min
- `isInCooldown`이 `hasKey` 결과 반환

**`NotificationSubscriptionService`**
- 생성/단건/목록/수정/삭제 정상
- 본인 검증 실패 → `SubscriptionAccessDeniedException`
- 존재하지 않는 ID → `SubscriptionNotFoundException`

**`JavaMailEmailSender`**
- `send` 호출 시 SimpleMailMessage with from/to/subject/text
- `JavaMailSender.send` 예외 시 외부 전파 안 함

**`PremiumRealtimeJob` (회귀 + 추가)**
- (회귀) 성공 시 cache save 동일
- (회귀) 누락 데이터 시 Skipped
- (신규) 성공 시 `eventPublisher.publishEvent` 1회, payload 일치
- (신규) 실패 시 publish 0회

### Controller Tests (`@WebMvcTest`)

- POST 201 / 유효성 실패 400
- GET 목록/단건 200, 타인 404
- PATCH 200, 타인 404
- DELETE 204, 타인 404
- 미인증 401

### Integration Tests (`@Tag("integration")`, Testcontainers)

- `NotificationSubscriptionRepositoryImpl`: 저장→조회→삭제 라운드트립
- `ActiveSubscriptionReadRepository`: 활성+JOIN 정상 / INACTIVE 미반환 / 심볼 필터

## 10. 영향 / 미변경 영역

**미변경**: `supports/monitoring/*`, `JobExecutor.kt`(운영 알람), member/premium/ticker/position 등 기존 도메인.

**변경**: 위 4. 모듈 구조 표 참조.

## 11. 후속 이슈 (이번 스코프 제외)

- 포지션 이벤트 알림
- 사용자별 cooldown 설정 / rate limit / dedup 고도화
- SMS / 푸시 / 슬랙 채널 추가
- 운영자용 AlertService Email 전환 (#26 재오픈 예정)
- E2E (MailHog) 통합 테스트
