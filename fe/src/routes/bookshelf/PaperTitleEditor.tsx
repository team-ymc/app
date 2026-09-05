// 제목 자리에서 이름만 편집한다(확장자 고정). 진입은 부모가 editing으로 켠다 — 제목에 클릭 핸들러를 두지 않는다.
import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { joinPdfName, splitPdfName } from './pdfName';

export interface PaperTitleEditorProps {
  filename: string;
  editing: boolean;
  titleStyle: CSSProperties;
  onSave: (filename: string) => void;
  onCancel: () => void;
}

export default function PaperTitleEditor({ filename, editing, titleStyle, onSave, onCancel }: PaperTitleEditorProps) {
  if (!editing) return <div style={titleStyle}>{filename}</div>;
  return <Editor filename={filename} titleStyle={titleStyle} onSave={onSave} onCancel={onCancel} />;
}

function Editor({ filename, titleStyle, onSave, onCancel }: Omit<PaperTitleEditorProps, 'editing'>) {
  const { stem, ext } = splitPdfName(filename);
  const [value, setValue] = useState(stem);
  const inputRef = useRef<HTMLInputElement>(null);
  const doneRef = useRef(false); // Enter 뒤 따라오는 blur가 두 번 저장하지 않게

  useEffect(() => {
    inputRef.current?.focus();
    inputRef.current?.select();
  }, []);

  function finish(save: boolean) {
    if (doneRef.current) return;
    doneRef.current = true;
    const next = joinPdfName(value, ext);
    if (!save || !value.trim() || next === filename) {
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
      {ext && <span style={{ ...titleStyle, flex: 'none', color: 'var(--color-text-muted)' }}>{ext}</span>}
    </div>
  );
}
