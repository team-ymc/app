// 컴포저 인용 첨부 목록의 순수 로직 (FT-006 Story 5·6·7).
// 완전 동일 anchors만 무시하고 부분 겹침은 허용한다 — 겹침 최적화는 AI 소관.
// 크기 검증은 첨부 시점에 한다 — 서버 SELECTION_TOO_LARGE는 방어선일 뿐이다.
import type { PaperBlock } from '../markdown/paperContent';
import type { SelectionAnchors } from '../routes/study/selectionAnchors';

export type AttachmentKind = 'selection' | 'image' | 'table' | 'formula';

export interface SelectionAttachment {
  text: string;
  anchors: SelectionAnchors;
  kind: AttachmentKind;
}

export const MAX_ATTACHMENTS = 5;
export const MAX_SELECTION_BLOCKS = 150;
export const MAX_SELECTION_CHARS = 30000;

export type AttachResult =
  | { ok: true; attachments: SelectionAttachment[] }
  | { ok: false; reason: 'limit' | 'too-many-blocks' | 'too-long'; attachments: SelectionAttachment[] };

export function attachmentKindOf(blocks: PaperBlock[], anchors: SelectionAnchors): AttachmentKind {
  if (anchors.start.blockId !== anchors.end.blockId) return 'selection';
  const block = blocks.find((b) => b.id === anchors.start.blockId);
  switch (block?.type) {
    case 'figure': return 'image';
    case 'table': return 'table';
    case 'equation': return 'formula';
    default: return 'selection';
  }
}

function sameAnchor(a: { blockId: string; offset?: number }, b: { blockId: string; offset?: number }): boolean {
  return a.blockId === b.blockId && a.offset === b.offset;
}

export function sameAnchors(a: SelectionAnchors, b: SelectionAnchors): boolean {
  return sameAnchor(a.start, b.start) && sameAnchor(a.end, b.end);
}

type SizeCheck = 'ok' | 'too-many-blocks' | 'too-long';

// 렌더링 글자 수는 sourceText 길이 합으로 근사한다 — atomic 블록은 텍스트가 없어 0으로 친다.
function checkSelectionSize(blocks: PaperBlock[], anchors: SelectionAnchors): SizeCheck {
  const startIdx = blocks.findIndex((b) => b.id === anchors.start.blockId);
  const endIdx = blocks.findIndex((b) => b.id === anchors.end.blockId);
  if (startIdx < 0 || endIdx < 0 || startIdx > endIdx) return 'ok'; // 판정 불가 — 서버 방어선에 맡긴다
  if (endIdx - startIdx + 1 > MAX_SELECTION_BLOCKS) return 'too-many-blocks';
  let chars = 0;
  for (let i = startIdx; i <= endIdx; i += 1) {
    const source = blocks[i].sourceText;
    if (source === undefined) continue;
    const from = i === startIdx ? anchors.start.offset ?? 0 : 0;
    const to = i === endIdx ? anchors.end.offset ?? source.length : source.length;
    chars += Math.max(0, to - from);
    if (chars > MAX_SELECTION_CHARS) return 'too-long';
  }
  return 'ok';
}

export function attachSelection(
  current: SelectionAttachment[],
  blocks: PaperBlock[],
  next: { text: string; anchors: SelectionAnchors },
): AttachResult {
  if (current.some((a) => sameAnchors(a.anchors, next.anchors))) {
    return { ok: true, attachments: current };
  }
  if (current.length >= MAX_ATTACHMENTS) {
    return { ok: false, reason: 'limit', attachments: current };
  }
  const size = checkSelectionSize(blocks, next.anchors);
  if (size !== 'ok') {
    return { ok: false, reason: size, attachments: current };
  }
  const kind = attachmentKindOf(blocks, next.anchors);
  return { ok: true, attachments: [...current, { text: next.text, anchors: next.anchors, kind }] };
}
