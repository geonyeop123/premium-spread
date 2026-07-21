# AWS host setup 가이드

> validation/PRIVATE_LIVE host를 준비하는 참고 절차다. CI가 host에 접속하거나 배포하는 절차가 아니며, 실제 host 생성과
> activation은 별도 operator 작업이다.

## 1. EC2 인스턴스 생성

1. AWS Console → EC2 → 인스턴스 시작
2. AMI: Amazon Linux 2023
3. 인스턴스 유형: t3.medium (2 vCPU, 4GB RAM)
4. 키 페어: 생성 또는 기존 키 선택
5. 보안 그룹 설정:
   - SSH (22): 내 IP만
   - HTTP (80): 0.0.0.0/0
   - HTTPS (443): 0.0.0.0/0
6. 스토리지: 30GB gp3

## 2. Elastic IP 할당

1. EC2 → 탄력적 IP → 할당
2. 생성된 EIP를 EC2 인스턴스에 연결

## 3. 도메인 설정

### 가비아 (.kr 도메인)

1. 가비아에서 도메인 구매 (연 ~15,000원)
2. DNS 관리 → A 레코드 추가: `@` → EC2 Elastic IP

### Route 53 (.com 도메인)

1. Route 53 → 도메인 등록 ($12/년)
2. 호스팅 영역 → A 레코드: Elastic IP

## 4. 서버 초기 설정

```bash
# Docker 설치
sudo yum update -y
sudo yum install -y docker curl
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker ec2-user

# Docker Compose 설치
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl --fail --show-error --location \
  "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-$(uname -m)" \
  --output /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod 0755 /usr/local/lib/docker/cli-plugins/docker-compose
docker compose version

# operator가 검증된 배포 bundle을 배치할 디렉터리만 생성한다.
# 운영 서버에서 source checkout/build는 수행하지 않는다.
cd /home/ec2-user
mkdir -p premium-spread/docker premium-spread/.deploy
chown -R ec2-user:ec2-user premium-spread
chmod -R go-w premium-spread
```

배포 operator가 compose와 `docker/deploy.sh`를 소유한다. application의 `runtime service account` 또는 host runtime UID에는
이 `operator-owned command`, 배포 bundle과 host secret source의 쓰기 권한을 주지 않는다. 별도의 인증 장비나 runner를
추가하는 요구가 아니라 기존 host 파일 owner/mode로 유지하는 최소 권한 경계다.

## 5. SSL 인증서 발급 (Let's Encrypt)

```bash
cd /home/ec2-user/premium-spread
mkdir -p docker/certbot/www docker/certbot/conf

# 먼저 아래 host-local 배포 절차로 application/nginx를 기동한다.
# 서버에서 git pull 또는 docker compose --build를 실행하지 않는다.

# certbot으로 인증서 발급
docker run -it --rm \
  -v $(pwd)/docker/certbot/conf:/etc/letsencrypt \
  -v $(pwd)/docker/certbot/www:/var/www/certbot \
  certbot/certbot certonly \
  --webroot -w /var/www/certbot \
  -d yourdomain.com

# SSL 발급 후 nginx.conf에 HTTPS 블록 추가 (수동)
# 그 후 실행 중인 nginx 재시작
docker restart premium-spread-nginx
```

## 6. Host-local application 배포

실제 ReleaseCandidate는 green Quality Gate 중 정확히 `event=push`, `branch=dev`인 merged `dev` push run만 허용한다.
pull_request artifact는 배포 후보가 아니다. PR artifact는 변경 검토를 위한 review evidence로만 사용한다.

1. operator는 green Quality Gate summary의 run ID, commit과 artifact ID를 확인한다.
2. `docker-images-<github.sha>` archive와 같은 commit에서 만든 compose/deploy bundle을 operator가 확보한다.
3. 세 archive를 `docker load`하고, host에서만 사용할 수 있는 registry credential로 선택한 registry에 같은 40자리 SHA
   tag를 publish한다. image의 OCI revision도 그 SHA와 일치해야 한다.
4. operator-controlled host secret source에서 registry, DB/Redis, JWT와 외부 연동 값을 환경변수로 주입한다.
5. operator가 host에서 `bash docker/deploy.sh`를 직접 실행하고 readiness와 smoke 결과를 확인한다.

```bash
cd /home/ec2-user/premium-spread

# 예: operator가 관리하는 실제 host 경로에서 runtime 값을 주입한다.
# set -a; source /operator/managed/path/runtime.env; set +a
# DEPLOY_SHA, IMAGE_REGISTRY, IMAGE_NAMESPACE를 포함한 필수 값이 준비된 뒤 실행한다.
bash docker/deploy.sh

# 배포 후 확인
cat /home/ec2-user/premium-spread/.deploy/last-successful.env
docker inspect --format '{{.Config.Image}} {{.State.Health.Status}}' premium-spread-api premium-spread-batch
```

host secret은 저장소, image 또는 배포 bundle에 복사하지 않는다. GitHub Actions는 archive/evidence 생성까지만 담당하고
host credential 주입, migration, 배포와 activation은 operator 책임으로 남긴다.

## 7. certbot 자동 갱신

```bash
# crontab에 추가
echo "0 0 1 * * docker run --rm -v $(pwd)/docker/certbot/conf:/etc/letsencrypt -v $(pwd)/docker/certbot/www:/var/www/certbot certbot/certbot renew && docker restart premium-spread-nginx" | crontab -
```

## 8. 예상 비용

| 항목 | 월 비용 |
|------|--------|
| EC2 t3.medium | ~$30 |
| Elastic IP | 무료 (인스턴스 연결 시) |
| 도메인 (.kr) | ~15,000원/년 |
| **합계** | **~$30/월** |
