// 파서가 추출한 표 html을 정화해 렌더한다 — 계약이 렌더 전 정화를 요구한다.
// 셀 안 인라인 $…$ LaTeX는 정화 직후 분리된 엘리먼트에서 KaTeX로 변환해 완성된 html을
// 통째로 넣는다. DOM에 넣고 나서 후처리(effect)하는 방식은 리렌더가 innerHTML을 원본으로
// 되돌리는 타이밍과 경합하므로 쓰지 않는다 — 렌더 결과가 입력 html의 순수 함수여야 안전하다.
import { useMemo } from 'react';
import DOMPurify from 'dompurify';
import renderMathInElement from 'katex/dist/contrib/auto-render';
import 'katex/dist/katex.min.css';
import './markdown.css';

function sanitizeAndRenderMath(html: string): string {
  const el = document.createElement('div');
  el.innerHTML = DOMPurify.sanitize(html);
  renderMathInElement(el, {
    delimiters: [
      { left: '$$', right: '$$', display: true },
      { left: '$', right: '$', display: false },
    ],
    throwOnError: false,
  });
  return el.innerHTML;
}

export function SanitizedHtmlTable({ html }: { html: string }) {
  const rendered = useMemo(() => sanitizeAndRenderMath(html), [html]);
  return (
    <div className="pt-markdown">
      <div className="pt-table-scroll" dangerouslySetInnerHTML={{ __html: rendered }} />
    </div>
  );
}
