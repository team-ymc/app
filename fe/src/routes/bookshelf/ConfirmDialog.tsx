// UploadDialog와 같은 오버레이 프레임의 소형 확인 모달. 오버레이 클릭은 취소.
import { useEffect, type ReactNode } from 'react';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: ReactNode;
  confirmLabel: string;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export default function ConfirmDialog({ open, title, message, confirmLabel, busy, onConfirm, onCancel }: ConfirmDialogProps) {
  // 열려 있고 처리 중이 아닐 때 Esc로 취소한다
  useEffect(() => {
    if (!open || busy) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [open, busy, onCancel]);

  if (!open) return null;
  const btn = {
    fontFamily: 'var(--font-sans)',
    fontWeight: 600,
    fontSize: '12px',
    borderRadius: 'var(--radius-control)',
    padding: '7px 12px',
    cursor: busy ? 'not-allowed' : 'pointer',
    opacity: busy ? 0.6 : 1,
  } as const;
  return (
    <div
      onClick={busy ? undefined : onCancel}
      style={{ position: 'fixed', inset: 0, background: 'rgba(31,53,82,0.35)', zIndex: 60, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        onClick={(e) => e.stopPropagation()}
        style={{
          width: '360px',
          maxWidth: '90vw',
          background: 'var(--color-bg-paper)',
          border: '1px solid var(--color-border)',
          borderRadius: 'var(--radius-control)',
          boxShadow: 'var(--shadow-menu)',
          padding: '22px 22px 18px',
          boxSizing: 'border-box',
        }}
      >
        <div id="confirm-dialog-title" style={{ fontFamily: 'var(--font-serif)', fontSize: '16px', fontWeight: 600, color: 'var(--color-text-heading)', marginBottom: '8px' }}>
          {title}
        </div>
        <div style={{ fontFamily: 'var(--font-sans)', fontSize: '13px', color: 'var(--color-text-body)', lineHeight: 1.6, marginBottom: '18px' }}>
          {message}
        </div>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
          <button onClick={onCancel} disabled={busy} autoFocus style={{ ...btn, border: '1px solid var(--color-border)', background: 'var(--color-bg-paper)', color: 'var(--color-text-body)' }}>
            취소
          </button>
          <button onClick={onConfirm} disabled={busy} style={{ ...btn, border: '1px solid var(--color-danger)', background: 'var(--color-danger)', color: '#FFFDF7' }}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
