import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { Badge } from './Badge';

afterEach(cleanup);

describe('Badge', () => {
  it('children을 렌더하고 tone 색을 적용한다', () => {
    render(<Badge tone="proOnDark">Pro</Badge>);
    const el = screen.getByText('Pro');
    expect(el.style.color).toBe('var(--color-accent-brass-on-dark)');
  });

  it('icon이 있으면 아이콘을 함께 렌더한다', () => {
    const { container } = render(<Badge tone="pro" icon="seal-check">Pro</Badge>);
    expect(container.querySelector('svg')).not.toBeNull();
  });

  it('모르는 tone은 neutral로 폴백한다', () => {
    // @ts-expect-error 런타임 폴백 확인
    render(<Badge tone="nope">Free</Badge>);
    expect(screen.getByText('Free').style.color).toBe('var(--color-text-muted)');
  });
});
