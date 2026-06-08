#!/usr/bin/env bash
# Premium Spread — 배포 (인프라 → 앱 빌드/기동 → 헬스체크)
# 레포 루트에 .env 가 있어야 한다 (cp deploy/.env.prd.example .env 후 값 채움).
# 사용:  ./deploy/deploy.sh           # 기존 코드로 빌드/기동
#        ./deploy/deploy.sh --pull    # git pull 후 빌드/기동
#
# ARM64 참고: 앱 이미지는 인스턴스에서 직접 build 되므로(arm64 네이티브) --platform 불필요.
set -euo pipefail

cd "$(dirname "$0")/.."   # 레포 루트
log() { printf '\n\033[1;32m[deploy]\033[0m %s\n' "$*"; }
err() { printf '\n\033[1;31m[deploy]\033[0m %s\n' "$*" >&2; }

[ -f .env ] || { err ".env 없음 → cp deploy/.env.prd.example .env 후 값을 채우세요"; exit 1; }

if [ "${1:-}" = "--pull" ]; then
  log "git pull"
  git pull --ff-only
fi

INFRA="docker compose -f docker/infra-compose.yml"
APP="docker compose -f docker/app-compose.yml"

# ── 1. 인프라 (MySQL + Redis master/replica) ─────────────────
log "인프라 기동"
$INFRA up -d

wait_healthy() {  # $1=container  $2=timeout초
  local name=$1 timeout=${2:-120} waited=0
  log "헬스 대기: $name"
  until [ "$(docker inspect -f '{{.State.Health.Status}}' "$name" 2>/dev/null || echo none)" = "healthy" ]; do
    sleep 3; waited=$((waited+3))
    [ "$waited" -ge "$timeout" ] && { err "$name 헬스 타임아웃(${timeout}s)"; docker logs --tail 30 "$name" || true; exit 1; }
  done
  log "$name healthy"
}
wait_healthy premium-spread-mysql 120
wait_healthy redis-master 60

# ── 2. 앱 빌드 + 기동 (api가 Flyway로 스키마 생성, batch는 그 후 자동 복구) ──
log "앱 빌드 + 기동 (최초 빌드는 ARM 1vCPU에서 수 분 소요)"
$APP up -d --build

# ── 3. API 헬스체크 (Flyway 마이그레이션 + 부팅 완료 확인) ────
log "API 헬스 대기 (Flyway 마이그레이션 포함)"
waited=0
until curl -fsS http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do
  sleep 5; waited=$((waited+5))
  if [ "$waited" -ge 180 ]; then
    err "API 헬스 타임아웃 — 로그 확인:"; docker logs --tail 50 premium-spread-api || true; exit 1
  fi
done
log "API UP"

# ── 4. 상태 요약 ────────────────────────────────────────────
log "컨테이너 상태"
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
log "배포 완료 → http://<PUBLIC_IP>/  (SSL은 deploy/README.md 5단계 참조)"
log "batch가 잠시 restarting이면 정상 — api Flyway 완료 후 자동 복구됩니다."
