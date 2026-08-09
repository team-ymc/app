import { describe, expect, it } from 'vitest';
import { collectRuns, isMathRun, resolveOffset, trimRuns } from './sourceOffset';
import type { PaperBlock } from '../../markdown/paperContent';

const para: PaperBlock = {
  id: 'b1', type: 'para',
  markdown: '이 방식은 **병렬화**가 좋다',
  sourceText: '이 방식은 **병렬화**가 좋다',
  sourceOffsetShift: 0,
};
const heading: PaperBlock = {
  id: 'h1', type: 'heading', markdown: '## 결론', headingText: '결론', headingLevel: 2,
  sourceText: '결론', sourceOffsetShift: 3,
};
const atomic: PaperBlock = { id: 'f1', type: 'figure', markdown: '![](/x.png)' };

function mount(html: string) {
  document.body.innerHTML = html;
  return document.body;
}

function textIn(el: Element): Text {
  return el.firstChild as Text;
}

describe('collectRuns', () => {
  it('단일 텍스트 노드의 부분 선택을 그대로 담는다', () => {
    const body = mount('<span data-src-start="0" data-src-end="5">안녕하세요</span>');
    const node = textIn(body.querySelector('span')!);
    const range = document.createRange();
    range.setStart(node, 1);
    range.setEnd(node, 3);
    expect(collectRuns(range).map((r) => r.node.data.slice(r.from, r.to))).toEqual(['녕하']);
  });

  it('여러 텍스트 노드를 가로지르면 문서 순서로 모은다', () => {
    const body = mount(
      '<p><span data-src-start="0" data-src-end="2">가나</span>'
      + '<strong><span data-src-start="4" data-src-end="6">다라</span></strong>'
      + '<span data-src-start="8" data-src-end="10">마바</span></p>',
    );
    const spans = body.querySelectorAll('span');
    const range = document.createRange();
    range.setStart(textIn(spans[0]), 1);
    range.setEnd(textIn(spans[2]), 1);
    expect(collectRuns(range).map((r) => r.node.data.slice(r.from, r.to))).toEqual(['나', '다라', '마']);
  });

  it('끝 경계가 다음 블록의 시작이면 그 블록 run을 만들지 않는다', () => {
    const body = mount(
      '<section data-block-id="b1"><span data-src-start="0" data-src-end="4">첫 문단</span></section>'
      + '<section data-block-id="b2"><span data-src-start="0" data-src-end="2">둘째</span></section>',
    );
    const first = textIn(body.querySelector('[data-block-id="b1"] span')!);
    const second = textIn(body.querySelector('[data-block-id="b2"] span')!);
    const range = document.createRange();
    range.setStart(first, 0);
    range.setEnd(second, 0);
    const runs = collectRuns(range);
    expect(runs.map((r) => r.node.data.slice(r.from, r.to))).toEqual(['첫 문단']);
  });

  it('element boundary로 표현된 끝 경계도 같은 결과를 낸다', () => {
    const body = mount(
      '<section data-block-id="b1"><p><span data-src-start="0" data-src-end="4">첫 문단</span></p></section>'
      + '<section data-block-id="b2"><p><span data-src-start="0" data-src-end="2">둘째</span></p></section>',
    );
    const first = textIn(body.querySelector('[data-block-id="b1"] span')!);
    const range = document.createRange();
    range.setStart(first, 0);
    range.setEnd(body.querySelector('[data-block-id="b2"] p')!, 0);
    expect(collectRuns(range).map((r) => r.node.data.slice(r.from, r.to))).toEqual(['첫 문단']);
  });

  it('역방향 드래그도 문서 순서로 정규화된다', () => {
    const body = mount(
      '<p><span data-src-start="0" data-src-end="2">가나</span>'
      + '<span data-src-start="2" data-src-end="4">다라</span></p>',
    );
    const spans = body.querySelectorAll('span');
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.setBaseAndExtent(textIn(spans[1]), 1, textIn(spans[0]), 1);
    expect(collectRuns(selection.getRangeAt(0)).map((r) => r.node.data.slice(r.from, r.to)))
      .toEqual(['나', '다']);
  });
});

