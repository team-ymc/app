import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  createPaper, completeUpload, getStatus, getDownloadUrl, uploadToS3, listPapers, fetchPaperContent,
} from './papers';

function mockFetch({ ok = true, status = 200, body = {} }: { ok?: boolean; status?: number; body?: unknown }) {
  globalThis.fetch = vi.fn().mockResolvedValue({ ok, status, json: async () => body }) as unknown as typeof fetch;
}

describe('api.js — fetch 계열', () => {
  afterEach(() => vi.restoreAllMocks());

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

  it('completeUpload: POST /complete', async () => {
    mockFetch({ body: { status: 'PROCESSING' } });
    await completeUpload('p1');
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/papers/p1/complete',
      expect.objectContaining({ method: 'POST' }));
  });

  it('getStatus: GET /status', async () => {
    mockFetch({ body: { status: 'COMPLETED' } });
    const res = await getStatus('p1');
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/papers/p1/status', expect.objectContaining({}));
    expect(res.status).toBe('COMPLETED');
  });

  it('getDownloadUrl: GET /download → {downloadUrl, expiresAt}', async () => {
    mockFetch({ body: { downloadUrl: 'https://s3/get', expiresAt: '2026-07-15T00:00:00Z' } });
    const res = await getDownloadUrl('p1');
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/papers/p1/download', expect.objectContaining({}));
    expect(res.downloadUrl).toBe('https://s3/get');
  });

  it('listPapers: GET /api/papers → {papers}', async () => {
    mockFetch({ body: { papers: [{ paperId: 'p1', status: 'COMPLETED' }] } });
    const res = await listPapers();
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/papers', expect.objectContaining({}));
    expect(res.papers[0].paperId).toBe('p1');
  });

  it('fetchPaperContent: GET /content, 본문 응답을 그대로 돌려준다', async () => {
    mockFetch({ body: { paperId: 'p1', title: 'T', schemaVersion: 1, blocks: [], assets: {} } });
    const res = await fetchPaperContent('p1');
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/papers/p1/content', expect.anything());
    expect(res.title).toBe('T');
  });

  it('fetchPaperContent: 409는 ApiError(code)로 던진다', async () => {
    mockFetch({ ok: false, status: 409, body: { code: 'PAPER_NOT_READY', message: '' } });
    await expect(fetchPaperContent('p1')).rejects.toMatchObject({ code: 'PAPER_NOT_READY' });
  });

  it('실패 응답: code·httpStatus를 실은 Error를 던진다', async () => {
    mockFetch({ ok: false, status: 409, body: { code: 'DUPLICATE_FILENAME', message: '중복' } });
    await expect(createPaper('a.pdf', 'application/pdf', 1234, 'ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=')).rejects.toMatchObject({
      code: 'DUPLICATE_FILENAME', httpStatus: 409, message: '중복',
    });
  });
});

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

  it('400 + BadDigest 응답은 checksum 검증 실패 문구의 Error로 reject한다', async () => {
    stubXhr((x) => {
      x.status = 400;
      x.responseText = '<Error><Code>BadDigest</Code></Error>';
    });

    await expect(uploadToS3('https://s3/put', new Blob(['x']), UPLOAD_HEADERS))
      .rejects.toThrow('파일 검증에 실패했습니다. 파일이 업로드 중 변경되었을 수 있습니다');
  });

  it('그 외 실패는 상태코드를 실은 일반 Error로 reject한다', async () => {
    stubXhr((x) => { x.status = 403; });

    await expect(uploadToS3('https://s3/put', new Blob(['x']), UPLOAD_HEADERS))
      .rejects.toThrow('S3 업로드 실패: 403');
  });
});
