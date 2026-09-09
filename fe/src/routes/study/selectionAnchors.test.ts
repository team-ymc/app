import { describe, expect, it } from 'vitest';
import { computeSelectionAnchors } from './selectionAnchors';
import type { PaperBlock } from '../../markdown/paperContent';

const SRC_A = '이 방식은 **병렬화**가 좋다';
const SRC_B = '둘째 문단이다';

const blocks: PaperBlock[] = [
  { id: 'b1', type: 'para', markdown: SRC_A, sourceText: SRC_A, sourceOffsetShift: 0 },
  { id: 'b2', type: 'para', markdown: SRC_B, sourceText: SRC_B, sourceOffsetShift: 0 },
  { id: 'f1', type: 'figure', markdown: '![](/x.png)' },
];

function mount(html: string) {
  document.body.innerHTML = html;
  return document.body;
}

function textIn(el: Element): Text {
  return el.firstChild as Text;
}

// b1을 "이 방식은 " / "병렬화" / "가 좋다" 세 구간으로 렌더한 모습
function mountBlockA() {
  return mount(
    '<section data-block-id="b1"><p>'
    + '<span data-src-start="0" data-src-end="6">이 방식은 </span>'
    + '<strong><span data-src-start="8" data-src-end="11">병렬화</span></strong>'
    + '<span data-src-start="13" data-src-end="17">가 좋다</span>'
    + '</p></section>',
  );
}

