import { describe, it, expect, vi, beforeEach } from 'vitest';

const sdk = vi.hoisted(() => ({
  init: vi.fn(),
  track: vi.fn(),
  identify: vi.fn(),
  reset: vi.fn(),
}));
vi.mock('mixpanel-browser/src/loaders/loader-module-core', () => ({ default: sdk }));

import { initAnalytics, track, identifyUser, resetUser, _resetForTest } from './analytics';

const TOKENS = { 'app.test': 'tok-1' };

describe('analytics', () => {
  beforeEach(() => {
    _resetForTest();
    Object.values(sdk).forEach((fn) => fn.mockReset());
  });

  it('목록에 없는 주소에서는 초기화하지 않고 아무것도 보내지 않는다', () => {
    initAnalytics('localhost', TOKENS);
    track('viewer_opened', { paper_id: 'p1' });
    identifyUser('u1');
    resetUser();

    expect(sdk.init).not.toHaveBeenCalled();
    expect(sdk.track).not.toHaveBeenCalled();
    expect(sdk.identify).not.toHaveBeenCalled();
    expect(sdk.reset).not.toHaveBeenCalled();
  });

  it('초기화 전에는 보내지 않는다', () => {
    track('signup_completed');
    expect(sdk.track).not.toHaveBeenCalled();
  });

  it('주소에 맞는 토큰으로 초기화하고 자동 수집과 세션 녹화를 끈다', () => {
    initAnalytics('app.test', TOKENS);

    expect(sdk.init).toHaveBeenCalledWith('tok-1', expect.objectContaining({
      autocapture: false,
      record_sessions_percent: 0,
      track_pageview: 'url-with-path',
    }));
  });

  it('이벤트와 속성을 그대로 넘긴다', () => {
    initAnalytics('app.test', TOKENS);
    track('prerequisite_toggled', { paper_id: 'p1', enabled: true });
    track('signup_completed');

    expect(sdk.track).toHaveBeenNthCalledWith(1, 'prerequisite_toggled', { paper_id: 'p1', enabled: true });
    expect(sdk.track).toHaveBeenNthCalledWith(2, 'signup_completed', undefined);
  });

  it('식별과 초기화를 위임한다', () => {
    initAnalytics('app.test', TOKENS);
    identifyUser('u1');
    resetUser();

    expect(sdk.identify).toHaveBeenCalledWith('u1');
    expect(sdk.reset).toHaveBeenCalledTimes(1);
  });

  it('SDK가 던져도 호출자에게 전파하지 않는다', () => {
    initAnalytics('app.test', TOKENS);
    sdk.track.mockImplementation(() => { throw new Error('blocked'); });

    expect(() => track('viewer_opened', { paper_id: 'p1' })).not.toThrow();
  });

  it('초기화가 실패하면 이후 호출을 무시한다', () => {
    sdk.init.mockImplementation(() => { throw new Error('init failed'); });
    initAnalytics('app.test', TOKENS);
    track('signup_completed');

    expect(sdk.track).not.toHaveBeenCalled();
  });
});
