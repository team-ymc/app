# FE 업로드 checksum 전송 (YMC-282) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 업로드 전 SHA-256을 계산해 create 요청과 presigned PUT 헤더에 싣는다 (계약 0.3.0).

**Architecture:** 해시 헬퍼 1개 → API 층(create 4번째 필드, uploadHeaders 전송, BadDigest 식별) → 다이얼로그(해시 중 잠금, 에러 표시). 스펙: `docs/superpowers/specs/2026-08-13-fe-checksum-upload-design.md`.

**Tech Stack:** React 19 + TypeScript, Vitest(jsdom) + @testing-library/react (jest-dom 없음 — `hasAttribute`/`toBeTruthy`로 단언), Web Crypto API.

## Global Constraints

- 커밋 메시지: `[YMC-282] type(fe): subject` 한 줄. Co-Authored-By 등 attribution 금지.
- 코드 주석은 제약 설명 1~2줄만. 티켓·스펙 인용 괄호 금지.
- % 진행률·워커·스트리밍 해시 금지 — 단발 `crypto.subtle.digest`만.
- UI 문구는 아래 값 그대로: `파일 검사 중…` / `파일 검증에 실패했습니다. 파일이 업로드 중 변경되었을 수 있습니다`
- 작업 디렉터리: `fe/` (모든 npm 명령은 `app/fe`에서).

---

### Task 1: SHA-256 해시 헬퍼

**Files:**
- Create: `fe/src/routes/bookshelf/fileChecksum.ts`
- Test: `fe/src/routes/bookshelf/fileChecksum.test.ts`

**Interfaces:**
- Produces: `sha256Base64(file: Blob): Promise<string>` — 표준 Base64 44자(`=` padding).

- [ ] **Step 1: 실패하는 테스트 작성**

```ts
// fe/src/routes/bookshelf/fileChecksum.test.ts
import { beforeAll, describe, expect, it } from 'vitest';
import { sha256Base64 } from './fileChecksum';

// jsdom의 Blob엔 arrayBuffer()가 없어 테스트에서만 채운다. 브라우저엔 표준 API로 존재.
beforeAll(() => {
  Blob.prototype.arrayBuffer ??= function (this: Blob) {
    return new Response(this).arrayBuffer();
  };
});

describe('sha256Base64', () => {
  // 기대값은 공개 검증 벡터: printf 'abc' | openssl dgst -sha256 -binary | base64
  it('표준 벡터 "abc"를 표준 Base64 44자로 돌려준다', async () => {
    const out = await sha256Base64(new Blob(['abc']));
    expect(out).toBe('ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=');
    expect(out).toMatch(/^[A-Za-z0-9+/]{43}=$/);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm run test -- src/routes/bookshelf/fileChecksum.test.ts`
Expected: FAIL — `fileChecksum` 모듈 없음.

- [ ] **Step 3: 구현**

```ts
// fe/src/routes/bookshelf/fileChecksum.ts
// 계약 checksumSha256: 전체 파일 바이트 SHA-256의 표준 Base64(32 bytes → 44자).
export async function sha256Base64(file: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  let binary = '';
  for (const byte of new Uint8Array(digest)) binary += String.fromCharCode(byte);
  return btoa(binary);
}
```

- [ ] **Step 4: 통과 확인**

Run: `npm run test -- src/routes/bookshelf/fileChecksum.test.ts`
Expected: PASS (1 test)

- [ ] **Step 5: Commit**

```bash
git add fe/src/routes/bookshelf/fileChecksum.ts fe/src/routes/bookshelf/fileChecksum.test.ts
git commit -m "[YMC-282] feat(fe): SHA-256 파일 checksum 헬퍼 추가"
```

---

### Task 2: API 층 — checksum 전달·uploadHeaders 전송·BadDigest 식별

**Files:**
- Modify: `fe/src/api/types.ts` (CreatePaperResponse, 신규 타입·에러)
- Modify: `fe/src/api/papers.ts:8-34` (createPaper, uploadToS3)
- Test: `fe/src/api/papers.test.ts`

