# Oracle Cloud 배포 런북

> 이슈 [#25](../../../issues/25) Always Free(ARM Ampere A1) 배포 절차. 계정 가입(1단계) 이후 진행.

## 브랜치 전략

서버는 **`prd` 브랜치만** 추적한다 (`dev`는 진행 중 통합 브랜치이므로 서버가 끌어가면 안 됨).

```
feature/* → dev (통합·테스트) → prd (= 서버가 배포·추적하는 실체)
```

- 릴리스 = `dev → prd` 머지 (필요 시 `vX.Y.Z` 태그)
- 서버는 `prd`만 clone/pull → `deploy.sh --pull`이 `prd`의 최신 커밋만 반영
- 운영 config는 `prd` 프로파일(`application-prd.yml`)이 담당, 시크릿은 `.env`(gitignore)로 주입

## 0. 한눈에 보는 흐름

```
인스턴스 생성 → SSH 접속 → setup-server.sh → .env 작성 → deploy.sh → SSL
   (2단계)                    (3단계)        (4단계)              (5단계)
```

| 스크립트 | 역할 |
|---|---|
| `deploy/setup-server.sh` | 서버 1회 부트스트랩: 업데이트·swap·Docker·방화벽(80/443) |
| `deploy/deploy.sh` | 인프라→앱 빌드/기동→헬스체크 (재배포는 `--pull`) |
| `deploy/.env.prd.example` | 운영 환경변수 템플릿 (`.env`로 복사) |
| `deploy/nginx.ssl.conf` | SSL 적용 nginx 설정 템플릿 |

---

## ⚠️ Oracle 고유 함정 (먼저 읽기)

1. **방화벽이 2-레이어** — OCI 콘솔 **VCN Security List/NSG**(클라우드)와 **인스턴스 iptables**(OS) 둘 다 열어야 80/443이 통한다. 하나만 열면 "포트가 안 열림" 증상. `setup-server.sh`는 OS 레이어만 처리 → 콘솔 레이어는 수동.
2. **ARM "Out of host capacity"** — A1 무료 용량이 자주 소진. 인스턴스 생성 실패 시 리전/AD 바꿔가며 재시도(또는 콘솔에서 반복 시도).
3. **메모리** — 6GB 박스에 MySQL+Redis×2+api+batch+web+nginx가 모두 올라간다. `app-compose.yml`에 `mem_limit`을 추가해 JVM이 호스트 전체를 잡지 않도록 했다(미적용 시 api·batch 동시 OOM). 빌드 OOM 방지용 swap은 `setup-server.sh`가 4GB 생성.
4. **스키마 생성 순서** — Flyway는 `api`만 보유. 첫 부팅 때 **api가 V1~V12 마이그레이션으로 스키마 생성**, `batch`는 그때까지 `validate` 실패로 잠깐 재시작하다 자동 복구(`restart: unless-stopped`). batch가 잠시 restarting이어도 정상.
5. **SSL은 사실상 필수** — api prd 세션 쿠키가 `secure=true; same-site=strict` → HTTPS 없이는 로그인/세션 미동작. 이슈엔 "선택"이지만 인증 기능을 쓰려면 5단계까지 필수.
6. **30일 미사용 reclaim** — 주기적 접속/사용 유지.

---

## 2. 인스턴스 생성 (OCI 콘솔)

- Shape: **VM.Standard.A1.Flex** (Ampere ARM), 시작 스펙 1 OCPU + 6GB → 추후 확장
- Image: Ubuntu 22.04 또는 Oracle Linux 9 (스크립트는 둘 다 지원)
- SSH 키: 키쌍 생성 후 공개키 등록 (개인키는 로컬 보관)
- 생성 후 **Public IP** 확보

### "Out of host capacity" 자동 재시도
서울/춘천은 단일 AD라 AD 변경이 불가하고 용량이 자주 소진된다. 콘솔 반복 클릭 대신
`deploy/launch-retry.sh`로 자동화한다 (로컬에서 OCI CLI로 launch 반복):

```bash
cp deploy/.oci-launch.env.example .oci-launch.env   # OCID 5개 채우기 (수집법은 파일 주석 참고)
./deploy/launch-retry.sh                            # 용량 날 때까지 재시도 → 성공 시 Public IP 출력
```

### VCN Security List 인그레스 규칙 추가 (클라우드 레이어)
콘솔 → Networking → VCN → Security Lists → Default → Add Ingress Rules:

| Source CIDR | Protocol | Dest Port |
|---|---|---|
| 0.0.0.0/0 | TCP | 80 |
| 0.0.0.0/0 | TCP | 443 |

(22번 SSH는 기본 개방됨)

---

## 3. 서버 초기 설정

```bash
ssh -i <개인키> ubuntu@<PUBLIC_IP>     # Oracle Linux면 opc@<PUBLIC_IP>

git clone -b prd https://github.com/geonyeop123/premium-spread.git   # 서버는 prd 브랜치 추적
cd premium-spread

chmod +x deploy/*.sh
./deploy/setup-server.sh               # 업데이트·swap·Docker·iptables(80/443)
# docker 그룹 반영 위해 재로그인
exit && ssh -i <개인키> ubuntu@<PUBLIC_IP>
cd premium-spread
```

검증:
```bash
docker --version && docker compose version
free -h | grep -i swap        # swap 4G 확인
sudo iptables -L INPUT -n | grep -E '80|443'
```

---

## 4. premium-spread 배포

```bash
cp deploy/.env.prd.example .env
# .env 편집 — ★ 표시된 값(DB/Redis 비밀번호, API 키, 메일)을 실제 값으로 채움
nano .env

./deploy/deploy.sh
```

`deploy.sh`가 수행: 인프라 기동 → MySQL/Redis healthy 대기 → 앱 빌드·기동 → `/actuator/health` UP 대기 → 상태 출력.

> 최초 빌드는 1 OCPU ARM에서 수 분 소요(gradle×2 + npm). swap 덕에 OOM 없이 완주.

확인:
```bash
curl http://localhost:8080/actuator/health     # {"status":"UP"}
curl http://<PUBLIC_IP>/                        # web 응답 (nginx→web)
docker ps
```

재배포(코드 갱신 시):
```bash
./deploy/deploy.sh --pull
```

---

## 5. 도메인 + SSL

### 5-1. DNS
도메인 구매 후 A 레코드: `@`(또는 서브도메인) → `<PUBLIC_IP>`. 전파 후 `dig +short your.domain.com` 확인.

### 5-2. 인증서 발급 (webroot 방식)
nginx가 80에서 `/.well-known/acme-challenge/`를 `./certbot/www`로 서빙 중인 상태에서:
```bash
docker run --rm \
  -v "$PWD/docker/certbot/conf:/etc/letsencrypt" \
  -v "$PWD/docker/certbot/www:/var/www/certbot" \
  certbot/certbot certonly --webroot -w /var/www/certbot \
  -d your.domain.com --email you@example.com --agree-tos --no-eff-email
```

### 5-3. SSL 설정 적용
```bash
sed 's/__DOMAIN__/your.domain.com/g' deploy/nginx.ssl.conf > docker/nginx/nginx.conf
docker compose -f docker/app-compose.yml restart nginx
curl https://your.domain.com/actuator/health
```

### 5-4. 자동 갱신 (cron)
```bash
# crontab -e
0 3 * * * cd /home/ubuntu/premium-spread && docker run --rm -v "$PWD/docker/certbot/conf:/etc/letsencrypt" -v "$PWD/docker/certbot/www:/var/www/certbot" certbot/certbot renew --quiet && docker compose -f docker/app-compose.yml restart nginx
```

---

## ARM64 호환성 검증 결과

모든 베이스 이미지가 arm64 멀티아치 지원, 앱 이미지는 **인스턴스에서 직접 빌드**하므로 `--platform` 불필요:

| 이미지 | arm64 |
|---|---|
| eclipse-temurin:21-jdk/jre-alpine (api·batch) | ✅ |
| node:20-alpine (web) | ✅ |
| mysql:8.0 / redis:7-alpine / nginx:alpine | ✅ |
| prom/prometheus / grafana/grafana | ✅ |

> x86 머신에서 빌드해 푸시하는 경우에만 `docker buildx --platform linux/arm64`가 필요. 본 런북(on-instance build)에는 해당 없음.

## 운영 메모

- 모니터링(`monitoring-compose.yml`)은 별도 네트워크 + Grafana 기본 비번(admin/admin) → 공개 배포 시 비번 변경·네트워크 정리 후 올릴 것. 필수 아님.
- 로그: `docker logs -f premium-spread-api` / `premium-spread-batch`
- 헬스 키/메트릭: `ws.connection.state`, `ws.stale.*`, `ws.last.message.age` (배치 WebSocket 수집 상태)
