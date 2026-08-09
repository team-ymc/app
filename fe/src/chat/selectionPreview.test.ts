import { describe, expect, it } from 'vitest';
import { resolveSelectionPreview } from './selectionPreview';
import type { PaperBlock } from '../markdown/paperContent';

const blocks: PaperBlock[] = [
  { id: 'b0', type: 'para', markdown: '첫 문단', sourceText: '첫 문단', sourceOffsetShift: 0 },
  { id: 'b1', type: 'figure', markdown: '![](https://example/img.png)' },
  { id: 'b2', type: 'heading', markdown: '## 결론', headingText: '결론', headingLevel: 2, sourceText: '결론', sourceOffsetShift: 3 },
  { id: 'b3', type: 'para', markdown: '셋째 문단', sourceText: '셋째 문단', sourceOffsetShift: 0 },
];

describe('resolveSelectionPreview', () => {
  it('선택 범위의 텍스트 블록을 이어 붙인다 — 원문(sourceText) 기준, 그림·표는 건너뜀', () => {
    expect(resolveSelectionPreview(blocks, { start: { blockId: 'b0' }, end: { blockId: 'b3' } }))
      .toBe('첫 문단 결론 셋째 문단');
  });

  it('120자를 넘으면 말줄임한다', () => {
    const long: PaperBlock[] = [{ id: 'b0', type: 'para', markdown: 'a'.repeat(200), sourceText: 'a'.repeat(200), sourceOffsetShift: 0 }];
    const preview = resolveSelectionPreview(long, { start: { blockId: 'b0' }, end: { blockId: 'b0' } });
    expect(preview!.length).toBe(121); // 120 + '…'
    expect(preview!.endsWith('…')).toBe(true);
  });

  it('블록을 찾지 못하면 null이다', () => {
    expect(resolveSelectionPreview(blocks, { start: { blockId: '없음' }, end: { blockId: 'b2' } })).toBeNull();
  });

  it('범위가 뒤집혀 있으면 null이다', () => {
    expect(resolveSelectionPreview(blocks, { start: { blockId: 'b2' }, end: { blockId: 'b0' } })).toBeNull();
  });

  it('offset이 있으면 그 범위만 잘라 보여준다', () => {
    const withSource: PaperBlock[] = [
      { id: 'b0', type: 'para', markdown: '첫 문단 전체 내용', sourceText: '첫 문단 전체 내용', sourceOffsetShift: 0 },
    ];
    expect(resolveSelectionPreview(withSource, {
      start: { blockId: 'b0', offset: 2 },
      end: { blockId: 'b0', offset: 7 },
    })).toBe('문단 전체');
  });

  it('양끝 블록에만 offset을 적용하고 가운데 블록은 통째로 담는다', () => {
    const withSource: PaperBlock[] = [
      { id: 'b0', type: 'para', markdown: '첫째 문단', sourceText: '첫째 문단', sourceOffsetShift: 0 },
      { id: 'b1', type: 'para', markdown: '가운데', sourceText: '가운데', sourceOffsetShift: 0 },
      { id: 'b2', type: 'para', markdown: '마지막 문단', sourceText: '마지막 문단', sourceOffsetShift: 0 },
    ];
    expect(resolveSelectionPreview(withSource, {
      start: { blockId: 'b0', offset: 3 },
      end: { blockId: 'b2', offset: 3 },
    })).toBe('문단 가운데 마지막');
  });

  it('offset 없는 기존 이력은 블록 전체를 그대로 보여준다', () => {
    const withSource: PaperBlock[] = [
      { id: 'b0', type: 'para', markdown: '첫 문단 전체 내용', sourceText: '첫 문단 전체 내용', sourceOffsetShift: 0 },
    ];
    expect(resolveSelectionPreview(withSource, {
      start: { blockId: 'b0' },
      end: { blockId: 'b0' },
    })).toBe('첫 문단 전체 내용');
  });
});