**Interfaces:**
- Produces:
  - `type PaperUploadHeaders = { 'Content-Type': 'application/pdf'; 'x-amz-checksum-sha256': string } & Record<string, string>`
  - `class ChecksumMismatchError extends Error` — message가 곧 UI 문구.
  - `createPaper(filename: string, contentType: string, size: number, checksumSha256: string): Promise<CreatePaperResponse>` — 응답에 `uploadHeaders: PaperUploadHeaders` 포함.
  - `uploadToS3(uploadUrl: string, file: Blob, headers: PaperUploadHeaders, onProgress?: (pct: number) => void): Promise<void>`

- [ ] **Step 1: 기존 테스트 수정 + 신규 테스트 작성 (실패 상태로)**

`fe/src/api/papers.test.ts`의 createPaper 테스트를 교체:

```ts
  it('createPaper: POST /api/papers에 filename·contentType·size·checksumSha256을 JSON으로 보낸다', async () => {
    mockFetch({ body: { paperId: 'p1', uploadUrl: 'https://s3/put' } });
    const res = await createPaper('a.pdf', 'application/pdf', 1234, 'ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=');
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/papers', expect.objectContaining({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        filename: 'a.pdf', contentType: 'application/pdf', size: 1234,
        checksumSha256: 'ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=',
      }),
    }));
    expect(res.paperId).toBe('p1');
  });
```

같은 파일의 `실패 응답` 테스트도 4번째 인자를 추가해 교체:

```ts
    await expect(createPaper('a.pdf', 'application/pdf', 1234, 'ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=')).rejects.toMatchObject({
      code: 'DUPLICATE_FILENAME', httpStatus: 409, message: '중복',
    });
```

`uploadToS3 (XHR)` describe 블록을 통째로 교체 (헤더 맵 전송 + BadDigest 식별):

```ts
describe('api.js — uploadToS3 (XHR)', () => {
  const UPLOAD_HEADERS = {
    'Content-Type': 'application/pdf',
    'x-amz-checksum-sha256': 'ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=',
  } as const;

  function stubXhr(finish: (xhr: { status: number; responseText: string }) => void) {
    const headers: Record<string, string> = {};
    let onload: () => void;
    const xhr = {
      open: vi.fn(),
      setRequestHeader: (k: string, v: string) => { headers[k] = v; },
      upload: {},
      status: 0,
      responseText: '',
      send: vi.fn(function () { finish(xhr); onload(); }),
      set onload(fn: () => void) { onload = fn; },
      set onerror(_fn: () => void) { /* noop */ },
    };
    vi.stubGlobal('XMLHttpRequest', vi.fn(() => xhr));
    return { xhr, headers };
  }

  it('PUT + uploadHeaders 맵 전체를 헤더로 전송, 2xx에 resolve (D6)', async () => {
    const { xhr, headers } = stubXhr((x) => { x.status = 204; });

    await uploadToS3('https://s3/put', new Blob(['x']), UPLOAD_HEADERS);

    expect(xhr.open).toHaveBeenCalledWith('PUT', 'https://s3/put');
    expect(headers['Content-Type']).toBe('application/pdf');
    expect(headers['x-amz-checksum-sha256']).toBe(UPLOAD_HEADERS['x-amz-checksum-sha256']);
    expect(xhr.send).toHaveBeenCalled();
  });

  it('400 + BadDigest 응답은 ChecksumMismatchError로 reject한다', async () => {
    stubXhr((x) => {
      x.status = 400;
      x.responseText = '<Error><Code>BadDigest</Code></Error>';
    });

    await expect(uploadToS3('https://s3/put', new Blob(['x']), UPLOAD_HEADERS))
      .rejects.toBeInstanceOf(ChecksumMismatchError);
  });

  it('그 외 실패는 상태코드를 실은 일반 Error로 reject한다', async () => {
    stubXhr((x) => { x.status = 403; });

    await expect(uploadToS3('https://s3/put', new Blob(['x']), UPLOAD_HEADERS))
      .rejects.toThrow('S3 업로드 실패: 403');
  });
});
```

