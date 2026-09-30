import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import FailedRowStatus from './FailedRowStatus';
import type { Paper } from '../../api/types';

afterEach(cleanup);

const failed: Paper = {
  paperId: 'p1', title: 'A paper', filename: 'a.pdf', status: 'FAILED',
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', lastAccessedAt: null,
  failReason: 'PROCESSING_FAILED',
};

function renderInRow(paper: Paper, retrying = false, stacked = false) {
  const rowClick = vi.fn();
  const rowKeyDown = vi.fn();
  const onRetry = vi.fn();
  render(
    <div role="button" tabIndex={0} onClick={rowClick} onKeyDown={rowKeyDown} data-testid="row">
      <FailedRowStatus paper={paper} retrying={retrying} onRetry={onRetry} stacked={stacked} />
    </div>,
  );
  return { rowClick, rowKeyDown, onRetry };
}

describe('FailedRowStatus', () => {
  it('재시도할 수 있는 실패는 버튼과 차감 안내를 보여준다', () => {
    renderInRow(failed);
    expect(screen.getByText('실패')).toBeTruthy();
    expect(screen.getByText('등록 횟수가 차감되지 않았습니다')).toBeTruthy();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
  });

  it('버튼을 누르면 재시도가 불리고 행 클릭으로 이어지지 않는다', () => {
    const { rowClick, onRetry } = renderInRow(failed);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(rowClick).not.toHaveBeenCalled();
  });

  it('버튼에서 누른 Enter는 행의 키 처리로 이어지지 않는다', () => {
    const { rowKeyDown } = renderInRow(failed);
    fireEvent.keyDown(screen.getByRole('button', { name: '다시 시도' }), { key: 'Enter' });
    expect(rowKeyDown).not.toHaveBeenCalled();
  });

  it('재시도 중에는 버튼이 비활성이다', () => {
    const { onRetry } = renderInRow(failed, true);
    const button = screen.getByRole('button', { name: '다시 시도' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    fireEvent.click(button);
    expect(onRetry).not.toHaveBeenCalled();
  });

  it('시도 횟수를 모두 쓴 파일은 버튼 없이 안내만 보여준다', () => {
    renderInRow({ ...failed, failReason: 'RETRY_LIMIT_EXCEEDED' });
    expect(screen.getByText('처리할 수 없는 파일입니다')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
  });

  it('실패 사유가 없으면 실패만 보여준다', () => {
    renderInRow({ ...failed, status: 'EXPIRED', failReason: null });
    expect(screen.getByText('실패')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
    expect(screen.queryByText('등록 횟수가 차감되지 않았습니다')).toBeNull();
  });

  it('격자 카드에서는 표시를 세로로 쌓는다', () => {
    renderInRow(failed, false, true);
    const layout = screen.getByTestId('failed-row-status');
    expect(layout.getAttribute('data-layout')).toBe('stacked');
    expect(layout.style.flexDirection).toBe('column');
  });

  it('목록 행에서는 표시를 가로로 놓는다', () => {
    renderInRow(failed);
    const layout = screen.getByTestId('failed-row-status');
    expect(layout.getAttribute('data-layout')).toBe('inline');
    expect(layout.style.flexDirection).toBe('row');
  });

  it('실패 사유 필드가 아예 없는 응답도 실패만 보여준다', () => {
    const { failReason: _omit, ...legacy } = failed;
    renderInRow(legacy);
    expect(screen.getByText('실패')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
  });
});