describe('computeSelectionAnchors', () => {
  it('한 텍스트 노드 안의 선택은 정확한 offset을 준다', () => {
    const body = mountBlockA();
    const node = textIn(body.querySelectorAll('span')[0]);
    const range = document.createRange();
    range.setStart(node, 2);
    range.setEnd(node, 5);
    const anchors = computeSelectionAnchors(range, blocks)!;
    expect(anchors).toEqual({ start: { blockId: 'b1', offset: 2 }, end: { blockId: 'b1', offset: 5 } });
    expect(SRC_A.slice(2, 5)).toBe('방식은');
  });

  it('강조를 가로지르면 원문 기호를 포함한 구간을 가리킨다', () => {
    const body = mountBlockA();
    const spans = body.querySelectorAll('span');
    const range = document.createRange();
    range.setStart(textIn(spans[0]), 3);
    range.setEnd(textIn(spans[1]), 2);
    const anchors = computeSelectionAnchors(range, blocks)!;
    expect(anchors.start).toEqual({ blockId: 'b1', offset: 3 });
    expect(anchors.end).toEqual({ blockId: 'b1', offset: 10 });
    expect(SRC_A.slice(3, 10)).toBe('식은 **병렬');
  });

  it('역방향 드래그도 같은 결과를 준다', () => {
    const body = mountBlockA();
    const spans = body.querySelectorAll('span');
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.setBaseAndExtent(textIn(spans[1]), 2, textIn(spans[0]), 3);
    const anchors = computeSelectionAnchors(selection.getRangeAt(0), blocks)!;
    expect(anchors.start).toEqual({ blockId: 'b1', offset: 3 });
    expect(anchors.end).toEqual({ blockId: 'b1', offset: 10 });
  });

  it('블록을 가로지르면 양끝 블록에만 offset이 붙는다', () => {
    const body = mount(
      '<section data-block-id="b1"><p><span data-src-start="0" data-src-end="6">이 방식은 </span></p></section>'
      + '<section data-block-id="b2"><p><span data-src-start="0" data-src-end="7">둘째 문단이다</span></p></section>',
    );
    const spans = body.querySelectorAll('span');
    const range = document.createRange();
    range.setStart(textIn(spans[0]), 2);
    range.setEnd(textIn(spans[1]), 2);
    const anchors = computeSelectionAnchors(range, blocks)!;
    expect(anchors).toEqual({ start: { blockId: 'b1', offset: 2 }, end: { blockId: 'b2', offset: 2 } });
  });

  it('문단 끝을 넘긴 드래그는 blockId와 offset이 함께 이전 블록으로 당겨진다', () => {
    const body = mount(
      '<section data-block-id="b1"><p><span data-src-start="0" data-src-end="6">이 방식은 </span></p></section>'
      + '<section data-block-id="b2"><p><span data-src-start="0" data-src-end="7">둘째 문단이다</span></p></section>',
    );
    const spans = body.querySelectorAll('span');
    const range = document.createRange();
    range.setStart(textIn(spans[0]), 0);
    range.setEnd(textIn(spans[1]), 0);
    const anchors = computeSelectionAnchors(range, blocks)!;
    expect(anchors.end.blockId).toBe('b1');
    expect(anchors.end.offset).toBe(5); // 끝 공백까지 오므려진다
  });

  it('atomic 블록 anchor에는 offset이 없다', () => {
    const body = mount(
      '<section data-block-id="b1"><p><span data-src-start="0" data-src-end="6">이 방식은 </span></p></section>'
      + '<section data-block-id="f1"><figure><figcaption>캡션</figcaption></figure></section>',
    );
    const start = textIn(body.querySelector('[data-block-id="b1"] span')!);
    const caption = textIn(body.querySelector('figcaption')!);
    const range = document.createRange();
    range.setStart(start, 2);
    range.setEnd(caption, 2);
    const anchors = computeSelectionAnchors(range, blocks)!;
    expect(anchors.start).toEqual({ blockId: 'b1', offset: 2 });
    expect(anchors.end).toEqual({ blockId: 'f1' });
  });

  it('좌표 span이 없는 구간(이스케이프 등)에 경계가 있으면 그 anchor만 폴백한다', () => {
    const body = mount(
      '<section data-block-id="b1"><p>'
      + '<span data-src-start="0" data-src-end="6">이 방식은 </span>'
      + '맨텍스트'
      + '</p></section>',
    );
    const mapped = textIn(body.querySelector('span')!);
    const bare = body.querySelector('p')!.lastChild as Text;
    const range = document.createRange();
    range.setStart(mapped, 2);
    range.setEnd(bare, 3);
    const anchors = computeSelectionAnchors(range, blocks)!;
    expect(anchors.start).toEqual({ blockId: 'b1', offset: 2 });
    expect(anchors.end).toEqual({ blockId: 'b1' });
  });

  it('빈 선택이면 null이다', () => {
    const body = mountBlockA();
    const node = textIn(body.querySelectorAll('span')[0]);
    const range = document.createRange();
    range.setStart(node, 5);
    range.setEnd(node, 6); // 공백 한 칸
    expect(computeSelectionAnchors(range, blocks)).toBeNull();
  });

  it('블록 밖 선택이면 null이다', () => {
    const body = mount('<div><p>바깥</p></div>');
    const node = textIn(body.querySelector('p')!);
    const range = document.createRange();
    range.setStart(node, 0);
    range.setEnd(node, 2);
    expect(computeSelectionAnchors(range, blocks)).toBeNull();
  });

  it('번역 셀 안에서만 선택하면 null이다', () => {
    const body = mount(
      '<div class="pt-row"><section data-block-id="b1"><p>'
      + '<span data-src-start="0" data-src-end="4">첫 문단</span></p></section>'
      + '<aside class="pt-translation"><p>번역문이다</p></aside></div>',
    );
    const node = textIn(body.querySelector('.pt-translation p')!);
    const range = document.createRange();
    range.setStart(node, 0);
    range.setEnd(node, 3);
    expect(computeSelectionAnchors(range, blocks)).toBeNull();
  });

  it('원문에서 시작해 번역 셀로 넘어간 선택은 원문 끝에서 잘린다', () => {
    const body = mount(
      '<div class="pt-row"><section data-block-id="b1"><p>'
      + `<span data-src-start="0" data-src-end="${SRC_A.length}">${SRC_A}</span></p></section>`
      + '<aside class="pt-translation"><p>번역문이다</p></aside></div>',
    );
    const source = textIn(body.querySelector('section span')!);
    const translated = textIn(body.querySelector('.pt-translation p')!);
    const range = document.createRange();
    range.setStart(source, 0);
    range.setEnd(translated, 5);
    expect(computeSelectionAnchors(range, blocks)).toEqual({
      start: { blockId: 'b1', offset: 0 }, end: { blockId: 'b1', offset: SRC_A.length },
    });
  });

  it('blocks에 없는 blockId면 offset 없이 블록 단위로 준다', () => {
    const body = mount(
      '<section data-block-id="unknown"><p><span data-src-start="0" data-src-end="4">모르는</span></p></section>',
    );
    const node = textIn(body.querySelector('span')!);
    const range = document.createRange();
    range.setStart(node, 0);
    range.setEnd(node, 3);
    expect(computeSelectionAnchors(range, blocks)).toEqual({
      start: { blockId: 'unknown' }, end: { blockId: 'unknown' },
    });
  });
});
