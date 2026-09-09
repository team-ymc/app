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

// heading level 2 구분선은 border(비텍스트)라 드래그 선택에 포함되지 않는다.
// 이미지·표·수식은 내부 텍스트를 부분 선택할 수 없는 atomic 블록이다 (FT-006 Story 6) —
// 첨부는 ContentAskLayer의 질문하기로 블록 단위로만 한다.
function sectionClass(b: PaperBlock): string | undefined {
  if (b.type === 'caption') return 'pt-caption-block';
  if (b.type === 'heading' && b.headingLevel === 2) return 'pt-section-start';
  if (b.type === 'figure' || b.type === 'table' || b.type === 'equation') return 'pt-atomic-block';
  return undefined;
}

export type TranslationMode = 'off' | 'below' | 'side';

export interface PaperViewerProps {
  blocks: PaperBlock[];
  containerRef: Ref<HTMLDivElement>;
  translationMode: TranslationMode;
  onImageError?: () => void;
}

export function PaperViewer({ blocks, containerRef, translationMode, onImageError }: PaperViewerProps) {
  const side = translationMode === 'side';
  // 옆 배치에서만 바깥 wrap을 넓혀 두 열을 담는다. 시트(article)는 아트보드대로
  // wrap보다 좁게 한 번 더 조여 본문 폭을 1160px로 맞춘다(wrap 1320 - 시트 padding 40*2 = 1240 시트, 1240 - 40*2 = 1160 본문).
  const wrapWidth = side ? '1320px' : '1000px';
  const sheetWidth = side ? '1240px' : '1000px';
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
      <div style={{ width: '100%', maxWidth: wrapWidth }}>
        <PaperSheet style={{ width: '100%', maxWidth: sheetWidth, padding: side ? '28px 40px' : '28px 36px' }}>
          {/* 아래 배치는 쌍 사이를 문단 간격보다 넓혀 어떤 원문의 번역인지 구분한다. */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: translationMode === 'below' ? '34px' : '24px' }}>
            {blocks.map((b) => {
              const translation = translationMode === 'off' ? undefined : b.translation;
              return (
                <div
                  key={b.id}
                  className={translation ? 'pt-row pt-row-pair' : 'pt-row'}
                  data-mode={translationMode}
                >
                  <section
                    data-block-id={b.id}
                    id={b.id}
                    className={sectionClass(b)}
                    style={{ scrollMarginTop: '24px' }}
                  >
                    {b.type === 'table' && b.tableHtml != null ? (
                      <SanitizedHtmlTable html={b.tableHtml} />
                    ) : (
                      <PaperMarkdown sourcePos onImageError={onImageError}>{b.markdown ?? ''}</PaperMarkdown>
                    )}
                  </section>
                  {translation ? (
                    <>
                      <span className="pt-rule" aria-hidden="true" />
                      {/* data-block-id를 붙이지 않아 선택 앵커·질문하기 대상에서 빠진다. */}
                      {/* 캡션 번역 칸은 원문과 같은 캡션 박스 모양(pt-caption-block)으로 그린다. */}
                      <aside className={b.type === 'caption' ? 'pt-translation pt-caption-block' : 'pt-translation'} role="note">
                        <PaperMarkdown>{translation}</PaperMarkdown>
                      </aside>
                    </>
                  ) : null}
                </div>
              );
            })}
          </div>
        </PaperSheet>
      </div>
    </div>
  );
}
