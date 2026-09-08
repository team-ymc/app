import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, cleanup, act, waitFor } from '@testing-library/react';
import { createRef } from 'react';
import { SelectionLayer } from './SelectionLayer';
import { useTextSelection, type TextSelection } from './useTextSelection';
import { streamTranslation, type TranslationStreamEvent } from '../../translation/translationStream';
import type { PaperBlock } from '../../markdown/paperContent';

vi.mock('./useTextSelection', () => ({ useTextSelection: vi.fn() }));
vi.mock('../../translation/translationStream', () => ({ streamTranslation: vi.fn() }));

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
      <SelectionLayer paperId="p-1" viewerRef={viewerRef} blocks={BLOCKS} onAsk={onAsk} />
    </div>,
  );
  return { onAsk };
}

type Captured = { onEvent: (e: TranslationStreamEvent) => void; signal?: AbortSignal };
let captured: Captured | null = null;

beforeEach(() => {
  captured = null;
  vi.mocked(streamTranslation).mockImplementation(async ({ onEvent, signal }) => {
    captured = { onEvent, signal };
  });
});

function emit(e: TranslationStreamEvent) {
  act(() => { captured!.onEvent(e); });
}

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

  it('번역 클릭 → 요청 body에 paperId·anchors, delta가 누적 표시되고 completed에서 완성본으로 교체된다', async () => {
    const sel = selection();
    setup(sel);
    fireEvent.click(screen.getByRole('button', { name: /번역/ }));
    expect(screen.getByText('번역 중…')).toBeTruthy();
    await waitFor(() => expect(captured).not.toBeNull());
    expect(vi.mocked(streamTranslation).mock.calls[0][0]).toMatchObject({ paperId: 'p-1', selection: sel.anchors });

    emit({ type: 'started', translationId: 't-1' });
    emit({ type: 'delta', delta: '어텐' });
    emit({ type: 'delta', delta: '션은' });
    expect(screen.getByText('어텐션은')).toBeTruthy();

    emit({ type: 'completed', translation: '어텐션은 **함수**다' });
    expect(screen.getByText('함수').tagName).toBe('STRONG'); // PaperMarkdown 렌더
    expect(screen.queryByText('번역 중…')).toBeNull();
  });

  it('anchor가 없으면 번역 버튼이 비활성이고 사유가 title로 보인다', () => {
    setup(selection({ anchors: null }));
    const button = screen.getByRole('button', { name: /번역/ }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.closest('span')?.title).toBe('번역할 수 없는 선택 영역입니다.');
    fireEvent.click(button);
    expect(streamTranslation).not.toHaveBeenCalled();
  });

  it('선택이 너무 길면 번역 버튼이 비활성이다', () => {
    const long: PaperBlock[] = [{ id: 'L', type: 'para', markdown: 'x', sourceText: 'a'.repeat(30001), sourceOffsetShift: 0 }];
    vi.mocked(useTextSelection).mockReturnValue(selection({ anchors: { start: { blockId: 'L' }, end: { blockId: 'L' } } }));
    const viewerRef = createRef<HTMLDivElement>();
    render(<div><div ref={viewerRef}><section data-block-id="L">x</section></div><SelectionLayer paperId="p-1" viewerRef={viewerRef} blocks={long} onAsk={vi.fn()} /></div>);
    const button = screen.getByRole('button', { name: /번역/ }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.closest('span')?.title).toBe('선택 영역이 너무 큽니다.');
  });

  it('실패 이벤트는 팝업 안에 메시지로 보이고 닫을 수 있다', async () => {
    const sel = selection();
    setup(sel);
    fireEvent.click(screen.getByRole('button', { name: /번역/ }));
    await waitFor(() => expect(captured).not.toBeNull());
    emit({ type: 'failed', confirmed: true, code: 'SELECTION_TOO_LARGE', message: '선택 영역이 너무 큽니다.', retryable: false });
    expect(screen.getByText('선택 영역이 너무 큽니다.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(sel.clear).toHaveBeenCalled();
  });

  it('409 TRANSLATION_IN_PROGRESS는 고정 안내 문구로 보인다', async () => {
    setup(selection());
    fireEvent.click(screen.getByRole('button', { name: /번역/ }));
    await waitFor(() => expect(captured).not.toBeNull());
    emit({ type: 'failed', confirmed: true, code: 'TRANSLATION_IN_PROGRESS', message: '서버 문구', retryable: true });
    expect(screen.getByText('이전 번역이 끝나면 다시 시도해 주세요.')).toBeTruthy();
  });

  it('번역 중 팝업을 닫으면 요청이 abort된다', async () => {
    setup(selection());
    fireEvent.click(screen.getByRole('button', { name: /번역/ }));
    await waitFor(() => expect(captured).not.toBeNull());
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(captured!.signal?.aborted).toBe(true);
  });

  it('닫기 → clear가 불리고 팝업이 사라진다', async () => {
    const sel = selection();
    setup(sel);
    fireEvent.click(screen.getByRole('button', { name: /번역/ }));
    await waitFor(() => expect(captured).not.toBeNull());
    emit({ type: 'completed', translation: '어텐션' });
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(sel.clear).toHaveBeenCalled();
    expect(screen.queryByText('어텐션')).toBeNull();
  });

  it('질문하기 클릭 즉시 현재 채팅으로 onAsk(text, current, anchors)를 부르고 선택 팝업은 뜨지 않는다', () => {
    const sel = selection();
    const { onAsk } = setup(sel);
    fireEvent.click(screen.getByRole('button', { name: /질문하기/ }));
    expect(onAsk).toHaveBeenCalledWith('attention', 'current', sel.anchors);
    expect(sel.clear).toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: '현재 채팅' })).toBeNull();
    expect(screen.queryByRole('button', { name: '새 채팅' })).toBeNull();
  });

  it('팝업 밖 mousedown → clear 후 idle', async () => {
    const sel = selection();
    setup(sel);
    fireEvent.click(screen.getByRole('button', { name: /번역/ }));
    await waitFor(() => expect(captured).not.toBeNull());
    emit({ type: 'completed', translation: '어텐션' });
    act(() => { fireEvent.mouseDown(document.body); });
    expect(sel.clear).toHaveBeenCalled();
    expect(screen.queryByText('어텐션')).toBeNull();
  });
});
