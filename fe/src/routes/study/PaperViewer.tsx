// 이식: Paper Study Page.dc.html R4 Paper viewer.
// 목업 blocks x-map(더미 BLOCKS 배열, sc-if 타입 분기)은 픽스처 기반 실제 데이터로 교체한다.
// 블록 콘텐츠 렌더는 PaperMarkdown이 전담 — brief Step 3 literal 계약대로
// <section data-block-id id><PaperMarkdown>{markdown}</PaperMarkdown></section> 구조를 유지한다.
// 타입별 wrapper 시각 스타일(heading/subheading 크기 등)은 markdown.css에서 맞춘다 (report 참고).
import type { Ref } from 'react';
import { PaperSheet } from '../../design/components/PaperSheet';
import { PaperMarkdown } from '../../markdown/PaperMarkdown';
import { SanitizedHtmlTable } from '../../markdown/SanitizedHtmlTable';
import type { PaperBlock } from '../../markdown/paperContent';

export interface PaperViewerProps {
  blocks: PaperBlock[];
  containerRef: Ref<HTMLDivElement>;
  onImageError?: () => void;
}

export function PaperViewer({ blocks, containerRef, onImageError }: PaperViewerProps) {
  return (
    <div
      ref={containerRef}
      style={{
        flex: 1,
        minWidth: 0,
        overflowY: 'auto',
        background: 'var(--color-bg-canvas)',
        padding: '8px 8px 24px',
        display: 'flex',
        justifyContent: 'center',
      }}
    >
      <div style={{ width: '100%', maxWidth: '1000px' }}>
        <PaperSheet style={{ width: '100%', maxWidth: '1000px', padding: '28px 36px' }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '24px' }}>
            {blocks.map((b) => (
              <section key={b.id} data-block-id={b.id} id={b.id} style={{ scrollMarginTop: '24px' }}>
                {b.type === 'table' && b.tableHtml != null ? (
                  <SanitizedHtmlTable html={b.tableHtml} />
                ) : (
                  <PaperMarkdown onImageError={onImageError}>{b.markdown ?? ''}</PaperMarkdown>
                )}
              </section>
            ))}
          </div>
        </PaperSheet>
      </div>
    </div>
  );
}
