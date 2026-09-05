import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import ConfirmDialog from './ConfirmDialog';

afterEach(cleanup);

describe('ConfirmDialog', () => {
  it('open이 아니면 아무것도 그리지 않는다', () => {
    render(<ConfirmDialog open={false} title="t" message="m" confirmLabel="삭제" busy={false} onConfirm={() => {}} onCancel={() => {}} />);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('확인·취소 버튼이 각 핸들러를 부르고 busy면 둘 다 비활성이다', () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    const { rerender } = render(
      <ConfirmDialog open title="논문을 삭제할까요?" message="본문" confirmLabel="삭제" busy={false} onConfirm={onConfirm} onCancel={onCancel} />,
    );
    expect(screen.getByRole('dialog', { name: '논문을 삭제할까요?' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '삭제' }));
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onCancel).toHaveBeenCalledTimes(1);

    rerender(<ConfirmDialog open title="논문을 삭제할까요?" message="본문" confirmLabel="삭제" busy onConfirm={onConfirm} onCancel={onCancel} />);
    expect((screen.getByRole('button', { name: '삭제' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: '취소' }) as HTMLButtonElement).disabled).toBe(true);
  });
});
