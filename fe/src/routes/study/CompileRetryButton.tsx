// 상단 바의 번역·지식 그래프 재시도 버튼. 크기·색은 옆의 번역 버튼과 같다.
import { useState } from 'react';
import { ArrowClockwise } from '@phosphor-icons/react';

export interface CompileRetryButtonProps {
  busy: boolean;
  /** 직전 재시도가 거절된 사유. 없으면 null. */
  error: string | null;
  blocked: boolean;
  onRetry: () => void;
}

const LABEL = '번역·지식 그래프 다시 만들기';

const VISUALLY_HIDDEN = {
  position: 'absolute',
  width: '1px',
  height: '1px',
  overflow: 'hidden',
  clipPath: 'inset(50%)',
  whiteSpace: 'nowrap',
} as const;

export function CompileRetryButton({ busy, error, blocked, onRetry }: CompileRetryButtonProps) {
  const [hover, setHover] = useState(false);
  const hovered = hover && !busy;

  return (
    <>
      <button
        type="button"
        onClick={onRetry}
        disabled={busy || blocked}
        aria-label={LABEL}
        title={error ?? LABEL}
        onMouseEnter={() => setHover(true)}
        onMouseLeave={() => setHover(false)}
        style={{
          height: '32px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          gap: '7px',
          flexShrink: 0,
          background: hovered ? 'rgba(255,253,247,0.14)' : 'rgba(255,253,247,0.08)',
          border: '1px solid rgba(255,253,247,0.22)',
          borderRadius: '8px',
          color: 'var(--color-on-dark)',
          opacity: busy || blocked ? 0.42 : 1,
          padding: '0 10px 0 8px',
          cursor: busy || blocked ? 'not-allowed' : 'pointer',
          fontFamily: 'var(--font-sans)',
          fontSize: '12px',
          fontWeight: 600,
          whiteSpace: 'nowrap',
          transition: 'background 150ms ease',
        }}
      >
        <ArrowClockwise size={16} />
        <span>{LABEL}</span>
      </button>
      <span role="status" style={VISUALLY_HIDDEN}>{error ?? ''}</span>
    </>
  );
}
