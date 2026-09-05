import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import PaperTitleEditor from './PaperTitleEditor';

afterEach(cleanup);

function renderEditing(filename = 'attention.pdf') {
  const onSave = vi.fn();
  const onCancel = vi.fn();
  const rowKey = vi.fn();
  render(
    <div role="button" onKeyDown={rowKey}>
      <PaperTitleEditor filename={filename} editing titleStyle={{}} onSave={onSave} onCancel={onCancel} />
    </div>,
  );
  const input = screen.getByRole('textbox', { name: '새 이름' }) as HTMLInputElement;
  return { input, onSave, onCancel, rowKey };
}

describe('PaperTitleEditor', () => {
  it('editing이 아니면 제목만 보인다', () => {
    render(<PaperTitleEditor filename="attention.pdf" editing={false} titleStyle={{}} onSave={() => {}} onCancel={() => {}} />);
    expect(screen.getByText('attention.pdf')).toBeTruthy();
    expect(screen.queryByRole('textbox')).toBeNull();
  });

  it('편집 모드는 stem만 입력창에 두고 .pdf를 고정 표시하며 포커스·선택된다', () => {
    const { input } = renderEditing();
    expect(input.value).toBe('attention');
    expect(screen.getByText('.pdf')).toBeTruthy();
    expect(document.activeElement).toBe(input);
    expect(input.selectionStart).toBe(0);
    expect(input.selectionEnd).toBe('attention'.length);
  });

  it('Enter로 저장하면 .pdf를 붙인 이름으로 onSave가 불리고 행 키 핸들러는 안 불린다', () => {
    const { input, onSave, rowKey } = renderEditing();
    fireEvent.change(input, { target: { value: ' renamed ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onSave).toHaveBeenCalledWith('renamed.pdf');
    expect(rowKey).not.toHaveBeenCalled();
  });

  it('Esc는 onCancel, 빈 값·동일 값은 onSave 없이 onCancel', () => {
    const a = renderEditing();
    fireEvent.keyDown(a.input, { key: 'Escape' });
    expect(a.onCancel).toHaveBeenCalledTimes(1);
    expect(a.onSave).not.toHaveBeenCalled();
    cleanup();

    const b = renderEditing();
    fireEvent.change(b.input, { target: { value: '   ' } });
    fireEvent.keyDown(b.input, { key: 'Enter' });
    expect(b.onSave).not.toHaveBeenCalled();
    expect(b.onCancel).toHaveBeenCalledTimes(1);
    cleanup();

    const c = renderEditing();
    fireEvent.blur(c.input);
    expect(c.onSave).not.toHaveBeenCalled();
    expect(c.onCancel).toHaveBeenCalledTimes(1);
  });

  it('blur는 저장으로 처리하되 Enter 직후의 blur는 두 번 저장하지 않는다', () => {
    const { input, onSave } = renderEditing();
    fireEvent.change(input, { target: { value: 'x' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    fireEvent.blur(input);
    expect(onSave).toHaveBeenCalledTimes(1);
  });
});
