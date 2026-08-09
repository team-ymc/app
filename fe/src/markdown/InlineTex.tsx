// 목차처럼 markdown 파이프라인을 태울 수 없는 자리(제목이 리스트/강조로 오인될 위험)에서
// 인라인 수식 $...$ 만 KaTeX로 바꾼다.
import type { ReactNode } from 'react';
import katex from 'katex';
import 'katex/dist/katex.min.css';

const INLINE_MATH = /\$([^$\n]+)\$/g;

export function InlineTex({ children }: { children: string }) {
  const parts: ReactNode[] = [];
  let last = 0;
  for (const m of children.matchAll(INLINE_MATH)) {
    if (m.index > last) parts.push(children.slice(last, m.index));
    parts.push(
      <span
        key={m.index}
        dangerouslySetInnerHTML={{ __html: katex.renderToString(m[1], { throwOnError: false }) }}
      />,
    );
    last = m.index + m[0].length;
  }
  if (parts.length === 0) return <>{children}</>;
  if (last < children.length) parts.push(children.slice(last));
  return <>{parts}</>;
}
