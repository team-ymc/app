import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';
import { rehypeSourcePos } from './rehypeSourcePos';
import { rehypePrerequisiteHighlight } from './rehypePrerequisiteHighlight';
import type { HighlightRange } from './paperContent';
import 'katex/dist/katex.min.css';
import './markdown.css';

export function PaperMarkdown({ children, onImageError, sourcePos = false, highlights }: {
  children: string;
  onImageError?: () => void;
  sourcePos?: boolean;
  /** markdown 좌표 기준 선행지식 범위. sourcePos일 때만 그린다. */
  highlights?: HighlightRange[];
}) {
  const katex: [typeof rehypeKatex, { throwOnError: boolean }] = [rehypeKatex, { throwOnError: false }];
  const highlight: [typeof rehypePrerequisiteHighlight, { ranges: HighlightRange[] }] =
    [rehypePrerequisiteHighlight, { ranges: highlights ?? [] }];
  // 하이라이트 → 좌표 → KaTeX 순서. 쪼갠 조각마다 좌표가 붙어야 선택 anchor가 밀리지 않는다.
  const plugins = sourcePos ? [highlight, rehypeSourcePos, katex] : [katex];
  return (
    <div className="pt-markdown">
      <Markdown
        remarkPlugins={[remarkGfm, remarkMath]}
        rehypePlugins={plugins}
        components={{
          img({ src, alt }) {
            return (
              <figure className="pt-figure">
                <img src={src ?? ''} alt={alt ?? ''} loading="lazy"
                  onError={(e) => { e.currentTarget.classList.add('pt-figure-broken'); onImageError?.(); }} />
                {alt && <figcaption>{alt}</figcaption>}
              </figure>
            );
          },
          table(props) {
            return <div className="pt-table-scroll"><table {...props} /></div>;
          },
          p({ node, children, ...props }) {
            const kids = node?.children?.filter(
              (c) => !(c.type === 'text' && /^\s*$/.test(c.value)),
            );
            if (kids?.length === 1 && kids[0].type === 'element' && kids[0].tagName === 'img') {
              return <>{children}</>;
            }
            return <p {...props}>{children}</p>;
          },
        }}
      >
        {children}
      </Markdown>
    </div>
  );
}
