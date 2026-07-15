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
    inside && $0 ~ /^  [a-z0-9-]+:$/ && $0 != "  " job ":" { exit }
    inside { print }
  ' "${quality_workflow}"
}

deploy_workflow_job_block() {
  local job="$1"
  awk -v job="${job}" '
    $0 == "  " job ":" { inside = 1 }
    inside && $0 ~ /^  [a-z0-9-]+:$/ && $0 != "  " job ":" { exit }
    inside { print }
  ' "${deploy_workflow}"
}

workflow_step_block() {
  local step="$1"
  awk -v step="${step}" '
    $0 == "      - name: " step { inside = 1 }
    inside && $0 ~ /^      - (name: |uses: )/ && $0 != "      - name: " step { exit }
    inside { print }
  ' "${quality_workflow}"
}

gradle_task_block() {
  local task="$1"
  awk -v task="${task}" '
    $0 == "tasks.register(\"" task "\") {" { inside = 1 }
    inside && $0 ~ /^tasks\.register\("/ && $0 != "tasks.register(\"" task "\") {" { exit }
    inside { print }
  ' "${root_dir}/build.gradle.kts"
}

for script in "${root_dir}"/ci/*.sh; do
  bash -n "${script}"
done

[[ -f "${quality_workflow}" ]] || fail "quality-gate.yml is missing"
grep -q 'pull_request:' "${quality_workflow}" || fail "pull request trigger is required"
grep -q 'refactor/infrastructure-boundary' "${quality_workflow}" || fail "refactor branch push trigger is required"
grep -q 'workflow_dispatch:' "${quality_workflow}" || fail "manual SHA trigger is required"
grep -q 'description: Commit SHA to verify' "${quality_workflow}" || fail "manual dispatch must accept an explicit SHA"
grep -q 'TARGET_SHA:.*github.event.inputs.sha.*github.sha' "${quality_workflow}" || fail "all triggers must resolve an explicit candidate SHA"
[[ "$(grep -c 'run: bash ci/verify-target-sha.sh' "${quality_workflow}")" -eq 7 ]] ||
  fail "all seven required jobs must validate checkout HEAD against the candidate SHA"
grep -q '\^\[0-9a-fA-F\]{40}\$' "${root_dir}/ci/verify-target-sha.sh" || fail "manual candidate SHA must be 40-hex"
grep -q 'git rev-parse HEAD' "${root_dir}/ci/verify-target-sha.sh" || fail "checked out HEAD must be verified"

compile_job="$(workflow_job_block compile-architecture)"
grep -q 'fetch-depth: 2' <<< "${compile_job}" || fail "dependency bootstrap validation requires the candidate parent"
grep -q 'persist-credentials: false' <<< "${compile_job}" || fail "compile checkout must not retain push credentials"
grep -q 'id: bootstrap-request' <<< "${compile_job}" || fail "compile job must validate the dependency bootstrap marker"
grep -Fq 'bash ci/check-dependency-bootstrap-request.sh "${GITHUB_OUTPUT}"' <<< "${compile_job}" ||
  fail "dependency bootstrap marker must use the fail-closed validator"
grep -Fq "cache-disabled: \${{ steps.bootstrap-request.outputs.requested == 'true' }}" <<< "${compile_job}" ||
  fail "dependency bootstrap generation must not restore or save Gradle caches"
grep -q 'bash ci/generate-dependency-bootstrap.sh' <<< "${compile_job}" ||
  fail "dependency bootstrap must use the allowlisted generation script"
grep -Fq 'name: dependency-bootstrap-${{ env.TARGET_SHA }}-for-${{ steps.bootstrap-request.outputs.target_sha }}' \
  <<< "${compile_job}" || fail "dependency bootstrap artifact must bind request and dependency SHAs"
grep -q 'Require dependency bootstrap review and marker removal' <<< "${compile_job}" ||
  fail "dependency bootstrap must fail pending artifact review"

bootstrap_validator="${root_dir}/ci/check-dependency-bootstrap-request.sh"
bootstrap_generator="${root_dir}/ci/generate-dependency-bootstrap.sh"
bootstrap_output_validator="${root_dir}/ci/validate-dependency-bootstrap-output.sh"
fingerprint_script="${root_dir}/ci/dependency-fingerprint.sh"
for bootstrap_script in \
  "${bootstrap_validator}" "${bootstrap_generator}" "${bootstrap_output_validator}" "${fingerprint_script}"; do
  [[ -f "${bootstrap_script}" ]] || fail "missing dependency bootstrap script: ${bootstrap_script}"
  grep -q 'set -euo pipefail' "${bootstrap_script}" || fail "dependency bootstrap scripts must fail closed"
done
grep -q 'GITHUB_EVENT_NAME.*push' "${bootstrap_validator}" || fail "bootstrap marker must be restricted to push events"
grep -q 'refs/heads/refactor/infrastructure-boundary' "${bootstrap_validator}" ||
  fail "bootstrap marker must be restricted to the refactor branch"
grep -q 'target_sha.*revision_line\[1\]' "${bootstrap_validator}" ||
  fail "bootstrap target must be the marker commit parent"
grep -q 'changed_paths\[0\].*marker' "${bootstrap_validator}" ||
  fail "bootstrap marker commit must contain no other changes"
grep -q 'timedelta(hours=48)' "${bootstrap_validator}" || fail "bootstrap marker expiry must be limited to 48 hours"
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
if rg -n -- '--dependency-verification[= ]off|--write-verification-metadata (md5|sha1)|git (commit|push)' \
  "${bootstrap_generator}" "${bootstrap_validator}" "${bootstrap_output_validator}"; then
  fail "bootstrap must not disable verification or mutate the remote repository"
fi
grep -q 'each artifact must use SHA-256 and no other trust mechanism' "${bootstrap_output_validator}" ||
  fail "bootstrap output validation must enforce SHA-256 per artifact"
grep -q 'trusted-keys' "${bootstrap_output_validator}" ||
  fail "bootstrap output validation must reject alternate trusted-key paths"
bash "${root_dir}/ci/dependency-bootstrap-contract-test.sh"
grep -q '^permissions:$' "${quality_workflow}" && grep -q '^  contents: read$' "${quality_workflow}" ||
  fail "quality workflow token must remain read-only"

expected_jobs=(compile-architecture unit-coverage api-integration batch-integration static-analysis dependency-security docker-build)
for job in "${expected_jobs[@]}"; do
  grep -q "^  ${job}:$" "${quality_workflow}" || fail "required job ${job} is missing"
done
actual_jobs="$(grep -Ec '^  (compile-architecture|unit-coverage|api-integration|batch-integration|static-analysis|dependency-security|docker-build):$' "${quality_workflow}")"
[[ "${actual_jobs}" -eq 7 ]] || fail "quality gate must define exactly seven required job IDs"

while IFS= read -r use; do
  ref="${use##*@}"
  [[ "${ref}" =~ ^[0-9a-f]{40}$ ]] || fail "action is not SHA-pinned: ${use}"
done < <(rg -o 'uses: [^ ]+@[^ ]+' "${root_dir}/.github/workflows")

grep -q 'bash ci/bootstrap-quality-tools.sh --verify-checksums' "${quality_workflow}" || fail "checksum bootstrap is required"
grep -q 'detekt-cli.jar' "${quality_workflow}" || fail "standalone detekt is required"
grep -q -- '--config config/detekt/detekt.yml' "${quality_workflow}" || fail "standalone detekt configuration is required"
grep -q -- '--baseline config/detekt/baseline.xml' "${quality_workflow}" || fail "detekt baseline contract is required"
grep -q 'ktlint.jar' "${quality_workflow}" || fail "standalone ktlint is required"
grep -q 'run-dependency-check.sh' "${quality_workflow}" || fail "dependency-check wrapper is required"
grep -q -- '--failOnCVSS 7' "${root_dir}/ci/run-dependency-check.sh" || fail "CVSS 7 dependency-check threshold is required"
grep -q 'run-npm-audit.sh' "${quality_workflow}" || fail "web audit gate is required"
grep -q 'npm --prefix apps/web run lint' "${quality_workflow}" || fail "locked web lint gate is required"
grep -q 'npm --prefix apps/web ci --include=optional' "${quality_workflow}" || fail "web install must include locked native optional dependencies"
grep -q 'npm ci --include=optional' "${root_dir}/apps/web/Dockerfile" || fail "web image must include locked native optional dependencies"

grep -q 'id: verification-metadata' "${quality_workflow}" || fail "compile job must detect committed verification metadata"
[[ "$(grep -c "if: steps.verification-metadata.outputs.committed == 'false'" "${quality_workflow}")" -eq 3 ]] ||
  fail "missing metadata must have bootstrap, artifact publication, and fail-closed steps"
[[ "$(grep -c 'resolveVerificationArtifacts --write-verification-metadata sha256 --no-daemon' "${quality_workflow}")" -eq 2 ]] ||
  fail "root and build-logic verification metadata must both be generated by isolated CI resolvers"
grep -q './gradlew -p build-logic resolveVerificationArtifacts --write-verification-metadata sha256 --no-daemon' \
  "${quality_workflow}" || fail "build-logic requires its own verification artifact resolver"
grep -q 'tasks.register("resolveVerificationArtifacts")' "${root_dir}/build-logic/build.gradle.kts" ||
  fail "build-logic verification artifact resolver task is missing"
grep -q 'name: gradle-verification-metadata-${{ env.TARGET_SHA }}' "${quality_workflow}" ||
  fail "verification metadata artifact must be bound to the immutable candidate SHA"
grep -q 'gradle/verification-metadata.xml && -f build-logic/gradle/verification-metadata.xml' "${quality_workflow}" ||
  fail "root and build-logic verification metadata must both be committed"
grep -q '^            gradle/verification-metadata.xml' "${quality_workflow}" || fail "root verification metadata must be published"
grep -q '^            build-logic/gradle/verification-metadata.xml' "${quality_workflow}" ||
  fail "build-logic verification metadata must be published"
grep -q 'Require metadata review and a follow-up commit' "${quality_workflow}" || fail "bootstrap run must require review and a follow-up commit"
grep -q 'exit 1' "${quality_workflow}" || fail "uncommitted verification metadata must fail closed"
for dependent_job in unit-coverage api-integration batch-integration static-analysis dependency-security docker-build; do
  job_line="$(grep -n "^  ${dependent_job}:$" "${quality_workflow}" | cut -d: -f1)"
  sed -n "${job_line},$((job_line + 5))p" "${quality_workflow}" | grep -q 'needs: compile-architecture' ||
    fail "${dependent_job} must wait for strict compile/bootstrap verification"
done
while IFS= read -r gradle_command; do
  [[ "${gradle_command}" == *"--write-verification-metadata"* ]] && continue
  [[ "${gradle_command}" == *"--dependency-verification strict"* ]] ||
    fail "committed-metadata Gradle gate is not explicitly strict: ${gradle_command}"
done < <(rg 'run: ./gradlew' "${quality_workflow}")

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

grep -q 'run: ./gradlew prepareDependencyCheckInput --dependency-verification strict --no-daemon' "${quality_workflow}" ||
  fail "OWASP scan must stage strict-verified runtime artifacts"
grep -q 'prepareDependencyCheckInput' "${root_dir}/ci/stage-dependency-check-input.sh" ||
  fail "external production runtime dependency JAR staging is required"
grep -q 'externalArtifactsOf(runtimeProjects' "${root_dir}/build.gradle.kts" ||
  fail "dependency-check input must resolve API/Batch external runtime artifacts"
grep -q -- '--dependency-verification strict' "${root_dir}/ci/stage-dependency-check-input.sh" ||
  fail "runtime JAR staging must use strict dependency verification"
grep -q 'build/dependency-check-input' "${root_dir}/ci/stage-dependency-check-input.sh" ||
  fail "runtime JAR staging directory is missing"
grep -q -- '--scan "${input_dir}"' "${root_dir}/ci/run-dependency-check.sh" ||
  fail "Dependency-Check must scan only staged production runtime JARs"
if grep -q -- '--scan "${root_dir}"' "${root_dir}/ci/run-dependency-check.sh"; then
  fail "Dependency-Check must not scan the repository source tree"
fi
grep -q -- '--data "${data_dir}"' "${root_dir}/ci/run-dependency-check.sh" ||
  fail "Dependency-Check must use its isolated NVD data directory"
grep -q 'dependency_data_dir=.*dependency-check-data' "${root_dir}/ci/bootstrap-quality-tools.sh" ||
  fail "bootstrap must create a separate dependency data directory"
if rg -n 'rm -rf.*dependency-check-data|rm -rf[[:space:]]+"\$\{(tool_dir|dependency_data_dir)\}"' \
  "${root_dir}/ci/bootstrap-quality-tools.sh"; then
  fail "tool bootstrap must never delete cached Dependency-Check data"
fi
grep -q 'path: .ci-tools/dependency-check-data' "${quality_workflow}" || fail "NVD data must be cached separately"
grep -q 'dependency-check-datafeed-v1-${{ runner.os }}' "${quality_workflow}" ||
  fail "NVD datafeed cache key is missing"
grep -q 'uses: actions/cache/restore@' "${quality_workflow}" || fail "NVD data cache must have an explicit restore step"
grep -q 'uses: actions/cache/save@' "${quality_workflow}" || fail "verified NVD data cache must have an explicit save step"
nvd_update_step="$(workflow_step_block "Update Dependency-Check NVD data")"
nvd_save_step="$(workflow_step_block "Save verified Dependency-Check NVD data")"
nvd_scan_step="$(workflow_step_block "OWASP Dependency-Check (CVSS 7+ fails)")"
grep -Fxq '        run: bash ci/update-dependency-check-data.sh' <<< "${nvd_update_step}" ||
  fail "NVD update step must execute the fail-closed updater directly"
grep -Fxq '        run: bash ci/run-dependency-check.sh' <<< "${nvd_scan_step}" ||
  fail "NVD scan step must execute the fail-closed scanner directly"
if rg -n 'continue-on-error|always\(\)' <<< "${nvd_update_step}${nvd_save_step}${nvd_scan_step}"; then
  fail "NVD update, cache save and scan steps must not override normal failure propagation"
fi
grep -Fxq '        uses: actions/cache/save@5a3ec84eff668545956fd18022155c47e93e2684 # v4.2.3' \
  <<< "${nvd_save_step}" || fail "verified NVD data must use the pinned cache save action"
grep -Fxq '          path: .ci-tools/dependency-check-data' <<< "${nvd_save_step}" ||
  fail "verified NVD cache save step must write only the isolated data directory"
grep -Fxq '          key: dependency-check-datafeed-v1-${{ runner.os }}-${{ hashFiles('"'"'ci/quality-tools.lock'"'"') }}-${{ github.run_id }}' \
  <<< "${nvd_save_step}" || fail "verified NVD cache save key must be immutable per run"
nvd_update_line="$(grep -n 'run: bash ci/update-dependency-check-data.sh' "${quality_workflow}" | cut -d: -f1)"
nvd_save_line="$(grep -n 'name: Save verified Dependency-Check NVD data' "${quality_workflow}" | cut -d: -f1)"
nvd_scan_line="$(grep -n 'run: bash ci/run-dependency-check.sh' "${quality_workflow}" | cut -d: -f1)"
[[ -n "${nvd_update_line}" && -n "${nvd_save_line}" && -n "${nvd_scan_line}" &&
  "${nvd_update_line}" -lt "${nvd_save_line}" && "${nvd_save_line}" -lt "${nvd_scan_line}" ]] ||
  fail "NVD data must be saved after a successful update and before the fail-closed scan"
if grep -q '^          path: \.ci-tools$' "${quality_workflow}"; then
  fail "tool installation cache must not absorb the isolated NVD data directory"
fi
grep -Fq -- '--nvdDatafeed "https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz"' \
  "${root_dir}/ci/update-dependency-check-data.sh" || fail "Dependency-Check must use the authoritative NVD static datafeed"
grep -Fq 'set -euo pipefail' "${root_dir}/ci/update-dependency-check-data.sh" ||
  fail "Dependency-Check update must propagate command failures"
grep -Fq 'set -euo pipefail' "${root_dir}/ci/run-dependency-check.sh" ||
  fail "Dependency-Check scan must propagate suppression validation and scanner failures"
expected_nvd_data_dir='data_dir="${root_dir}/.ci-tools/dependency-check-data"'
grep -Fxq "${expected_nvd_data_dir}" "${root_dir}/ci/update-dependency-check-data.sh" ||
  fail "Dependency-Check updater must use the canonical isolated data directory"
grep -Fxq "${expected_nvd_data_dir}" "${root_dir}/ci/run-dependency-check.sh" ||
  fail "Dependency-Check scanner must use the same canonical isolated data directory"
grep -q -- '--data "${data_dir}"' "${root_dir}/ci/update-dependency-check-data.sh" ||
  fail "Dependency-Check update and scan must share the isolated data directory"
grep -q -- '--updateonly' "${root_dir}/ci/update-dependency-check-data.sh" ||
  fail "Dependency-Check data must be updated independently before scanning"
grep -q -- '--noupdate' "${root_dir}/ci/run-dependency-check.sh" ||
  fail "Dependency-Check scan must read the database completed by the update step"
if rg -n -- '--nvdApiKey|--noupdate|\|\|[[:space:]]*true' "${root_dir}/ci/update-dependency-check-data.sh"; then
  fail "Dependency-Check update must neither skip nor hide authoritative datafeed failures"
fi
if rg -n -- '--nvdApiKey|--updateonly|\|\|[[:space:]]*true' "${root_dir}/ci/run-dependency-check.sh"; then
  fail "Dependency-Check scan must not update data, use a runner-specific API key, or hide failures"
fi

docker_job="$(workflow_job_block docker-build)"
[[ "$(grep -c 'outputs: type=docker,dest=${{ runner.temp }}/docker-images/' <<< "${docker_job}")" -eq 3 ]] ||
  fail "Docker job must export API, Batch and Web as loadable archives"
for component in api batch web; do
  grep -Fq "tags: ghcr.io/\${{ github.repository }}/${component}:\${{ env.TARGET_SHA }}" <<< "${docker_job}" ||
    fail "Docker ${component} archive must carry the immutable candidate tag"
  grep -Fq "outputs: type=docker,dest=\${{ runner.temp }}/docker-images/${component}.tar" <<< "${docker_job}" ||
    fail "Docker ${component} archive output is missing"
done
grep -Fq 'name: docker-images-${{ env.TARGET_SHA }}' <<< "${docker_job}" ||
  fail "Docker archives must be uploaded as a candidate-SHA artifact"
grep -Fq 'path: ${{ runner.temp }}/docker-images/*.tar' <<< "${docker_job}" ||
  fail "Docker archive artifact path is missing"
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
[[ "${lock_entries}" -eq 3 ]] || fail "ktlint, detekt and dependency-check must all be locked"
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

publish_job="$(deploy_workflow_job_block publish-images)"
grep -q 'workflow_run:' "${deploy_workflow}" || fail "deploy must consume the completed quality workflow"
grep -q 'github.event.workflow_run.conclusion == .success.' <<< "${publish_job}" || fail "deploy must require successful quality gate"
grep -q 'github.event.workflow_run.head_branch == .main.' <<< "${publish_job}" || fail "deploy must accept protected main only"
grep -q 'github.event.workflow_run.event == .push.' <<< "${publish_job}" || fail "deploy must accept main push only"
grep -q 'environment: production' "${deploy_workflow}" || fail "deploy must use the approval-protected production environment"
grep -Fq 'DEPLOY_SHA: ${{ github.event.workflow_run.head_sha }}' "${deploy_workflow}" ||
  fail "deploy SHA must be the exact successful workflow head SHA"
grep -q '^  actions: read$' "${deploy_workflow}" || fail "deploy needs read access to Quality Gate artifacts"
if grep -q 'DEPLOY_SHA:.*||' "${deploy_workflow}"; then
  fail "deploy SHA must not have a fallback"
fi
grep -Fq 'name: docker-images-${{ env.DEPLOY_SHA }}' <<< "${publish_job}" || fail "deploy must download the exact candidate artifact"
grep -Fq 'run-id: ${{ github.event.workflow_run.id }}' <<< "${publish_job}" || fail "deploy artifact must come from the successful workflow run"
grep -q 'docker load --input' <<< "${publish_job}" || fail "deploy promotion must load the verified image archive"
grep -q 'docker push "${image}"' <<< "${publish_job}" || fail "deploy promotion must push the loaded verified image"
if rg -n 'docker/build-push-action|docker build|setup-buildx-action' "${deploy_workflow}"; then
  fail "deploy must promote Quality Gate archives without rebuilding"
fi
[[ "$(grep -c 'run: bash ci/verify-target-sha.sh' "${deploy_workflow}")" -eq 2 ]] ||
  fail "image publication and deployment must both verify the quality-gated SHA"

if rg -n 'org\.jlleitschuh\.gradle\.ktlint|io\.gitlab\.arturbosch\.detekt|org\.owasp\.dependencycheck' \
  "${root_dir}" --glob '*.gradle' --glob '*.gradle.kts' --glob 'settings.gradle*'; then
  fail "CI-only quality/security tools must not participate in Gradle resolution"
fi

echo "quality gate and supply-chain contracts verified"
