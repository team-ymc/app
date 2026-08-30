import type { CSSProperties } from 'react';

// 플랜·사용량 미터. MONTHLY: used/limit + 바, UNLIMITED: '무제한' + 사선 무늬 바(횟수 미노출).
const UNLIMITED_FILL =
  'repeating-linear-gradient(135deg, var(--color-primary-selection) 0 4px, var(--color-primary-subtle) 4px 8px)';

export interface UsageMeterProps {
  label: string;
  used?: number;
  limit?: number | null;
  unlimited?: boolean;
  resetLabel?: string | null;
  compact?: boolean;
  style?: CSSProperties;
}

export function UsageMeter({ label, used = 0, limit = null, unlimited = false, resetLabel, compact = false, style }: UsageMeterProps) {
  const lim = Number(limit);
  const finite = !unlimited && Number.isFinite(lim) && lim > 0;
  const u = finite ? Math.max(0, Math.min(lim, Number(used) || 0)) : 0;
  const remaining = finite ? lim - u : Infinity;
  const pct = unlimited ? 100 : finite ? Math.round((u / lim) * 100) : 0;
  const fill = unlimited ? UNLIMITED_FILL : remaining === 0 ? 'var(--color-danger)' : 'var(--color-primary)';
  const valueText = unlimited ? '무제한' : finite ? `${u} / ${lim}` : '';
  const caption = resetLabel ?? (unlimited ? '이번 달 제한 없음' : null);
  const fs = compact ? '12px' : 'var(--caption-size)';
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', fontFamily: 'var(--font-sans)', ...style }}>
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: '12px' }}>
        <span style={{ fontSize: fs, color: 'var(--color-text-muted)' }}>{label}</span>
        <span style={{ fontSize: fs, fontWeight: 600, color: 'var(--color-text-heading)', fontVariantNumeric: 'tabular-nums' }}>
          {valueText}
        </span>
      </div>
      <div style={{ height: compact ? '4px' : '6px', borderRadius: 'var(--radius-pill)', background: 'var(--color-border)', overflow: 'hidden' }}>
        <div
          data-part="fill"
          style={{ height: '100%', width: `${pct}%`, background: fill, borderRadius: 'var(--radius-pill)', transition: 'width 200ms ease' }}
        />
      </div>
      {caption && !compact ? <div style={{ fontSize: '12px', color: 'var(--color-text-muted)' }}>{caption}</div> : null}
    </div>
  );
}
