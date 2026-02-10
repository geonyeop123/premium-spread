# HTTP 샘플 작성 가이드

## 파일 위치

```text
http/
├── http-client.env.json   # 환경 변수 정의
└── api/
    ├── premiums.http      # Premium API
    ├── positions.http     # Position API
    └── tickers.http       # Ticker API
```

## API 추가 시 필수 작업

새로운 Controller 생성 시:

1. `http/api/{도메인}.http` 파일 생성
2. 모든 endpoint에 대한 요청 샘플 작성

기존 Controller에 endpoint 추가 시:

1. 해당 `http/api/{도메인}.http` 파일에 요청 추가

## HTTP 파일 작성 형식

```http
### {API 설명}
{METHOD} {{commerce-api}}/api/v1/{path}
Content-Type: application/json  # POST/PUT 요청 시 필수

{
  "field": "value"  # RequestBody 있을 경우
}
```

## 예시

```http
### 포지션 오픈
POST {{commerce-api}}/api/v1/positions
Content-Type: application/json

{
  "symbol": "BTC",
  "exchange": "UPBIT",
  "quantity": 0.1
}

### 포지션 조회
GET {{commerce-api}}/api/v1/positions/1
```
