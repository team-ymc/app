import { useState } from 'react';
import type { CSSProperties, MouseEventHandler } from 'react';
import * as PhosphorIcons from '@phosphor-icons/react';
import type { Icon } from '@phosphor-icons/react';

export interface IconButtonProps {
  icon: string;
  label: string;
  selected?: boolean;
  disabled?: boolean;
  size?: number;
  onClick?: MouseEventHandler<HTMLButtonElement>;
  style?: CSSProperties;
}

// Phosphor CDN webfont classes (`ph ph-<icon>`) in the original design system are
// replaced with @phosphor-icons/react components. `icon` stays a kebab-case string
// prop (as in the original), resolved to the matching PascalCase icon component.
function iconComponent(name: string): Icon | undefined {
  const pascal = name
    .split('-')
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join('');
  return (PhosphorIcons as unknown as Record<string, Icon>)[pascal];
}

export function IconButton({
  icon,
  label,
  selected = false,
  disabled = false,
  size = 40,
  onClick,
  style,
}: IconButtonProps) {
  const [hover, setHover] = useState(false);
  const IconCmp = iconComponent(icon);
  return (
    <button
      aria-label={label}
      title={label}
      disabled={disabled}
      onClick={onClick}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        width: `${size}px`,
        height: `${size}px`,
        borderRadius: 'var(--radius-pill)',
        border: 'none',
        background: selected ? 'var(--color-primary-subtle)' : hover ? 'var(--color-primary-subtle)' : 'transparent',
        color: 'var(--color-primary)',
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.5 : 1,
        transition: 'background 150ms ease',
        ...style,
      }}
    >
      {IconCmp ? <IconCmp size={Math.round(size * 0.45)} weight="regular" /> : null}
    </button>
  );
}
