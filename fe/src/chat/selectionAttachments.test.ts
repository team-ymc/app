import { describe, it, expect } from 'vitest';
import {
  attachSelection,
  attachmentKindOf,
  checkTranslationSelection,
  MAX_ATTACHMENTS,
  MAX_SELECTION_BLOCKS,
  MAX_SELECTION_CHARS,
  type SelectionAttachment,
} from './selectionAttachments';
import type { PaperBlock } from '../markdown/paperContent';
import type { SelectionAnchors } from '../routes/study/selectionAnchors';

function para(id: string, text = '본문'): PaperBlock {
  return { id, type: 'para', markdown: text, sourceText: text, sourceOffsetShift: 0 };
}

function anchors(startId: string, endId: string): SelectionAnchors {
  return { start: { blockId: startId }, end: { blockId: endId } };
}

function attachment(startId: string, endId = startId): SelectionAttachment {
  return { text: '미리보기', anchors: anchors(startId, endId), kind: 'selection' };
}

const BLOCKS: PaperBlock[] = [
  para('b0'),
  para('b1'),
  { id: 'f0', type: 'figure', markdown: '![](u)' },
  { id: 't0', type: 'table', tableHtml: '<table></table>' },
  { id: 'e0', type: 'equation', markdown: '$$x$$' },
  para('b2'),
];

describe('attachmentKindOf', () => {
  it('단일 atomic 블록 선택은 블록 타입대로 image/table/formula다', () => {
    expect(attachmentKindOf(BLOCKS, anchors('f0', 'f0'))).toBe('image');
    expect(attachmentKindOf(BLOCKS, anchors('t0', 't0'))).toBe('table');
    expect(attachmentKindOf(BLOCKS, anchors('e0', 'e0'))).toBe('formula');
  });

  it('텍스트 블록·여러 블록에 걸친 선택은 selection이다', () => {
    expect(attachmentKindOf(BLOCKS, anchors('b0', 'b0'))).toBe('selection');
    expect(attachmentKindOf(BLOCKS, anchors('b1', 'b2'))).toBe('selection');
  });
});

describe('attachSelection', () => {
  it('새 선택을 뒤에 누적한다', () => {
    const r = attachSelection([attachment('b0')], BLOCKS, { text: 't', anchors: anchors('b1', 'b1') });
    expect(r.ok).toBe(true);
    expect(r.attachments).toHaveLength(2);
    expect(r.attachments[1].anchors.start.blockId).toBe('b1');
  });

  it('완전히 동일한 anchors는 무시하고 기존 목록을 유지한다', () => {
    const current = [attachment('b0', 'b1')];
    const r = attachSelection(current, BLOCKS, { text: '다른 미리보기', anchors: anchors('b0', 'b1') });
    expect(r.ok).toBe(true);
    expect(r.attachments).toBe(current);
  });

  it('offset이 다르면 같은 블록이라도 다른 선택이다 (부분 겹침 허용)', () => {
    const current = [{ ...attachment('b0'), anchors: { start: { blockId: 'b0', offset: 0 }, end: { blockId: 'b0', offset: 5 } } }];
    const r = attachSelection(current, BLOCKS, {
      text: 't',
      anchors: { start: { blockId: 'b0', offset: 3 }, end: { blockId: 'b0', offset: 8 } },
    });
    expect(r.ok).toBe(true);
    expect(r.attachments).toHaveLength(2);
  });

  it(`${MAX_ATTACHMENTS}개가 찬 상태에서 새 선택은 limit으로 거절한다`, () => {
    const current = ['b0', 'b1', 'f0', 't0', 'e0'].map((id) => attachment(id));
    const r = attachSelection(current, BLOCKS, { text: 't', anchors: anchors('b2', 'b2') });
    expect(r).toMatchObject({ ok: false, reason: 'limit' });
    expect(r.attachments).toBe(current);
  });

  it('찬 상태라도 완전 중복이면 limit이 아니라 무시다', () => {
    const current = ['b0', 'b1', 'f0', 't0', 'e0'].map((id) => attachment(id));
    const r = attachSelection(current, BLOCKS, { text: 't', anchors: anchors('b0', 'b0') });
    expect(r.ok).toBe(true);
    expect(r.attachments).toBe(current);
  });

  it('150 블록을 넘는 선택은 too-many-blocks로 거절한다', () => {
    const many: PaperBlock[] = Array.from({ length: 151 }, (_, i) => para(`p${i}`));
    const r = attachSelection([], many, { text: 't', anchors: anchors('p0', 'p150') });
    expect(r).toMatchObject({ ok: false, reason: 'too-many-blocks' });
  });

  it('렌더링 30,000자를 넘는 선택은 too-long으로 거절한다', () => {
    const long: PaperBlock[] = [para('L0', 'a'.repeat(15001)), para('L1', 'a'.repeat(15000))];
    const r = attachSelection([], long, { text: 't', anchors: anchors('L0', 'L1') });
    expect(r).toMatchObject({ ok: false, reason: 'too-long' });
  });

  it('경계값(150 블록·30,000자)은 허용한다', () => {
    const many: PaperBlock[] = Array.from({ length: 150 }, (_, i) => para(`p${i}`, 'aa'));
    const r = attachSelection([], many, { text: 't', anchors: anchors('p0', 'p149') });
    expect(r.ok).toBe(true);
  });

  it('첨부의 kind는 blocks에서 판정해 채운다', () => {
    const r = attachSelection([], BLOCKS, { text: '', anchors: anchors('t0', 't0') });
    expect(r.ok).toBe(true);
    expect(r.attachments[0].kind).toBe('table');
  });
});

describe('checkTranslationSelection — 번역 버튼 활성 판정', () => {
  it('anchor가 없거나 블록을 못 찾으면 unknown (채팅 첨부와 달리 ok로 완화하지 않는다)', () => {
    expect(checkTranslationSelection(BLOCKS, null)).toBe('unknown');
    expect(checkTranslationSelection(BLOCKS, anchors('nope', 'b0'))).toBe('unknown');
    expect(checkTranslationSelection(BLOCKS, anchors('b1', 'b0'))).toBe('unknown'); // 순서 뒤집힘
  });

  it('정상 범위는 ok', () => {
    expect(checkTranslationSelection(BLOCKS, anchors('b0', 'b1'))).toBe('ok');
    expect(checkTranslationSelection(BLOCKS, { start: { blockId: 'b0', offset: 1 }, end: { blockId: 'b0', offset: 2 } })).toBe('ok');
  });

  it('블록 수·글자 수 상한을 넘으면 사유를 돌려준다', () => {
    const many: PaperBlock[] = Array.from({ length: MAX_SELECTION_BLOCKS + 1 }, (_, i) => para(`m${i}`, 'x'));
    expect(checkTranslationSelection(many, anchors('m0', `m${MAX_SELECTION_BLOCKS}`))).toBe('too-many-blocks');
    const long: PaperBlock[] = [para('L', 'a'.repeat(MAX_SELECTION_CHARS + 1))];
    expect(checkTranslationSelection(long, anchors('L', 'L'))).toBe('too-long');
  });
});
