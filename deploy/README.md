# DEPRECATED: legacy Oracle Cloud 배포 안내

이 문서와 `deploy/` 아래 script/template은 과거 서버 source 기반 배포의 참고 자료다. 현재 배포 절차로 사용하지 않는다.
서버에서 source를 clone/pull/build하거나 `deploy/setup-server.sh`, `deploy/deploy.sh`를 실행하지 않는다.

현재 정본은 다음 세 경로다.

- 배포·rollback 계약: [`docs/runbooks/deployment.md`](../docs/runbooks/deployment.md)
- host 준비 절차: [`docs/deploy/aws-setup.md`](../docs/deploy/aws-setup.md)
- host-local 실행 script: [`docker/deploy.sh`](../docker/deploy.sh)

operator는 green Quality Gate artifact와 commit provenance를 확인하고, host secret source에서 runtime 값을 주입한 뒤
정본 `docker/deploy.sh`를 직접 실행한다. legacy script와 nginx template은 역사적 호환을 위해 남아 있을 뿐 활성 배포
경로나 production credential 전달을 승인하지 않는다.
