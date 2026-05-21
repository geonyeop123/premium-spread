# Plan — prd 프로파일 환경 정상화 (issue #54)

> 2026-05-21 · `fix/issue-54-prd-profile-fix` · base `dev`
> Spec: `docs/superpowers/specs/2026-05-21-issue-54-prd-profile-fix-design.md`

## Task 1 — logback `prd` 프로파일 정렬

**파일:** `supports/logging/src/main/resources/logback-spring.xml`

**변경 1 — `prod` 블록을 `prd`로 이름 변경**

기존(L100-106):
```xml
    <!-- prod 프로파일: JSON 로깅 (ELK 연동) -->
    <springProfile name="prod">
        <root level="INFO">
            <appender-ref ref="CONSOLE_JSON"/>
            <appender-ref ref="ASYNC_FILE_JSON"/>
        </root>
    </springProfile>
```

변경 후:
```xml
    <!-- prd 프로파일: JSON 로깅 (ELK 연동, 운영 컨테이너 SPRING_PROFILES_ACTIVE=prd) -->
    <springProfile name="prd">
        <root level="INFO">
            <appender-ref ref="CONSOLE_JSON"/>
            <appender-ref ref="ASYNC_FILE_JSON"/>
        </root>
    </springProfile>
```

**변경 범위:** `prod` → `prd` rename 단 한 곳. `dev` / `local` / `default` 블록은
그대로 유지한다. (`dev`는 `jpa.yml`/`redis.yml`/`api application.yml`에
`on-profile: dev` 섹션이 있는 활성 프로파일 — 삭제 시 dev 기동에서 무로깅 장애 재발.)

**검증:**
- XML well-formed: `python3 -c "import xml.dom.minidom; xml.dom.minidom.parse('supports/logging/src/main/resources/logback-spring.xml')"`.
- 결과 파일에 `name="prd"` 1건, `name="local"` 1건, `name="dev"` 1건, `name="default"` 1건.
- `name="prod"` 0건.

## Task 2 — batch `application-prd.yml`에 ingestion 모드 추가

**파일:** `apps/batch/src/main/resources/application-prd.yml`

`logging:` 블록 앞에 추가:
```yaml
# 시세 수집 모드 — 운영은 WebSocket 실시간 수집 (#52 Binance bookTicker, #31 Bithumb WebSocket)
premium:
  ingestion:
    binance:
      mode: websocket
    bithumb:
      mode: websocket
```

**검증:**
- YAML 파싱 정상.
- `premium.ingestion.binance.mode == websocket`, `premium.ingestion.bithumb.mode == websocket`.

## Task 2.5 — TickerIngestionJob no-op 경로를 Skipped로 정정 (codex 코드리뷰 반영)

**파일:** `apps/batch/src/main/kotlin/io/premiumspread/application/job/ticker/TickerIngestionJob.kt`

`run()` 시작부에 가드 추가: `bithumbMode != "rest" && binanceMode != "rest"`이면
`JobResult.Skipped("no_rest_sources")` 반환. 양쪽 websocket(=prd)일 때 no-op인데도
`Success`를 반환하면 `JobExecutor`가 `batch:last_run:ticker`/`scheduler.ticker.success`를
갱신해 WebSocket 수집 장애를 가린다.

**검증:** `TickerIngestionJobTest` / `TickerIngestionJobModeTest`에
양쪽 websocket → `Skipped`, 한쪽만 websocket → `Success` 케이스 추가/갱신.

## Task 3 — 빌드/테스트

```bash
./gradlew :apps:batch:compileKotlin :apps:batch:test
```

예상: BUILD SUCCESSFUL. logback은 컴파일 대상 아님 — XML 검증은 Task 1에서 수행.

## 체크리스트

- [x] Task 1 — logback `prod` → `prd` rename (`dev`/`local`/`default` 보존)
- [x] Task 2 — `application-prd.yml` ingestion 모드 추가
- [x] Task 2.5 — `TickerIngestionJob` no-op 경로 Skipped 정정 (codex 리뷰 반영)
- [x] Task 3 — 빌드/테스트 통과