describe('trimRuns', () => {
  it('양끝 공백을 오므리고 공백 전용 run을 버린다', () => {
    const body = mount(
      '<p><span data-src-start="0" data-src-end="5">  본문</span>'
      + '<span data-src-start="5" data-src-end="8">   </span></p>',
    );
    const spans = body.querySelectorAll('span');
    const range = document.createRange();
    range.setStart(textIn(spans[0]), 0);
    range.setEnd(textIn(spans[1]), 3);
    const trimmed = trimRuns(collectRuns(range));
    expect(trimmed.map((r) => r.node.data.slice(r.from, r.to))).toEqual(['본문']);
  });

  it('전부 공백이면 빈 배열이다', () => {
    const body = mount('<span data-src-start="0" data-src-end="3">   </span>');
    const node = textIn(body.querySelector('span')!);
    const range = document.createRange();
    range.setStart(node, 0);
    range.setEnd(node, 3);
    expect(trimRuns(collectRuns(range))).toEqual([]);
  });
});

describe('resolveOffset', () => {
  it('span 좌표에 노드 내 offset을 더한다', () => {
    const body = mount('<span data-src-start="10" data-src-end="13">병렬화</span>');
    const node = textIn(body.querySelector('span')!);
    expect(resolveOffset(node, 2, para, 'start')).toBe(12);
  });

  it('heading은 shift만큼 뺀 원문 좌표를 준다', () => {
    const body = mount('<h2><span data-src-start="3" data-src-end="5">결론</span></h2>');
    const node = textIn(body.querySelector('span')!);
    expect(resolveOffset(node, 0, heading, 'start')).toBe(0);
    expect(resolveOffset(node, 2, heading, 'end')).toBe(2);
  });

  it('좌표 span이 없으면 undefined다', () => {
    const body = mount('<p>맨텍스트</p>');
    const node = textIn(body.querySelector('p')!);
    expect(resolveOffset(node, 1, para, 'start')).toBeUndefined();
  });

  it('atomic 블록은 undefined다', () => {
    const body = mount('<span data-src-start="0" data-src-end="3">캡션</span>');
    const node = textIn(body.querySelector('span')!);
    expect(resolveOffset(node, 1, atomic, 'start')).toBeUndefined();
  });

  it('수식 래퍼 안의 경계는 바깥으로 스냅한다', () => {
    const body = mount('<span data-src-start="4" data-src-end="12" data-src-math="true"><span class="katex"><span>E</span></span></span>');
    const node = textIn(body.querySelector('.katex span')!);
    expect(isMathRun(node)).toBe(true);
    expect(resolveOffset(node, 0, para, 'start')).toBe(4);
    expect(resolveOffset(node, 1, para, 'end')).toBe(12);
  });

  it('원문 길이를 넘는 좌표는 undefined다', () => {
    const body = mount('<span data-src-start="900" data-src-end="903">엉뚱</span>');
    const node = textIn(body.querySelector('span')!);
    expect(resolveOffset(node, 0, para, 'start')).toBeUndefined();
  });

  it('surrogate pair를 쪼개는 offset은 undefined다', () => {
    const emoji: PaperBlock = { id: 'e1', type: 'para', markdown: '로켓🚀', sourceText: '로켓🚀', sourceOffsetShift: 0 };
    const body = mount('<span data-src-start="0" data-src-end="4">로켓🚀</span>');
    const node = textIn(body.querySelector('span')!);
    expect(resolveOffset(node, 3, emoji, 'end')).toBeUndefined(); // 🚀 중간
    expect(resolveOffset(node, 4, emoji, 'end')).toBe(4);         // 🚀 뒤
  });
});
