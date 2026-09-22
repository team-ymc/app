// 상단바 선행지식 토글 (Design v3 role=switch). 치수는 아트보드 .switch-track·.switch-thumb 그대로.
export interface PrerequisiteToggleProps {
  checked: boolean;
  disabled: boolean;
  /** 비활성 사유 — 툴팁으로 보인다. */
  disabledReason?: string;
  onToggle: () => void;
}

export function PrerequisiteToggle({ checked, disabled, disabledReason, onToggle }: PrerequisiteToggleProps) {
  const on = checked && !disabled;
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label="선행지식"
      disabled={disabled}
      title={disabled ? disabledReason : '선행지식'}
      onClick={onToggle}
      style={{
        display: 'flex', alignItems: 'center', gap: '9px', padding: '8px 0',
        background: 'transparent', border: 'none', cursor: disabled ? 'default' : 'pointer',
        color: on ? '#fffdf7' : 'rgba(255,253,247,0.7)', opacity: disabled ? 0.5 : 1,
        fontFamily: 'var(--font-sans)', fontSize: '14px',
      }}
    >
      <span>선행지식</span>
      <span aria-hidden="true" style={{
        position: 'relative', width: '34px', height: '19px', borderRadius: '999px',
        background: on ? '#b8924e' : '#74665e', boxShadow: 'inset 0 0 0 1px rgba(255,255,255,.12)',
        transition: 'background .16s ease',
      }}>
        <span style={{
          position: 'absolute', top: '3px', left: '3px', width: '13px', height: '13px', borderRadius: '50%',
          background: on ? '#fffdf7' : '#eee9e1', boxShadow: '0 1px 2px rgba(0,0,0,.3)',
          transform: on ? 'translateX(15px)' : 'none',
          transition: 'transform .16s ease, background .16s ease',
        }} />
      </span>
    </button>
  );
}
