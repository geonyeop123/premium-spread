#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_compose="${root_dir}/docker/app-compose.yml"
workflow="${root_dir}/.github/workflows/deploy.yml"
quality_workflow="${root_dir}/.github/workflows/quality-gate.yml"
deploy_script="${root_dir}/docker/deploy.sh"
monitoring_compose="${root_dir}/docker/monitoring-compose.yml"

fail() {
  echo "deploy contract failed: $*" >&2
  exit 1
}

quality_job_block() {
  local job="$1"
  awk -v job="${job}" '
    $0 == "  " job ":" { inside = 1 }
    inside && $0 ~ /^  [a-z0-9-]+:$/ && $0 != "  " job ":" { exit }
    inside { print }
  ' "${quality_workflow}"
}

deploy_job_block() {
  local job="$1"
  awk -v job="${job}" '
    $0 == "  " job ":" { inside = 1 }
    inside && $0 ~ /^  [a-z0-9-]+:$/ && $0 != "  " job ":" { exit }
    inside { print }
  ' "${workflow}"
}

bash -n "${deploy_script}"

[[ "$(grep -c 'image:.*DEPLOY_SHA' "${app_compose}")" -eq 3 ]] ||
  fail "api, batch and web images must use DEPLOY_SHA"
[[ "$(grep -c '/actuator/health/readiness' "${app_compose}")" -ge 2 ]] ||
  fail "api and batch readiness healthchecks are required"
grep -q 'localhost:9080/actuator/health/readiness' "${app_compose}" ||
  fail "API healthcheck must use its dedicated management port"
grep -q '127.0.0.1:9080:9080' "${app_compose}" ||
  fail "API management port must be loopback-bound on the host"
grep -q 'MANAGEMENT_PORT: 9080' "${app_compose}" ||
  fail "API management port must be a fixed container contract"
grep -q 'MANAGEMENT_PORT: 9081' "${app_compose}" ||
  fail "Batch management port must be a fixed container contract"
if grep -q 'API_MANAGEMENT_PORT\|BATCH_MANAGEMENT_PORT' "${app_compose}"; then
  fail "management port overrides would desynchronize healthchecks and deploy smoke"
fi
grep -q 'condition: service_healthy' "${app_compose}" ||
  fail "batch/nginx must wait for API health"

if grep -Eq 'git pull|docker compose .*--build' "${workflow}" "${deploy_script}"; then
  fail "deployment must not pull source or build on the server"
fi
api_job="$(quality_job_block api-integration)"
batch_job="$(quality_job_block batch-integration)"
docker_job="$(quality_job_block docker-build)"
publish_job="$(deploy_job_block publish-images)"
grep -Fq 'run: ./gradlew :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest --dependency-verification strict --no-daemon' <<< "${api_job}" ||
  fail "Quality Gate API job must run exact strict API/common integration tasks"
grep -Fq 'run: ./gradlew :apps:batch:integrationTest --dependency-verification strict --no-daemon' <<< "${batch_job}" ||
  fail "Quality Gate Batch job must run exact strict Batch integration task"

grep -q 'workflow_run:' "${workflow}" || fail "deploy must be triggered by a completed Quality Gate"
grep -q "github.event.workflow_run.conclusion == 'success'" <<< "${publish_job}" || fail "deploy requires a successful gate"
grep -q "github.event.workflow_run.head_branch == 'main'" <<< "${publish_job}" || fail "deploy accepts main only"
grep -q "github.event.workflow_run.event == 'push'" <<< "${publish_job}" || fail "deploy accepts protected main push only"
grep -Fq 'DEPLOY_SHA: ${{ github.event.workflow_run.head_sha }}' "${workflow}" || fail "deploy must use the exact verified SHA"
if grep -q 'DEPLOY_SHA:.*||' "${workflow}"; then
  fail "deploy SHA fallback is forbidden"
