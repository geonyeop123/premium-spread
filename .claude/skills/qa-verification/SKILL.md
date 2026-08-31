---
name: qa-verification
description: "QA 검증 스킬. 빌드·테스트·마이그레이션·문서 검사를 실제로 실행하고, 경계면(응답 shape↔DTO, Entity↔migration, http 샘플↔실제 endpoint) 교차 검증과 실측 보고를 가이드한다. 구현 완료 후 검증, 빌드 확인, 테스트 실행, 품질 점검, 완료 판정 시 반드시 이 스킬을 사용할 것."
---

# QA 검증

## 실행 순서

명령을 **실제로 돌린다.** 돌리지 않은 것을 통과로 적지 않는다.

```bash
./gradlew test architectureTest --offline --no-daemon
./gradlew :infrastructure:common:integrationTest --offline --no-daemon
./gradlew :apps:api:integrationTest --offline --no-daemon
./gradlew :apps:batch:integrationTest --offline --no-daemon
./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon
bash docs/check-documentation.sh
```

- `lint` 태스크는 이 저장소에 없다. 정적 경계는 `architectureTest`가 본다.
- integration 계열은 Docker가 필요하다. Docker가 없으면 `[ENV_ISSUE]`로 보고하고 **green으로 기록하지
  않는다.**
- 커버리지 게이트를 확인할 때는 `./gradlew jacocoTestReport jacocoTestCoverageVerification`을 쓰고,
  결과의 최종 증거는 해당 commit SHA의 CI artifact다.

## 점진 검증

전체 완성을 기다리지 않는다. 모듈이 하나 끝날 때마다 `./gradlew :{module}:test --offline --no-daemon`을
돌리고, 하위 모듈이 바뀌었으면 상위 모듈 테스트도 함께 돌린다.

## 경계면 교차 검증

테스트가 통과해도 경계는 어긋날 수 있다. 아래를 **양쪽 파일을 동시에 열어** 대조한다.

| 경계 | 확인 |
|------|------|
| Controller 반환 ↔ Response DTO | 필드 누락·타입 불일치·네이밍 불일치 |
| Request → Criteria → Command | 변환에서 조용히 떨어지는 필드 |
| Entity ↔ Flyway migration | 컬럼 타입·길이·nullable·인덱스 정합 |
| `http/api/{domain}.http` ↔ 실제 endpoint | method·path·payload가 실제와 같은지 |
| 공개 endpoint ↔ `PublicEndpointPolicy` | method+path 조합이 policy·security matcher·contract test에서 일치 |
| 캐시 payload ↔ `docs/runbooks/redis-contract.md` | key·TTL·payload 변경이 문서와 함께 갔는지 |
| MarketPair | 요청 pair와 응답/row pair가 같은지, non-default pair가 symbol-only로 흐르지 않는지 |
| 시간·범위 | Instant·UTC 버킷·`[from,to)` exclusive |

## 보고 형식

```markdown
## QA 보고서

### 실행 결과
- [PASS] ./gradlew test architectureTest --offline --no-daemon — 1,204 실행 / 실패 0 (2m 31s)
- [FAIL] ./gradlew :apps:api:integrationTest — 42 실행 / 실패 1
  - PremiumQueryIntegrationTest.pair_miss: expected MISS, got default-pair row
- [ENV_ISSUE] :apps:batch:integrationTest — Docker 데몬 미기동으로 미실행

### 경계면
- [MISMATCH] Position.entryPremium DECIMAL(18,8) ↔ V15 migration DECIMAL(18,6)

### 종합
빌드 PASS · 테스트 1 FAIL · 경계 1 MISMATCH · 미실행 1
```

수치는 실측값을 적는다. "통과"라는 단어만 적힌 보고는 증거가 아니다.

## 읽을 것

- `.ai/rules/testing.md` — 검증 범위와 실패 처리 규칙
- `test-strategy` 스킬 — 계층별 테스트 작성
