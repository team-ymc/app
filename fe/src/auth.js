// 인증 연동 단일 접점 — api.js와 같은 결. access token은 이 모듈 메모리에만 존재한다
// (URL·스토리지 노출 금지, design §3). 표현 층과 분리 — 진짜 FE가 이 모듈을 승계한다.

let accessToken = null;
let refreshPromise = null;
let sessionExpiredHandler = null;

/** 세션이 죽었을 때(재갱신 실패) 한 곳에서 비로그인 전환을 처리하게 한다. */
export function onSessionExpired(handler) {
  sessionExpiredHandler = handler;
}

/** 앱 시작 시 1회 — refresh 쿠키로 세션 복원. 성공: user, 실패(비로그인): null. */
export function bootstrap() {
  return doRefresh();
}

// 동시 401들이 refresh를 중복 호출하지 않게 진행 중 Promise를 공유한다 (single-flight).
function doRefresh() {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const res = await fetch('/api/auth/refresh', { method: 'POST' });
        if (!res.ok) {
          accessToken = null;
          return null;
        }
        const body = await res.json();
        accessToken = body.accessToken;
        return body.user;
      } finally {
        refreshPromise = null;
      }
    })();
  }
  return refreshPromise;
}

/**
 * Google 로그인 시작 — 팝업에서 진행하고 랜딩을 떠나지 않는다 (design §3).
 * 팝업 차단 시 전체 리다이렉트 폴백. 반환값은 message 리스너 해제 함수.
 */
export function login({ onComplete }) {
  const url = '/api/oauth2/authorization/google';
  const popup = window.open(url, 'ymc-auth', 'width=480,height=640');
  if (!popup) {
    window.location.href = url; // 폴백 — 복귀는 브릿지가 루트로 돌려보내고 bootstrap이 집어 올린다
    return () => {};
  }
  const listener = async (event) => {
    if (event.origin !== window.location.origin) return;
    if (!event.data || event.data.type !== 'auth:complete') return;
    window.removeEventListener('message', listener);
    if (event.data.error) {
      onComplete(null, event.data.error);
      return;
    }
    const user = await doRefresh();
    onComplete(user, user ? null : 'refresh_failed');
  };
  window.addEventListener('message', listener);
  return () => window.removeEventListener('message', listener);
}

export async function logout() {
  await fetch('/api/auth/logout', { method: 'POST' });
  accessToken = null;
}

/**
 * Authorization 자동 부착 fetch. 401이면 refresh 1회 후 재시도 —
 * 그래도 401이면 원 응답을 돌려주고 onSessionExpired로 통지한다.
 * S3 presigned 요청(uploadToS3)에는 쓰지 않는다.
 */
export async function authFetch(url, opts = {}) {
  const res = await doFetch(url, opts);
  if (res.status !== 401) return res;
  const user = await doRefresh();
  if (!user) {
    if (sessionExpiredHandler) sessionExpiredHandler();
    return res;
  }
  return doFetch(url, opts);
}

function doFetch(url, opts) {
  const headers = { ...(opts.headers || {}) };
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  return fetch(url, { ...opts, headers });
}

/** 테스트 전용 — 모듈 상태 초기화. */
export function _resetForTest() {
  accessToken = null;
  refreshPromise = null;
  sessionExpiredHandler = null;
}
