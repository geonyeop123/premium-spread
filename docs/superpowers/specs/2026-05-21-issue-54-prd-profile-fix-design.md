# Spec — prd 프로파일 환경 정상화 (issue #54)

> 2026-05-21 · 브랜치 `fix/issue-54-prd-profile-fix` · base `dev`

## 배경 / 문제

이슈 #52(Binance bookTicker 전환) 검증 중 prd 프로파일 환경의 설정 결함 2건이 확인됨.
둘 다 #52와 무관한 기존 문제이나, WebSocket 수집이 운영에 정상 반영되려면 선결되어야 함.

### 문제 1 — logback `prd` 프로파일 부재 → 운영 로그 미출력

- 운영 컨테이너는 `SPRING_PROFILES_ACTIVE=prd`로 기동
  (`docker/app-compose.yml`, `docker/api-compose.yml`, `docker/batch-compose.yml` 확인 완료).
- `supports/logging/src/main/resources/logback-spring.xml`의 `<springProfile>` 블록은
  `local` / `dev` / `prod` / `default`만 정의 — **`prd`가 없음**.
- 결과: `prd` 기동 시 매칭되는 `<root>` appender가 없어 api·batch 운영 컨테이너가
  로그를 콘솔·파일 어디에도 출력하지 않음.

### 문제 2 — `application-prd.yml` 시세 수집 모드 미설정

- `apps/batch/src/main/resources/application-prd.yml`에
  `premium.ingestion.{binance,bithumb}.mode` 키가 없음.
- batch `application.yml` 기본값은 둘 다 `rest` → prd가 `rest`를 상속.
- 결과: #52(Binance bookTicker)·#31(Bithumb WebSocket)이 머지됐어도
  prd에서는 여전히 REST 폴링으로 수집.

## 검증된 사실

- `application-prod.yml` / `application-dev.yml` 같은 프로파일별 `application-*.yml` 파일은
  api·batch 어디에도 존재하지 않음. api·batch 모두 `application-prd.yml`만 보유.
- **`prod` 프로파일은 어디서도 활성화되지 않음** — 코드/설정 전체에서
  `on-profile: prod` / `SPRING_PROFILES_ACTIVE=prod` 0건 → logback `prod` 블록은 진짜 고아.
- **`dev` 프로파일은 실제 활성 프로파일** — `spring.config.activate.on-profile: dev`
  섹션이 `modules/jpa/src/main/resources/jpa.yml`, `modules/redis/src/main/resources/redis.yml`,
  `apps/api/src/main/resources/application.yml`에 존재. `application-dev.yml` 파일이
  없을 뿐 `dev`는 import된 공통 config에서 살아있음 → logback `dev` 블록 제거 금지.
- `qa` 프로파일도 위 3개 config 파일에 `on-profile: qa` 섹션이 있으나
  logback에는 `qa` 블록이 없음 (선재 결함, #54 스코프 밖 — 관찰 사항으로만 기록).
- 모드 소비처: `TickerIngestionJob`(`@Value`), `IngestionModeConfig`(검증),
  `BinanceWebSocketClient` / `BithumbWebSocketClient` / `*TickerIngestion` /
  `*FlushJob` / `*FlushScheduler`의 `@ConditionalOnProperty(havingValue = "websocket")`.
- `supports/logging`에는 logback 관련 테스트 없음.

## 목표 (스코프)

1. `logback-spring.xml`이 `prd` 프로파일을 인식하도록 정렬.
   - 운영 정책은 기존 `prod` 블록(`CONSOLE_JSON` + `ASYNC_FILE_JSON`)과 동일.
   - 미사용 `prod` 블록 → `prd`로 이름 변경 (rename).
   - `local` / `dev` / `default` 블록은 **모두 보존** — `dev`는 활성 프로파일이므로
     제거 시 dev 기동에서 동일한 무로깅 장애가 재발함.
2. batch `application-prd.yml`에
   `premium.ingestion.binance.mode: websocket` + `premium.ingestion.bithumb.mode: websocket` 추가.

## 비목표

- REST 폴링 코드 제거 (#32 별도).
- 로깅 아키텍처 재설계.
- `qa` 프로파일 logback 블록 추가 (선재 결함, 별도 이슈).
- DB 마이그레이션 / API 변경 (없음).
- `ASYNC_FILE` 등 appender 정의 정리 — 재설계 비목표에 따라 손대지 않음.

## 설계 결정

| 결정 | 근거 |
|------|------|
| `prod` 블록을 `name="prd"`로 rename (merge 대신) | `prod` 프로파일은 코드/설정 어디서도 활성화되지 않음(검증 완료) → 단순 rename이 가장 명확. `name="prod,prd"` 병합은 죽은 별칭을 남김. |
| `dev` `<springProfile>` 블록 **유지** | `dev`는 `jpa.yml`/`redis.yml`/`api application.yml`에 `on-profile: dev` 섹션이 있는 활성 프로파일. `application-dev.yml` 파일 부재는 미사용 증거가 아님. 삭제 시 dev 기동에서 무로깅 장애 재발 → 본 픽스가 막으려는 바로 그 문제. |
| `local` / `default` 블록 유지 | `local`은 실사용(application-local.yml), `default`는 프로파일 미지정 안전망. |
| appender 정의(`CONSOLE`/`FILE`/`ASYNC_FILE` 등) 유지 | "로깅 아키텍처 재설계"는 비목표. 블록 정리만 수행. |
| `qa` 프로파일 logback 블록 미추가 | `qa`도 활성 프로파일이나 logback 블록 부재 — 선재 결함. #54는 `prd` 정상화 스코프이므로 별도 이슈 대상. |

## 수용 기준

- [ ] logback에 `name="prd"` `<springProfile>` 존재, `CONSOLE_JSON` + `ASYNC_FILE_JSON` 참조.
- [ ] logback에 `name="prod"` `<springProfile>` 미존재.
- [ ] logback `local` / `dev` / `default` 블록 보존.
- [ ] XML well-formed.
- [ ] batch `application-prd.yml`에 binance/bithumb mode `websocket` 추가.
- [ ] `./gradlew :apps:batch:compileKotlin :apps:batch:test` 통과.

## 영향 범위

- 모듈: `supports/logging` (api·batch 공유), `apps/batch` (설정).
- API 변경: 없음 / DB 마이그레이션: 없음.
- 위험: logback은 api·batch 공유 → `local` 블록 보존으로 로컬 개발 로깅 회귀 방지.
</content>
