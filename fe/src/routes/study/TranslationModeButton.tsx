// 툴바 전체 번역 순환 버튼(안 보기 → 아래 → 옆). 스타일은 아트보드 상단바 칩 값 그대로.
// design/components/IconButton은 아이콘 전용이라 가시 라벨·aria-pressed가 필요한 여기서는 쓰지 않는다.
import { useState } from 'react';
import { Translate } from '@phosphor-icons/react';
import type { TranslationMode } from './PaperViewer';

export interface TranslationModeButtonProps {
  mode: TranslationMode;
  disabled: boolean;
  /** 비활성 사유 — 툴팁으로 보인다. */
  disabledReason?: string;
  onCycle: () => void;
}

const LABEL: Record<TranslationMode, string> = { off: '번역', below: '번역 · 아래', side: '번역 · 옆' };

export function TranslationModeButton({ mode, disabled, disabledReason, onCycle }: TranslationModeButtonProps) {
  const [hover, setHover] = useState(false);
  const label = LABEL[mode];
  const active = mode !== 'off';
  const paperChip = active && !disabled;

  const bg = disabled ? 'rgba(255,253,247,0.05)' : paperChip ? 'var(--color-bg-paper)' : 'rgba(255,253,247,0.08)';
  const border = disabled ? 'rgba(255,253,247,0.12)' : paperChip ? 'var(--color-bg-paper)' : 'rgba(255,253,247,0.18)';
  const color = paperChip ? 'var(--color-primary)' : 'rgba(255,253,247,0.88)';
  const hovered = hover && !disabled;

  return (
    <button
      type="button"
      onClick={onCycle}
      disabled={disabled}
      aria-disabled={disabled}
      aria-pressed={active}
      title={disabled ? disabledReason : label}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        height: '32px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: '7px',
        flexShrink: 0,
        background: hovered ? (paperChip ? 'var(--color-bg-surface)' : 'rgba(255,253,247,0.16)') : bg,
        border: `1px solid ${hovered ? (paperChip ? 'var(--color-bg-surface)' : 'rgba(255,253,247,0.32)') : border}`,
        borderRadius: '8px',
        color: hovered && !paperChip ? 'var(--color-on-dark)' : color,
        opacity: disabled ? 0.42 : 1,
        padding: '0 10px 0 8px',
        cursor: disabled ? 'not-allowed' : 'pointer',
        fontFamily: 'var(--font-sans)',
        fontSize: '12px',
        fontWeight: 600,
        whiteSpace: 'nowrap',
        transition: 'background 150ms ease, color 150ms ease, border-color 150ms ease',
      }}
    >
      <Translate size={16} />
      <span>{label}</span>
    </button>
  );
}
