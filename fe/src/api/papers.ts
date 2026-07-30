// BE 연동 단일 접점 (DESIGN.md D3). 표현 층과 분리 — 진짜 FE는 이 모듈을 그대로 승계한다.
// BE 호출은 authFetch(자동 Bearer + 401 재시도)를 쓴다. S3 presigned PUT은 예외 — 서명 URL이 인가다.

import { authFetch } from './auth';
import { ApiError, type CreatePaperResponse, type Paper, type PaperStatusResponse } from './types';

export async function createPaper(filename: string, contentType: string): Promise<CreatePaperResponse> {
  const res = await authFetch('/api/papers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ filename, contentType }),
  });
  if (!res.ok) throw await apiError(res);
  return res.json(); // { paperId, fileKey, uploadUrl, uploadExpiresAt, status, createdAt }
}

// presigned PUT. Content-Type은 서명값과 정확히 일치해야 한다 (DESIGN.md D6).
export function uploadToS3(uploadUrl: string, file: Blob, onProgress?: (pct: number) => void): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', uploadUrl);
    xhr.setRequestHeader('Content-Type', 'application/pdf');
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) onProgress(Math.round((e.loaded / e.total) * 100));
    };
    xhr.onload = () =>
      xhr.status >= 200 && xhr.status < 300
        ? resolve()
        : reject(new Error(`S3 업로드 실패: ${xhr.status}`));
    xhr.onerror = () => reject(new Error('S3 업로드 네트워크 오류'));
    xhr.send(file);
  });
}

export async function completeUpload(paperId: string): Promise<PaperStatusResponse> {
  const res = await authFetch(`/api/papers/${paperId}/complete`, { method: 'POST' });
  if (!res.ok) throw await apiError(res);
  return res.json(); // { paperId, status, updatedAt }
}

export async function getStatus(paperId: string): Promise<PaperStatusResponse> {
  const res = await authFetch(`/api/papers/${paperId}/status`);
  if (!res.ok) throw await apiError(res);
  return res.json(); // { paperId, status, updatedAt }
}

// 원본 PDF 다운로드 URL 발급 (계약 0.1.1).
export async function getDownloadUrl(paperId: string): Promise<{ downloadUrl: string; expiresAt: string }> {
  const res = await authFetch(`/api/papers/${paperId}/download`);
  if (!res.ok) throw await apiError(res);
  return res.json(); // { downloadUrl, expiresAt }
}

// 서재 목록 (FT-002, BE는 YMC-223). D3 폐기 — 실제 목록을 받는다.
export async function listPapers(): Promise<{ papers: Paper[] }> {
  const res = await authFetch('/api/papers');
  if (!res.ok) throw await apiError(res);
  return res.json(); // { papers: [{ paperId, filename, status, createdAt, updatedAt }] }
}

async function apiError(res: Response): Promise<ApiError> {
  let body: { code?: string; message?: string } = {};
  try { body = await res.json(); } catch { /* 비-JSON 응답 */ }
  return new ApiError(body.message || `HTTP ${res.status}`, body.code, res.status);
}
