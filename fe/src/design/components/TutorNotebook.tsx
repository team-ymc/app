import type { CSSProperties, ReactNode } from 'react';

export interface TutorNotebookProps {
  children?: ReactNode;
  composer?: ReactNode;
  style?: CSSProperties;
}

export function TutorNotebook({ children, composer, style }: TutorNotebookProps) {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        background: 'var(--color-bg-paper)',
        borderLeft: '1px solid var(--color-border)',
        fontFamily: 'var(--font-sans)',
        ...style,
      }}
    >
      <div
        style={{
          flex: 1,
          overflowY: 'auto',
          padding: '20px',
          display: 'flex',
          flexDirection: 'column',
          gap: '18px',
        }}
      >
        {children}
      </div>
      {composer ? (
        <div style={{ padding: '16px', borderTop: '1px solid var(--color-border)' }}>{composer}</div>
      ) : null}
    </div>
  );
}
