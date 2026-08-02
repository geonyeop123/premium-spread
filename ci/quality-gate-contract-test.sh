#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
quality_workflow="${root_dir}/.github/workflows/quality-gate.yml"
deploy_workflow="${root_dir}/.github/workflows/deploy.yml"
tool_lock="${root_dir}/ci/quality-tools.lock"

fail() {
  echo "quality gate contract failed: $*" >&2
  exit 1
}

workflow_job_block() {
  local job="$1"
  awk -v job="${job}" '
    $0 == "  " job ":" { inside = 1 }
    inside && $0 ~ /^  [^[:space:]#][^:]*:$/ && $0 != "  " job ":" { exit }
    inside { print }
  ' "${quality_workflow}"
}

yaml_top_level_block() {
  local key="$1"
  awk -v key="${key}" '
    $0 == key ":" { inside = 1; next }
    inside && $0 ~ /^[^[:space:]]/ { exit }
    inside { print }
  ' "${quality_workflow}"
}

workflow_step_block() {
  local step="$1"
  awk -v step="${step}" '
    $0 == "      - name: " step { inside = 1 }
    inside && $0 ~ /^      - (name: |uses: )/ && $0 != "      - name: " step { exit }
    inside { print }
  ' "${quality_workflow}"
}

workflow_job_step_block() {
  local job="$1"
  local step="$2"
  local job_block
  job_block="$(workflow_job_block "${job}")"
  awk -v step="${step}" '
    $0 == "      - name: " step { inside = 1 }
    inside && $0 ~ /^      - (name: |uses: )/ && $0 != "      - name: " step { exit }
    inside { print }
  ' <<< "${job_block}"
}

gradle_task_block() {
  local task="$1"
  awk -v task="${task}" '
    $0 == "tasks.register(\"" task "\") {" { inside = 1 }
    inside && $0 ~ /^tasks\.register\("/ && $0 != "tasks.register(\"" task "\") {" { exit }
    inside { print }
  ' "${root_dir}/build.gradle.kts"
}

bootstrap_gradle_invocation() {
  local command="$1"
  awk -v command="${command}" '
    $0 == command " \\" { inside = 1 }
    inside { print }
    inside && $0 ~ /--no-daemon$/ { exit }
  ' "${bootstrap_generator}"
}

for script in "${root_dir}"/ci/*.sh; do
  bash -n "${script}"
done

[[ -f "${quality_workflow}" ]] || fail "quality-gate.yml is missing"
[[ ! -e "${deploy_workflow}" && ! -L "${deploy_workflow}" ]] ||
  fail "secret-bearing deploy workflow must not exist"

trigger_block="$(yaml_top_level_block on)"
expected_trigger_block=$'  pull_request:\n  push:\n    branches:\n      - dev\n      - main'
[[ "${trigger_block}" == "${expected_trigger_block}" ]] ||
  fail "Quality Gate trigger block must be exactly pull_request and push branches dev/main"

if grep -Eq 'workflow_dispatch|refactor/infrastructure-boundary|TARGET_SHA|verify-target-sha' "${quality_workflow}"; then
  fail "manual, stale-branch and custom target SHA paths must not exist"
fi
[[ ! -e "${root_dir}/ci/verify-target-sha.sh" && ! -L "${root_dir}/ci/verify-target-sha.sh" ]] ||
  fail "custom target SHA verifier must be deleted"
[[ "$(grep -Ec '^[[:space:]]+ref:' "${quality_workflow}" || true)" -eq 0 ]] ||
  fail "checkout ref overrides must not exist"
grep -Fq 'group: quality-gate-${{ github.sha }}' "${quality_workflow}" ||
  fail "concurrency must use the event github.sha"

permissions_block="$(yaml_top_level_block permissions)"
[[ "$(grep -c '^permissions:$' "${quality_workflow}")" -eq 1 ]] ||
  fail "Quality Gate must define exactly one top-level permissions block"
[[ "${permissions_block}" == "  contents: read" ]] || fail "Quality Gate permissions must be contents: read only"
if grep -Eq '^[[:space:]]+permissions:' "${quality_workflow}"; then
  fail "job and nested permission overrides are forbidden"
fi
if grep -Eq '\$\{\{[[:space:]]*secrets\.|^[[:space:]]+contents:[[:space:]]+write|^[[:space:]]+id-token:' \
  "${quality_workflow}"; then
  fail "Quality Gate must not consume secrets or request contents-write/id-token permissions"
fi
if grep -Eqi 'packages:|workflow_run|ssh-action|scp-action|EC2_SSH_KEY' "${quality_workflow}"; then
  fail "Quality Gate must not publish packages, deploy over SSH, or consume workflow_run"
fi
docker_job="$(workflow_job_block docker-build)"
expected_build_record_env=$'    env:\n      DOCKER_BUILD_RECORD_UPLOAD: '\''false'\'''
grep -Fq "${expected_build_record_env}" <<< "${docker_job}" ||
  fail "docker-build job must disable the default Docker build record artifact"

compile_job="$(workflow_job_block compile-architecture)"
grep -q 'persist-credentials: false' <<< "${compile_job}" || fail "compile checkout must not retain push credentials"
bootstrap_condition="        if: steps.bootstrap-request.outputs.requested == 'true'"
for bootstrap_step_name in \
  "Generate dependency locks and SHA-256 metadata for review" \
  "Publish dependency bootstrap review bundle" \
  "Require dependency bootstrap review and marker removal"; do
  bootstrap_step="$(workflow_job_step_block compile-architecture "${bootstrap_step_name}")"
  [[ -n "${bootstrap_step}" ]] || fail "compile job is missing bootstrap step: ${bootstrap_step_name}"
  [[ "$(grep -Fxc "${bootstrap_condition}" <<< "${bootstrap_step}")" -eq 1 ]] ||
    fail "bootstrap step must use the exact requested condition: ${bootstrap_step_name}"
  [[ "$(grep -Ec '^        if:' <<< "${bootstrap_step}")" -eq 1 ]] ||
    fail "bootstrap step must define exactly one condition: ${bootstrap_step_name}"
done
[[ "$(grep -c 'id: bootstrap-request' <<< "${compile_job}")" -eq 1 ]] ||
  fail "compile job must validate the dependency bootstrap marker exactly once"
grep -Fq 'bash ci/check-dependency-bootstrap-request.sh "${GITHUB_OUTPUT}"' <<< "${compile_job}" ||
  fail "dependency bootstrap marker must use the fail-closed validator"
[[ "$(grep -Fc 'bash ci/check-dependency-bootstrap-request.sh "${GITHUB_OUTPUT}"' "${quality_workflow}")" -eq 1 ]] ||
  fail "dependency bootstrap validator must be wired exactly once"
grep -Fq "cache-disabled: \${{ steps.bootstrap-request.outputs.requested == 'true' }}" <<< "${compile_job}" ||
  fail "dependency bootstrap generation must not restore or save Gradle caches"
grep -Fq 'run: bash ci/generate-dependency-bootstrap.sh build/reports/dependency-bootstrap-review' <<< "${compile_job}" ||
  fail "dependency bootstrap generator must receive only the review directory"
[[ "$(grep -Fc 'bash ci/generate-dependency-bootstrap.sh build/reports/dependency-bootstrap-review' "${quality_workflow}")" -eq 1 ]] ||
  fail "dependency bootstrap generator must be wired exactly once"
grep -Fq 'name: dependency-bootstrap-review-${{ github.sha }}' <<< "${compile_job}" ||
  fail "dependency bootstrap review artifact must use github.sha"
bootstrap_review_step="$(workflow_job_step_block compile-architecture "Require dependency bootstrap review and marker removal")"
grep -Eq '^[[:space:]]+exit 1$' <<< "${bootstrap_review_step}" ||
  fail "dependency bootstrap marker run must fail pending review"
if grep -Eq 'id: verification-metadata|steps\.verification-metadata|gradle-verification-metadata|Bootstrap dependency verification metadata|Require metadata review and a follow-up commit' \
  "${quality_workflow}"; then
  fail "legacy missing-metadata bootstrap fallback must not exist"
fi

bootstrap_validator="${root_dir}/ci/check-dependency-bootstrap-request.sh"
bootstrap_generator="${root_dir}/ci/generate-dependency-bootstrap.sh"
bootstrap_output_validator="${root_dir}/ci/validate-dependency-bootstrap-output.sh"
for bootstrap_script in \
  "${bootstrap_validator}" "${bootstrap_generator}" "${bootstrap_output_validator}"; do
  [[ -f "${bootstrap_script}" ]] || fail "missing dependency bootstrap script: ${bootstrap_script}"
  grep -q 'set -euo pipefail' "${bootstrap_script}" || fail "dependency bootstrap scripts must fail closed"
done
grep -q 'GITHUB_EVENT_NAME.*pull_request' "${bootstrap_validator}" ||
  fail "bootstrap marker must be restricted to pull_request events"
grep -Fq "expected='request=gradle-dependency-bootstrap-v1'" "${bootstrap_validator}" ||
  fail "bootstrap marker must use the fixed v1 request"
grep -q 'cmp -s' "${bootstrap_validator}" || fail "bootstrap marker must use byte-safe exact comparison"
if grep -Eq 'TARGET_SHA|target_sha|dependency_fingerprint|SHA256SUMS' \
  "${bootstrap_validator}" "${bootstrap_generator}" "${bootstrap_output_validator}"; then
  fail "dependency bootstrap scripts must not use custom target or checksum bundle paths"
fi
grep -q 'resolveAndLockAll resolveVerificationArtifacts' "${bootstrap_generator}" ||
  fail "bootstrap must generate both locks and verification metadata"
grep -q 'compileKotlin architectureTest' "${bootstrap_generator}" ||
  fail "bootstrap metadata generation must exercise the final compile and architecture path"
grep -q 'verifySecurityDependencyVersions' "${bootstrap_generator}" ||
  fail "bootstrap must reject stale production runtime security versions before publication"
grep -q ':build-logic:test' "${bootstrap_generator}" ||
  fail "bootstrap metadata generation must exercise the build-logic test path"
grep -q 'candidate.buildscript.configurations' "${root_dir}/build.gradle.kts" ||
  fail "verification artifact resolution must materialize buildscript plugin classpaths"
[[ "$(grep -c -- '--write-verification-metadata sha256' "${bootstrap_generator}")" -eq 2 ]] ||
  fail "root and build-logic bootstrap must generate SHA-256 metadata"
for bootstrap_invocation in \
  "$(bootstrap_gradle_invocation './gradlew -p build-logic')" \
  "$(bootstrap_gradle_invocation './gradlew')"; do
  [[ -n "${bootstrap_invocation}" ]] || fail "root and build-logic bootstrap invocations must exist"
  [[ "$(grep -c -- '--write-verification-metadata sha256' <<< "${bootstrap_invocation}")" -eq 1 ]] ||
    fail "each bootstrap invocation must generate SHA-256 metadata exactly once"
  [[ "$(grep -c -- '--refresh-dependencies' <<< "${bootstrap_invocation}")" -eq 1 ]] ||
    fail "each bootstrap invocation must refresh cached metadata exactly once"
done
if grep -En -- '--dependency-verification[= ]off|--write-verification-metadata (md5|sha1)|git (commit|push)' \
  "${bootstrap_generator}" "${bootstrap_validator}" "${bootstrap_output_validator}"; then
  fail "bootstrap must not disable verification or mutate the remote repository"
fi
grep -q 'each artifact must use SHA-256 and no other trust mechanism' "${bootstrap_output_validator}" ||
  fail "bootstrap output validation must enforce SHA-256 per artifact"
grep -q 'trusted-keys' "${bootstrap_output_validator}" ||
  fail "bootstrap output validation must reject alternate trusted-key paths"
bash "${root_dir}/ci/dependency-bootstrap-contract-test.sh"

expected_jobs=(compile-architecture unit-coverage api-integration batch-integration static-analysis dependency-security docker-build)
jobs_block="$(yaml_top_level_block jobs)"
mapfile -t actual_jobs < <(
  awk '/^  [^[:space:]#][^:]*:$/ { value = $0; sub(/^  /, "", value); sub(/:$/, "", value); print value }' \
    <<< "${jobs_block}"
)
[[ "${actual_jobs[*]}" == "${expected_jobs[*]}" ]] ||
  fail "Quality Gate job IDs must be exactly the seven required jobs in order"

upload_step_names=(
  'Publish dependency bootstrap review bundle'
  'Publish unit and coverage evidence'
  'Publish API integration evidence'
  'Publish Batch integration evidence'
  'Publish static-analysis evidence'
  'Publish Docker image archives'
)
upload_artifact_names=(
  'dependency-bootstrap-review-${{ github.sha }}'
  'unit-coverage-${{ github.sha }}'
  'api-integration-${{ github.sha }}'
  'batch-integration-${{ github.sha }}'
  'static-analysis-${{ github.sha }}'
  'docker-images-${{ github.sha }}'
)
upload_action_line='        uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2'
for index in "${!upload_step_names[@]}"; do
  upload_step="$(workflow_step_block "${upload_step_names[${index}]}")"
  [[ -n "${upload_step}" ]] || fail "Quality Gate is missing upload step: ${upload_step_names[${index}]}"
  [[ "$(grep -Fxc "${upload_action_line}" <<< "${upload_step}")" -eq 1 ]] ||
    fail "upload step must use the pinned upload-artifact action: ${upload_step_names[${index}]}"
  [[ "$(grep -Fxc "          name: ${upload_artifact_names[${index}]}" <<< "${upload_step}")" -eq 1 ]] ||
    fail "upload step has the wrong exact artifact name: ${upload_step_names[${index}]}"
done
[[ "$(grep -c 'uses: actions/upload-artifact@' "${quality_workflow}")" -eq 6 ]] ||
  fail "Quality Gate must publish exactly six review/evidence artifacts"

mapfile -d '' -t workflow_files < <(
  find "${root_dir}/.github/workflows" -maxdepth 1 -type f \
    \( -name '*.yml' -o -name '*.yaml' \) -print0
)
mapfile -t action_uses < <(grep -hE '^[[:space:]]*(-[[:space:]]+)?uses:' "${workflow_files[@]}")
[[ "${#action_uses[@]}" -gt 0 ]] || fail "Quality Gate must use pinned actions"
action_use_pattern='^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]+[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}([[:space:]]+#[^[:cntrl:]]*)?$'
for action_use in "${action_uses[@]}"; do
  [[ "${action_use}" =~ ${action_use_pattern} ]] || fail "action use is not owner/action@40hex: ${action_use}"
done

grep -q 'bash ci/bootstrap-quality-tools.sh --verify-checksums' "${quality_workflow}" || fail "checksum bootstrap is required"
grep -q 'detekt-cli.jar' "${quality_workflow}" || fail "standalone detekt is required"
grep -q -- '--config config/detekt/detekt.yml' "${quality_workflow}" || fail "standalone detekt configuration is required"
grep -q -- '--baseline config/detekt/baseline.xml' "${quality_workflow}" || fail "detekt baseline contract is required"
grep -q 'ktlint.jar' "${quality_workflow}" || fail "standalone ktlint is required"
grep -q 'run-npm-audit.sh' "${quality_workflow}" || fail "web audit gate is required"
grep -q 'npm --prefix apps/web run lint' "${quality_workflow}" || fail "locked web lint gate is required"
grep -q 'npm --prefix apps/web ci --include=optional' "${quality_workflow}" || fail "web install must include locked native optional dependencies"
grep -q 'npm ci --include=optional' "${root_dir}/apps/web/Dockerfile" || fail "web image must include locked native optional dependencies"

grep -q 'tasks.register("resolveVerificationArtifacts")' "${root_dir}/build-logic/build.gradle.kts" ||
  fail "build-logic verification artifact resolver task is missing"
for dependent_job in unit-coverage api-integration batch-integration static-analysis dependency-security docker-build; do
  job_line="$(grep -n "^  ${dependent_job}:$" "${quality_workflow}" | cut -d: -f1)"
  sed -n "${job_line},$((job_line + 5))p" "${quality_workflow}" | grep -q 'needs: compile-architecture' ||
    fail "${dependent_job} must wait for strict compile/bootstrap verification"
done
while IFS= read -r gradle_command; do
  [[ "${gradle_command}" == *"--write-verification-metadata"* ]] && continue
  [[ "${gradle_command}" == *"--dependency-verification strict"* ]] ||
    fail "committed-metadata Gradle gate is not explicitly strict: ${gradle_command}"
done < <(grep -E 'run: ./gradlew' "${quality_workflow}")

api_job="$(workflow_job_block api-integration)"
batch_job="$(workflow_job_block batch-integration)"
api_integration_command='run: ./gradlew :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest --dependency-verification strict --no-daemon'
batch_integration_command='run: ./gradlew :apps:batch:integrationTest --dependency-verification strict --no-daemon'
grep -Fq "${api_integration_command}" <<< "${api_job}" ||
  fail "api-integration job must execute the exact strict API/common integration tasks"
grep -Fq "${batch_integration_command}" <<< "${batch_job}" ||
  fail "batch-integration job must execute the exact strict Batch integration task"

compile_contract='compileKotlin architectureTest verifyTestIsolationPolicy verifyCoverageExclusions verifySecurityDependencyVersions :build-logic:test --dependency-verification strict'
grep -q "${compile_contract}" "${quality_workflow}" ||
  fail "required compiler, architecture, isolation, exclusion and build-logic gates must execute together"
grep -q 'tasks.registering.*verifySecurityDependencyVersions\|val verifySecurityDependencyVersions by tasks.registering' \
  "${root_dir}/build.gradle.kts" || fail "production runtime security-version gate is required"
grep -q 'SpringLibraryConventionPlugin.importSpringBootBom' \
  "${root_dir}/build-logic/src/main/java/io/premiumspread/buildlogic/SpringBootApplicationConventionPlugin.java" ||
  fail "Boot applications must re-apply the reviewed BOM overrides after the Boot plugin"
grep -Fq 'include("test.exec")' "${root_dir}/build.gradle.kts" ||
  fail "unit coverage must consume only unit-test execution data"
if grep -Fq 'include("*.exec")' "${root_dir}/build.gradle.kts"; then
  fail "unit coverage must not absorb stale integration execution data"
fi
for contract_command in \
  'bash ci/quality-gate-contract-test.sh' \
  'bash ci/local-offline-contract-test.sh' \
  'bash docs/check-documentation.sh' \
  'bash docker/deploy-contract-test.sh'; do
  grep -q "${contract_command}" "${quality_workflow}" || fail "required CI contract is not executed: ${contract_command}"
done


docker_job="$(workflow_job_block docker-build)"
[[ "$(grep -c 'outputs: type=docker,dest=${{ runner.temp }}/docker-images/' <<< "${docker_job}")" -eq 3 ]] ||
  fail "Docker job must export API, Batch and Web as loadable archives"
for component in api batch web; do
  case "${component}" in
    api) image_step="$(workflow_job_step_block docker-build "Build API image")" ;;
    batch) image_step="$(workflow_job_step_block docker-build "Build Batch image")" ;;
    web) image_step="$(workflow_job_step_block docker-build "Build Web image")" ;;
  esac
  grep -Fxq "          tags: ghcr.io/\${{ github.repository }}/${component}:\${{ github.sha }}" <<< "${image_step}" ||
    fail "Docker ${component} archive must carry the event github.sha tag"
  grep -Fxq '          labels: org.opencontainers.image.revision=${{ github.sha }}' <<< "${image_step}" ||
    fail "Docker ${component} image must carry the standard OCI revision"
  grep -Fxq "          outputs: type=docker,dest=\${{ runner.temp }}/docker-images/${component}.tar" <<< "${image_step}" ||
    fail "Docker ${component} archive output is missing"
done
docker_archive_step="$(workflow_job_step_block docker-build "Publish Docker image archives")"
grep -Fxq '        id: docker-archives' <<< "${docker_archive_step}" || fail "Docker archive upload step must expose a stable ID"
grep -Fxq "${upload_action_line}" <<< "${docker_archive_step}" || fail "Docker archives must use pinned upload-artifact"
grep -Fxq '          name: docker-images-${{ github.sha }}' <<< "${docker_archive_step}" ||
  fail "Docker archives must use the event github.sha"
grep -Fxq '          path: ${{ runner.temp }}/docker-images/*.tar' <<< "${docker_archive_step}" ||
  fail "Docker archive artifact path is missing"
docker_provenance_step="$(workflow_job_step_block docker-build "Record Docker artifact provenance")"
[[ "$(grep -Fxc '      - name: Publish Docker image archives' <<< "${docker_job}")" -eq 1 ]] ||
  fail "Docker archive upload step must exist exactly once"
[[ "$(grep -Fxc '      - name: Record Docker artifact provenance' <<< "${docker_job}")" -eq 1 ]] ||
  fail "Docker provenance summary step must exist exactly once"
docker_upload_line="$(grep -nF '      - name: Publish Docker image archives' <<< "${docker_job}" | cut -d: -f1)"
docker_summary_line="$(grep -nF '      - name: Record Docker artifact provenance' <<< "${docker_job}" | cut -d: -f1)"
[[ "${docker_upload_line}" -lt "${docker_summary_line}" ]] ||
  fail "Docker archive upload must complete before provenance is recorded"
grep -Fq 'echo "run_id=${GITHUB_RUN_ID}"' <<< "${docker_provenance_step}" ||
  fail "Docker provenance summary must record github.run_id"
grep -Fq 'echo "commit=${GITHUB_SHA}"' <<< "${docker_provenance_step}" ||
  fail "Docker provenance summary must record github.sha"
grep -Fq 'echo "artifact_id=${{ steps.docker-archives.outputs.artifact-id }}"' <<< "${docker_provenance_step}" ||
  fail "Docker provenance summary must record the platform artifact ID"
grep -Fq '} >> "${GITHUB_STEP_SUMMARY}"' <<< "${docker_provenance_step}" ||
  fail "Docker provenance must be written to GITHUB_STEP_SUMMARY"
for dockerfile in "${root_dir}/apps/api/Dockerfile" "${root_dir}/apps/batch/Dockerfile"; do
  gradle_lines="$(grep 'RUN ./gradlew' "${dockerfile}")"
  [[ -n "${gradle_lines}" ]] || fail "${dockerfile} must contain Gradle build steps"
  while IFS= read -r gradle_line; do
    [[ "${gradle_line}" == *"--dependency-verification strict"* ]] ||
      fail "container Gradle resolution is not explicitly strict: ${gradle_line}"
  done <<< "${gradle_lines}"
  grep -q 'RUN ./gradlew materializeProductionBuildArtifacts .*--dependency-verification strict' "${dockerfile}" ||
    fail "container dependency layer must materialize verified production build artifacts"
  grep -q 'COPY gradle gradle' "${dockerfile}" ||
    fail "container dependency layer must receive committed verification metadata"
  grep -q 'gradle.properties gradle.lockfile ./' "${dockerfile}" ||
    fail "container dependency layer must receive the root dependency lock"
  grep -q 'COPY build-logic build-logic' "${dockerfile}" ||
    fail "container dependency layer must receive build-logic lock and verification metadata"
  coverage_copy_line="$(grep -n 'COPY config/coverage/exclusions.txt config/coverage/exclusions.txt' "${dockerfile}" | cut -d: -f1)"
  first_gradle_line="$(grep -n 'RUN ./gradlew' "${dockerfile}" | head -n 1 | cut -d: -f1)"
  [[ -n "${coverage_copy_line}" && "${coverage_copy_line}" -lt "${first_gradle_line}" ]] ||
    fail "container dependency layer must receive coverage exclusions before Gradle configuration"
  locked_projects=(
    apps/api apps/batch architecture-tests domain
    infrastructure/common infrastructure/api infrastructure/batch
    modules/jpa modules/redis
    supports/email supports/logging supports/monitoring
  )
  for locked_project in "${locked_projects[@]}"; do
    grep -q "${locked_project}/gradle.lockfile" "${dockerfile}" ||
      fail "container dependency layer is missing ${locked_project}/gradle.lockfile"
  done
done

materializer_task="$(gradle_task_block materializeProductionBuildArtifacts)"
[[ -n "${materializer_task}" ]] ||
  fail "container runtime artifact materializer is missing"
grep -Fq 'externalArtifactsOf(productionProjects, configurationNames)' <<< "${materializer_task}" ||
  fail "production build materializer must resolve the declared project/configuration matrix"
production_projects=(
  :apps:api :apps:batch :domain
  :infrastructure:common :infrastructure:api :infrastructure:batch
  :modules:jpa :modules:redis
  :supports:logging :supports:email :supports:monitoring
)
for production_project in "${production_projects[@]}"; do
  grep -Fq "project(\"${production_project}\")" <<< "${materializer_task}" ||
    fail "production build materializer is missing ${production_project}"
done
actual_production_projects="$(sed -n 's/^                project("\([^"]*\)"),$/\1/p' <<< "${materializer_task}" | sort -u)"
expected_production_projects="$(printf '%s\n' "${production_projects[@]}" | sort -u)"
[[ "${actual_production_projects}" == "${expected_production_projects}" ]] ||
  fail "production build materializer project set must match the reviewed production modules exactly"
production_configurations=(
  compileClasspath runtimeClasspath productionRuntimeClasspath
  kotlinCompilerClasspath kotlinCompilerPluginClasspathMain kotlinBuildToolsApiClasspath
)
for production_configuration in "${production_configurations[@]}"; do
  grep -Fq "\"${production_configuration}\"" <<< "${materializer_task}" ||
    fail "production build materializer is missing ${production_configuration}"
done
actual_production_configurations="$(sed -n 's/^                "\([^"]*\)",$/\1/p' <<< "${materializer_task}" | sort -u)"
expected_production_configurations="$(printf '%s\n' "${production_configurations[@]}" | sort -u)"
[[ "${actual_production_configurations}" == "${expected_production_configurations}" ]] ||
  fail "production build materializer configuration set must match the reviewed build inputs exactly"
grep -q 'check(artifact.file.isFile).*Production build artifact was not materialized' \
  <<< "${materializer_task}" || fail "production build materializer must prove artifact files were downloaded"

verification_materializer_task="$(gradle_task_block resolveVerificationArtifacts)"
for kotlin_build_configuration in kotlinCompilerPluginClasspathMain kotlinBuildToolsApiClasspath; do
  grep -Fq "\"${kotlin_build_configuration}\"" <<< "${verification_materializer_task}" ||
    fail "verification metadata bootstrap is missing ${kotlin_build_configuration}"
done

lock_entries="$(grep -Evc '^#|^[[:space:]]*$' "${tool_lock}")"
[[ "${lock_entries}" -eq 2 ]] || fail "ktlint and detekt must both be locked"
while IFS='|' read -r name version url sha; do
  [[ -z "${name}" || "${name}" == \#* ]] && continue
  [[ -n "${version}" && "${url}" == https://* && "${sha}" =~ ^[0-9a-f]{64}$ ]] ||
    fail "invalid checksum lock entry for ${name}"
done < "${tool_lock}"
ktlint_property="$(sed -n 's/^ktLintVersion=//p' "${root_dir}/gradle.properties")"
detekt_property="$(sed -n 's/^detektVersion=//p' "${root_dir}/gradle.properties")"
ktlint_locked="$(awk -F'|' '$1 == "ktlint" { print $2 }' "${tool_lock}")"
detekt_locked="$(awk -F'|' '$1 == "detekt" { print $2 }' "${tool_lock}")"
[[ -n "${ktlint_property}" && "${ktlint_property}" == "${ktlint_locked}" ]] ||
  fail "ktlint version must have one value across gradle.properties and the checksum lock"
[[ -n "${detekt_property}" && "${detekt_property}" == "${detekt_locked}" ]] ||
  fail "detekt version must have one value across gradle.properties and the checksum lock"

mapfile -d '' -t gradle_files < <(
  find "${root_dir}" \
    \( -type d \( -name .git -o -name .gradle -o -name build -o -name node_modules \) -prune \) -o \
    \( -type f \( -name '*.gradle' -o -name '*.gradle.kts' -o -name 'settings.gradle*' \) -print0 \)
)
if [[ "${#gradle_files[@]}" -gt 0 ]] &&
  grep -En 'org\.jlleitschuh\.gradle\.ktlint|io\.gitlab\.arturbosch\.detekt|org\.owasp\.dependencycheck' \
    "${gradle_files[@]}"; then
  fail "CI-only quality/security tools must not participate in Gradle resolution"
fi

echo "quality gate and supply-chain contracts verified"
