import { describe, expect, it } from 'vitest';
import { resolveSelectionPreview } from './selectionPreview';
import type { PaperBlock } from '../markdown/paperContent';

const blocks: PaperBlock[] = [
  { id: 'b0', type: 'para', markdown: '첫 문단' },
  { id: 'b1', type: 'figure', markdown: '![](https://example/img.png)' },
  { id: 'b2', type: 'heading', markdown: '## 결론', headingText: '결론', headingLevel: 2 },
  { id: 'b3', type: 'para', markdown: '셋째 문단' },
];

describe('resolveSelectionPreview', () => {
  it('선택 범위의 텍스트 블록을 이어 붙인다 — 문단은 markdown, 제목은 headingText, 그림·표는 건너뜀', () => {
    expect(resolveSelectionPreview(blocks, { start: { blockId: 'b0' }, end: { blockId: 'b3' } }))
      .toBe('첫 문단 결론 셋째 문단');
  });

  it('120자를 넘으면 말줄임한다', () => {
    const long: PaperBlock[] = [{ id: 'b0', type: 'para', markdown: 'a'.repeat(200) }];
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
});