fi
grep -Fq 'name: docker-images-${{ env.TARGET_SHA }}' <<< "${docker_job}" || fail "Quality Gate must publish candidate image archives"
grep -Fq 'name: docker-images-${{ env.DEPLOY_SHA }}' <<< "${publish_job}" || fail "deploy must select the exact candidate artifact"
grep -Fq 'run-id: ${{ github.event.workflow_run.id }}' <<< "${publish_job}" || fail "deploy must download from the successful gate run"
grep -q 'docker load --input' <<< "${publish_job}" || fail "deploy must load verified archives"
grep -q 'docker push "${image}"' <<< "${publish_job}" || fail "deploy must promote loaded images"
if rg -n 'docker/build-push-action|docker build|setup-buildx-action' "${workflow}"; then
  fail "deploy must not rebuild Quality Gate images"
fi
grep -q 'rollback' "${deploy_script}" || fail "automatic rollback is required"
grep -q 'wait_healthy api' "${deploy_script}" || fail "API readiness gate is required"
grep -q 'wait_http http://127.0.0.1/ "public ingress"' "${deploy_script}" ||
  fail "public ingress smoke is required"

batch_stop_line="$(grep -n '^compose_app stop batch' "${deploy_script}" | cut -d: -f1)"
preflight_line="$(grep -n '^bash .*preflight-v12' "${deploy_script}" | cut -d: -f1)"
api_ready_line="$(grep -n '^wait_healthy api' "${deploy_script}" | tail -n 1 | cut -d: -f1)"
batch_start_line="$(grep -n '^compose_app up .* batch$' "${deploy_script}" | tail -n 1 | cut -d: -f1)"
(( batch_stop_line < preflight_line && preflight_line < api_ready_line && api_ready_line < batch_start_line )) ||
  fail "batch must stay stopped through preflight, migration and API readiness"

grep -q 'profiles: \["local"\]' "${monitoring_compose}" ||
  fail "Grafana admin fallback must be local profile only"
grep -q '127.0.0.1:3000:3000' "${monitoring_compose}" ||
  fail "local Grafana must bind loopback only"
grep -Fq 'image: prom/prometheus:v3.5.5@sha256:332c2f43e7e389d74d3893b55bb02fbbd684208e681eeb604641d5d769c0fe2a' \
  "${monitoring_compose}" || fail "Prometheus must use the reviewed security-fixed tag and immutable digest"
grep -Fq 'image: grafana/grafana:13.1.0@sha256:121a7a9ece6dc10b969f1f96eed64b4f07dfac0d0b8abc070f7cb83bbde86f63' \
  "${monitoring_compose}" || fail "Grafana must use the reviewed tag and immutable digest"
grep -q "targets: \['api:9080'\]" "${root_dir}/docker/grafana/prometheus.yml" ||
  fail "Prometheus must scrape the API management port"
grep -q "targets: \['batch:9081'\]" "${root_dir}/docker/grafana/prometheus.yml" ||
  fail "Prometheus must scrape the Batch management port"

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  IMAGE_REGISTRY=ghcr.io \
  IMAGE_NAMESPACE=example/premium-spread \
  DEPLOY_SHA=0123456789abcdef0123456789abcdef01234567 \
  MYSQL_USER=test MYSQL_PWD=test REDIS_PASSWORD=test \
  JWT_SECRET_KEY=test JWT_ISSUER=test JWT_AUDIENCE=test \
  JWT_ACCESS_TOKEN_EXPIRY_MS=1 JWT_REFRESH_TOKEN_EXPIRY_MS=1 JWT_CLOCK_SKEW_SECONDS=1 \
  AUTH_REFRESH_HMAC_KEY=test AUTH_CORS_ALLOWED_ORIGINS=https://example.com \
  EXCHANGE_RATE_API_KEY=test \
    docker compose -f "${app_compose}" config --quiet
  docker compose -f "${root_dir}/docker/infra-compose.yml" config --quiet
  docker compose -f "${monitoring_compose}" config --quiet
fi

echo "deploy contracts verified"
