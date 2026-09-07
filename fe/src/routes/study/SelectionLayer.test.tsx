import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, cleanup, act, waitFor } from '@testing-library/react';
import { createRef } from 'react';
import { SelectionLayer } from './SelectionLayer';
import { useTextSelection, type TextSelection } from './useTextSelection';
import { translateSelection } from '../../api/translate';
import type { PaperBlock } from '../../markdown/paperContent';

vi.mock('./useTextSelection', () => ({ useTextSelection: vi.fn() }));
vi.mock('../../api/translate', () => ({ translateSelection: vi.fn() }));

const BLOCKS: PaperBlock[] = [
  { id: 'b0', type: 'para', markdown: 'An attention function', sourceText: 'An attention function', sourceOffsetShift: 0 },
  { id: 'b1', type: 'para', markdown: 'can be described', sourceText: 'can be described', sourceOffsetShift: 0 },
];

function selection(overrides: Partial<TextSelection> = {}): TextSelection {
  return {
    text: 'attention',
    rect: { top: 20, left: 10, right: 110, bottom: 36, width: 100, height: 16 } as DOMRect,
    anchors: { start: { blockId: 'b0', offset: 3 }, end: { blockId: 'b0', offset: 12 } },
    clear: vi.fn(),
    ...overrides,
  };
}

function setup(sel: TextSelection | null) {
  vi.mocked(useTextSelection).mockReturnValue(sel);
  const onAsk = vi.fn();
  const viewerRef = createRef<HTMLDivElement>();
  render(
    <div style={{ position: 'relative' }}>
      <div ref={viewerRef}>
        <section data-block-id="b0">An attention function</section>
        <section data-block-id="b1">can be described</section>
      </div>
      <SelectionLayer viewerRef={viewerRef} blocks={BLOCKS} onAsk={onAsk} />
    </div>,
  );
  return { onAsk };
}

beforeEach(() => {
  vi.mocked(translateSelection).mockResolvedValue({ translation: '어텐션' });
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('SelectionLayer — 현재 상태기계', () => {
  it('선택이 없으면 아무것도 렌더하지 않는다', () => {
    setup(null);
    expect(screen.queryByRole('button', { name: /번역/ })).toBeNull();
  });

  it('선택이 있으면 번역·질문하기 툴바가 뜬다', () => {
    setup(selection());
    expect(screen.getByRole('button', { name: /번역/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /질문하기/ })).toBeTruthy();
  });

  it('번역 클릭 → 번역 중 → 결과 표시, 원문 발췌가 함께 보인다', async () => {
    setup(selection());
    fireEvent.click(screen.getByRole('button', { name: /번역/ }));
    expect(screen.getByText('번역 중…')).toBeTruthy();
    await waitFor(() => expect(screen.getByText('어텐션')).toBeTruthy());
    expect(screen.getByText('attention')).toBeTruthy();
  });

  it('닫기 → clear가 불리고 팝업이 사라진다', async () => {
    const sel = selection();
    setup(sel);
    fireEvent.click(screen.getByRole('button', { name: /번역/ }));
    await waitFor(() => screen.getByText('어텐션'));
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(sel.clear).toHaveBeenCalled();
    expect(screen.queryByText('어텐션')).toBeNull();
  });

  it('질문하기 → 현재 채팅: onAsk(text, current, anchors)', () => {
    const sel = selection();
    const { onAsk } = setup(sel);
    fireEvent.click(screen.getByRole('button', { name: /질문하기/ }));
    fireEvent.click(screen.getByRole('button', { name: '현재 채팅' }));
    expect(onAsk).toHaveBeenCalledWith('attention', 'current', sel.anchors);
    expect(sel.clear).toHaveBeenCalled();
  });

  it('팝업 밖 mousedown → clear 후 idle', async () => {
    const sel = selection();
    setup(sel);
    fireEvent.click(screen.getByRole('button', { name: /번역/ }));
    await waitFor(() => screen.getByText('어텐션'));
    act(() => { fireEvent.mouseDown(document.body); });
    expect(sel.clear).toHaveBeenCalled();
    expect(screen.queryByText('어텐션')).toBeNull();
  });
});
