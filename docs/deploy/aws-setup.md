# AWS 배포 가이드

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
sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose

# GitHub Actions가 검증된 deploy bundle을 전송할 디렉터리만 생성한다.
# 운영 서버에서 source checkout/build는 수행하지 않는다.
cd /home/ec2-user
mkdir -p premium-spread/docker premium-spread/.deploy
chown -R ec2-user:ec2-user premium-spread
```

## 5. SSL 인증서 발급 (Let's Encrypt)

```bash
# certbot 디렉터리 생성
mkdir -p docker/certbot/www docker/certbot/conf

# 최초 application 배포는 production Environment 승인 후 GitHub Actions workflow로 수행한다.
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

## 6. 인프라 실행

```bash
# production Environment secret/variable을 등록하고 main의 Deploy workflow를 승인한다.
# workflow가 SHA-tag image pull, migration/API readiness, Batch, smoke/rollback 순서를 수행한다.

# 배포 후 서버에서 확인
cat /home/ec2-user/premium-spread/.deploy/last-successful.env
docker inspect --format '{{.Config.Image}} {{.State.Health.Status}}' premium-spread-api premium-spread-batch
```

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
