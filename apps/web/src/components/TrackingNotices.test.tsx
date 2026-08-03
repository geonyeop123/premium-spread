import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import {
  NonOrderNotice,
  GrossPnlFootnote,
  DenominatorLabel,
  LeverageFootnote,
  PremiumDirectionNote,
  PriceBasisBadge,
  ConfirmUnavailableNotice,
  UnknownClosedAtNotice,
} from './TrackingNotices';

/**
 * SEM-1·SEM-4 는 사용자가 **보는 것**에 관한 계약이다.
 * 문자열이 파일에 있다는 것만으로는 주석·미사용 컴포넌트·도달 불가 분기로도 통과하므로
 * 실제 DOM 출현을 검증한다 (dod.md AC4).
 */
describe('추적 기록 고지', () => {
  it('비주문 고지가 보인다', () => {
    render(<NonOrderNotice />);
    expect(screen.getByText(/주문을 내지 않습니다/)).toBeInTheDocument();
  });

  it('gross 각주가 제외 항목과 계정 손익 아님을 함께 말한다', () => {
    render(<GrossPnlFootnote />);
    expect(screen.getByText(/수수료·펀딩비·슬리피지·환전 스프레드/)).toBeInTheDocument();
    expect(screen.getByText(/계정 손익이나 실제 체결 손익이 아닙니다/)).toBeInTheDocument();
  });

  it('백분율 분모를 라벨이 밝힌다', () => {
    render(<DenominatorLabel />);
    expect(screen.getByText(/한국 leg 명목가 대비/)).toBeInTheDocument();
  });

  it('레버리지가 손익 금액에 반영되지 않음을 밝힌다', () => {
    render(<LeverageFootnote />);
    expect(screen.getByText(/필요 증거금에만 영향/)).toBeInTheDocument();
  });

  it('프리미엄 방향을 설명한다', () => {
    render(<PremiumDirectionNote />);
    expect(screen.getByText(/프리미엄이 축소될 때 이익/)).toBeInTheDocument();
  });

  it('확정 스냅샷은 관측값 기준으로 표시하고 확정값이라 부르지 않는다', () => {
    render(<PriceBasisBadge priceBasis="ARCHIVED_SNAPSHOT" observedAt="2026-08-03T00:00:00Z" />);
    expect(screen.getByText(/관측값 기준/)).toBeInTheDocument();
  });

  it('오래된 시세는 현재 시세가 아니라고 표시한다', () => {
    render(<PriceBasisBadge priceBasis="STALE_MARKET" observedAt="2026-08-03T00:00:00Z" />);
    expect(screen.getByText(/현재 시세가 아닙니다/)).toBeInTheDocument();
  });

  it('확정하지 못한 종료는 손익을 제공하지 않는다고 밝힌다', () => {
    render(<ConfirmUnavailableNotice />);
    expect(screen.getByText(/손익을 제공하지 않습니다/)).toBeInTheDocument();
  });

  it('종료 시각을 모르면 추정하지 않고 불명으로 표시한다', () => {
    render(<UnknownClosedAtNotice />);
    expect(screen.getByText(/종료 시각 불명/)).toBeInTheDocument();
  });
});

/**
 * 회귀 차단 — 확정된 종료 기록의 손익이 화면에 도달해야 한다.
 * 상세 화면이 ACTIVE 일 때만 gross-pnl 을 조회하면, API 가 ARCHIVED_SNAPSHOT 을 돌려줘도
 * 사용자는 "확정하지 못함" 만 본다 (codex 코드리뷰 high-1).
 */
describe('확정 종료 표시', () => {
  it('ARCHIVED_SNAPSHOT 배지는 관측값 기준으로 표시된다', () => {
    render(<PriceBasisBadge priceBasis="ARCHIVED_SNAPSHOT" observedAt="2026-08-03T01:23:00Z" />);
    const badge = screen.getByText(/관측값 기준/);
    expect(badge).toBeInTheDocument();
    expect(badge.textContent).not.toMatch(/확정값/);
  });
});
