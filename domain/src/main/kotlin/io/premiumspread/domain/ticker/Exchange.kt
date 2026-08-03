package io.premiumspread.domain.ticker

enum class Exchange(val region: ExchangeRegion) {
    /**
     * 미연결. 수집·표시 경로가 없고 main 소스 사용처가 0건이다.
     * 테스트가 pair 분리를 검증할 때 두 번째 한국 거래소로만 쓴다.
     * 실제 연결 여부는 Phase 1 의 수집 계약이 재판정한다
     * (docs/work/private-live-autotrader-phase-0/design.md §5.6).
     */
    UPBIT(ExchangeRegion.KOREA),
    BITHUMB(ExchangeRegion.KOREA),
    BINANCE(ExchangeRegion.FOREIGN),
    FX_PROVIDER(ExchangeRegion.FOREIGN),
}
