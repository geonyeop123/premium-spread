# 알림 문구 템플릿화와 문구 정리 (issue #102)

알림 문구(owner Discord·메일, 회원 김프 메일)를 domain 템플릿 계층(`io.premiumspread.domain.alertmessage`)에서 조립하도록 바꾸고, 22종 알림 문구를 정리한다.
Discord 는 embed 레이아웃으로 저장·발송한다(V23).

## 문서

- [설계](design.md) — 템플릿 계층과 공용 서식(§3), 시간 계약(§7), 변경 내역(§15)
- [실행 계획](plan.md) — Task 0~16
- [완료 기준 계약서](dod.md) — 수용기준과 증거 로그
- [메시지 명세](message-spec.md) — 알림별 문구 spec
- [개발자 이해문서](understanding.md) — 배경·결정·함정·후속
- 이슈: #102 (후속 이슈 #105)
