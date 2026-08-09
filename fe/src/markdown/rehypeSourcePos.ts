// 렌더 시 원문 좌표를 DOM에 심는다. 1:1 매핑이 증명된 텍스트만 감싸므로
// span의 존재 자체가 "이 구간은 좌표를 신뢰할 수 있다"는 증거다.
import { SKIP, visit } from 'unist-util-visit';
import type { Element, Root, Text } from 'hast';

export function rehypeSourcePos() {
  return (tree: Root, file: { toString(): string }) => {
    const source = String(file);
    visit(tree, (node, index, parent) => {
      if (!parent || index === undefined) return;
      const start = node.position?.start.offset;
      const end = node.position?.end.offset;
      if (start === undefined || end === undefined) return;

      if (node.type === 'element') {
        const className = (node as Element).properties?.className;
        const names = Array.isArray(className) ? className.map(String) : [];
        // display 수식은 equation 블록으로 이미 atomic이라 감싸지 않는다.
        if (!names.includes('math-inline')) return;
        parent.children[index] = wrap(node as Element, start, end, true);
        return [SKIP, index + 1];
      }

      if (node.type !== 'text') return;
      const text = node as Text;
      // 공백 전용은 선택 경계로 쓰이지 않고, 감싸면 이미지 문단 언래핑 판별을 방해한다.
      if (/^\s*$/.test(text.value)) return;
      // 길이가 아니라 내용 일치를 요구해 동길이 치환까지 걸러낸다.
      if (source.slice(start, end) !== text.value) return;
      parent.children[index] = wrap(text, start, end, false);
      return [SKIP, index + 1];
    });
  };
}

function wrap(child: Element | Text, startOffset: number, endOffset: number, isMath: boolean): Element {
  return {
    type: 'element',
    tagName: 'span',
    properties: isMath
      ? { dataSrcStart: startOffset, dataSrcEnd: endOffset, dataSrcMath: 'true' }
      : { dataSrcStart: startOffset, dataSrcEnd: endOffset },
    children: [child],
  };
}
