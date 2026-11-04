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
docker_assembly_job="$(workflow_job_block docker-build)"
indented_permissions="$(grep -E '^[[:space:]]+permissions:' "${quality_workflow}" || true)"
assembly_permissions="$(grep -Fx '    permissions:' <<< "${docker_assembly_job}" || true)"
assembly_permission_entries="$(
  awk '
    $0 == "    permissions:" { inside = 1; next }
    inside && $0 ~ /^      [^[:space:]]/ { print; next }
    inside { exit }
  ' <<< "${docker_assembly_job}"
)"
[[ "${indented_permissions}" == "    permissions:" && "${assembly_permissions}" == "    permissions:" &&
  "${assembly_permission_entries}" == "      actions: read" ]] ||
  fail "only docker-build may override permissions, and only with actions: read"
if grep -Eq '\$\{\{[[:space:]]*secrets\.|^[[:space:]]+contents:[[:space:]]+write|^[[:space:]]+id-token:' \
  "${quality_workflow}"; then
  fail "Quality Gate must not consume secrets or request contents-write/id-token permissions"
fi
if grep -Eqi 'packages:|workflow_run|ssh-action|scp-action|EC2_SSH_KEY' "${quality_workflow}"; then
  fail "Quality Gate must not publish packages, deploy over SSH, or consume workflow_run"
fi
docker_image_job="$(workflow_job_block docker-image)"
expected_build_record_env=$'    env:\n      DOCKER_BUILD_RECORD_UPLOAD: '\''false'\'''
[[ "${docker_image_job}" == *"${expected_build_record_env}"* ]] ||
  fail "docker-image job must disable the default Docker build record artifact"

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
bash "${root_dir}/ci/delete-actions-caches-test.sh"

expected_jobs=(compile-architecture unit-coverage api-integration batch-integration static-analysis dependency-security docker-image docker-build common-integration)
jobs_block="$(yaml_top_level_block jobs)"
mapfile -t actual_jobs < <(
  awk '/^  [^[:space:]#][^:]*:$/ { value = $0; sub(/^  /, "", value); sub(/:$/, "", value); print value }' \
    <<< "${jobs_block}"
)
[[ "${actual_jobs[*]}" == "${expected_jobs[*]}" ]] ||
  fail "Quality Gate job IDs must be exactly the nine required jobs in order"

