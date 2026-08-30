import type { CSSProperties, ReactNode } from 'react';
import { iconComponent } from './icons';

export type BadgeTone = 'neutral' | 'success' | 'danger' | 'pro' | 'neutralOnDark' | 'proOnDark';

interface ToneStyle { background: string; color: string; border: string }

// 플랜 배지 톤 — Pro는 Navy Ink, 다크(월넛) 바 위에서는 *OnDark 톤을 쓴다.
const TONES: Record<BadgeTone, ToneStyle> = {
  success: { background: 'var(--color-primary-subtle)', color: 'var(--success-sage)', border: '1px solid var(--success-sage)' },
  danger: { background: 'transparent', color: 'var(--color-danger)', border: '1px solid var(--color-danger)' },
  neutral: { background: 'var(--color-bg-surface)', color: 'var(--color-text-muted)', border: '1px solid var(--color-border)' },
  pro: { background: 'var(--color-primary-subtle)', color: 'var(--color-primary)', border: '1px solid var(--color-primary)' },
  neutralOnDark: { background: 'transparent', color: 'rgba(255,253,247,0.85)', border: '1px solid rgba(255,253,247,0.35)' },
  proOnDark: { background: 'transparent', color: 'var(--color-accent-brass-on-dark)', border: '1px solid var(--color-accent-brass-on-dark)' },
};

export interface BadgeProps {
  tone?: BadgeTone;
  icon?: string;
  children?: ReactNode;
  style?: CSSProperties;
}

export function Badge({ tone = 'neutral', icon, children, style }: BadgeProps) {
  const t = TONES[tone] || TONES.neutral;
  const IconCmp = icon ? iconComponent(icon) : undefined;
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: '5px',
        fontFamily: 'var(--font-sans)',
        fontSize: 'var(--label-size)',
        fontWeight: 'var(--label-weight)' as CSSProperties['fontWeight'],
        letterSpacing: 'var(--label-tracking)',
        textTransform: 'uppercase',
        padding: '4px 10px',
        borderRadius: 'var(--radius-pill)',
        ...t,
        ...style,
      }}
    >
      {IconCmp ? <IconCmp size={12} weight="regular" /> : null}
      {children}
    </span>
  );
}