import에 `ChecksumMismatchError` 추가:

```ts
import { ChecksumMismatchError } from './types';
```

- [ ] **Step 2: 실패 확인**

Run: `npm run test -- src/api/papers.test.ts`
Expected: FAIL — `ChecksumMismatchError` export 없음 / 인자 불일치.

- [ ] **Step 3: types.ts 구현**

`fe/src/api/types.ts`의 `CreatePaperResponse`를 교체하고 아래 타입·에러를 추가:

```ts
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

// S3가 실제 바이트와 checksum 불일치(BadDigest)로 PUT을 거절한 경우. BE 에러(ApiError)와 출처가 다르다.
export class ChecksumMismatchError extends Error {
  constructor() {
    super('파일 검증에 실패했습니다. 파일이 업로드 중 변경되었을 수 있습니다');
    this.name = 'ChecksumMismatchError';
  }
}
```

- [ ] **Step 4: papers.ts 구현**

`createPaper`와 `uploadToS3`를 교체:

```ts
import { authFetch } from './auth';
import {
  ApiError, ChecksumMismatchError,
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
        return reject(new ChecksumMismatchError());
      }
      reject(new Error(`S3 업로드 실패: ${xhr.status}`));
    };
    xhr.onerror = () => reject(new Error('S3 업로드 네트워크 오류'));
    xhr.send(file);
  });
}
```

나머지 함수(`completeUpload`, `getStatus`, `getDownloadUrl`, `listPapers`, `fetchPaperContent`, `apiError`)는 그대로 둔다.

- [ ] **Step 5: 통과 확인**

Run: `npm run test -- src/api/papers.test.ts`
Expected: PASS. 이 시점에 `UploadDialog.tsx`가 새 시그니처와 안 맞아 typecheck는 깨진다 — Task 3에서 해소되므로 여기선 테스트만 확인.

- [ ] **Step 6: Commit**

```bash
git add fe/src/api/types.ts fe/src/api/papers.ts fe/src/api/papers.test.ts
git commit -m "[YMC-282] feat(fe): create에 checksumSha256 전달, presigned PUT을 uploadHeaders로 전환"
```

---

### Task 3: UploadDialog — 해시 계산·잠금·에러 표시

**Files:**
- Modify: `fe/src/routes/bookshelf/UploadDialog.tsx`
- Test: `fe/src/routes/bookshelf/UploadDialog.test.tsx` (신규)

**Interfaces:**
- Consumes: Task 1 `sha256Base64`, Task 2 `createPaper`(4인자)·`uploadToS3`(headers)·`ChecksumMismatchError`.

- [ ] **Step 1: 실패하는 컴포넌트 테스트 작성**

