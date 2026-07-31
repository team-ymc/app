// 본문 계약(DocumentParseResponse) 미확정 — FT-004 블로커, Jira 백로그.
// 이 모듈이 "무엇이 오든 블록 배열로 정규화"하는 유일한 접점이다.
// 계약 확정 시 getPaperContent 내부만 바꾼다 (spec §6).
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import type { Content, Parent } from 'mdast';
import samplePaper from '../fixtures/sample-paper.md?raw';

export type BlockType = 'heading' | 'subheading' | 'para' | 'figure' | 'equation' | 'table' | 'other';

export interface PaperBlock {
  id: string;
  type: BlockType;
  markdown: string;
  headingText?: string;
  headingLevel?: number;
}

export interface TocEntry { blockId: string; text: string; level: number; }
export interface PaperContent { blocks: PaperBlock[]; toc: TocEntry[]; }

const parser = unified().use(remarkParse).use(remarkGfm).use(remarkMath);

export function parsePaperMarkdown(source: string): PaperContent {
  const tree = parser.parse(source) as Parent;
  const blocks = (tree.children as Content[]).map((node, i): PaperBlock => {
    const markdown = source.slice(node.position!.start.offset!, node.position!.end.offset!);
    return { id: `block-${i}`, markdown, ...classify(node) };
  });
  const toc = blocks
    .filter((b) => (b.type === 'heading' || b.type === 'subheading') && b.headingLevel! >= 2)
    .map((b) => ({ blockId: b.id, text: b.headingText!, level: b.headingLevel! }));
  return { blocks, toc };
}

function classify(node: Content): Omit<PaperBlock, 'id' | 'markdown'> {
  switch (node.type) {
    case 'heading':
      return {
        type: node.depth <= 2 ? 'heading' : 'subheading',
        headingText: textOf(node),
        headingLevel: node.depth,
      };
    case 'paragraph': {
      const children = (node as Parent).children as Content[];
      const meaningful = children.filter((c) => !(c.type === 'text' && /^\s*$/.test((c as { value: string }).value)));
      if (meaningful.length === 1 && meaningful[0].type === 'image') return { type: 'figure' };
      return { type: 'para' };
    }
    case 'math':
      return { type: 'equation' };
    case 'table':
      return { type: 'table' };
    default:
      return { type: 'other' };
  }
}

function textOf(node: Content): string {
  if ('value' in node && typeof node.value === 'string') return node.value;
  if ('children' in node) return ((node as Parent).children as Content[]).map(textOf).join('');
  return '';
}

export async function getPaperContent(_paperId: string): Promise<PaperContent> {
  // 계약 미확정 — 픽스처 반환. 확정 시 이 함수만 실제 fetch로 교체 (spec §6).
  return parsePaperMarkdown(samplePaper);
}
