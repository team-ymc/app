// 출처: project-docs/contracts/frontend-backend/openapi.yaml (PaperStatus, Error 스키마)
export type PaperStatus =
  | 'UPLOAD_PENDING' | 'UPLOADED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'EXPIRED';

export const TERMINAL_STATUSES: ReadonlySet<PaperStatus> = new Set(['COMPLETED', 'FAILED', 'EXPIRED']);

export interface Paper {
  paperId: string;
  filename: string;
  status: PaperStatus;
  createdAt: string;
  updatedAt: string;
}

export interface CreatePaperResponse {
  paperId: string;
  fileKey: string;
  uploadUrl: string;
  uploadExpiresAt: string;
  status: PaperStatus;
  createdAt: string;
}

export interface PaperStatusResponse { paperId: string; status: PaperStatus; updatedAt: string; }

export interface AuthUser { email?: string; displayName?: string; }

export class ApiError extends Error {
  constructor(message: string, readonly code: string | undefined, readonly httpStatus: number) {
    super(message);
    this.name = 'ApiError';
  }
}

// 본문 블록 내용 — format으로 판별한다.
export type PaperBlockContentDto =
  | { format: 'text'; text: string }
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

export interface PaperContentResponse {
  paperId: string;
  title: string | null;
  schemaVersion: number;
  blocks: PaperContentBlockDto[];
  assets: Record<string, PaperContentAssetDto>;
}
