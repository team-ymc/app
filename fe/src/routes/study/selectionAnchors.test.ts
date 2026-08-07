import { describe, expect, it } from 'vitest';
import { computeSelectionAnchors } from './selectionAnchors';

function setupBlocks(): void {
  document.body.innerHTML = `
    <div id="viewer">
      <section data-block-id="p0001-b0000"><p>첫 번째 문단</p></section>
      <section data-block-id="p0001-b0001"><p>두 번째 <strong>문단</strong></p></section>
    </div>
    <div id="outside">본문 밖 텍스트</div>
  `;
}

function rangeOf(startNode: Node, startOffset: number, endNode: Node, endOffset: number): Range {
  const range = document.createRange();
  range.setStart(startNode, startOffset);
  range.setEnd(endNode, endOffset);
  return range;
}

describe('computeSelectionAnchors', () => {
  it('한 블록 안의 선택은 start와 end가 같은 blockId다', () => {
    setupBlocks();
    const text = document.querySelector('[data-block-id="p0001-b0000"] p')!.firstChild!;
    expect(computeSelectionAnchors(rangeOf(text, 0, text, 3))).toEqual({
      start: { blockId: 'p0001-b0000' },
      end: { blockId: 'p0001-b0000' },
    });
  });

  it('블록 경계를 넘는 선택은 걸친 두 블록을 가리킨다', () => {
    setupBlocks();
    const first = document.querySelector('[data-block-id="p0001-b0000"] p')!.firstChild!;
    const strong = document.querySelector('[data-block-id="p0001-b0001"] strong')!.firstChild!;
    expect(computeSelectionAnchors(rangeOf(first, 2, strong, 1))).toEqual({
      start: { blockId: 'p0001-b0000' },
      end: { blockId: 'p0001-b0001' },
    });
  });

  it('블록 밖에 걸친 선택은 null이다', () => {
    setupBlocks();
    const inside = document.querySelector('[data-block-id="p0001-b0001"] p')!.firstChild!;
    const outside = document.getElementById('outside')!.firstChild!;
    expect(computeSelectionAnchors(rangeOf(inside, 0, outside, 2))).toBeNull();
  });

  it('뒤에서 앞으로 드래그해도(역방향) start/end가 문서 순서로 정규화된다', () => {
    setupBlocks();
    const first = document.querySelector('[data-block-id="p0001-b0000"] p')!.firstChild!;
    const strong = document.querySelector('[data-block-id="p0001-b0001"] strong')!.firstChild!;
    // 마우스 다운을 block1(strong)에서, 마우스 업을 block0(first)에서 — anchor가 focus보다 뒤.
    const sel = window.getSelection()!;
    sel.setBaseAndExtent(strong, 1, first, 2);
    expect(computeSelectionAnchors(sel.getRangeAt(0))).toEqual({
      start: { blockId: 'p0001-b0000' },
      end: { blockId: 'p0001-b0001' },
    });
  });
});
