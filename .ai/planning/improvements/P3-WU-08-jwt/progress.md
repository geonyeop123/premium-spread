# WU-08: JWT 인증 전환 — 진행 상황

## 상태: 완료

## 완료 항목

- [x] jjwt 의존성 추가 (jjwt-api, jjwt-impl, jjwt-jackson 0.12.6)
- [x] spring-session-data-redis 의존성 제거
- [x] JwtTokenProvider 구현 (Access Token 30분, Refresh Token 7일)
- [x] JwtTokenProvider 단위 테스트 (생성, 검증, 만료, 위조 등)
- [x] JwtAuthenticationFilter 구현 (Bearer 토큰 파싱, SecurityContext 설정)
- [x] JwtAuthenticationFilter 단위 테스트
- [x] SecurityConfig Stateless 전환 (SessionCreationPolicy.STATELESS)
- [x] LoginSuccessHandler JWT 발급 변경 (accessToken 응답 + refresh_token HTTP-only 쿠키)
- [x] AuthController 구현 (/api/v1/auth/refresh, /api/v1/auth/logout)
- [x] 기존 WebMvcTest에 JwtTokenProvider MockkBean 추가 (PositionControllerTest, TickerControllerTest, PremiumControllerTest)
- [x] HTTP 샘플 파일 갱신 (members.http, auth.http 신규)
- [x] application.yml에 jwt 설정 추가 (local/test용 dummy secret 포함)
- [x] 전체 단위 테스트 통과 확인

## Known Limitations

- 서버 측 토큰 파기(블랙리스트)는 이번 범위에서 제외
- Refresh Token Rotation만 적용 (탈취 시 완전 무효화 불가)
