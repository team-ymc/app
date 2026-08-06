import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';
import 'katex/dist/katex.min.css';
import './markdown.css';

export function PaperMarkdown({ children, onImageError }: { children: string; onImageError?: () => void }) {
  return (
    <div className="pt-markdown">
      <Markdown
        remarkPlugins={[remarkGfm, remarkMath]}
        rehypePlugins={[[rehypeKatex, { throwOnError: false }]]}
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
