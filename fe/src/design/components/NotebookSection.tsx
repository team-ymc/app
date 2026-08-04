import type { ReactNode } from 'react';

export interface NotebookSectionProps {
  label?: string;
  children?: ReactNode;
}

export function NotebookSection({ label, children }: NotebookSectionProps) {
  return (
    <div>
      {label ? (
        <div
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: 'var(--label-size)',
            fontWeight: 'var(--label-weight)',
            letterSpacing: 'var(--label-tracking)',
            textTransform: 'uppercase',
            color: 'var(--color-accent-brass)',
            marginBottom: '6px',
          }}
        >
          {label}
        </div>
      ) : null}
      <div
        style={{
          fontFamily: 'var(--font-serif)',
          fontSize: 'var(--ui-body-size)',
          lineHeight: 'var(--paper-body-line)',
          color: 'var(--color-text-body)',
        }}
      >
        {children}
      </div>
    </div>
  );
}
