// 본문 계약(getPaperContent) 응답을 뷰어 블록 모델로 정규화하는 유일한 접점.
// 제목·본문·수식·이미지는 markdown 문자열로 변환해 기존 PaperMarkdown 렌더러를 재사용하고,
// 표는 html 그대로 넘겨 렌더 측에서 정화한다.
import { fetchPaperContent } from '../api/papers';
import type { PaperContentBlockDto, PaperContentResponse } from '../api/types';

export type BlockType = 'heading' | 'subheading' | 'para' | 'figure' | 'equation' | 'table' | 'other';

export interface PaperBlock {
  id: string;
  type: BlockType;
  markdown?: string;
  tableHtml?: string;
  headingText?: string;
  headingLevel?: number;
}

export interface TocEntry { blockId: string; text: string; level: number; }

export interface PaperContent {
  title: string | null;
  blocks: PaperBlock[];
  toc: TocEntry[];
  /** 이미지 presigned URL 중 가장 이른 만료 시각. asset이 없으면 null. */
  assetExpiresAt: string | null;
}

export async function getPaperContent(paperId: string): Promise<PaperContent> {
  return adaptPaperContent(await fetchPaperContent(paperId));
}

export function adaptPaperContent(res: PaperContentResponse): PaperContent {
  const blocks = res.blocks.map((b) => adaptBlock(b, res));
  const toc = blocks
    .filter((b) => (b.type === 'heading' || b.type === 'subheading') && (b.headingLevel ?? 0) >= 2)
    .map((b) => ({ blockId: b.id, text: b.headingText!, level: b.headingLevel! }));
  const expiries = Object.values(res.assets).map((a) => a.expiresAt);
  const earliest = expiries.length
    ? expiries.reduce((min, v) => (Date.parse(v) < Date.parse(min) ? v : min))
    : null;
  return { title: res.title, blocks, toc, assetExpiresAt: earliest };
}

function adaptBlock(b: PaperContentBlockDto, res: PaperContentResponse): PaperBlock {
  const c = b.content;
  if (!c) {
    console.warn(`content가 없는 블록 — other로 강등: ${b.blockId}`);
    return { id: b.blockId, type: 'other', markdown: '' };
  }
  switch (c.format) {
    case 'text': {
      if (b.label === 'doc_title' || b.label === 'paragraph_title') {
        const level = b.headingLevel ?? 1;
        return {
          id: b.blockId,
          type: level <= 2 ? 'heading' : 'subheading',
          markdown: `${'#'.repeat(Math.min(level, 6))} ${c.text}`,
          headingText: c.text,
          headingLevel: level,
        };
      }
      return { id: b.blockId, type: 'para', markdown: c.text };
    }
    case 'formula':
      return { id: b.blockId, type: 'equation', markdown: `$$\n${c.tex}\n$$` };
    case 'table':
      return { id: b.blockId, type: 'table', tableHtml: c.html };
    case 'image': {
      const asset = res.assets[c.assetKey];
      if (!asset) {
        console.warn(`assets에 없는 assetKey — 블록을 건너뜀: ${c.assetKey} (${b.blockId})`);
        return { id: b.blockId, type: 'other', markdown: '' };
      }
      return { id: b.blockId, type: 'figure', markdown: `![](${asset.url})` };
    }
    default: {
      console.warn(`알 수 없는 content format — para로 강등: ${(c as { format: string }).format} (${b.blockId})`);
      const text = (c as { text?: string }).text ?? '';
      return { id: b.blockId, type: 'para', markdown: text };
    }
  }
}
