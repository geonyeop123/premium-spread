#!/usr/bin/env bash
# Premium Spread — Oracle Cloud (ARM Ampere A1) 서버 부트스트랩
# 신규 인스턴스에서 1회 실행. 멱등(idempotent)하게 작성되어 재실행해도 안전하다.
#   chmod +x deploy/setup-server.sh && ./deploy/setup-server.sh
#
# 처리: 패키지 업데이트 → swap → Docker → 방화벽(iptables/firewalld) 80·443 개방
set -euo pipefail

log() { printf '\n\033[1;34m[setup]\033[0m %s\n' "$*"; }

# ── 0. 배포판 감지 ──────────────────────────────────────────
. /etc/os-release
log "감지된 OS: ${PRETTY_NAME} ($(uname -m))"
if [ "$(uname -m)" != "aarch64" ]; then
  log "경고: aarch64가 아님 — Oracle ARM A1 인스턴스가 맞는지 확인하세요."
fi

# ── 1. 패키지 업데이트 ──────────────────────────────────────
log "패키지 업데이트"
if command -v apt-get >/dev/null; then
  sudo apt-get update -y && sudo apt-get upgrade -y
  PKG=apt
elif command -v dnf >/dev/null; then
  sudo dnf update -y
  PKG=dnf
else
  echo "지원하지 않는 패키지 매니저"; exit 1
fi

# ── 2. Swap 4GB (빌드 OOM 방지 — 6GB 박스에서 gradle+npm 빌드가 메모리를 많이 씀) ──
if ! sudo swapon --show | grep -q '/swapfile'; then
  log "swap 4GB 생성"
  sudo fallocate -l 4G /swapfile || sudo dd if=/dev/zero of=/swapfile bs=1M count=4096
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
else
  log "swap 이미 존재 — skip"
fi

# ── 3. Docker + compose plugin ──────────────────────────────
if ! command -v docker >/dev/null; then
  log "Docker 설치 (공식 convenience script)"
  curl -fsSL https://get.docker.com | sudo sh
  sudo systemctl enable --now docker
  sudo usermod -aG docker "$USER"
  log "docker 그룹 반영을 위해 재로그인 필요 (또는 'newgrp docker')"
else
  log "Docker 이미 설치됨 — skip"
fi

# ── 4. 방화벽: 80/443 개방 (★ Oracle 2-레이어 중 '인스턴스' 레이어) ──
# ★★ 클라우드 레이어(VCN Security List / NSG)는 OCI 콘솔에서 별도로 열어야 한다. README 참조.
open_iptables() {
  for port in 80 443; do
    if ! sudo iptables -C INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null; then
      sudo iptables -I INPUT -p tcp --dport "$port" -j ACCEPT
      log "iptables: $port/tcp ACCEPT 추가"
    fi
  done
  # 영구화
  if command -v netfilter-persistent >/dev/null; then
    sudo netfilter-persistent save
  else
    sudo "$PKG" install -y iptables-persistent || true
    sudo netfilter-persistent save || true
  fi
}

if command -v firewall-cmd >/dev/null && sudo systemctl is-active --quiet firewalld; then
  log "firewalld 감지 — http/https 서비스 개방"
  sudo firewall-cmd --permanent --add-service=http
  sudo firewall-cmd --permanent --add-service=https
  sudo firewall-cmd --reload
else
  log "iptables로 80/443 개방"
  open_iptables
fi

log "완료. 다음: 재로그인 후  ./deploy/deploy.sh  실행"
log "잊지 말 것 → OCI 콘솔에서 VCN Security List에 80/443 Ingress 규칙 추가!"
