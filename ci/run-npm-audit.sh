#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
suppression_file="${root_dir}/ci/npm-audit-suppressions.json"

node - "${suppression_file}" <<'NODE'
const fs = require('fs');
const file = process.argv[2];
const document = JSON.parse(fs.readFileSync(file, 'utf8'));
if (!Array.isArray(document.suppressions)) {
  throw new Error('npm audit suppression manifest must contain a suppressions array');
}
if (document.suppressions.length !== 0) {
  throw new Error('npm audit suppressions require an implemented advisory-filter gate; unreviewed suppression is forbidden');
}
NODE

npm --prefix "${root_dir}/apps/web" audit --audit-level=high
