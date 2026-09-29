import type {
  AuthUser,
  CreatePaperResponse,
  Paper,
  PaperContentResponse,
  PaperStatusResponse,
  PlanUsageResponse,
  PrerequisiteDefinitionResponse,
} from '../api/types';

// `npm run dev:preview` uses Vite's preview mode. The DEV check ensures this
// branch can never become active in a production build, even if its mode is
// accidentally set to "preview".
export const isDevPreview = import.meta.env.DEV && import.meta.env.MODE === 'preview';

export const previewUser: AuthUser = {
  displayName: 'Preview User',
  email: 'preview@paperteacher.local',
};

let previewPapers: Paper[] = [
  {
    paperId: 'preview-transformers',
    title: 'Attention Is All You Need',
    filename: 'attention-is-all-you-need.pdf',
    status: 'COMPLETED',
    createdAt: '2026-09-18T05:30:00.000Z',
    updatedAt: '2026-09-18T05:34:00.000Z',
    lastAccessedAt: '2026-09-28T09:15:00.000Z',
  },
  {
    paperId: 'preview-rag',
    title: 'Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks',
    filename: 'retrieval-augmented-generation.pdf',
    status: 'PROCESSING',
    createdAt: '2026-09-27T11:10:00.000Z',
    updatedAt: '2026-09-27T11:12:00.000Z',
    lastAccessedAt: null,
  },
  {
    paperId: 'preview-bert',
    title: 'BERT: Pre-training of Deep Bidirectional Transformers',
    filename: 'bert-pretraining.pdf',
    status: 'FAILED',
    createdAt: '2026-09-12T02:45:00.000Z',
    updatedAt: '2026-09-12T02:47:00.000Z',
    lastAccessedAt: null,
  },
];

function copyPaper(paper: Paper): Paper {
  return { ...paper };
}

export async function listPreviewPapers(): Promise<{ papers: Paper[] }> {
  return { papers: previewPapers.map(copyPaper) };
}

export async function getPreviewPaperStatus(paperId: string): Promise<PaperStatusResponse> {
  const paper = previewPapers.find((item) => item.paperId === paperId);
  if (!paper) throw new Error('Preview paper not found');
  return {
    paperId,
    status: paper.status,
    translationStatus: paper.status === 'COMPLETED' ? 'READY' : 'PENDING',
    knowledgeGraphStatus: null,
    updatedAt: paper.updatedAt,
  };
}

