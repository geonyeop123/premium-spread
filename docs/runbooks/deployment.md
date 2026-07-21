# Commit image host-local 배포·복구 런북

## 소유권

- Flyway 파일/adapter owner: `infrastructure:common`
- runtime migration owner: API 하나(`spring.flyway.enabled=true`)
- Batch: migration 금지(`spring.flyway.enabled=false`)
- 배포/rollback owner: validation/PRIVATE_LIVE host의 operator

V12는 destructive immutable 예외, V13은 premium MarketPair backfill/index, V14는 notification pair/revision과
durable delivery queue다. 배포 전에 `:infrastructure:common:verifyMigrations`와 V12 preflight를 실행한다.
migration 파일을 수정하거나 `flyway_schema_history`를 수동 repair해 배포를 통과시키지 않는다.

## 불변 배포 단위

운영 배포 단위는 Git branch나 서버 source tree가 아니라 green `Quality Gate artifact`가 가리키는 정확한 40자리
`github.sha`다. Quality Gate는 아래 세 이미지를 같은 commit tag로 build하되 registry로 push하지 않고
`docker-images-${{ github.sha }}` archive로 보존한다. 각 image의 `OCI revision` label도 같은 `github.sha`여야 한다.

```text
ghcr.io/<owner>/<repository>/api:<commit-sha>
ghcr.io/<owner>/<repository>/batch:<commit-sha>
ghcr.io/<owner>/<repository>/web:<commit-sha>
```

Quality Gate summary의 run ID, commit과 `artifact ID`를 함께 확인해 artifact 이름, image tag와 OCI revision이 같은
commit을 가리키는지 대조한다. operator는 검증된 archive를 확보해 load하고 host에서만 사용할 수 있는 registry
credential로 선택한 registry에 같은 SHA tag를 publish한다. 같은 commit의 compose와 `docker/deploy.sh`로 구성한
operator-owned 배포 bundle을 host에 배치한다. 서버에서 `git pull`, Gradle/npm build, mutable `latest` tag 사용을 금지한다.

실제 ReleaseCandidate는 green Quality Gate 중 정확히 `event=push`, `branch=dev`인 merged `dev` push run만 허용한다.
pull_request artifact는 배포 후보가 아니다. PR artifact는 변경 검토를 위한 review evidence로만 사용한다.

## Host secret source

runtime 값은 operator가 관리하는 `host secret source`에서 배포 직전에 환경변수로 주입한다. 저장소, Quality Gate
artifact, image 또는 배포 bundle에는 값을 기록하지 않는다. 구체적인 host 보관 수단은 환경 owner가 선택하며 Phase -1은
새 secret manager를 요구하거나 구성하지 않는다.

- registry: host-local `REGISTRY_USERNAME`, `REGISTRY_TOKEN`
- DB/Redis: `MYSQL_ROOT_PASSWORD`, `MYSQL_USER`, `MYSQL_PWD`, `REDIS_PASSWORD`
- 인증: `JWT_SECRET_KEY`, `JWT_ISSUER`, `JWT_AUDIENCE`, token expiry/clock skew,
  `AUTH_REFRESH_HMAC_KEY`, `AUTH_CORS_ALLOWED_ORIGINS`
- 외부 연동: `EXCHANGE_RATE_API_KEY`, 필요 시 exchange/SMTP/Slack credential

기능 flag인 `NOTIFICATION_EMAIL_ENABLED`, 일회성 승인인 `MIGRATION_V12_ALLOW_EMPTY`도 같은 host source에서
주입한다. V12 승인은 해당 배포가 끝나면 즉시 `false`로 되돌린다.
GitHub Actions에는 production 또는 exchange credential을 제공하지 않는다. host SSH와 runtime secret 전달은
Actions의 책임이 아니다.

## 배포 순서

`docker/deploy.sh`는 다음 순서를 강제한다.

1. MySQL/Redis health 확인과 정확한 SHA image pull
2. 기존 Batch 중지
3. V12 destructive migration preflight
4. API 단독 교체·기동: API가 Flyway migration을 수행
5. API readiness 성공 확인
6. Batch 교체·기동 및 Batch readiness 확인
7. Web/Nginx 교체
8. loopback API/Batch readiness와 public Nginx/Web ingress smoke
9. 성공 SHA를 권한 0600의 `.deploy/last-successful.env`에 원자 기록

API migration 또는 readiness가 실패하면 Batch target image는 시작하지 않는다. 어느 smoke라도
실패하면 이전 성공 SHA의 API, Batch, Web image를 순서대로 재기동하고 readiness를 다시 확인한다.
롤백도 실패하면 자동 재시도를 반복하지 말고 container log와 DB migration 상태를 보존한 채
incident로 전환한다.

## Rollback 제약

자동 rollback은 application image rollback이며 DB down migration을 수행하지 않는다. 모든 forward
migration은 최소 한 배포 동안 이전 application image와 호환되어야 한다. 호환되지 않는 migration은
별도 expand/contract 배포로 나눈다. 첫 배포처럼 이전 성공 SHA가 없는 경우 자동 rollback이 불가능하므로
사전에 한 개 이상의 검증 image를 pull하고 state를 확립해야 한다.

V12 cutover/backfill 도중 검증이 실패한 경우 application image rollback으로 해결하지 않는다. traffic을 닫은
상태를 유지하고 검증된 전체 DB backup으로 복원한 뒤 Flyway history와 row/hash/합계를 다시 대조한다. V13/V14
적용 뒤 image rollback은 이전 image가 추가 column/table을 무시하는 호환 범위에서만 허용한다.

상태 확인:

```bash
cat /home/ec2-user/premium-spread/.deploy/last-successful.env
docker inspect --format '{{.Config.Image}} {{.State.Health.Status}}' \
  premium-spread-api premium-spread-batch
curl --fail http://127.0.0.1:9080/actuator/health/readiness
curl --fail http://127.0.0.1:9081/actuator/health/readiness
```

## Local monitoring

`docker/monitoring-compose.yml`의 Grafana `admin/admin` fallback은 `local` profile이며
`127.0.0.1:3000`에만 bind된다.

```bash
docker compose -f docker/monitoring-compose.yml --profile local up -d
```

운영 Grafana는 이 profile을 사용하지 않는다. 별도 운영 monitoring stack에서 admin credential을
secret manager로 주입하고 public ingress 인증·TLS를 구성한다.
