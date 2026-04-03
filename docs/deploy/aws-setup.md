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
sudo yum install -y docker git
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker ec2-user

# Docker Compose 설치
sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose

# 프로젝트 클론
cd /home/ec2-user
git clone https://github.com/geonyeop123/premium-spread.git
cd premium-spread
```

## 5. SSL 인증서 발급 (Let's Encrypt)

```bash
# certbot 디렉터리 생성
mkdir -p docker/certbot/www docker/certbot/conf

# 먼저 HTTP만으로 nginx 시작
docker compose -f docker/infra-compose.yml up -d
docker compose -f docker/app-compose.yml up -d

# certbot으로 인증서 발급
docker run -it --rm \
  -v $(pwd)/docker/certbot/conf:/etc/letsencrypt \
  -v $(pwd)/docker/certbot/www:/var/www/certbot \
  certbot/certbot certonly \
  --webroot -w /var/www/certbot \
  -d yourdomain.com

# SSL 발급 후 nginx.conf에 HTTPS 블록 추가 (수동)
# 그 후 nginx 재시작
docker compose -f docker/app-compose.yml restart nginx
```

## 6. 인프라 실행

```bash
# Docker 네트워크 생성
docker network create premium-spread

# 인프라 (MySQL + Redis) 시작
docker compose -f docker/infra-compose.yml up -d

# 앱 (API + Batch + Web + Nginx) 시작
docker compose -f docker/app-compose.yml up -d --build
```

## 7. certbot 자동 갱신

```bash
# crontab에 추가
echo "0 0 1 * * docker run --rm -v $(pwd)/docker/certbot/conf:/etc/letsencrypt -v $(pwd)/docker/certbot/www:/var/www/certbot certbot/certbot renew && docker compose -f docker/app-compose.yml restart nginx" | crontab -
```

## 8. 예상 비용

| 항목 | 월 비용 |
|------|--------|
| EC2 t3.medium | ~$30 |
| Elastic IP | 무료 (인스턴스 연결 시) |
| 도메인 (.kr) | ~15,000원/년 |
| **합계** | **~$30/월** |