export async function getPreviewPaperContent(paperId: string): Promise<PaperContentResponse> {
  const paper = previewPapers.find((item) => item.paperId === paperId);
  if (!paper || paper.status !== 'COMPLETED') throw new Error('Preview paper content not found');
  return {
    paperId,
    title: paper.title,
    sourceLanguage: 'en',
    translationStatus: 'READY',
    schemaVersion: 1,
    assets: {},
    prerequisiteHighlights: [
      {
        highlightId: 'preview-prerequisite-encoder-decoder',
        blockId: 'preview-architecture',
        startOffset: 27,
        endOffset: 42,
        text: 'encoder-decoder',
      },
      {
        highlightId: 'preview-prerequisite-self-attention',
        blockId: 'preview-architecture',
        startOffset: 67,
        endOffset: 81,
        text: 'self-attention',
      },
    ],
    blocks: [
      {
        blockId: 'preview-title',
        globalOrder: 1,
        label: 'doc_title',
        headingLevel: 1,
        sectionPath: [],
        content: { format: 'text', text: 'Attention Is All You Need' },
      },
      {
        blockId: 'preview-abstract-title',
        globalOrder: 2,
        label: 'paragraph_title',
        headingLevel: 2,
        sectionPath: ['Abstract'],
        content: { format: 'text', text: 'Abstract' },
      },
      {
        blockId: 'preview-abstract',
        globalOrder: 3,
        label: 'paragraph',
        headingLevel: null,
        sectionPath: ['Abstract'],
        content: {
          format: 'text',
          text: 'The dominant sequence transduction models are based on complex recurrent or convolutional neural networks. We propose a new, simple network architecture—the Transformer—based solely on attention mechanisms.',
          textKor: '기존의 시퀀스 변환 모델은 복잡한 순환 신경망이나 합성곱 신경망을 기반으로 합니다. 이 논문은 어텐션 메커니즘만으로 구성된 단순한 새 구조인 Transformer를 제안합니다.',
        },
      },
      {
        blockId: 'preview-introduction-title',
        globalOrder: 4,
        label: 'paragraph_title',
        headingLevel: 2,
        sectionPath: ['1. Introduction'],
        content: { format: 'text', text: '1. Introduction' },
      },
      {
        blockId: 'preview-introduction',
        globalOrder: 5,
        label: 'paragraph',
        headingLevel: null,
        sectionPath: ['1. Introduction'],
        content: {
          format: 'text',
          text: 'Recurrent neural networks have been firmly established as state-of-the-art approaches in sequence modeling. Their sequential nature, however, prevents parallelization within training examples and becomes critical at longer sequence lengths.',
          textKor: '순환 신경망은 시퀀스 모델링의 대표적인 접근 방식으로 자리 잡았습니다. 하지만 순차적인 계산 특성 때문에 학습 예제 내부를 병렬화하기 어렵고, 긴 시퀀스에서는 이 문제가 더욱 커집니다.',
        },
      },
      {
        blockId: 'preview-architecture-title',
        globalOrder: 6,
        label: 'paragraph_title',
        headingLevel: 2,
        sectionPath: ['3. Model Architecture'],
        content: { format: 'text', text: '3. Model Architecture' },
      },
      {
        blockId: 'preview-architecture',
        globalOrder: 7,
        label: 'paragraph',
        headingLevel: null,
        sectionPath: ['3. Model Architecture'],
        content: {
          format: 'text',
          text: 'The Transformer follows an encoder-decoder structure using stacked self-attention and position-wise fully connected layers for both the encoder and decoder.',
          textKor: 'Transformer는 인코더와 디코더 모두에 다층 self-attention과 위치별 완전 연결 계층을 사용하는 인코더-디코더 구조를 따릅니다.',
        },
      },
      {
        blockId: 'preview-attention-title',
        globalOrder: 8,
        label: 'paragraph_title',
        headingLevel: 3,
        sectionPath: ['3. Model Architecture', '3.2 Attention'],
        content: { format: 'text', text: '3.2 Scaled Dot-Product Attention' },
      },
      {
        blockId: 'preview-attention',
        globalOrder: 9,
        label: 'paragraph',
        headingLevel: null,
        sectionPath: ['3. Model Architecture', '3.2 Attention'],
        content: {
          format: 'text',
          text: 'The input consists of queries and keys of dimension $d_k$, and values of dimension $d_v$. We compute the dot products of the query with all keys, divide each by $\\sqrt{d_k}$, and apply a softmax function.',
          textKor: '입력은 차원이 $d_k$인 query와 key, 그리고 차원이 $d_v$인 value로 구성됩니다. query와 모든 key의 내적을 계산하고 $\\sqrt{d_k}$로 나눈 뒤 softmax 함수를 적용합니다.',
        },
      },
      {
        blockId: 'preview-equation',
        globalOrder: 10,
        label: 'formula',
        headingLevel: null,
        sectionPath: ['3. Model Architecture', '3.2 Attention'],
        content: { format: 'formula', tex: '\\operatorname{Attention}(Q,K,V)=\\operatorname{softmax}\\left(\\frac{QK^T}{\\sqrt{d_k}}\\right)V' },
      },
      {
        blockId: 'preview-results-title',
        globalOrder: 11,
        label: 'paragraph_title',
        headingLevel: 2,
        sectionPath: ['6. Results'],
        content: { format: 'text', text: '6. Results' },
      },
      {
        blockId: 'preview-results-table',
        globalOrder: 12,
        label: 'table',
        headingLevel: null,
        sectionPath: ['6. Results'],
        content: {
          format: 'table',
          html: '<table><thead><tr><th>Model</th><th>BLEU</th><th>Training Cost</th></tr></thead><tbody><tr><td>Transformer (base)</td><td>27.3</td><td>3.3 × 10¹⁸ FLOPs</td></tr><tr><td>Transformer (big)</td><td>28.4</td><td>2.3 × 10¹⁹ FLOPs</td></tr></tbody></table>',
        },
      },
      {
        blockId: 'preview-conclusion-title',
        globalOrder: 13,
        label: 'paragraph_title',
        headingLevel: 2,
        sectionPath: ['7. Conclusion'],
        content: { format: 'text', text: '7. Conclusion' },
      },
      {
        blockId: 'preview-conclusion',
        globalOrder: 14,
        label: 'paragraph',
        headingLevel: null,
        sectionPath: ['7. Conclusion'],
        content: {
          format: 'text',
          text: 'We presented the Transformer, the first sequence transduction model based entirely on attention. It trains significantly faster than architectures based on recurrent or convolutional layers.',
          textKor: '이 논문은 전적으로 attention에 기반한 최초의 시퀀스 변환 모델인 Transformer를 제시했습니다. 순환 또는 합성곱 계층 기반 구조보다 훨씬 빠르게 학습됩니다.',
        },
      },
    ],
  };
}

