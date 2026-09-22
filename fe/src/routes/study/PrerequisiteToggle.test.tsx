import { fireEvent, render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PrerequisiteToggle } from './PrerequisiteToggle';

afterEach(cleanup);

describe('PrerequisiteToggle', () => {
  it('role=switch이고 aria-checked를 반영한다', () => {
    render(<PrerequisiteToggle checked disabled={false} onToggle={() => {}} />);
    expect(screen.getByRole('switch', { name: '선행지식' }).getAttribute('aria-checked')).toBe('true');
  });

  it('누르면 onToggle', () => {
    const onToggle = vi.fn();
    render(<PrerequisiteToggle checked={false} disabled={false} onToggle={onToggle} />);
    fireEvent.click(screen.getByRole('switch'));
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  it('비활성이면 사유가 title에 있고 눌러도 onToggle이 없다', () => {
    const onToggle = vi.fn();
    render(<PrerequisiteToggle checked={false} disabled disabledReason="표시할 선행지식이 없습니다" onToggle={onToggle} />);
    const sw = screen.getByRole('switch') as HTMLButtonElement;
    expect(sw.disabled).toBe(true);
    expect(sw.getAttribute('title')).toBe('표시할 선행지식이 없습니다');
    fireEvent.click(sw);
    expect(onToggle).not.toHaveBeenCalled();
  });
});
