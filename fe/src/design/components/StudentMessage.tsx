import type { CSSProperties, ReactNode } from 'react';

export interface StudentMessageProps {
  children?: ReactNode;
  style?: CSSProperties;
}

export function StudentMessage({ children, style }: StudentMessageProps) {
  return (
    <div
      style={{
        display: 'inline-block',
        background: 'var(--color-bg-paper)',
        color: 'var(--color-primary)',
        border: '1px solid var(--color-primary)',
        borderRadius: 'var(--radius-message)',
        padding: '12px 14px',
        fontFamily: 'var(--font-sans)',
        fontSize: 'var(--ui-body-size)',
        lineHeight: 'var(--ui-body-line)',
        maxWidth: '80%',
        marginLeft: 'auto',
        ...style,
      }}
    >
      {children}
    </div>
  );
}
