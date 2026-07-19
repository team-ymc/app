import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { bootstrap, authFetch, login, logout, onSessionExpired, _resetForTest } from './auth';

function jsonRes(status, body = {}) {
  return { ok: status < 400, status, json: async () => body };
}
const TOKEN_BODY = { accessToken: 'A1', expiresIn: 1800, user: { userId: 'u1', email: 'e@x.y', displayName: '홍길동' } };

describe('auth.js', () => {
  beforeEach(() => _resetForTest());
  afterEach(() => vi.restoreAllMocks());

  it('bootstrap: refresh 성공 → user 반환, 이후 요청에 Bearer 부착', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce(jsonRes(200, TOKEN_BODY))
      .mockResolvedValueOnce(jsonRes(200));
    const user = await bootstrap();
    expect(user.email).toBe('e@x.y');
    expect(global.fetch).toHaveBeenCalledWith('/api/auth/refresh', { method: 'POST' });

    await authFetch('/api/papers');
    expect(global.fetch).toHaveBeenLastCalledWith('/api/papers',
      expect.objectContaining({ headers: { Authorization: 'Bearer A1' } }));
  });

  it('bootstrap: 401(쿠키 없음) → null', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonRes(401));
    expect(await bootstrap()).toBeNull();
  });

  it('authFetch: 401 → refresh 1회 → 원 요청 재시도 성공', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce(jsonRes(401))            // 원 요청
      .mockResolvedValueOnce(jsonRes(200, TOKEN_BODY)) // refresh
      .mockResolvedValueOnce(jsonRes(200, { papers: [] })); // 재시도
    const res = await authFetch('/api/papers');
    expect(res.status).toBe(200);
    expect(global.fetch).toHaveBeenCalledTimes(3);
    expect(global.fetch.mock.calls[1][0]).toBe('/api/auth/refresh');
  });

  it('authFetch: refresh도 401 → 원 401 반환 + onSessionExpired 통지', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce(jsonRes(401))
      .mockResolvedValueOnce(jsonRes(401));
    const expired = vi.fn();
    onSessionExpired(expired);
    const res = await authFetch('/api/papers');
    expect(res.status).toBe(401);
    expect(expired).toHaveBeenCalledOnce();
  });

  it('single-flight: 동시 401 두 건 → refresh는 1번만', async () => {
    let refreshCalls = 0;
    global.fetch = vi.fn(async (url) => {
      if (url === '/api/auth/refresh') { refreshCalls += 1; return jsonRes(200, TOKEN_BODY); }
      return refreshCalls === 0 ? jsonRes(401) : jsonRes(200);
    });
    await Promise.all([authFetch('/api/a'), authFetch('/api/b')]);
    expect(refreshCalls).toBe(1);
  });

  it('login: 팝업 후 same-origin auth:complete 수신 → refresh → onComplete(user)', async () => {
    vi.stubGlobal('open', vi.fn(() => ({})));
    global.fetch = vi.fn().mockResolvedValue(jsonRes(200, TOKEN_BODY));
    const onComplete = vi.fn();

    login({ onComplete });
    expect(window.open).toHaveBeenCalledWith('/api/oauth2/authorization/google', 'ymc-auth', expect.any(String));

    window.dispatchEvent(new MessageEvent('message', {
      data: { type: 'auth:complete', error: null }, origin: window.location.origin,
    }));
    await vi.waitFor(() => expect(onComplete).toHaveBeenCalled());
    expect(onComplete).toHaveBeenCalledWith(expect.objectContaining({ email: 'e@x.y' }), null);
  });

  it('login: 다른 origin 메시지는 무시', async () => {
    vi.stubGlobal('open', vi.fn(() => ({})));
    global.fetch = vi.fn();
    const onComplete = vi.fn();
    login({ onComplete });
    window.dispatchEvent(new MessageEvent('message', {
      data: { type: 'auth:complete' }, origin: 'https://evil.example',
    }));
    await new Promise((r) => setTimeout(r, 0));
    expect(onComplete).not.toHaveBeenCalled();
  });

  it('logout: POST 후 Authorization 미부착', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonRes(200, TOKEN_BODY));
    await bootstrap();
    global.fetch = vi.fn().mockResolvedValue(jsonRes(204));
    await logout();
    expect(global.fetch).toHaveBeenCalledWith('/api/auth/logout', { method: 'POST' });

    global.fetch = vi.fn().mockResolvedValue(jsonRes(200));
    await authFetch('/api/papers');
    expect(global.fetch).toHaveBeenLastCalledWith('/api/papers',
      expect.objectContaining({ headers: {} }));
  });
});
