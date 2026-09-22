import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PaperMarkdown } from './PaperMarkdown';

function marks(container: HTMLElement) {
  return Array.from(container.querySelectorAll('mark.term-highlight')).map((m) => ({
    id: m.getAttribute('data-highlight-id'),
    text: m.textContent,
    srcStart: m.querySelector('[data-src-start]')?.getAttribute('data-src-start'),
  }));
}

describe('rehypePrerequisiteHighlight', () => {
  it('한 범위를 mark로 감싸고 조각마다 data-src-start가 붙는다', () => {
    const { container } = render(
      <PaperMarkdown sourcePos highlights={[{ id: 'h1', start: 8, end: 15 }]}>
        {'softmax dropout layer'}
      </PaperMarkdown>,
    );
    expect(marks(container)).toEqual([{ id: 'h1', text: 'dropout', srcStart: '8' }]);
    const spans = Array.from(container.querySelectorAll('[data-src-start]')).map((s) => s.getAttribute('data-src-start'));
    expect(spans).toEqual(['0', '8', '15']);
  });

  it('한 블록의 여러 범위를 모두 감싼다', () => {
    const { container } = render(
      <PaperMarkdown sourcePos highlights={[{ id: 'a', start: 0, end: 7 }, { id: 'b', start: 16, end: 21 }]}>
        {'softmax dropout layer'}
      </PaperMarkdown>,
    );
    expect(marks(container).map((m) => m.text)).toEqual(['softmax', 'layer']);
  });

  it('heading은 shift만큼 밀린 markdown 좌표로 그린다', () => {
    // 원문 'Intro model'의 6..11 = 'model'. markdown '## Intro model'에서는 9..14.
    const { container } = render(
      <PaperMarkdown sourcePos highlights={[{ id: 'h', start: 9, end: 14 }]}>{'## Intro model'}</PaperMarkdown>,
    );
    expect(marks(container)).toEqual([{ id: 'h', text: 'model', srcStart: '9' }]);
  });

  it('인라인 수식 옆 텍스트도 감싼다', () => {
    const { container } = render(
      <PaperMarkdown sourcePos highlights={[{ id: 'h', start: 0, end: 7 }]}>{'softmax $x$ tail'}</PaperMarkdown>,
    );
    expect(marks(container).map((m) => m.text)).toEqual(['softmax']);
  });

  it('보조평면 문자 뒤 범위도 UTF-16 offset으로 맞는다', () => {
    // '𝑥 dropout': 𝑥는 code unit 2개라 dropout은 3..10.
    const { container } = render(
      <PaperMarkdown sourcePos highlights={[{ id: 'h', start: 3, end: 10 }]}>{'𝑥 dropout'}</PaperMarkdown>,
    );
    expect(marks(container)).toEqual([{ id: 'h', text: 'dropout', srcStart: '3' }]);
  });

  it('1:1 매핑이 안 되는 노드에 걸친 범위는 그리지 않는다', () => {
    // 강조 안쪽 텍스트는 markdown 소스와 내용이 같아 감싸지지만, 범위가 ** 경계를 넘으면 버린다.
    const { container } = render(
      <PaperMarkdown sourcePos highlights={[{ id: 'h', start: 0, end: 10 }]}>{'ab **cd** ef'}</PaperMarkdown>,
    );
    expect(marks(container)).toEqual([]);
  });

  it('highlights가 없으면 mark를 만들지 않는다', () => {
    const { container } = render(<PaperMarkdown sourcePos>{'plain'}</PaperMarkdown>);
    expect(marks(container)).toEqual([]);
  });
});