export async function getPreviewPrerequisiteDefinition(
  highlightId: string,
): Promise<PrerequisiteDefinitionResponse> {
  if (highlightId === 'preview-prerequisite-encoder-decoder') {
    return {
      term: 'encoder-decoder',
      definitionEn: 'A neural architecture in which an encoder turns the input sequence into an internal representation and a decoder uses that representation to generate an output sequence.',
      definitionKo: '입력 시퀀스를 내부 표현으로 변환하는 인코더와, 그 표현을 사용해 출력 시퀀스를 생성하는 디코더로 구성된 신경망 구조입니다.',
    };
  }
  if (highlightId === 'preview-prerequisite-self-attention') {
    return {
      term: 'self-attention',
      definitionEn: 'A mechanism that lets every token in a sequence weigh and combine information from the other tokens in that same sequence.',
      definitionKo: '시퀀스의 각 토큰이 같은 시퀀스 안의 다른 토큰들을 얼마나 참고할지 계산하고 그 정보를 결합하는 메커니즘입니다.',
    };
  }
  throw new Error('Preview prerequisite definition not found');
}

export async function renamePreviewPaper(paperId: string, title: string): Promise<Paper> {
  const paper = previewPapers.find((item) => item.paperId === paperId);
  if (!paper) throw new Error('Preview paper not found');
  paper.title = title;
  paper.updatedAt = new Date().toISOString();
  return copyPaper(paper);
}

export async function deletePreviewPaper(paperId: string): Promise<void> {
  previewPapers = previewPapers.filter((paper) => paper.paperId !== paperId);
}

export async function createPreviewPaper(filename: string): Promise<CreatePaperResponse> {
  const now = new Date().toISOString();
  const paperId = `preview-${Date.now()}`;
  previewPapers = [
    {
      paperId,
      title: filename.replace(/\.pdf$/i, ''),
      filename,
      status: 'UPLOAD_PENDING',
      createdAt: now,
      updatedAt: now,
      lastAccessedAt: null,
    },
    ...previewPapers,
  ];
  return {
    paperId,
    fileKey: `preview/${filename}`,
    uploadUrl: 'preview://upload',
    uploadHeaders: {
      'Content-Type': 'application/pdf',
      'x-amz-checksum-sha256': 'preview',
    },
    uploadExpiresAt: new Date(Date.now() + 15 * 60 * 1000).toISOString(),
    status: 'UPLOAD_PENDING',
    createdAt: now,
  };
}

export async function completePreviewUpload(paperId: string): Promise<PaperStatusResponse> {
  const paper = previewPapers.find((item) => item.paperId === paperId);
  if (!paper) throw new Error('Preview paper not found');
  paper.status = 'PROCESSING';
  paper.updatedAt = new Date().toISOString();
  return {
    paperId,
    status: paper.status,
    translationStatus: 'PENDING',
    knowledgeGraphStatus: null,
    updatedAt: paper.updatedAt,
  };
}

export async function getPreviewPlan(): Promise<PlanUsageResponse> {
  const nextMonth = new Date();
  nextMonth.setMonth(nextMonth.getMonth() + 1, 1);
  nextMonth.setHours(0, 0, 0, 0);
  return {
    plan: 'FREE',
    planExpiresAt: null,
    usage: {
      aiQuery: { mode: 'MONTHLY', limit: 30, used: 8, remaining: 22, resetAt: nextMonth.toISOString() },
      paperRegistration: { mode: 'MONTHLY', limit: 10, used: 3, remaining: 7, resetAt: nextMonth.toISOString() },
    },
  };
}
