package io.premiumspread.application.common

enum class ApplicationError {
    AUTHENTICATION_FAILED,
    INVALID_REFRESH_TOKEN,
    DUPLICATE_EMAIL,
    MEMBER_NOT_FOUND,
    INVALID_TICKER,
    INVALID_QUOTE,
    INVALID_PREMIUM_INPUT,
    INVALID_TRACKING,
    DOMAIN_ERROR,
    TICKER_NOT_FOUND,
    TRACKING_NOT_FOUND,
    TRACKING_CLOSE_SNAPSHOT_UNAVAILABLE,
    PREMIUM_NOT_FOUND,
    PREMIUM_SNAPSHOT_NOT_AVAILABLE,
    STALE_PREMIUM_SNAPSHOT,
    NOTIFICATION_SUBSCRIPTION_NOT_FOUND,

    // 거래 준비 (design.md D3·D10·D13·D16·D23)
    /** 남의 계획도 여기로 온다 — 403 은 계획 ID 의 존재를 노출한다 (D10). */
    TRADE_PREPARATION_NOT_FOUND,

    /** 보유 `ACTIVE` tracking 이 있으면 준비도 목표 등록도 거절한다 (D13). */
    ACTIVE_TRACKING_EXISTS,

    /** owner 당 활성 계획 유일성을 DB unique index 가 막았다 (D16·D23의 심층 방어). */
    WATCHING_ALREADY_EXISTS,

    /** `ARMED` 계획은 새 등록이 조용히 대체하지 않는다 — owner 가 먼저 refresh·invalidate 한다 (D23). */
    ARMED_PLAN_EXISTS,

    /** 판정용 잔고가 낡았거나 확보되지 않았다. exposure 를 늘리는 요청만 거절한다 (D3·D20). */
    STALE_BALANCE_FOR_EXPOSURE,

    /** 레버 캡·효율 캡을 위반해 계획을 만들지 않았다 (design.md §3). */
    CAP_VIOLATED,

    /**
     * 캡은 위반하지 않았지만 계획을 만들 수 없다 — lot/step 반올림 뒤 물량이 0 이 된 경우다
     * (design.md D12, `TradePrepSizingCalculation.isPlannable`).
     *
     * [CAP_VIOLATED] 와 합치지 않는다. 위반한 캡이 없는데 `CAP_VIOLATED` 를 실으면 거짓이고,
     * `code` 를 비우면 클라이언트가 이 422 를 파싱 실패와 구별하지 못한다.
     */
    NOT_PLANNABLE,
}

class ApplicationException(val error: ApplicationError, cause: Throwable? = null) : RuntimeException(error.name, cause)