```tsx
// fe/src/routes/bookshelf/UploadDialog.test.tsx
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import UploadDialog from './UploadDialog';
import { ChecksumMismatchError } from '../../api/types';
import { sha256Base64 } from './fileChecksum';
import { createPaper, uploadToS3 } from '../../api/papers';

vi.mock('./fileChecksum', () => ({ sha256Base64: vi.fn() }));
vi.mock('../../api/papers', () => ({
  createPaper: vi.fn(), uploadToS3: vi.fn(), completeUpload: vi.fn(),
}));

const HASH = 'ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=';

function renderDialog() {
  const qc = new QueryClient();
  return render(
    <QueryClientProvider client={qc}>
      <UploadDialog open onClose={() => {}} onUploaded={() => {}} />
    </QueryClientProvider>,
  );
}

function selectPdf(container: HTMLElement) {
  const input = container.querySelector('input[type="file"]')!;
  fireEvent.change(input, {
    target: { files: [new File(['x'], 'a.pdf', { type: 'application/pdf' })] },
  });
}

describe('UploadDialog — checksum', () => {
  beforeEach(() => vi.clearAllMocks());

  it('해시가 끝나기 전엔 업로드·제거 버튼이 잠기고 "파일 검사 중…"을 보여준다', async () => {
    let resolveHash!: (v: string) => void;
    vi.mocked(sha256Base64).mockReturnValue(new Promise((r) => { resolveHash = r; }));
    const { container } = renderDialog();
    selectPdf(container);

    expect(screen.getByText('파일 검사 중…')).toBeTruthy();
    expect(screen.getByRole('button', { name: '업로드' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByRole('button', { name: '파일 제거' }).hasAttribute('disabled')).toBe(true);

    resolveHash(HASH);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '업로드' }).hasAttribute('disabled')).toBe(false));
    expect(screen.getByRole('button', { name: '파일 제거' }).hasAttribute('disabled')).toBe(false);
  });

  it('업로드 시 checksum을 create에, uploadHeaders를 S3 PUT에 넘긴다', async () => {
    vi.mocked(sha256Base64).mockResolvedValue(HASH);
    vi.mocked(createPaper).mockResolvedValue({
      paperId: 'p1', fileKey: 'k', uploadUrl: 'https://s3/put',
      uploadHeaders: { 'Content-Type': 'application/pdf', 'x-amz-checksum-sha256': HASH },
      uploadExpiresAt: '', status: 'UPLOAD_PENDING', createdAt: '',
    });
    vi.mocked(uploadToS3).mockResolvedValue(undefined);
    const { container } = renderDialog();
    selectPdf(container);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '업로드' }).hasAttribute('disabled')).toBe(false));

    fireEvent.click(screen.getByRole('button', { name: '업로드' }));

    await waitFor(() => expect(createPaper).toHaveBeenCalledWith('a.pdf', 'application/pdf', 1, HASH));
    await waitFor(() => expect(uploadToS3).toHaveBeenCalledWith(
      'https://s3/put', expect.anything(),
      expect.objectContaining({ 'x-amz-checksum-sha256': HASH }), expect.any(Function),
    ));
  });

  it('S3가 BadDigest로 거절하면 checksum 에러 문구를 보여준다', async () => {
    vi.mocked(sha256Base64).mockResolvedValue(HASH);
    vi.mocked(createPaper).mockResolvedValue({
      paperId: 'p1', fileKey: 'k', uploadUrl: 'https://s3/put',
      uploadHeaders: { 'Content-Type': 'application/pdf', 'x-amz-checksum-sha256': HASH },
      uploadExpiresAt: '', status: 'UPLOAD_PENDING', createdAt: '',
    });
    vi.mocked(uploadToS3).mockRejectedValue(new ChecksumMismatchError());
    const { container } = renderDialog();
    selectPdf(container);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '업로드' }).hasAttribute('disabled')).toBe(false));

    fireEvent.click(screen.getByRole('button', { name: '업로드' }));

    await waitFor(() =>
      expect(screen.getByText('파일 검증에 실패했습니다. 파일이 업로드 중 변경되었을 수 있습니다')).toBeTruthy());
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm run test -- src/routes/bookshelf/UploadDialog.test.tsx`
Expected: FAIL — "파일 검사 중…" 미표시, createPaper 인자 불일치.

- [ ] **Step 3: UploadDialog 구현**

`fe/src/routes/bookshelf/UploadDialog.tsx` 수정 사항:

import 추가:

```tsx
import { sha256Base64 } from './fileChecksum';
```

state 추가 (기존 state 선언 아래):

```tsx
const [checksum, setChecksum] = useState<string | null>(null);
```

`isUploading` 옆에 파생값 추가:

```tsx
const isHashing = phase === 'file-selected' && checksum === null;
```

`onFileChosen` 교체 — 선택 즉시 해시 시작:

```tsx
function onFileChosen(file: File | null) {
  if (!file) return;
  const validationError = validatePdfUpload(file);
  if (validationError) {
    setSelectedFile(null);
    setPhase('idle');
    setError(new Error(validationError));
    return;
  }
  setError(null);
  setSelectedFile(file);
  setChecksum(null);
  setPhase('file-selected');
  // 해시 중엔 제거·업로드가 잠기므로 결과 도착 시점의 선택 파일은 항상 이 file이다
  sha256Base64(file).then(
    (sum) => setChecksum(sum),
    (e: unknown) => {
      setError(e);
      setSelectedFile(null);
      setPhase('idle');
    },
  );
}
```

