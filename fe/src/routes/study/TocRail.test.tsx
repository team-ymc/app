import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { TocRail } from './TocRail';

afterEach(cleanup);

const toc = [
  { blockId: 'b1', text: 'Introduction', level: 2 },
  { blockId: 'b2', text: 'Method', level: 2 },
];

describe('TocRail — 목차 레일', () => {
  it('목차가 레일보다 길면 목록 안에서 스크롤된다', () => {
    render(<TocRail toc={toc} activeId={null} tocOpen onToggle={vi.fn()} onJump={vi.fn()} />);
    const list = screen.getByRole('button', { name: 'Method' }).parentElement as HTMLElement;
    expect(list.style.overflowY).toBe('auto');
  });
});
