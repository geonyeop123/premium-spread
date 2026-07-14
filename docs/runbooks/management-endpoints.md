# Management Endpoint Policy

- 외부에서 인증 없이 접근 가능한 경로는 GET `/actuator/health/liveness`와
  `/actuator/health/readiness`뿐이다.
- `/actuator/health` 루트와 그 밖의 actuator 경로는 Security allowlist에 포함하지 않는다.
- Batch management는 컨테이너 내부 전용 9081 포트를 사용하고 host에 publish하지 않는다.
- health detail은 모든 프로필에서 `never`가 기본이다.
- Prometheus/metrics는 Phase 8에서 별도 management port와 내부 Docker/network ACL을 구성한 뒤에만
  노출한다. 그 전에는 public exposure 목록에서 제외한다.
- Phase 1 readiness는 Spring의 기본 readiness state를 제공한다. 필수 MySQL/Redis indicator를 readiness
  group에 포함하는 작업은 Phase 8에서 수행하며, liveness에는 외부 dependency를 포함하지 않는다.