`clearFile`에 checksum 초기화 추가:

```tsx
function clearFile() {
  setSelectedFile(null);
  setChecksum(null);
  setPhase('idle');
}
```

`startUpload` 교체 — checksum 가드 + uploadHeaders 전달:

```tsx
async function startUpload() {
  if (phase !== 'file-selected' || !selectedFile || checksum === null) return;
  setPhase('uploading');
  setUploadPct(0);
  setError(null);
  try {
    const created = await createPaper(selectedFile.name, 'application/pdf', selectedFile.size, checksum);
    await uploadToS3(created.uploadUrl, selectedFile, created.uploadHeaders, (pct) => setUploadPct(pct));
    await completeUpload(created.paperId);
    queryClient.invalidateQueries({ queryKey: ['papers'] });
    onClose();
    onUploaded();
  } catch (e) {
    setError(e); // 숨기지 않는다 — 다이얼로그 안에 그대로 노출
    setPhase('file-selected'); // 재시도 가능하도록 복귀
  }
}
```

파일 카드 보조 라벨 교체 (기존 `{isUploading ? … : formatBytes(…)}` 부분):

```tsx
{isUploading ? `업로드 중… ${uploadPct}%` : isHashing ? '파일 검사 중…' : formatBytes(selectedFile.size)}
```

파일 제거(X) 버튼에 잠금 추가 (기존 `onClick={clearFile}` 버튼 교체):

```tsx
<button
  onClick={clearFile}
  disabled={isHashing}
  aria-label="파일 제거"
  style={{
    width: '26px',
    height: '26px',
    flexShrink: 0,
    borderRadius: 'var(--radius-control)',
    border: '1px solid var(--color-border)',
    background: 'var(--color-bg-paper)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    cursor: isHashing ? 'not-allowed' : 'pointer',
    color: 'var(--color-text-muted)',
    opacity: isHashing ? 0.5 : 1,
  }}
>
  <X size={12} />
</button>
```

업로드 버튼 disabled 조건 교체:

```tsx
<Button variant="primary" onClick={startUpload} disabled={phase !== 'file-selected' || checksum === null}>
```

`describeError`는 수정하지 않는다 — `ChecksumMismatchError.message`가 곧 UI 문구라 기존 `err instanceof Error` 분기로 그대로 표시된다.

- [ ] **Step 4: 통과 확인**

Run: `npm run test -- src/routes/bookshelf/UploadDialog.test.tsx`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add fe/src/routes/bookshelf/UploadDialog.tsx fe/src/routes/bookshelf/UploadDialog.test.tsx
git commit -m "[YMC-282] feat(fe): 업로드 다이얼로그 해시 계산·잠금·checksum 에러 표시"
```

---

### Task 4: 전체 검증

**Files:** 없음 (검증만)

- [ ] **Step 1: 타입·전체 테스트**

Run (fe/에서): `npm run typecheck && npm run test`
Expected: 둘 다 통과. Task 2 시점의 typecheck 깨짐이 Task 3으로 해소됐는지 여기서 확인.

- [ ] **Step 2: 로컬 스택 E2E 1회**

```bash
# 1) 로컬 인프라 (infra/local)
cd ../../infra/local && ./up.sh
# 2) BE (app/be) — 별도 터미널
cd ../../app/be && ./gradlew bootRun
# 3) FE dev 서버 (app/fe) — 별도 터미널
cd ../fe && npm run dev
```

브라우저에서 PDF 1개 업로드 → 확인 항목:
- Network 탭: POST `/api/papers` body에 `checksumSha256`(44자), 응답에 `uploadHeaders`.
- S3 PUT 요청 헤더에 `x-amz-checksum-sha256` 포함, 2xx 응답.
- complete 200, 서재 목록에 논문 표시.

Expected: 업로드 성공. 실패 시 원인을 고치기 전에 재현 조건을 기록한다.
