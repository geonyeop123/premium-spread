# Git 히스토리에서 평문 API 키 제거 가이드

## 배경
`6b3707324e1b85989002df17` 키가 Git 히스토리에 남아 있으므로
아래 절차로 완전 제거 후 force push가 필요합니다.

## 방법: git filter-repo 사용 (권장)

### 1. 설치
```bash
pip install git-filter-repo
```

### 2. 히스토리에서 문자열 제거
```bash
git filter-repo --replace-text <(echo "6b3707324e1b85989002df17==>REDACTED_API_KEY")
```

### 3. 원격 저장소 force push
```bash
git push origin --force --all
git push origin --force --tags
```

### 4. 팀원 공지
팀원 전원이 `git fetch --all && git reset --hard origin/<branch>` 실행 필요

## 주의사항
- force push 전 반드시 팀원과 협의
- PR이 열려 있는 경우 베이스 브랜치 갱신 필요
- GitHub에서 캐시된 커밋 뷰 갱신에 시간이 걸릴 수 있음
