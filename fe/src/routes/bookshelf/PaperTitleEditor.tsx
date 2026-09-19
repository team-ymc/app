// 서재 표시 제목을 그 자리에서 편집한다. 진입은 부모가 editing으로 켠다.
import { useEffect, useRef, useState, type CSSProperties } from 'react';

export interface PaperTitleEditorProps {
  title: string;
  editing: boolean;
  titleStyle: CSSProperties;
  onSave: (title: string) => void;
  onCancel: () => void;
}

export default function PaperTitleEditor({ title, editing, titleStyle, onSave, onCancel }: PaperTitleEditorProps) {
  if (!editing) return <div style={titleStyle}>{title}</div>;
  return <Editor title={title} titleStyle={titleStyle} onSave={onSave} onCancel={onCancel} />;
}

function Editor({ title, titleStyle, onSave, onCancel }: Omit<PaperTitleEditorProps, 'editing'>) {
  const [value, setValue] = useState(title);
  const inputRef = useRef<HTMLInputElement>(null);
  const doneRef = useRef(false); // Enter 뒤 따라오는 blur가 두 번 저장하지 않게

  useEffect(() => {
    inputRef.current?.focus();
    inputRef.current?.select();
  }, []);

  function finish(save: boolean) {
    if (doneRef.current) return;
    doneRef.current = true;
    const next = value.trim();
    if (!save || !next || next === title) {
      onCancel();
      return;
    }
    onSave(next);
  }

  return (
    <div
      style={{ flex: 1, minWidth: 0, display: 'flex', alignItems: 'center', gap: '8px' }}
      onClick={(e) => e.stopPropagation()}
      onKeyDown={(e) => e.stopPropagation()}
    >
      <input
        ref={inputRef}
        aria-label="새 이름"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') finish(true);
          if (e.key === 'Escape') finish(false);
        }}
        onBlur={() => finish(true)}
        style={{
          ...titleStyle,
          flex: 1,
          minWidth: 0,
          background: 'var(--color-bg-paper)',
          border: '1px solid var(--color-primary)',
          borderRadius: 'var(--radius-structural)',
          padding: '4px 10px',
          outline: 'none',
        }}
      />
    </div>
  );
}
