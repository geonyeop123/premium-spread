# Quality Gate Docker image build 시간 단축 (issue #107)

GitHub Actions cache 한도 초과로 생긴 Docker build 시간 편차를 없애고(PR cache 쓰기 중단·PR/Gradle cache 자동 정리),
API·Batch·Web 이미지를 matrix 로 병렬 빌드한 뒤 기존 `docker-images-<sha>` artifact 하나로 조립한다.

## 문서

- [설계](design.md) — 원인 분석, F1~F8 외부 사실(F5: 누락 blob 은 빌드 실패), D1~D7
- [실행 계획](plan.md) — Task 0~7
- [완료 기준 계약서](dod.md) — 수용기준·증거 로그·변경 요청(AC2·AC3 개정)
- [개발자 이해문서](understanding.md) — 배경·결정·함정·후속
- 이슈: #107 (선행 #104)
