// 화면 선택(Range)을 원문 offset으로 옮긴다. 좌표는 렌더 시 심어둔 [data-src-start]에서만
// 읽고, 없으면 undefined를 반환해 호출부가 블록 단위로 폴백하게 한다.
import type { PaperBlock } from '../../markdown/paperContent';

export interface SelectionRun {
  node: Text;
  from: number;
  to: number;
}

/** 번역 셀 텍스트 — 원문 offset 대상이 아니라 선택에 섞여도 무시한다. */
function isTranslationText(text: Text): boolean {
  return text.parentElement?.closest('.pt-translation') != null;
}

/** Range와 겹치는 텍스트 구간을 문서 순서로 모은다. 길이 0인 구간은 제외된다. */
export function collectRuns(range: Range): SelectionRun[] {
  const root = range.commonAncestorContainer;
  if (root.nodeType === Node.TEXT_NODE) {
    const text = root as Text;
    if (isTranslationText(text)) return [];
    const run = clip(text, range);
    return run ? [run] : [];
  }
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const runs: SelectionRun[] = [];
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const text = node as Text;
    if (isTranslationText(text)) continue;
    if (!range.intersectsNode(text)) continue;
    const run = clip(text, range);
    if (run) runs.push(run);
  }
  return runs;
}

function clip(text: Text, range: Range): SelectionRun | null {
  const from = text === range.startContainer ? range.startOffset : 0;
  const to = text === range.endContainer ? range.endOffset : text.data.length;
  return to > from ? { node: text, from, to } : null;
}

/** 선택 양끝의 공백을 안쪽으로 오므린다. 내용이 남지 않으면 빈 배열. */
export function trimRuns(runs: SelectionRun[]): SelectionRun[] {
  const trimmed = runs.map((run) => ({ ...run }));
  while (trimmed.length) {
    const first = trimmed[0];
    first.from += leadingSpaces(first);
    if (first.from < first.to) break;
    trimmed.shift();
  }
  while (trimmed.length) {
    const last = trimmed[trimmed.length - 1];
    last.to -= trailingSpaces(last);
    if (last.from < last.to) break;
    trimmed.pop();
  }
  return trimmed;
}

function leadingSpaces(run: SelectionRun): number {
  return /^\s*/.exec(run.node.data.slice(run.from, run.to))![0].length;
}

function trailingSpaces(run: SelectionRun): number {
  return /\s*$/.exec(run.node.data.slice(run.from, run.to))![0].length;
}

/** 경계가 수식 래퍼 안인지 — 안이면 래퍼 바깥으로 스냅해야 한다. */
export function isMathRun(node: Text): boolean {
  return node.parentElement?.closest('[data-src-start]')?.hasAttribute('data-src-math') ?? false;
}

/** 경계 하나를 원문 offset으로 옮긴다. 증명할 수 없으면 undefined. */
export function resolveOffset(
  node: Text,
  offsetInNode: number,
  block: PaperBlock,
  edge: 'start' | 'end',
): number | undefined {
  const { sourceText, sourceOffsetShift } = block;
  if (sourceText === undefined || sourceOffsetShift === undefined) return undefined;
  const span = node.parentElement?.closest('[data-src-start]');
  if (!span) return undefined;

  const base = span.hasAttribute('data-src-math')
    ? Number(span.getAttribute(edge === 'start' ? 'data-src-start' : 'data-src-end'))
    : Number(span.getAttribute('data-src-start')) + offsetInNode;
  const offset = base - sourceOffsetShift;

  if (!Number.isFinite(offset) || offset < 0 || offset > sourceText.length) return undefined;
  if (splitsSurrogatePair(sourceText, offset)) return undefined;
  return offset;
}

function splitsSurrogatePair(text: string, offset: number): boolean {
  const code = text.charCodeAt(offset);
  return code >= 0xdc00 && code <= 0xdfff;
}
