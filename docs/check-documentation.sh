#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

authoritative_files=(
  AGENTS.md
  .ai/PROJECT_STATUS.md
  .ai/instructions.md
  .ai/architecture/ARCHITECTURE_DESIGN.md
  .ai/context/project-overview.md
  .ai/rules/architecture.md
  .ai/rules/http.md
  .ai/rules/batch.md
  .ai/rules/testing.md
  .ai/rules/naming.md
  .ai/rules/git.md
  docs/runbooks/configuration-profiles.md
  docs/runbooks/deployment.md
  docs/runbooks/durable-notification-delivery.md
  docs/runbooks/management-endpoints.md
  docs/runbooks/observability-readiness.md
  docs/runbooks/v12-migration.md
  docs/runbooks/redis-contract.md
  docs/runbooks/auth-security.md
  docs/runbooks/metrics-alerting.md
)

required_paths=(
  domain
  infrastructure/common
  infrastructure/api
  infrastructure/batch
  modules/jpa
  modules/redis
  supports/logging
  supports/monitoring
  supports/email
  architecture-tests
  infrastructure/common/src/main/resources/db/migration/V12__restructure_position_to_pair.sql
  infrastructure/common/src/main/resources/db/migration/V13__add_market_pair_to_premium_tables.sql
  infrastructure/common/src/main/resources/db/migration/V14__create_durable_notification_delivery.sql
  docker/preflight-v12.sh
  docker/deploy.sh
)

for file in "${authoritative_files[@]}"; do
  test -f "$file" || { echo "missing authoritative document: $file" >&2; exit 1; }
done

for path in "${required_paths[@]}"; do
  test -e "$path" || { echo "documented path does not exist: $path" >&2; exit 1; }
done

if rg -n -i '\b(TBD|TODO|FIXME|XXX)\b' "${authoritative_files[@]}"; then
  echo "unresolved documentation placeholder found" >&2
  exit 1
fi

if rg -n 'PremiumUpdatedEvent|PremiumThresholdNotificationListener|NotificationCooldownStore|apps/(api|batch)/src/main/kotlin/.*/(infrastructure|cache|repository|client)/|Facade.{0,20}(optional|생략 가능)' \
  "${authoritative_files[@]}"; then
  echo "stale architecture description found" >&2
  exit 1
fi

for file in "${authoritative_files[@]}"; do
  while IFS= read -r markdown_link; do
    target="${markdown_link#*](}"
    target="${target%)}"
    target="${target%%#*}"
    target="${target#<}"
    target="${target%>}"
    case "$target" in
      ''|http://*|https://*|mailto:*) continue ;;
    esac
    resolved="$(realpath -m "$(dirname "$file")/$target")"
    if [[ ! -e "$resolved" ]]; then
      echo "broken Markdown link: $file -> $target" >&2
      exit 1
    fi
  done < <(rg -o '\[[^]]+\]\([^)]+\)' "$file" || true)
done

echo "documentation check passed (${#authoritative_files[@]} files, ${#required_paths[@]} required paths)"
