// 화면 선택(Range)을 [data-block-id] 조상 탐색으로 블록 앵커에 매핑한다.
// offset은 보내지 않는다 — 블록 단위 선택.

export interface SelectionAnchors {
  start: { blockId: string };
  end: { blockId: string };
}

export function computeSelectionAnchors(range: Range): SelectionAnchors | null {
  const start = closestBlockId(range.startContainer);
  const end = closestBlockId(range.endContainer);
  if (!start || !end) return null;
  return { start: { blockId: start }, end: { blockId: end } };
}

function closestBlockId(node: Node): string | null {
  const el = node instanceof Element ? node : node.parentElement;
  return el?.closest('[data-block-id]')?.getAttribute('data-block-id') ?? null;
}
