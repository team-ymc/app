import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { UsageMeter } from './UsageMeter';

afterEach(cleanup);

function bar(container: HTMLElement): HTMLElement {
  // 트랙(고정 높이 배경) 안의 채움 div
  return container.querySelector('[data-part="fill"]') as HTMLElement;
}

describe('UsageMeter', () => {
  it('MONTHLY: used / limit와 캡션을 표시하고 바 너비는 사용률', () => {
    const { container } = render(<UsageMeter label="AI 질의" used={37} limit={100} resetLabel="남은 질문 63회 · 9월 1일 초기화" />);
    expect(screen.getByText('37 / 100')).toBeTruthy();
    expect(screen.getByText('남은 질문 63회 · 9월 1일 초기화')).toBeTruthy();
    expect(bar(container).style.width).toBe('37%');
    expect(bar(container).style.background).toBe('var(--color-primary)');
  });

  it('소진(remaining 0)이면 바가 danger 색', () => {
    const { container } = render(<UsageMeter label="문서 등록" used={3} limit={3} />);
    expect(bar(container).style.background).toBe('var(--color-danger)');
  });

  it('UNLIMITED: 무제한 표시, 횟수 미노출, 기본 캡션', () => {
    const { container } = render(<UsageMeter label="AI 질의" unlimited />);
    expect(screen.getByText('무제한')).toBeTruthy();
    expect(screen.queryByText(/\//)).toBeNull();
    expect(screen.getByText('이번 달 제한 없음')).toBeTruthy();
    expect(bar(container).style.width).toBe('100%');
  });
});
