// 화면 선택(Range)을 블록 앵커로 옮긴다. 좌표를 증명할 수 있는 경계에는 원문 offset을 붙이고,
// 증명할 수 없으면 그 anchor만 offset을 생략해 블록 단위로 폴백한다.
import type { PaperBlock } from '../../markdown/paperContent';
import { collectRuns, isMathRun, resolveOffset, trimRuns } from './sourceOffset';

export interface SelectionAnchor {
  blockId: string;
  offset?: number;
}

export interface SelectionAnchors {
  start: SelectionAnchor;
  end: SelectionAnchor;
}

export function computeSelectionAnchors(range: Range, blocks: PaperBlock[]): SelectionAnchors | null {
  const runs = trimRuns(collectRuns(range));
  if (!runs.length) return null;

  const first = runs[0];
  const last = runs[runs.length - 1];
  const startBlockId = closestBlockId(first.node);
  const endBlockId = closestBlockId(last.node);
  if (!startBlockId || !endBlockId) return null;

  const startBlock = blocks.find((b) => b.id === startBlockId);
  const endBlock = blocks.find((b) => b.id === endBlockId);
  let start = startBlock && resolveOffset(first.node, first.from, startBlock, 'start');
  let end = endBlock && resolveOffset(last.node, last.to, endBlock, 'end');

  // 같은 블록인데 순서가 뒤집히거나 비면 둘 다 버린다.
  if (startBlockId === endBlockId && start !== undefined && end !== undefined && start >= end) {
    start = undefined;
    end = undefined;
  }

  // 선택이 한 구간 안에 다 들어가면 원문으로 재구성해 대조한다. 수식 래퍼는 화면 텍스트가
  // 원문과 다르므로 이 대조를 적용하지 않는다.
  if (runs.length === 1 && !isMathRun(first.node) && start !== undefined && end !== undefined) {
    const selected = first.node.data.slice(first.from, first.to);
    if (startBlock!.sourceText!.slice(start, end) !== selected) {
      start = undefined;
      end = undefined;
    }
  }

  return { start: anchor(startBlockId, start), end: anchor(endBlockId, end) };
}

function anchor(blockId: string, offset: number | undefined): SelectionAnchor {
  return offset === undefined ? { blockId } : { blockId, offset };
}

function closestBlockId(node: Text): string | null {
  return node.parentElement?.closest('[data-block-id]')?.getAttribute('data-block-id') ?? null;
}
