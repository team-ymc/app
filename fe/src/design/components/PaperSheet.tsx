import type { CSSProperties, ReactNode } from 'react';

export interface PaperSheetProps {
  title?: string;
  meta?: string;
  children?: ReactNode;
  style?: CSSProperties;
}

export function PaperSheet({ title, meta, children, style }: PaperSheetProps) {
  return (
    <article
      style={{
        background: 'var(--color-bg-paper)',
        borderRadius: 'var(--radius-paper)',
        boxShadow: 'var(--shadow-paper)',
        padding: '64px',
        maxWidth: '720px',
        margin: '0 auto',
        fontFamily: 'var(--font-serif)',
        ...style,
      }}
    >
      {title ? (
        <h1
          style={{
            fontSize: 'var(--paper-title-size)',
            fontWeight: 'var(--paper-title-weight)',
            lineHeight: 'var(--paper-title-line)',
            letterSpacing: 'var(--paper-title-tracking)',
            color: 'var(--color-text-heading)',
            margin: '0 0 8px',
          }}
        >
          {title}
        </h1>
      ) : null}
      {meta ? (
        <p
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: 'var(--caption-size)',
            color: 'var(--color-text-muted)',
            margin: '0 0 32px',
          }}
        >
          {meta}
        </p>
      ) : null}
      <div
        style={{
          fontSize: 'var(--paper-body-size)',
          fontWeight: 'var(--paper-body-weight)',
          lineHeight: 'var(--paper-body-line)',
          color: 'var(--color-text-body)',
        }}
      >
        {children}
      </div>
    </article>
  );
}
