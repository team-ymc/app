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
