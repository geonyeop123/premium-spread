#!/usr/bin/env bash
set -Eeuo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_compose="${APP_COMPOSE_FILE:-${root_dir}/docker/app-compose.yml}"
infra_compose="${INFRA_COMPOSE_FILE:-${root_dir}/docker/infra-compose.yml}"
state_dir="${DEPLOY_STATE_DIR:-${root_dir}/.deploy}"
state_file="${state_dir}/last-successful.env"
health_timeout_seconds="${DEPLOY_HEALTH_TIMEOUT_SECONDS:-180}"

required_environment=(
  DEPLOY_SHA IMAGE_REGISTRY IMAGE_NAMESPACE
  MYSQL_ROOT_PASSWORD MYSQL_USER MYSQL_PWD REDIS_PASSWORD
  JWT_SECRET_KEY JWT_ISSUER JWT_AUDIENCE
  JWT_ACCESS_TOKEN_EXPIRY_MS JWT_REFRESH_TOKEN_EXPIRY_MS JWT_CLOCK_SKEW_SECONDS
  AUTH_REFRESH_HMAC_KEY AUTH_CORS_ALLOWED_ORIGINS
  EXCHANGE_RATE_API_KEY
)

for name in "${required_environment[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "deployment environment is missing required value: ${name}" >&2
    exit 2
  fi
done

if [[ ! "${DEPLOY_SHA}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "DEPLOY_SHA must be the exact 40-character commit SHA" >&2
  exit 2
fi

compose_app() {
  docker compose -f "${app_compose}" "$@"
}

wait_healthy() {
  local service="$1"
  local deadline=$((SECONDS + health_timeout_seconds))
  local container_id status
  while (( SECONDS < deadline )); do
    container_id="$(compose_app ps -q "${service}")"
    if [[ -n "${container_id}" ]]; then
      status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}")"
      case "${status}" in
        healthy) return 0 ;;
        unhealthy|exited|dead)
          compose_app logs --tail=200 "${service}" >&2 || true
          return 1
          ;;
      esac
    fi
    sleep 2
  done
  compose_app logs --tail=200 "${service}" >&2 || true
  echo "${service} did not become healthy within ${health_timeout_seconds}s" >&2
  return 1
}

wait_http() {
  local url="$1"
  local label="$2"
  local deadline=$((SECONDS + health_timeout_seconds))
  while (( SECONDS < deadline )); do
    if curl --fail --silent --show-error "${url}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "${label} smoke failed: ${url}" >&2
  return 1
}

detect_previous_sha() {
  if [[ -f "${state_file}" ]]; then
    sed -n 's/^DEPLOY_SHA=//p' "${state_file}" | head -n 1
    return
  fi
  local image
  image="$(docker inspect --format '{{.Config.Image}}' premium-spread-api 2>/dev/null || true)"
  local tag="${image##*:}"
  if [[ "${tag}" =~ ^[0-9a-f]{40}$ ]]; then
    printf '%s\n' "${tag}"
  fi
}

previous_sha="$(detect_previous_sha)"
target_sha="${DEPLOY_SHA}"

rollback() {
  local failed_exit="$1"
  trap - ERR
  if [[ -z "${previous_sha}" || "${previous_sha}" == "${target_sha}" ]]; then
    echo "deployment failed and no previous image SHA is available for automatic rollback" >&2
    exit "${failed_exit}"
  fi

  export DEPLOY_SHA="${previous_sha}"
  echo "deployment failed; rolling back application images to ${previous_sha}" >&2
  compose_app up -d --no-deps --force-recreate --pull never api
  wait_healthy api
  compose_app up -d --no-deps --force-recreate --pull never batch
  wait_healthy batch
  compose_app up -d --no-deps --force-recreate --pull never web
  compose_app up -d --force-recreate --pull never nginx
  wait_http http://127.0.0.1:9080/actuator/health/readiness "rollback API readiness"
  wait_http http://127.0.0.1:9081/actuator/health/readiness "rollback Batch readiness"
  wait_http http://127.0.0.1/ "rollback public ingress"
  echo "rollback to ${previous_sha} completed" >&2
  exit "${failed_exit}"
}

trap 'rollback "$?"' ERR

docker compose -f "${infra_compose}" up -d --wait mysql redis-master redis-readonly

export DEPLOY_SHA="${target_sha}"
compose_app pull api batch web

# destructive migration preflight와 실제 Flyway migration 동안 기존 Batch도 DB를 사용하지 않게 한다.
compose_app stop batch
bash "${root_dir}/docker/preflight-v12.sh"

# API boot가 Flyway migration을 소유한다. API readiness가 확인되기 전에는 Batch를 시작하지 않는다.
compose_app up -d --no-deps --force-recreate api
wait_healthy api
compose_app up -d --no-deps --force-recreate batch
wait_healthy batch
compose_app up -d --no-deps --force-recreate web
compose_app up -d --force-recreate nginx

wait_http http://127.0.0.1:9080/actuator/health/readiness "API readiness"
wait_http http://127.0.0.1:9081/actuator/health/readiness "Batch readiness"
wait_http http://127.0.0.1/ "public ingress"

mkdir -p "${state_dir}"
umask 077
printf 'DEPLOY_SHA=%s\n' "${target_sha}" > "${state_file}.tmp"
mv "${state_file}.tmp" "${state_file}"
trap - ERR
echo "deployment ${target_sha} completed"
