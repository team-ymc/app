import { describe, it, expect, vi } from 'vitest';
import { adaptPaperContent } from './paperContent';
import type { PaperContentResponse } from '../api/types';

function res(partial: Partial<PaperContentResponse>): PaperContentResponse {
  return { paperId: 'p1', title: null, schemaVersion: 1, blocks: [], assets: {}, ...partial };
}

describe('adaptPaperContent', () => {
  it('제목 계열은 headingLevel대로 #을 붙이고 toc는 level 2 이상만 담는다', () => {
    const out = adaptPaperContent(res({
      title: 'Fixture',
      blocks: [
        { blockId: 'b0', globalOrder: 0, label: 'doc_title', headingLevel: 1, sectionPath: [], content: { format: 'text', text: 'Fixture' } },
        { blockId: 'b1', globalOrder: 1, label: 'paragraph_title', headingLevel: 2, sectionPath: [], content: { format: 'text', text: 'Intro' } },
        { blockId: 'b2', globalOrder: 2, label: 'paragraph_title', headingLevel: 3, sectionPath: [], content: { format: 'text', text: 'Detail' } },
      ],
    }));
    expect(out.title).toBe('Fixture');
    expect(out.blocks[0]).toMatchObject({ id: 'b0', type: 'heading', markdown: '# Fixture' });
    expect(out.blocks[2]).toMatchObject({ type: 'subheading', markdown: '### Detail' });
    expect(out.toc).toEqual([
      { blockId: 'b1', text: 'Intro', level: 2 },
      { blockId: 'b2', text: 'Detail', level: 3 },
    ]);
  });

  it('수식은 $$ 블록으로, 표는 tableHtml로, 이미지는 presigned URL 마크다운으로 변환한다', () => {
    const out = adaptPaperContent(res({
      blocks: [
        { blockId: 'f0', globalOrder: 0, label: 'display_formula', headingLevel: null, sectionPath: [], content: { format: 'formula', tex: 'E=mc^2' } },
        { blockId: 't0', globalOrder: 1, label: 'table', headingLevel: null, sectionPath: [], content: { format: 'table', html: '<table><tr><td>x</td></tr></table>' } },
        { blockId: 'i0', globalOrder: 2, label: 'image', headingLevel: null, sectionPath: [], content: { format: 'image', assetKey: 'image_0' } },
      ],
      assets: { image_0: { url: 'https://s3/img.jpg?sig=1', mediaType: 'image/jpeg', expiresAt: '2026-08-05T00:00:00Z' } },
    }));
    expect(out.blocks[0]).toMatchObject({ type: 'equation', markdown: '$$\nE=mc^2\n$$' });
    expect(out.blocks[1]).toMatchObject({ type: 'table', tableHtml: '<table><tr><td>x</td></tr></table>' });
    expect(out.blocks[1].markdown).toBeUndefined();
    expect(out.blocks[2]).toMatchObject({ type: 'figure', markdown: '![](https://s3/img.jpg?sig=1)' });
    expect(out.assetExpiresAt).toBe('2026-08-05T00:00:00Z');
  });

  it('레지스트리에 없는 assetKey·미지 format은 깨지지 않게 강등하고 경고한다', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const out = adaptPaperContent(res({
      blocks: [
        { blockId: 'x0', globalOrder: 0, label: 'image', headingLevel: null, sectionPath: [], content: { format: 'image', assetKey: 'missing' } },
        { blockId: 'x1', globalOrder: 1, label: 'text', headingLevel: null, sectionPath: [], content: { format: 'mystery', text: 'raw' } as never },
      ],
    }));
    expect(out.blocks[0].type).toBe('other');
    expect(out.blocks[1].type).toBe('para');
    expect(warn).toHaveBeenCalledTimes(2);
    warn.mockRestore();
  });

  it('assetExpiresAt은 문자열 사전순이 아니라 실제 시각 기준 최솟값이다', () => {
    const out = adaptPaperContent(res({
      assets: {
        a: { url: 'https://s3/a', mediaType: 'image/jpeg', expiresAt: '2026-08-05T00:00:00.001Z' },
        b: { url: 'https://s3/b', mediaType: 'image/jpeg', expiresAt: '2026-08-05T00:00:00Z' },
      },
    }));
    expect(out.assetExpiresAt).toBe('2026-08-05T00:00:00Z');
  });

  it('content가 없는 블록은 other로 강등하고 경고하며, 나머지 블록은 정상 매핑된다', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const out = adaptPaperContent(res({
      blocks: [
        { blockId: 'y0', globalOrder: 0, label: 'text', headingLevel: null, sectionPath: [], content: undefined as never },
        { blockId: 'y1', globalOrder: 1, label: 'text', headingLevel: null, sectionPath: [], content: { format: 'text', text: 'ok' } },
      ],
    }));
    expect(out.blocks[0].type).toBe('other');
    expect(out.blocks[1]).toMatchObject({ type: 'para', markdown: 'ok' });
    expect(warn).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });
});
