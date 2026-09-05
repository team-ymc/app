import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import PaperRowMenu from './PaperRowMenu';
import type { Paper } from '../../api/types';

afterEach(cleanup);

const completed: Paper = {
  paperId: 'p1', filename: 'a.pdf', status: 'COMPLETED',
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', lastAccessedAt: null,
};

function renderInRow(paper: Paper, rowClick = vi.fn()) {
  const handlers = { onDownload: vi.fn(), onRename: vi.fn(), onDelete: vi.fn() };
  render(
    <div role="button" onClick={rowClick} data-testid="row">
      <PaperRowMenu paper={paper} {...handlers} />
    </div>,
  );
  return { rowClick, ...handlers };
}

describe('PaperRowMenu', () => {
  it('케밥을 누르면 메뉴가 열리고 행 클릭은 전파되지 않는다', () => {
    const { rowClick } = renderInRow(completed);
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    expect(screen.getByRole('menu')).toBeTruthy();
    expect(rowClick).not.toHaveBeenCalled();
  });

  it('항목을 누르면 핸들러가 불리고 메뉴가 닫힌다', () => {
    const { onRename, onDelete, rowClick } = renderInRow(completed);
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '이름 변경' }));
    expect(onRename).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '삭제' }));
    expect(onDelete).toHaveBeenCalledTimes(1);
    expect(rowClick).not.toHaveBeenCalled();
  });

  it('COMPLETED가 아니면 다운로드가 비활성이다', () => {
    const { onDownload } = renderInRow({ ...completed, status: 'PROCESSING' });
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    const item = screen.getByRole('menuitem', { name: '원본 PDF 다운로드' }) as HTMLButtonElement;
    expect(item.disabled).toBe(true);
    fireEvent.click(item);
    expect(onDownload).not.toHaveBeenCalled();
  });

  it('바깥 mousedown과 Esc로 닫힌다', () => {
    renderInRow(completed);
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole('menu')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.keyDown(screen.getByRole('button', { name: '더 보기' }), { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
  });
});
