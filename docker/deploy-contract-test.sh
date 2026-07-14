#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_compose="${root_dir}/docker/app-compose.yml"
workflow="${root_dir}/.github/workflows/deploy.yml"
deploy_script="${root_dir}/docker/deploy.sh"
monitoring_compose="${root_dir}/docker/monitoring-compose.yml"

fail() {
  echo "deploy contract failed: $*" >&2
  exit 1
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
grep -q 'github.sha' "${workflow}" || fail "workflow must publish the triggering commit SHA"
grep -q ':apps:api:integrationTest' "${workflow}" || fail "deploy verify must run API integration tests"
grep -q ':apps:batch:integrationTest' "${workflow}" || fail "deploy verify must run Batch integration tests"
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
fi

echo "deploy contracts verified"
