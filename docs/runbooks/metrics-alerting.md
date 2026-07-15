# Metric, alert, dashboard 운영 런북

## 수집 경계

Prometheus는 Docker 내부 network에서 API `api:9080/actuator/prometheus`, Batch
`batch:9081/actuator/prometheus`를 5초마다 scrape한다. management port는 host loopback에만 publish하고 nginx
public ingress로 전달하지 않는다. Grafana local profile은 개발 확인용이며 운영 dashboard/protection이 아니다.

## Dashboard 구성

운영 dashboard는 아래 네 구역을 한 화면에 둔다. panel과 alert query에는 bounded tag만 사용한다.

| 구역 | 주요 metric/panel |
|---|---|
| API/Dependency | readiness, `http.server.requests` rate/error/latency, DB/Redis health |
| Batch Jobs | `batch.job.run`, `batch.job.duration`, `batch.job.last.success.age`, `batch.job.lock` |
| Ingestion/Cache | `ws.connection.state`, `ws.last.message.age`, reconnect/parse/stale, `cache.read.total` |
| Notification | `premiumspread.notification.delivery.transitions`, FAILED/retry/stale ownership, operator alert drop |

현재 운영/스테이징 환경은 없으므로 repository에는 local Prometheus datasource만 있다. 실제 운영 dashboard UID,
folder, alert route와 screenshot/URL은 운영 환경 생성 후 change record에 남겨야 하며 이 문서만으로 외부 설정
완료를 주장하지 않는다.

## Metric과 bounded tag

| Metric | 허용 tag/해석 |
|---|---|
| `http.server.requests` | method/status/outcome/templated uri/exception |
| `cache.read.total` | cache, `hit|miss|corrupt|legacy_hit|error` |
| `batch.job.run` | configured job, `succeeded|skipped|failed` |
| `batch.job.duration` | configured job |
| `batch.job.last.success.age` | configured job, seconds |
| `batch.job.lock` | `acquired|not_acquired|renewed|ownership_lost|released|error` |
| `ws.connection.state` | exchange; connected 1/disconnected 0 |
| `ws.last.message.age` | exchange; seconds |
| `ws.reconnect.attempt`, `ws.first.message.timeout`, `ws.parse.error`, `ws.out_of_order`, `ws.stale` | exchange counter |
| `ticker.flush` | exchange/outcome |
| `fx.fetch`, `fx.fetch.retry`, `fx.fetch.latency` | provider/bounded outcome |
| `premium.calculation` | `success|skipped|invalid|failure` |
| `premiumspread.notification.delivery.transitions` | pending/processing/sent/retry/failed/duplicate/recovered/scrubbed/stale_ownership |
| `batch.operator.alert` | accepted/dropped/dispatched/failed |
| `aggregation.zone.info` | configured zone 정보 |

email, member ID, token, cookie, message, delivery/run/owner ID, exception message를 metric tag로 사용하지 않는다.
HTTP URI는 template을 사용하고 허용 가능한 조합 수를 넘으면 meter filter가 거절해야 한다.

## 초기 alert 정책

운영 환경 생성 시 실제 traffic baseline에 맞춰 숫자를 조정하되 의미와 대응 순서는 유지한다.

| Alert | 시작 조건 | 1차 대응 |
|---|---|---|
| API/Batch readiness down | 1분 연속 scrape 가능하지만 readiness 0 | DB/Redis, Batch는 이어서 ingestion 확인 |
| scrape absent | 1분간 target absent | container/management network/port 확인 |
| job stale | `last.success.age`가 schedule 허용 지연을 2회 초과 | lock, timeout, dependency, scheduler enabled 확인 |
| ingestion disconnected | `ws.connection.state=0` 1분 | reconnect/first-message/endpoint 확인 |
| ingestion stale | `ws.last.message.age`가 idle timeout 초과 또는 `ws.stale` 증가 | 거래소 stream과 watchdog 확인 |
| cache corrupt/error | 5분 증가량 > 0 | key/version/payload와 Redis 장애 확인; 부분 값 사용 금지 |
| delivery failed | `failed` 5분 증가량 > 0 | SMTP/network/credential 확인 후 수동 redrive 판단 |
| delivery recovered/stale ownership | 5분 증가량 > 0 | deadline/stale threshold/DB latency 확인 |
| alert dropped | `batch.operator.alert{outcome=dropped}` 증가 | alert queue saturation과 downstream Slack 확인 |

단일 `not_acquired` lock은 정상 중복 실행 억제일 수 있으므로 즉시 page하지 않는다. job stale과 반복 lock error가
함께 나타날 때 incident로 승격한다. retry counter 단독 증가도 일시 장애일 수 있으므로 FAILED/age와 함께 본다.

## Notification 장애 확인

Dashboard transition counter는 흐름을 보여 주지만 현재 queue depth의 정본은 DB다. 식별자/PII를 metric tag로
추가하지 말고 인증된 운영 계정으로 `notification_delivery`의 status/attempt/time만 조회한다. FAILED redrive와
PII 취급은 [`durable-notification-delivery.md`](durable-notification-delivery.md)를 따른다.

## 변경 절차

1. metric 이름/tag allowlist 변경과 test를 같은 commit에 포함한다.
2. Prometheus scrape/management network contract test를 실행한다.
3. dashboard query와 alert rule을 candidate SHA에 귀속해 review한다.
4. alert firing과 recovery를 non-production test target에서 확인한다.
5. 운영 설정 URL/screenshot/change ID를 결과 문서에 기록한다.
