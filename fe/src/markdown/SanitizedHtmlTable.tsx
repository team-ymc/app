import { useMemo } from 'react';
import DOMPurify from 'dompurify';
import './markdown.css';

export function SanitizedHtmlTable({ html }: { html: string }) {
  const clean = useMemo(() => DOMPurify.sanitize(html), [html]);
  return (
    <div className="pt-markdown">
      <div className="pt-table-scroll" dangerouslySetInnerHTML={{ __html: clean }} />
    </div>
  );
}
