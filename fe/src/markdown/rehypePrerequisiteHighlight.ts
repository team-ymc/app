// 원문 범위를 <mark>로 감싼다. rehypeSourcePos보다 먼저 돌아 텍스트 노드를 쪼개 두면,
// 좌표 플러그인이 쪼개진 조각마다 position을 보고 data-src-start를 붙인다.
import { SKIP, visit } from 'unist-util-visit';
import type { Element, Root, Text } from 'hast';
import type { HighlightRange } from './paperContent';

export interface RehypePrerequisiteHighlightOptions {
  /** markdown 좌표 기준 범위(원문 offset + shift). */
  ranges: HighlightRange[];
}

export function rehypePrerequisiteHighlight({ ranges }: RehypePrerequisiteHighlightOptions) {
  const sorted = [...ranges].sort((a, b) => a.start - b.start);
  return (tree: Root, file: { toString(): string }) => {
    if (sorted.length === 0) return;
    const source = String(file);
    visit(tree, 'text', (node, index, parent) => {
      if (!parent || index === undefined) return;
      const start = node.position?.start.offset;
      const end = node.position?.end.offset;
      if (start === undefined || end === undefined) return;
      if (source.slice(start, end) !== node.value) return;

      // 이 노드 안에 완전히 들어오는 범위만 그린다 — 노드 경계를 넘는 범위는 매핑을 보장할 수 없다.
      const inside = sorted.filter((r) => r.start >= start && r.end <= end && r.start < r.end);
      if (inside.length === 0) return;

      const pieces: (Text | Element)[] = [];
      let cursor = start;
      for (const r of inside) {
        if (r.start < cursor) continue; // 겹침은 앞 범위 우선
        if (r.start > cursor) pieces.push(textNode(source, cursor, r.start));
        pieces.push({
          type: 'element',
          tagName: 'mark',
          properties: { className: ['term-highlight'], dataHighlightId: r.id, role: 'button', tabIndex: 0 },
          children: [textNode(source, r.start, r.end)],
        });
        cursor = r.end;
      }
      if (cursor < end) pieces.push(textNode(source, cursor, end));
      parent.children.splice(index, 1, ...pieces);
      return [SKIP, index + pieces.length];
    });
  };
}

function textNode(source: string, start: number, end: number): Text {
  return {
    type: 'text',
    value: source.slice(start, end),
    position: {
      start: { line: 0, column: 0, offset: start },
      end: { line: 0, column: 0, offset: end },
    },
  };
}
