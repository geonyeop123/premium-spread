/**
 * 추적 기록 화면의 고지 문구.
 *
 * SEM-1·SEM-4 는 사용자가 **보는 것**에 관한 계약이므로 한 곳에 모아 두고 목록·상세가 함께 쓴다.
 * 문구를 각 화면에 흩뿌리면 한쪽만 바뀌어 계약이 갈라진다.
 */

export function NonOrderNotice() {
  return (
    <p className="text-xs text-muted-foreground">
      다른 곳에서 실제로 연 포지션을 직접 적어 두고 손익을 보는 기능입니다. 이 화면은 주문을 내지 않습니다.
    </p>
  );
}

export function GrossPnlFootnote() {
  return (
    <p className="text-xs text-muted-foreground">
      수수료·펀딩비·슬리피지·환전 스프레드가 반영되지 않은 값입니다. 계정 손익이나 실제 체결 손익이 아닙니다.
    </p>
  );
}

export function DenominatorLabel() {
  return <span className="text-xs text-muted-foreground">한국 leg 명목가 대비 %</span>;
}

export function LeverageFootnote() {
  return (
    <p className="text-xs text-muted-foreground">
      필요 증거금에만 영향을 주며 위 손익 금액에는 반영되지 않습니다.
    </p>
  );
}

export function PremiumDirectionNote() {
  return (
    <p className="text-xs text-muted-foreground">
      이 조합(한국 롱 / 해외 숏)은 프리미엄이 축소될 때 이익입니다.
    </p>
  );
}

/** priceBasis 에 따른 배지. 값을 감추지 않되 현재 시세라고 부르지 않는다. */
export function PriceBasisBadge({ priceBasis, observedAt }: { priceBasis: string; observedAt: string }) {
  const at = new Date(observedAt).toLocaleString('ko-KR');
  if (priceBasis === 'ARCHIVED_SNAPSHOT') {
    return <span className="text-xs text-muted-foreground">종료 시점({at}) 관측값 기준</span>;
  }
  if (priceBasis === 'STALE_MARKET') {
    return <span className="text-xs text-amber-600">{at} 기준 — 현재 시세가 아닙니다</span>;
  }
  return <span className="text-xs text-muted-foreground">{at} 기준</span>;
}

export function ConfirmUnavailableNotice() {
  return (
    <p className="text-xs text-muted-foreground">종료 시점 시세를 확정하지 못해 손익을 제공하지 않습니다.</p>
  );
}

export function UnknownClosedAtNotice() {
  return <span className="text-xs text-muted-foreground">종료 시각 불명</span>;
}
