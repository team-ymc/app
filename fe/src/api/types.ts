// 출처: project-docs/contracts/frontend-backend/openapi.yaml (PaperStatus, Error 스키마)
export type PaperStatus =
  | 'UPLOAD_PENDING' | 'UPLOADED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'EXPIRED';

// 전체 번역 준비 상태. 파싱 status와 별개다 — 파싱이 COMPLETED인 채로 번역만 뒤따라 바뀐다.
export type TranslationStatus = 'NOT_APPLICABLE' | 'PENDING' | 'READY' | 'FAILED';

// 지식 그래프(viz.html) 준비 상태. 컴파일이 만드는 산출물이라 파싱 status와 별개다. Document 연결 전에는 null.
export type KnowledgeGraphStatus = 'PENDING' | 'READY' | 'FAILED';

export const TERMINAL_STATUSES: ReadonlySet<PaperStatus> = new Set(['COMPLETED', 'FAILED', 'EXPIRED']);

export interface Paper {
  paperId: string;
  filename: string;
  status: PaperStatus;
  createdAt: string;
  updatedAt: string;
  /** 마지막 접근 시각. 접근 이력이 없으면 null. */
  lastAccessedAt: string | null;
}

// 계약 PaperUploadHeaders: 필수 2키 + 향후 서명 헤더 추가 허용. FE는 맵 전체를 그대로 PUT에 싣는다.
export type PaperUploadHeaders = {
  'Content-Type': 'application/pdf';
  'x-amz-checksum-sha256': string;
} & Record<string, string>;

export interface CreatePaperResponse {
  paperId: string;
  fileKey: string;
  uploadUrl: string;
  uploadHeaders: PaperUploadHeaders;
  uploadExpiresAt: string;
  status: PaperStatus;
  createdAt: string;
}

export interface PaperStatusResponse {
  paperId: string;
  status: PaperStatus;
  translationStatus: TranslationStatus;
  knowledgeGraphStatus: KnowledgeGraphStatus | null;
  updatedAt: string;
}

export interface AuthUser { email?: string; displayName?: string; }

export class ApiError extends Error {
  constructor(message: string, readonly code: string | undefined, readonly httpStatus: number) {
    super(message);
    this.name = 'ApiError';
  }
}

// 본문 블록 내용 — format으로 판별한다.
export type PaperBlockContentDto =
  | { format: 'text'; text: string; textKor?: string }
  | { format: 'formula'; tex: string }
  | { format: 'table'; html: string }
  | { format: 'image'; assetKey: string };

export interface PaperContentBlockDto {
  blockId: string;
  globalOrder: number;
  /** 파서 분류. 새 label이 추가될 수 있어 enum이 아니라 string으로 받는다. */
  label: string;
  headingLevel: number | null;
  sectionPath: string[];
  content: PaperBlockContentDto;
}

export interface PaperContentAssetDto { url: string; mediaType: string; expiresAt: string; }

// GET /api/papers/{paperId}/knowledge-graph. viz.html을 여는 presigned GET URL — iframe src로 쓴다.
export interface KnowledgeGraphView { url: string; expiresAt: string; }

export interface PaperContentResponse {
  paperId: string;
  title: string | null;
  /** BE가 검증한 문서 언어. 없거나 무효면 null. */
  sourceLanguage: string | null;
  translationStatus: TranslationStatus;
  schemaVersion: number;
  blocks: PaperContentBlockDto[];
  assets: Record<string, PaperContentAssetDto>;
}

// GET /api/me/plan. UNLIMITED면 limit·used·remaining·resetAt 모두 null.
export type UsageMode = 'MONTHLY' | 'UNLIMITED';

export interface UsageLimit {
  mode: UsageMode;
  limit: number | null;
  used: number | null;      // 확정 + 진행 중 예약
  remaining: number | null;
  resetAt: string | null;   // 다음 KST 월간 버킷 시작
}

export interface PlanUsageResponse {
  plan: 'FREE' | 'PRO';
  planExpiresAt: string | null; // PRO만, FREE면 null
  usage: { aiQuery: UsageLimit; paperRegistration: UsageLimit };
}
