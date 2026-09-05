// BE 연동 단일 접점 (DESIGN.md D3). 표현 층과 분리 — 진짜 FE는 이 모듈을 그대로 승계한다.
// BE 호출은 authFetch(자동 Bearer + 401 재시도)를 쓴다. S3 presigned PUT은 예외 — 서명 URL이 인가다.

import { authFetch } from './auth';
import {
  ApiError,
  type CreatePaperResponse, type Paper, type PaperStatusResponse, type PaperContentResponse,
  type PaperUploadHeaders,
} from './types';

// size는 presigned PUT 서명에 박히는 정확한 바이트 수다 — 업로드가 이 값과 다르면 S3가 403으로 거절한다.
// checksumSha256도 서명에 들어간다 — 실제 바이트의 digest와 다르면 S3가 400 BadDigest로 거절한다.
export async function createPaper(
  filename: string, contentType: string, size: number, checksumSha256: string,
): Promise<CreatePaperResponse> {
  const res = await authFetch('/api/papers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ filename, contentType, size, checksumSha256 }),
  });
  if (!res.ok) throw await apiError(res);
  return res.json(); // { paperId, fileKey, uploadUrl, uploadHeaders, uploadExpiresAt, status, createdAt }
}

// presigned PUT. headers(create 응답의 uploadHeaders)와 바이트 수가 서명값과 정확히 일치해야 한다.
export function uploadToS3(
  uploadUrl: string, file: Blob, headers: PaperUploadHeaders, onProgress?: (pct: number) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', uploadUrl);
    for (const [name, value] of Object.entries(headers)) xhr.setRequestHeader(name, value);
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) onProgress(Math.round((e.loaded / e.total) * 100));
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) return resolve();
      // S3는 checksum 불일치를 400 + <Code>BadDigest</Code> XML로 거절한다
      if (xhr.status === 400 && xhr.responseText.includes('BadDigest')) {
        return reject(new Error('파일 검증에 실패했습니다. 파일이 업로드 중 변경되었을 수 있습니다'));
      }
      reject(new Error(`S3 업로드 실패: ${xhr.status}`));
    };
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

// 이름 변경 (계약 0.4.0). 바뀐 행을 돌려주므로 호출 측이 캐시의 해당 항목만 교체한다.
export async function renamePaper(paperId: string, filename: string): Promise<Paper> {
  const res = await authFetch(`/api/papers/${paperId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ filename }),
  });
  if (!res.ok) throw await apiError(res);
  return res.json();
}

// 논리 삭제 (계약 0.4.0). 204라 본문이 없다.
export async function deletePaper(paperId: string): Promise<void> {
  const res = await authFetch(`/api/papers/${paperId}`, { method: 'DELETE' });
  if (!res.ok) throw await apiError(res);
}

// 파싱된 논문 본문 조회 (blocks는 globalOrder 오름차순으로 온다).
export async function fetchPaperContent(paperId: string): Promise<PaperContentResponse> {
  const res = await authFetch(`/api/papers/${paperId}/content`);
  if (!res.ok) throw await apiError(res);
  return res.json();
}

export async function apiError(res: Response): Promise<ApiError> {
  let body: { code?: string; message?: string } = {};
  try { body = await res.json(); } catch { /* 비-JSON 응답 */ }
  return new ApiError(body.message || `HTTP ${res.status}`, body.code, res.status);
}