upload_step_names=(
  'Publish dependency bootstrap review bundle'
  'Publish unit and coverage evidence'
  'Publish API integration evidence'
  'Publish Batch integration evidence'
  'Publish static-analysis evidence'
  'Publish Docker image part'
  'Publish common integration evidence'
)
upload_artifact_names=(
  'dependency-bootstrap-review-${{ github.sha }}'
  'unit-coverage-${{ github.sha }}'
  'api-integration-${{ github.sha }}'
  'batch-integration-${{ github.sha }}'
  'static-analysis-${{ github.sha }}'
  'docker-image-part-${{ matrix.component }}-${{ github.sha }}'
  'common-integration-${{ github.sha }}'
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
[[ "$(grep -c 'uses: actions/upload-artifact@' "${quality_workflow}")" -eq 7 ]] ||
  fail "Quality Gate must publish exactly seven upload-artifact steps"
[[ "$(grep -c 'uses: actions/upload-artifact/merge@' "${quality_workflow}")" -eq 1 ]] ||
  fail "Quality Gate must assemble exactly one merged artifact"

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
# 사용자가 보는 고지(SEM-1·SEM-4)는 문자열 검색이 아니라 렌더 테스트로만 증명된다.
grep -q 'npm --prefix apps/web run test' "${quality_workflow}" || fail "web render test gate is required"
grep -q 'npm --prefix apps/web ci --include=optional' "${quality_workflow}" || fail "web install must include locked native optional dependencies"
grep -q 'npm ci --include=optional' "${root_dir}/apps/web/Dockerfile" || fail "web image must include locked native optional dependencies"

grep -q 'tasks.register("resolveVerificationArtifacts")' "${root_dir}/build-logic/build.gradle.kts" ||
  fail "build-logic verification artifact resolver task is missing"
# Gradle job 은 각자 --dependency-verification strict 로 의존성을 검증하고(아래 루프), PR 은 Gradle cache 를
# 읽기만 하므로 compile job 을 기다리지 않아도 미검증 artifact 를 쓰거나 cache 를 오염시키지 않는다.
# Docker 조립 job 만 matrix 를 기다린다.
[[ "$(grep -Ec '^[[:space:]]*needs:' "${quality_workflow}" || true)" -eq 1 ]] &&
  [[ "$(grep -Fx '    needs: docker-image' <<< "$(workflow_job_block docker-build)" || true)" == "    needs: docker-image" ]] ||
  fail "only docker-build may wait, and only for docker-image"
setup_gradle_line='      - uses: gradle/actions/setup-gradle@ac638b010cf58a27ee6c972d7336334ccaf61c96 # v4.4.1'
cache_read_only_line="          cache-read-only: \${{ github.ref != 'refs/heads/dev' }}"
for gradle_job in compile-architecture unit-coverage api-integration batch-integration common-integration; do
  gradle_job_block="$(workflow_job_block "${gradle_job}")"
  [[ "$(grep -Fxc "${setup_gradle_line}" <<< "${gradle_job_block}")" -eq 1 ]] ||
    fail "${gradle_job} must set up Gradle exactly once"
  [[ "$(grep -Fxc "${cache_read_only_line}" <<< "${gradle_job_block}")" -eq 1 ]] ||
    fail "${gradle_job} must restore the Gradle cache read-only outside dev pushes"
done
[[ "$(grep -Fc 'uses: gradle/actions/setup-gradle@' "${quality_workflow}")" -eq 5 ]] ||
  fail "only the five Gradle jobs may set up Gradle"
while IFS= read -r gradle_command; do
  [[ "${gradle_command}" == *"--write-verification-metadata"* ]] && continue
  [[ "${gradle_command}" == *"--dependency-verification strict"* ]] ||
    fail "committed-metadata Gradle gate is not explicitly strict: ${gradle_command}"
done < <(grep -E 'run: ./gradlew' "${quality_workflow}")

unit_job="$(workflow_job_block unit-coverage)"
api_job="$(workflow_job_block api-integration)"
batch_job="$(workflow_job_block batch-integration)"
common_job="$(workflow_job_block common-integration)"
unit_command='run: ./gradlew test jacocoTestReport jacocoTestCoverageVerification --parallel --dependency-verification strict --no-daemon'
api_integration_command='run: ./gradlew :apps:api:integrationTest --dependency-verification strict --no-daemon'
batch_integration_command='run: ./gradlew :apps:batch:integrationTest --dependency-verification strict --no-daemon'
common_integration_command='run: ./gradlew :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest --dependency-verification strict --no-daemon'
grep -Fq "${unit_command}" <<< "${unit_job}" ||
  fail "unit-coverage job must run unit tests and coverage across modules in parallel"
grep -Fq "${api_integration_command}" <<< "${api_job}" ||
  fail "api-integration job must execute the exact strict API integration task"
if grep -Fq ':infrastructure:common:' <<< "${api_job}"; then
  fail "api-integration job must not run common tasks; common-integration owns them"
fi
grep -Fq "${batch_integration_command}" <<< "${batch_job}" ||
  fail "batch-integration job must execute the exact strict Batch integration task"
grep -Fq "${common_integration_command}" <<< "${common_job}" ||
  fail "common-integration job must execute the exact strict migration and common integration tasks"
for integration_job in common-integration api-integration batch-integration; do
  grep -Fxq "          TESTCONTAINERS_REUSE_ENABLE: 'false'" <<< "$(workflow_job_block "${integration_job}")" ||
    fail "${integration_job} must disable Testcontainers reuse"
done

compile_contract='compileKotlin architectureTest verifyTestIsolationPolicy verifyCoverageExclusions verifySecurityDependencyVersions harnessCheck harnessCheckTest :build-logic:test --dependency-verification strict'
grep -q "${compile_contract}" "${quality_workflow}" ||
  fail "required compiler, architecture, isolation, exclusion, harness and build-logic gates must execute together"
grep -q 'tasks.registering.*verifySecurityDependencyVersions\|val verifySecurityDependencyVersions by tasks.registering' \
  "${root_dir}/build.gradle.kts" || fail "production runtime security-version gate is required"
grep -q 'val harnessCheck by tasks.registering' "${root_dir}/build.gradle.kts" ||
  fail "harness integrity gate task is required"
# 검사기만 있고 자가검증이 없으면 조용히 무력해진 게이트를 알아챌 수 없다.
grep -q 'val harnessCheckTest by tasks.registering' "${root_dir}/build.gradle.kts" ||
  fail "harness checker self-verification task is required"
[[ -f "${root_dir}/docs/check-harness.sh" ]] || fail "harness checker script is required"
[[ -f "${root_dir}/docs/test-check-harness.sh" ]] || fail "harness checker fixture suite is required"
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


docker_image_job="$(workflow_job_block docker-image)"
docker_job="$(workflow_job_block docker-build)"
docker_matrix=$'    strategy:\n      fail-fast: false\n      matrix:\n        component: [api, batch, web]'
[[ "${docker_image_job}" == *"${docker_matrix}"* ]] ||
  fail "Docker image job must build the api/batch/web matrix without fail-fast"
[[ "$(grep -c 'uses: docker/build-push-action@' "${quality_workflow}")" -eq 1 &&
  "$(grep -c 'uses: docker/setup-buildx-action@' "${quality_workflow}")" -eq 1 &&
  "$(grep -c 'uses: docker/build-push-action@' <<< "${docker_image_job}")" -eq 1 &&
  "$(grep -c 'uses: docker/setup-buildx-action@' <<< "${docker_image_job}")" -eq 1 ]] ||
  fail "Docker images must be built only by the docker-image matrix"
docker_build_step="$(workflow_job_step_block docker-image "Build image")"
docker_build_lines=(
  '          file: apps/${{ matrix.component }}/Dockerfile'
  '          push: false'
  '          tags: ghcr.io/${{ github.repository }}/${{ matrix.component }}:${{ github.sha }}'
  '          labels: org.opencontainers.image.revision=${{ github.sha }}'
  '          outputs: type=docker,dest=${{ runner.temp }}/docker-images/${{ matrix.component }}.tar'
  '          cache-from: type=gha,scope=${{ matrix.component }}'
  "          cache-to: \${{ github.event_name == 'push' && github.ref == 'refs/heads/dev' && format('type=gha,mode=max,scope={0},ignore-error=true', matrix.component) || '' }}"
)
for docker_line in "${docker_build_lines[@]}"; do
  grep -Fxq -- "${docker_line}" <<< "${docker_build_step}" || fail "Docker image step is missing: ${docker_line}"
done
[[ "$(grep -c 'cache-to:' "${quality_workflow}")" -eq 1 ]] ||
  fail "Docker cache may be written only by dev pushes"
docker_part_step="$(workflow_job_step_block docker-image "Publish Docker image part")"
docker_part_lines=(
  '          path: ${{ runner.temp }}/docker-images/${{ matrix.component }}.tar'
  '          if-no-files-found: error'
  '          compression-level: 0'
  '          retention-days: 1'
  '          overwrite: true'
)
for docker_line in "${docker_part_lines[@]}"; do
  grep -Fxq -- "${docker_line}" <<< "${docker_part_step}" || fail "Docker image part upload contract is broken"
done
docker_assembly_head=$'  docker-build:\n    name: 7. Docker image build\n    needs: docker-image\n    if: ${{ !cancelled() }}'
[[ "${docker_job}" == *"${docker_assembly_head}"* ]] ||
  fail "docker-build must assemble after every image leg, even when a leg fails"
if grep -Fq 'actions/checkout@' <<< "${docker_job}" || grep -Fq 'docker/' <<< "${docker_job}"; then
  fail "docker-build must assemble archives without rebuilding"
fi
docker_result_step="$(workflow_job_step_block docker-build "Require every image build to succeed")"
for docker_line in \
  '          IMAGE_BUILD_RESULT: ${{ needs.docker-image.result }}' \
  '          if [[ "${IMAGE_BUILD_RESULT}" != "success" ]]; then' \
  '            exit 1'; do
  grep -Fxq -- "${docker_line}" <<< "${docker_result_step}" ||
    fail "docker-build must fail when any image leg did not succeed"
done
docker_parts_step="$(workflow_job_step_block docker-build "Require exactly the three unexpired image parts")"
docker_parts_lines=(
  '          GH_TOKEN: ${{ github.token }}'
  "          expected=\"\$(printf 'docker-image-part-%s-%s\\n' api \"\${GITHUB_SHA}\" batch \"\${GITHUB_SHA}\" web \"\${GITHUB_SHA}\")\""
  "          actual=\"\$(gh api --paginate \"repos/\${GITHUB_REPOSITORY}/actions/runs/\${GITHUB_RUN_ID}/artifacts\" --jq '.artifacts[] | select(.expired == false) | select(.name | startswith(\"docker-image-part-\")) | .name' | LC_ALL=C sort)\""
  '          if [[ "${actual}" != "${expected}" ]]; then'
  '            exit 1'
)
for docker_line in "${docker_parts_lines[@]}"; do
  grep -Fxq -- "${docker_line}" <<< "${docker_parts_step}" ||
    fail "docker-build must verify exactly three unexpired image parts before merging"
done
docker_archive_step="$(workflow_job_step_block docker-build "Publish Docker image archives")"
docker_archive_lines=(
  '        id: docker-archives'
  '        uses: actions/upload-artifact/merge@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2'
  '          name: docker-images-${{ github.sha }}'
  '          pattern: docker-image-part-*-${{ github.sha }}'
  '          compression-level: 0'
  '          retention-days: 14'
  '          delete-merged: true'
)
for docker_line in "${docker_archive_lines[@]}"; do
  grep -Fxq -- "${docker_line}" <<< "${docker_archive_step}" || fail "Docker archive merge contract is broken"
done
docker_step_names=(
  'Require every image build to succeed'
  'Require exactly the three unexpired image parts'
  'Publish Docker image archives'
  'Record Docker artifact provenance'
)
previous_docker_line=0
for docker_step_name in "${docker_step_names[@]}"; do
  [[ "$(grep -Fxc "      - name: ${docker_step_name}" <<< "${docker_job}")" -eq 1 ]] ||
    fail "Docker assembly steps must run result check, part check, merge, provenance in order"
  current_docker_line="$(grep -nFx "      - name: ${docker_step_name}" <<< "${docker_job}" | cut -d: -f1)"
  [[ "${previous_docker_line}" -lt "${current_docker_line}" ]] ||
    fail "Docker assembly steps must run result check, part check, merge, provenance in order"
  previous_docker_line="${current_docker_line}"
done
docker_provenance_step="$(workflow_job_step_block docker-build "Record Docker artifact provenance")"
grep -Fq 'echo "run_id=${GITHUB_RUN_ID}"' <<< "${docker_provenance_step}" ||
  fail "Docker provenance summary must record github.run_id"
grep -Fq 'echo "commit=${GITHUB_SHA}"' <<< "${docker_provenance_step}" ||
  fail "Docker provenance summary must record github.sha"
grep -Fq 'echo "artifact_id=${{ steps.docker-archives.outputs.artifact-id }}"' <<< "${docker_provenance_step}" ||
  fail "Docker provenance summary must record the platform artifact ID"
grep -Fq '} | tee -a "${GITHUB_STEP_SUMMARY}"' <<< "${docker_provenance_step}" ||
  fail "Docker provenance must be written to both GITHUB_STEP_SUMMARY and the step log"
[[ "$(grep -Fc '} >> "${GITHUB_STEP_SUMMARY}"' <<< "${docker_provenance_step}" || true)" -eq 0 ]] ||
  fail "Docker provenance must not keep the old closing line"
[[ "$(grep -Ec '^[[:space:]]*\}' <<< "${docker_provenance_step}" || true)" -eq 1 ]] ||
  fail "Docker provenance group must have exactly one closing brace line"
for docker_syntax_job in docker-image docker-build; do
  docker_syntax_block="$(workflow_job_block "${docker_syntax_job}")"
  while IFS= read -r docker_syntax_step; do
    docker_syntax_body="$(
      workflow_job_step_block "${docker_syntax_job}" "${docker_syntax_step}" |
        awk '
          $0 == "        run: |" { inside = 1; next }
          inside && $0 ~ /^          / { print substr($0, 11); next }
          inside { exit }
        '
    )"
    [[ -z "${docker_syntax_body}" ]] && continue
    bash -n <<< "${docker_syntax_body}" 2> /dev/null ||
      fail "run script has a shell syntax error in ${docker_syntax_job} step: ${docker_syntax_step}"
  done < <(sed -n 's/^      - name: //p' <<< "${docker_syntax_block}")
done
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
