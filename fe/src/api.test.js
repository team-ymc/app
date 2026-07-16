import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  createPaper, completeUpload, getStatus, getDownloadUrl, uploadToS3, listPapers,
} from './api';

function mockFetch({ ok = true, status = 200, body = {} }) {
  global.fetch = vi.fn().mockResolvedValue({ ok, status, json: async () => body });
}

describe('api.js — fetch 계열', () => {
  afterEach(() => vi.restoreAllMocks());

  it('createPaper: POST /api/papers에 filename·contentType을 JSON으로 보낸다', async () => {
    mockFetch({ body: { paperId: 'p1', uploadUrl: 'https://s3/put' } });
    const res = await createPaper('a.pdf', 'application/pdf');
    expect(global.fetch).toHaveBeenCalledWith('/api/papers', expect.objectContaining({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ filename: 'a.pdf', contentType: 'application/pdf' }),
    }));
    expect(res.paperId).toBe('p1');
  });

  it('completeUpload: POST /complete', async () => {
    mockFetch({ body: { status: 'PROCESSING' } });
    await completeUpload('p1');
    expect(global.fetch).toHaveBeenCalledWith('/api/papers/p1/complete', { method: 'POST' });
  });

  it('getStatus: GET /status', async () => {
    mockFetch({ body: { status: 'COMPLETED' } });
    const res = await getStatus('p1');
    expect(global.fetch).toHaveBeenCalledWith('/api/papers/p1/status');
    expect(res.status).toBe('COMPLETED');
  });

  it('getDownloadUrl: GET /download → {downloadUrl, expiresAt}', async () => {
    mockFetch({ body: { downloadUrl: 'https://s3/get', expiresAt: '2026-07-15T00:00:00Z' } });
    const res = await getDownloadUrl('p1');
    expect(global.fetch).toHaveBeenCalledWith('/api/papers/p1/download');
    expect(res.downloadUrl).toBe('https://s3/get');
  });

  it('listPapers: GET /api/papers → {papers}', async () => {
    mockFetch({ body: { papers: [{ paperId: 'p1', status: 'COMPLETED' }] } });
    const res = await listPapers();
    expect(global.fetch).toHaveBeenCalledWith('/api/papers');
    expect(res.papers[0].paperId).toBe('p1');
  });

  it('실패 응답: code·httpStatus를 실은 Error를 던진다', async () => {
    mockFetch({ ok: false, status: 409, body: { code: 'DUPLICATE_FILENAME', message: '중복' } });
    await expect(createPaper('a.pdf', 'application/pdf')).rejects.toMatchObject({
      code: 'DUPLICATE_FILENAME', httpStatus: 409, message: '중복',
    });
  });
});

describe('api.js — uploadToS3 (XHR)', () => {
  it('PUT + Content-Type application/pdf 명시, 2xx에 resolve (D6)', async () => {
    const headers = {};
    let onload;
    const xhr = {
      open: vi.fn(),
      setRequestHeader: (k, v) => { headers[k] = v; },
      upload: {},
      send: vi.fn(function () { xhr.status = 204; onload(); }),
      set onload(fn) { onload = fn; },
      set onerror(_fn) { /* noop */ },
    };
    vi.stubGlobal('XMLHttpRequest', vi.fn(() => xhr));

    await uploadToS3('https://s3/put', new Blob(['x']));

    expect(xhr.open).toHaveBeenCalledWith('PUT', 'https://s3/put');
    expect(headers['Content-Type']).toBe('application/pdf');
    expect(xhr.send).toHaveBeenCalled();
  });
});
