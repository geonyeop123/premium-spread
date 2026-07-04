#!/usr/bin/env bash
# Premium Spread — OCI A1 인스턴스 자동 생성 재시도
# "Out of host capacity"가 풀릴 때까지 oci compute instance launch 를 반복한다.
# 로컬(WSL 등)에서 실행. OCI CLI 설치 + 인증(oci setup config) 필요.
#   cp deploy/.oci-launch.env.example .oci-launch.env   # OCID 채우기
#   ./deploy/launch-retry.sh                            # (또는 ./deploy/launch-retry.sh <env파일>)
set -uo pipefail   # -e 미사용: launch 실패를 직접 처리

cd "$(dirname "$0")/.."
ENV_FILE="${1:-.oci-launch.env}"
log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }

command -v oci >/dev/null || { echo "oci CLI 미설치 → deploy/.oci-launch.env.example 0) 참고"; exit 1; }
[ -f "$ENV_FILE" ] || { echo "env 없음: $ENV_FILE  (cp deploy/.oci-launch.env.example .oci-launch.env)"; exit 1; }

set -a; . "$ENV_FILE"; set +a

# 필수값 검증
for v in COMPARTMENT_OCID AD_NAME SUBNET_OCID IMAGE_OCID SSH_PUBKEY_PATH; do
  [ -n "${!v:-}" ] && [[ "${!v}" != *xxxx* ]] || { echo "필수 변수 미설정/미수정: $v"; exit 1; }
done
[ -f "$SSH_PUBKEY_PATH" ] || { echo "공개키 파일 없음: $SSH_PUBKEY_PATH"; exit 1; }

OCPUS="${OCPUS:-1}"; MEM_GB="${MEM_GB:-6}"; DISPLAY_NAME="${DISPLAY_NAME:-premium-spread}"
BOOT_VOLUME_GB="${BOOT_VOLUME_GB:-50}"; RETRY_INTERVAL="${RETRY_INTERVAL:-60}"; MAX_ATTEMPTS="${MAX_ATTEMPTS:-0}"
PUBKEY="$(cat "$SSH_PUBKEY_PATH")"

log "대상: $DISPLAY_NAME / A1.Flex ${OCPUS}OCPU ${MEM_GB}GB / AD=$AD_NAME"
log "재시도 간격 ${RETRY_INTERVAL}s, 최대 시도 $([ "$MAX_ATTEMPTS" -eq 0 ] && echo 무한 || echo "$MAX_ATTEMPTS")  (Ctrl-C 중단)"

attempt=0
while :; do
  attempt=$((attempt + 1))
  log "시도 #$attempt ..."
  # --no-retry: SDK 내부 재시도(지수 백오프 ~95s)를 끄고, 본 루프가 cadence를 제어 → 시간당 시도 횟수 ↑
  out="$(oci --no-retry compute instance launch \
      --availability-domain "$AD_NAME" \
      --compartment-id "$COMPARTMENT_OCID" \
      --shape VM.Standard.A1.Flex \
      --shape-config "{\"ocpus\": $OCPUS, \"memoryInGBs\": $MEM_GB}" \
      --image-id "$IMAGE_OCID" \
      --subnet-id "$SUBNET_OCID" \
      --assign-public-ip true \
      --display-name "$DISPLAY_NAME" \
      --boot-volume-size-in-gbs "$BOOT_VOLUME_GB" \
      --metadata "{\"ssh_authorized_keys\": \"$PUBKEY\"}" 2>&1)"
  rc=$?

  if [ $rc -eq 0 ]; then
    inst_id="$(printf '%s' "$out" | grep -oE 'ocid1\.instance\.[a-z0-9.-]+' | head -1)"
    log "🎉 성공! Instance OCID: ${inst_id:-(콘솔 확인)}"
    # Public IP 조회 (VNIC attach까지 잠시 대기)
    for _ in $(seq 1 24); do
      ip="$(oci compute instance list-vnics --instance-id "$inst_id" \
            --query 'data[0]."public-ip"' --raw-output 2>/dev/null || true)"
      [ -n "${ip:-}" ] && [ "$ip" != "null" ] && break
      sleep 10
    done
    log "Public IP: ${ip:-(콘솔에서 확인)}"
    log "다음: ssh -i ${SSH_PUBKEY_PATH%.pub} ubuntu@${ip:-<IP>}  →  3단계(setup-server.sh)"
    exit 0
  fi

  # 중단(exit) 대상: 재시도해도 안 풀리는 확정 오류만 — 인증실패(401)/잘못된요청(400)/쿼터/파라미터.
  # 403·404(NotAuthorized/NotFound)는 사전 검증된 OCID 기준 '일시적'(PAYG 전환·간헐)으로 보고 재시도.
  # capacity·429(rate limit)·5xx·네트워크 타임아웃도 모두 재시도한다.
  if printf '%s' "$out" | grep -qiE 'NotAuthenticated|"status": ?40[01]|LimitExceeded|QuotaExceeded|InvalidParameter|CannotParseRequest'; then
    log "❌ 재시도 불가 오류(인증/쿼터/파라미터) — 중단:"; printf '%s\n' "$out"; exit 1
  else
    log "재시도 대기 — ${RETRY_INTERVAL}s 후 (용량/rate-limit/403·404/네트워크 등 일시 오류)"
  fi

  if [ "$MAX_ATTEMPTS" -gt 0 ] && [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
    log "최대 시도($MAX_ATTEMPTS) 도달 — 중단"; exit 2
  fi
  sleep "$RETRY_INTERVAL"
done
