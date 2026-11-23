# 김프 박스권·전략 분석

Bithumb KRW-BTC, Binance BTCUSDT(현물·USDT-M 선물), Twelve Data USD/KRW 과거 데이터로
"환율 고정 시 김프 박스권" 가설과 박스 매매 전략을 검증한 분석이다. 앱 코드는 바꾸지 않았다.

## 리포트

| 파일 | 내용 |
|------|------|
| `analysis.html` | 박스권 가설 검증 (일 스냅샷, 2017-08~) |
| `strategy.html` | 현재 위치·조건부 기대값·전략 walk-forward·환율 미헤지 수익률 |
| `minute.html` | 분 단위 재분석: 환율 A(1,300원 고정) vs B(직전 1시간 환율), 장중 범위, 분 단위 전략 |

## 데이터 위치

원시 데이터는 크기 때문에 git 에 넣지 않는다. 아래 경로에 보존한다.

```text
~/.local/share/premium-spread/kimp-box-range-analysis/data/
├── bithumb.json, binance_spot.json, binance_fut.json   # 1시간봉 [봉 종료 epoch, 종가]
├── fx_hourly.json, fx_daily.json                        # Twelve Data USD/KRW
├── funding.json                                         # Binance 펀딩비 [epoch, rate]
├── now.json                                             # 2026-11-19 실시간 스냅샷
└── min/bithumb.npy, min/binance_fut.npy                 # 1분봉 (n,2) [봉 종료 epoch, 종가]
```

저장소의 `data_snapshot/`(약 3MB)에는 1시간봉 원본 전체(gzip)와 분봉을 1시간 단위로 묶은 김프 A·B 요약
(`premium_minute_to_hourly.csv.gz`: 시간별 평균·최저·최고·분 수·환율)을 둔다. 분봉 원본이 사라져도 시간 해상도까지는 이것으로 다시 볼 수 있다.

## 재현

```bash
export KIMP_DATA_DIR=~/.local/share/premium-spread/kimp-box-range-analysis/data
export EXCHANGE_RATE_API_KEY=...            # Twelve Data (fx·fxtop·now 모드만 필요)
python fetch.py spot|fut|bithumb|fx|funding|now   # 1시간봉·펀딩·스냅샷
python fetch_minute.py fut|bithumb|fxtop          # 1분봉 (재시작 시 이어받음)
python analyze.py && python strategy.py && python minute.py
python build_report.py <branch> <sha> <date>        # analysis.html
python build_strategy_report.py <branch> <sha> <date>
python build_minute_report.py <branch> <sha> <date>
python make_snapshot.py                             # data_snapshot/ 압축 사본 갱신
```

필요 패키지: `pandas numpy statsmodels`. Twelve Data basic 플랜은 하루 800회 — 앱 배치가 5분마다 1회 쓰므로
1시간봉 전체 재수집(약 15회)은 문제없지만 1분 환율을 받으려면 이틀에 나누거나 배치를 멈춘다.
