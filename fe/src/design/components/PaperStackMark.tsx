import type { CSSProperties } from 'react';

export interface PaperStackMarkProps {
  size?: number;
  color?: string;
  style?: CSSProperties;
}

export function PaperStackMark({ size = 32, color = 'currentColor', style }: PaperStackMarkProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" fill="none" style={style}>
      <rect x={6} y={10} width={20} height={16} rx={1} stroke={color} strokeWidth={1.5} />
      <rect
        x={4}
        y={7}
        width={20}
        height={16}
        rx={1}
        stroke={color}
        strokeWidth={1.5}
        fill="var(--color-bg-paper, #FFFDF7)"
      />
      <path
        d="M4 7 H20 L24 11 V23 H4 Z"
        stroke={color}
        strokeWidth={1.5}
        fill="var(--color-bg-paper, #FFFDF7)"
      />
      <path d="M20 7 V11 H24" stroke={color} strokeWidth={1.5} fill="none" />
      <line x1={8} y1={15} x2={18} y2={15} stroke={color} strokeWidth={1.2} />
      <line x1={8} y1={18.5} x2={15} y2={18.5} stroke={color} strokeWidth={1.2} />
    </svg>
  );
}
