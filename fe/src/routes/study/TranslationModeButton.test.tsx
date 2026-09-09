import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { TranslationModeButton } from './TranslationModeButton';

afterEach(cleanup);

function button(): HTMLButtonElement {
  return screen.getByRole('button', { name: /번역/ }) as HTMLButtonElement;
}

describe('TranslationModeButton — 전체 번역 순환 버튼', () => {
  it('모드가 라벨과 aria-pressed로 드러난다', () => {
    const { rerender } = render(<TranslationModeButton mode="off" disabled={false} onCycle={vi.fn()} />);
    expect(button().textContent).toBe('번역');
    expect(button().getAttribute('aria-pressed')).toBe('false');

    rerender(<TranslationModeButton mode="below" disabled={false} onCycle={vi.fn()} />);
    expect(button().textContent).toBe('번역 · 아래');
    expect(button().getAttribute('aria-pressed')).toBe('true');

    rerender(<TranslationModeButton mode="side" disabled={false} onCycle={vi.fn()} />);
    expect(button().textContent).toBe('번역 · 옆');
    expect(button().getAttribute('aria-pressed')).toBe('true');
  });

  it('클릭하면 onCycle이 불린다', () => {
    const onCycle = vi.fn();
    render(<TranslationModeButton mode="off" disabled={false} onCycle={onCycle} />);
    fireEvent.click(button());
    expect(onCycle).toHaveBeenCalledTimes(1);
  });

  it('비활성이면 클릭이 무시되고 사유가 title로 보인다', () => {
    const onCycle = vi.fn();
    render(
      <TranslationModeButton mode="off" disabled disabledReason="이 논문은 번역이 준비되지 않았습니다" onCycle={onCycle} />,
    );
    expect(button().disabled).toBe(true);
    expect(button().title).toBe('이 논문은 번역이 준비되지 않았습니다');
    expect(button().getAttribute('aria-disabled')).toBe('true');
    fireEvent.click(button());
    expect(onCycle).not.toHaveBeenCalled();
  });
});
