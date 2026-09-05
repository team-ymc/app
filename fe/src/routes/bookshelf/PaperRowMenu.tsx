// 서재 행·격자 카드 공용 케밥 메뉴. 행 자체가 클릭으로 학습 페이지를 여는 버튼이라, 이 안의 이벤트는 전부 행으로 번지지 않게 막는다.
import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { DotsThree, DownloadSimple, PencilSimple, Trash } from '@phosphor-icons/react';
import type { Paper } from '../../api/types';

export interface PaperRowMenuProps {
  paper: Paper;
  onDownload: () => void;
  onRename: () => void;
  onDelete: () => void;
  size?: number;
}

const itemBase: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: '10px',
  width: '100%',
  textAlign: 'left',
  padding: '9px 10px',
  border: 'none',
  background: 'transparent',
  borderRadius: '6px',
  fontFamily: 'var(--font-sans)',
  fontSize: '13px',
  color: 'var(--color-text-body)',
  cursor: 'pointer',
  whiteSpace: 'nowrap',
};

function MenuItem({ icon, label, danger, disabled, title, onSelect }: {
  icon: ReactNode; label: string; danger?: boolean; disabled?: boolean; title?: string; onSelect: () => void;
}) {
  const [hover, setHover] = useState(false);
  return (
    <button
      role="menuitem"
      disabled={disabled}
      title={title}
      onClick={onSelect}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        ...itemBase,
        color: danger ? 'var(--color-danger)' : itemBase.color,
        background: hover && !disabled ? 'var(--color-primary-subtle)' : 'transparent',
        opacity: disabled ? 0.4 : 1,
        cursor: disabled ? 'default' : 'pointer',
      }}
    >
      {icon}
      {label}
    </button>
  );
}

export default function PaperRowMenu({ paper, onDownload, onRename, onDelete, size = 32 }: PaperRowMenuProps) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const canDownload = paper.status === 'COMPLETED';

  useEffect(() => {
    if (!open) return;
    function onDocMouseDown(e: MouseEvent) {
      if (rootRef.current && rootRef.current.contains(e.target as Node)) return;
      setOpen(false);
    }
    function onDocKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false);
    }
    document.addEventListener('mousedown', onDocMouseDown, true);
    // 래퍼가 행으로 번지는 keydown을 막으므로 capture로 먼저 받는다
    document.addEventListener('keydown', onDocKeyDown, true);
    return () => {
      document.removeEventListener('mousedown', onDocMouseDown, true);
      document.removeEventListener('keydown', onDocKeyDown, true);
    };
  }, [open]);

  function select(action: () => void) {
    setOpen(false);
    action();
  }

  return (
    <div
      ref={rootRef}
      style={{ position: 'relative', flexShrink: 0 }}
      onClick={(e) => e.stopPropagation()}
      onKeyDown={(e) => e.stopPropagation()}
    >
      <button
        aria-label="더 보기"
        title="더 보기"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        style={{
          width: `${size}px`,
          height: `${size}px`,
          border: 'none',
          borderRadius: 'var(--radius-control)',
          background: open ? 'var(--color-primary-subtle)' : 'transparent',
          color: open ? 'var(--color-text-heading)' : 'var(--color-text-muted)',
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          cursor: 'pointer',
        }}
      >
        <DotsThree size={Math.round(size * 0.56)} weight="bold" />
      </button>
      {open && (
        <div
          role="menu"
          style={{
            position: 'absolute',
            right: 0,
            top: 'calc(100% + 4px)',
            zIndex: 20,
            minWidth: '180px',
            background: 'var(--color-bg-paper)',
            border: '1px solid var(--color-border)',
            borderRadius: '8px',
            boxShadow: 'var(--shadow-menu)',
            padding: '6px',
            display: 'flex',
            flexDirection: 'column',
            gap: '2px',
          }}
        >
          <MenuItem
            icon={<DownloadSimple size={16} />}
            label="원본 PDF 다운로드"
            disabled={!canDownload}
            title={canDownload ? undefined : '분석 완료 후 다운로드할 수 있습니다'}
            onSelect={() => select(onDownload)}
          />
          <MenuItem icon={<PencilSimple size={16} />} label="이름 변경" onSelect={() => select(onRename)} />
          <div style={{ height: '1px', background: 'var(--color-border)', margin: '2px 6px' }} />
          <MenuItem icon={<Trash size={16} />} label="삭제" danger onSelect={() => select(onDelete)} />
        </div>
      )}
    </div>
  );
}
