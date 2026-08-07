// 저장된 블록 앵커를 로컬 본문에서 텍스트로 복원한다 — 칩 미리보기용.
// selection은 텍스트를 저장하지 않으므로(계약) 항상 blocks에서 다시 찾는다.
import type { PaperBlock } from '../markdown/paperContent';
import type { SelectionAnchors } from '../routes/study/selectionAnchors';

const PREVIEW_MAX = 120;

export function resolveSelectionPreview(
  blocks: PaperBlock[],
  selection: SelectionAnchors,
): string | null {
  const startIdx = blocks.findIndex((b) => b.id === selection.start.blockId);
  const endIdx = blocks.findIndex((b) => b.id === selection.end.blockId);
  if (startIdx < 0 || endIdx < 0 || startIdx > endIdx) return null;
  const text = blocks
    .slice(startIdx, endIdx + 1)
    .map(blockText)
    .map((t) => t.trim())
    .filter(Boolean)
    .join(' ');
  if (!text) return null;
  return text.length > PREVIEW_MAX ? `${text.slice(0, PREVIEW_MAX)}…` : text;
}

function blockText(b: PaperBlock): string {
  if (b.type === 'heading' || b.type === 'subheading') return b.headingText ?? '';
  if (b.type === 'para') return b.markdown ?? '';
  return ''; // equation·table·figure·other는 미리보기에서 제외
}
