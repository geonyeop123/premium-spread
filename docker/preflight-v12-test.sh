#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/preflight-v12.sh"

classify_v12_state 1 100 false >/dev/null
classify_v12_state 0 0 true >/dev/null

set +e
classify_v12_state 0 1 true >/dev/null 2>&1
with_data_status=$?
classify_v12_state 0 0 false >/dev/null 2>&1
without_approval_status=$?
set -e

[[ "${with_data_status}" == "20" ]]
[[ "${without_approval_status}" == "21" ]]

echo "V12 preflight classification tests passed"
