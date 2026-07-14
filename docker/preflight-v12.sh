#!/usr/bin/env bash
set -euo pipefail

mysql_query() {
  docker exec -e MYSQL_PWD="${mysql_password}" "${mysql_container}" \
    mysql --batch --skip-column-names -u"${mysql_user}" "${mysql_database}" -e "$1"
}

container_env() {
  local key="$1"
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "${mysql_container}" |
    awk -F= -v key="${key}" '$1 == key {sub(/^[^=]*=/, ""); print; exit}'
}

classify_v12_state() {
  local v12_applied="$1"
  local position_rows="$2"
  local allow_empty="$3"

  if [[ "${v12_applied}" == "1" ]]; then
    echo "V12 preflight: APPLIED"
    return 0
  fi

  if (( position_rows > 0 )); then
    echo "V12 preflight: PENDING_WITH_DATA (${position_rows} position rows); deployment blocked" >&2
    return 20
  fi

  if [[ "${allow_empty}" != "true" ]]; then
    echo "V12 preflight: PENDING_EMPTY; set one-time MIGRATION_V12_ALLOW_EMPTY=true after approval" >&2
    return 21
  fi

  echo "V12 preflight: PENDING_EMPTY approved for this deployment"
}

main() {
  mysql_container="${MYSQL_CONTAINER:-premium-spread-mysql}"
  mysql_database="$(container_env MYSQL_DATABASE)"
  mysql_user="$(container_env MYSQL_USER)"
  mysql_password="$(container_env MYSQL_PASSWORD)"
  allow_empty="${MIGRATION_V12_ALLOW_EMPTY:-false}"

  : "${mysql_database:?MYSQL_DATABASE is missing from the MySQL container}"
  : "${mysql_user:?MYSQL_USER is missing from the MySQL container}"
  : "${mysql_password:?MYSQL_PASSWORD is missing from the MySQL container}"

  local v12_applied=0
  local flyway_table_exists
  flyway_table_exists="$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history';")"
  if [[ "${flyway_table_exists}" == "1" ]]; then
    v12_applied="$(mysql_query "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '12' AND success = 1;")"
  fi

  local position_rows=0
  local position_table_exists
  position_table_exists="$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'position';")"
  if [[ "${position_table_exists}" == "1" ]]; then
    position_rows="$(mysql_query "SELECT COUNT(*) FROM \`position\`;")"
  fi

  classify_v12_state "${v12_applied}" "${position_rows}" "${allow_empty}"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
