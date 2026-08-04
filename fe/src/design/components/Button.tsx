import { useState } from 'react';
import type { CSSProperties, MouseEventHandler, ReactNode } from 'react';
import { iconComponent } from './icons';

export type ButtonVariant = 'primary' | 'secondary' | 'landing' | 'text';

export interface ButtonProps {
  variant?: ButtonVariant;
  icon?: string;
  disabled?: boolean;
  type?: 'button' | 'submit' | 'reset';
  children?: ReactNode;
  onClick?: MouseEventHandler<HTMLButtonElement>;
  style?: CSSProperties;
}

interface VariantStyle {
  background: string;
  color: string;
  border: string;
  radius: string;
  padding: string;
  height: string;
}

const VARIANTS: Record<ButtonVariant, VariantStyle> = {
  primary: {
    background: 'var(--color-primary)',
    color: 'var(--color-on-primary)',
    border: '1px solid transparent',
    radius: 'var(--radius-pill)',
    padding: '11px 20px',
    height: '44px',
  },
  secondary: {
    background: 'var(--color-bg-paper)',
    color: 'var(--color-primary)',
    border: '1px solid var(--color-border)',
    radius: 'var(--radius-control)',
    padding: '10px 16px',
    height: '42px',
  },
  landing: {
    background: 'var(--color-bg-paper)',
    color: 'var(--color-primary)',
    border: '1px solid transparent',
    radius: 'var(--radius-pill)',
    padding: '13px 24px',
    height: '48px',
  },
  text: {
    background: 'transparent',
    color: 'var(--color-primary)',
    border: 'none',
    radius: 'var(--radius-none)',
    padding: '4px 2px',
    height: 'auto',
  },
};

const HOVER: Record<ButtonVariant, string> = {
  primary: 'var(--color-primary-hover)',
  secondary: 'var(--color-primary-subtle)',
  landing: 'var(--color-primary-subtle)',
  text: 'var(--color-primary-subtle)',
};

export function Button({
  variant = 'primary',
  icon,
  disabled = false,
  type = 'button',
  children,
  onClick,
  style,
}: ButtonProps) {
  const v = VARIANTS[variant] || VARIANTS.primary;
  const [hover, setHover] = useState(false);
  const IconCmp = icon ? iconComponent(icon) : undefined;
  return (
    <button
      type={type}
      disabled={disabled}
      onClick={onClick}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: '8px',
        fontFamily: 'var(--font-sans)',
        fontSize: 'var(--ui-strong-size)',
        fontWeight: 'var(--ui-strong-weight)',
        letterSpacing: 'var(--ui-strong-tracking)',
        lineHeight: 1,
        background: variant === 'primary' && hover ? HOVER.primary : v.background,
        color: v.color,
        border: v.border,
        borderRadius: v.radius,
        padding: v.padding,
        height: v.height,
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.5 : 1,
        textDecoration: variant === 'text' && hover ? 'underline' : 'none',
        borderColor: variant === 'secondary' && hover ? 'var(--color-primary)' : v.border,
        transition: 'background 150ms ease, border-color 150ms ease',
        ...style,
      }}
    >
      {IconCmp ? <IconCmp size={16} weight="regular" /> : null}
      {children}
    </button>
  );
}
