// 이식: Paper Study Page.dc.html R3 TOC nav rail (railStyle/toc item rowStyle 값 그대로).
import type { CSSProperties } from 'react';
import { IconButton } from '../../design/components/IconButton';
import type { TocEntry } from '../../markdown/paperContent';

export interface TocRailProps {
  toc: TocEntry[];
  activeId: string | null;
  tocOpen: boolean;
  onToggle: () => void;
  onJump: (blockId: string) => void;
}

export function TocRail({ toc, activeId, tocOpen, onToggle, onJump }: TocRailProps) {
  const railStyle: CSSProperties = {
    width: tocOpen ? '260px' : '44px',
    flexShrink: 0,
    background: 'var(--color-bg-surface)',
    borderRight: '1px solid var(--color-border)',
    display: 'flex',
    flexDirection: 'column',
    alignItems: tocOpen ? 'flex-start' : 'center',
    padding: `10px ${tocOpen ? '16px' : '0'}`,
    gap: '12px',
    overflow: 'hidden',
    boxSizing: 'border-box',
    transition: 'width 200ms ease',
  };

  return (
    <div style={railStyle}>
      <IconButton
        icon={tocOpen ? 'x' : 'list'}
        label={tocOpen ? '목차 닫기' : '목차 열기'}
        size={32}
        onClick={onToggle}
      />
      {tocOpen && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '2px', width: '100%' }}>
          {toc.map((item) => {
            const active = item.blockId === activeId;
            const sub = item.level >= 3;
            const rowStyle: CSSProperties = {
              display: 'flex',
              alignItems: 'center',
              width: '100%',
              textAlign: 'left',
              padding: sub ? '7px 10px 7px 26px' : '9px 10px',
              border: 'none',
              borderRadius: '6px',
              background: active ? 'var(--color-primary-subtle)' : 'transparent',
              color: active ? 'var(--color-primary)' : 'var(--color-text-body)',
              fontFamily: 'var(--font-sans)',
              fontSize: sub ? '12.5px' : '13px',
              fontWeight: active ? 600 : 400,
              cursor: 'pointer',
              transition: 'background 150ms ease',
            };
            return (
              <button key={item.blockId} onClick={() => onJump(item.blockId)} style={rowStyle}>
                {item.text}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
