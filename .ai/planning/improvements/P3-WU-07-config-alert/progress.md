# WU-07: 설정 강화 + AlertService 구현 — Progress

## 상태: 완료

## 완료 항목

### 1. SameSite strict 변경
- `apps/api/src/main/resources/application-prd.yml`: `same-site: lax` -> `same-site: strict`

### 2. 기본 프로필 제거
- `apps/api/src/main/resources/application.yml`: `spring.profiles.active: local` 제거
- 운영 환경에서 환경변수 `SPRING_PROFILES_ACTIVE`로 프로필 지정 필요

### 3. AlertService 인터페이스 분리
- `AlertService` -> interface로 변환 (기존 class에서)
- `LogAlertService`: 로그 기반 기본 구현 (local/test 환경)
- `SlackAlertService`: Slack Webhook 기반 구현 (prd 환경)
- `SlackAlertProperties`: `alert.slack.webhook-url` 설정 바인딩

### 4. MonitoringAutoConfiguration 수정
- `@ConditionalOnProperty("alert.slack.webhook-url")` -> SlackAlertService
- `@ConditionalOnMissingBean(AlertService)` -> LogAlertService (fallback)
- `@EnableConfigurationProperties(SlackAlertProperties::class)` 추가

### 5. 각 앱 prd 설정에 alert.slack 추가
- `apps/api/src/main/resources/application-prd.yml`: `alert.slack.webhook-url: ${SLACK_WEBHOOK_URL:}`
- `apps/batch/src/main/resources/application-prd.yml`: `alert.slack.webhook-url: ${SLACK_WEBHOOK_URL:}`

### 6. JobExecutor에 CRITICAL 알림 추가
- `JobResult.Failure` 반환 시 `alertService.sendCriticalAlert()` 호출
- `LockResult.Error` 발생 시 `alertService.sendCriticalAlert()` 호출

### 7. 테스트
- `LogAlertServiceTest`: 3개 테스트 (모든 severity 수준 검증)
- `SlackAlertServiceTest`: 4개 테스트 (Webhook 전송, 이모지, 실패 안전성)
- `JobExecutorTest`: 기존 7개 테스트 수정 (AlertService mock 추가, 알림 검증 추가)

### 8. 빌드 의존성
- `supports/monitoring/build.gradle.kts`: `spring-web`, `jackson-databind` compileOnly 추가
- 테스트용 MockWebServer 의존성 추가

## 미완료
- `.env.example` 파일이 현재 브랜치에 존재하지 않아 `SLACK_WEBHOOK_URL` 추가 생략
