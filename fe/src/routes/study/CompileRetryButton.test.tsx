import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { CompileRetryButton } from './CompileRetryButton';

afterEach(cleanup);

describe('CompileRetryButton', () => {
  it('누르면 재시도가 불린다', () => {
    const onRetry = vi.fn();
    render(<CompileRetryButton busy={false} error={null} blocked={false} onRetry={onRetry} />);
    fireEvent.click(screen.getByRole('button', { name: '번역·지식 그래프 다시 만들기' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('요청 중에는 비활성이다', () => {
    const onRetry = vi.fn();
    render(<CompileRetryButton busy error={null} blocked={false} onRetry={onRetry} />);
    const button = screen.getByRole('button', { name: '번역·지식 그래프 다시 만들기' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
  });

  it('실패하면 사유를 툴팁과 보조 기술에 알린다', () => {
    render(<CompileRetryButton busy={false} error="처리할 수 없는 파일입니다" blocked={false} onRetry={vi.fn()} />);
    const button = screen.getByRole('button', { name: '번역·지식 그래프 다시 만들기' });
    expect(button.getAttribute('title')).toBe('처리할 수 없는 파일입니다');
    expect(screen.getByRole('status').textContent).toBe('처리할 수 없는 파일입니다');
  });

  it('거절된 뒤에는 비활성으로 남고 사유를 툴팁에 둔다', () => {
    const onRetry = vi.fn();
    render(<CompileRetryButton busy={false} error="번역과 지식 그래프를 더 이상 다시 만들 수 없습니다" blocked onRetry={onRetry} />);
    const button = screen.getByRole('button', { name: '번역·지식 그래프 다시 만들기' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.getAttribute('title')).toBe('번역과 지식 그래프를 더 이상 다시 만들 수 없습니다');
    fireEvent.click(button);
    expect(onRetry).not.toHaveBeenCalled();
  });
});
