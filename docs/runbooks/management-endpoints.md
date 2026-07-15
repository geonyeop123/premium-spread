# Management Endpoint Policy

- management network에서 인증 없이 접근 가능한 경로는 GET
  `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus`다.
- 위 경로는 host loopback/Docker 내부 network에서만 접근하며 nginx public ingress에는 노출하지 않는다.
- `/actuator/health` 루트와 그 밖의 actuator 경로는 Security allowlist에 포함하지 않는다.
- API/Batch management는 각각 9080/9081을 사용한다. Docker 내부에서는 `0.0.0.0`으로
  bind하되 host publish는 `127.0.0.1`로 제한해 public ingress와 분리한다.
- health detail은 모든 프로필에서 `never`가 기본이다.
- Prometheus/metrics는 별도 management port와 내부 Docker network에서만 수집하며
  nginx public ingress에는 노출하지 않는다.
- readiness는 API의 MySQL/Redis, Batch의 MySQL/Redis/필수 ingestion을 포함하며,
  liveness에는 외부 dependency를 포함하지 않는다.
