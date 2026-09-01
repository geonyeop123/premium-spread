# Runtime profile 역할

이 프로젝트는 `local`, `test`, `prd` 세 profile만 운영 계약으로 사용한다. 별도 staging은 없으며,
`prd`는 승인된 40자리 commit tag image와 `operator-controlled host secret source`를 사용하는 host-local 배포에서만
활성화한다.

| 항목 | `local` | `test` | `prd` |
|---|---|---|---|
| 용도 | 개발자 Docker infra | Testcontainers 격리 테스트 | 승인된 `github.sha` image |
| DB 접속 | localhost `application/application` 허용 | dynamic `spring.datasource.*` | `MYSQL_*` 필수, localhost/default credential 기동 차단 |
| Hibernate DDL | `create-drop` | `create-drop` | `validate` |
| Flyway owner | API | API 통합 테스트 | API만 migration, Batch는 비활성 |
| SQL 로그 | `show-sql=true`, `org.hibernate.SQL=DEBUG` | 동일 | `show-sql=false`, logger `WARN` |
| Redis | localhost 기본 secret 허용 | Testcontainers dynamic properties | `REDIS_*` 필수, localhost/빈 password 차단 |
| 인증/외부 secret | local 전용 기본값 허용 | deterministic test value | operator가 host에서 runtime 주입, default secret 기동 차단 |
| Scheduler | 필요 시 활성, `AGGREGATION_ZONE` 적용 | 기본 비활성 | 활성, 모든 aggregation cron에 명시 zone |
| 거래 준비 owner 허가 | `TRADE_PREPARATION_OWNER_EMAILS` 미주입 시 계획 생성이 전부 404 | 계약 테스트가 자기 fixture 회원을 property로 주입 | operator가 host에서 주입, 빈 목록이면 전원 거부 |
| Management port | API 9080 / Batch 9081, 기본 loopback | random/loopback 통합 테스트 가능 | 컨테이너 0.0.0.0 + host 127.0.0.1 고정 mapping |
| Actuator exposure | `health,prometheus` | `health,prometheus` | 동일, public nginx ingress에는 미노출 |
| 로그 형식 | text console | text console | masking된 JSON console/file |

## 변경 계약

- `prd` runtime 값은 저장소, image 또는 배포 bundle에 넣지 않고 operator-controlled host secret source에서
  `docker/deploy.sh` 실행 직전에 주입한다. CI는 이 값을 공급하거나 host에 전달하지 않는다.
- DataSource SSOT는 `spring.datasource`, Redis SSOT는 `spring.data.redis`다.
- Hikari는 `DB_POOL_*`, Redisson은 `REDIS_POOL_*`/`REDIS_*_TIMEOUT`으로 tuning하며 typed
  property의 범위·교차 검증을 통과해야 한다.
- Docker management port 9080/9081은 healthcheck·Prometheus·rollback smoke의 하나의 고정 계약이다.
  변경하려면 Compose mapping, healthcheck, Prometheus target, deploy smoke를 함께 변경해야 한다.
- 설정이 없거나 local fallback이 `prd`에 유입되면 요청 처리 전 startup에서 실패해야 한다.
- `TRADE_PREPARATION_OWNER_EMAILS`는 startup을 막지 않고 **런타임에 전원 거부**로 나타나는 유일한 설정이다.
  거절이 계획의 존재를 노출하지 않는 404여야 하므로 미설정과 비허가 회원을 응답으로 구분할 수 없다.
  형식·증상·대응은 [deployment.md](deployment.md)가 소유한다.
