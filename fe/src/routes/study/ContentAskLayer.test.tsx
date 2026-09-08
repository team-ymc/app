import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { createRef } from 'react';
import { ContentAskLayer } from './ContentAskLayer';
import type { PaperBlock } from '../../markdown/paperContent';

const BLOCKS: PaperBlock[] = [
  { id: 'b0', type: 'para', markdown: '본문', sourceText: '본문', sourceOffsetShift: 0 },
  { id: 'f0', type: 'figure', markdown: '![](u)' },
  { id: 't0', type: 'table', tableHtml: '<table></table>' },
];

function setup() {
  const onAsk = vi.fn();
  const viewerRef = createRef<HTMLDivElement>();
  render(
    <div style={{ position: 'relative' }}>
      <div ref={viewerRef}>
        <section data-block-id="b0" id="b0">본문</section>
        <section data-block-id="f0" id="f0">그림</section>
        <section data-block-id="t0" id="t0">표</section>
      </div>
      <ContentAskLayer viewerRef={viewerRef} blocks={BLOCKS} onAsk={onAsk} />
    </div>,
  );
  return { onAsk };
}

afterEach(cleanup);

describe('ContentAskLayer — 구성요소 질문하기', () => {
  it('atomic 블록(figure)을 클릭하면 질문하기 버튼이 뜬다', () => {
    setup();
    fireEvent.click(document.querySelector('[data-block-id="f0"]')!);
    expect(screen.getByRole('button', { name: '질문하기' })).toBeTruthy();
  });

  it('텍스트 블록 클릭에는 아무것도 뜨지 않는다', () => {
    setup();
    fireEvent.click(document.querySelector('[data-block-id="b0"]')!);
    expect(screen.queryByRole('button', { name: '질문하기' })).toBeNull();
  });

  it('질문하기 클릭 즉시 현재 채팅으로 블록 단위 anchors를 onAsk에 넘기고 선택 팝업은 뜨지 않는다', () => {
    const { onAsk } = setup();
    fireEvent.click(document.querySelector('[data-block-id="t0"]')!);
    fireEvent.click(screen.getByRole('button', { name: '질문하기' }));
    expect(onAsk).toHaveBeenCalledWith('', 'current', { start: { blockId: 't0' }, end: { blockId: 't0' } });
    expect(screen.queryByRole('button', { name: '현재 채팅' })).toBeNull();
    expect(screen.queryByRole('button', { name: '새 채팅' })).toBeNull();
  });

  it('레이어가 열리면 대상 블록에 data-context-target 하이라이트가 켜지고, 닫히면 꺼진다', () => {
    setup();
    const section = document.querySelector('[data-block-id="f0"]')!;
    fireEvent.click(section);
    expect(section.getAttribute('data-context-target')).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: '질문하기' }));
    expect(section.getAttribute('data-context-target')).toBeNull();
  });

  it('질문하기 후 레이어가 닫힌다', () => {
    setup();
    fireEvent.click(document.querySelector('[data-block-id="f0"]')!);
    fireEvent.click(screen.getByRole('button', { name: '질문하기' }));
    expect(screen.queryByRole('button', { name: '질문하기' })).toBeNull();
  });
});
