import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { bootstrap, login, logout, onSessionExpired } from '../api/auth';
import type { AuthUser } from '../api/types';
import { identifyUser, resetUser, track } from '../analytics/analytics';
import { isDevPreview, previewUser } from '../dev/preview';

interface AuthContextValue {
  status: 'loading' | 'guest' | 'authed';
  user: AuthUser | null;
  initialError: string | null;
  startLogin: () => void;
  signOut: () => Promise<void>;
}

/** BE가 popup-done.html?error=로 실어 보낸 코드를 사용자 문구로 바꾼다. */
function loginErrorMessage(code: string): string {
  return code === 'not_allowed'
    ? '허용되지 않은 계정입니다. 관리자에게 문의해 주세요.'
    : '로그인에 실패했습니다. 다시 시도해 주세요.';
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<Pick<AuthContextValue, 'status' | 'user'>>(
    isDevPreview ? { status: 'authed', user: previewUser } : { status: 'loading', user: null },
  );
  const [initialError, setInitialError] = useState<string | null>(null);
  const queryClient = useQueryClient();

  useEffect(() => {
    if (isDevPreview) return;
    const params = new URLSearchParams(window.location.search);
    const errorCode = params.get('error');
    if (errorCode) {
      window.history.replaceState(null, '', '/');
      setInitialError(loginErrorMessage(errorCode));
    }
    // 팝업이 막혀 전체 리다이렉트로 로그인한 경우 신규 가입 신호가 주소로 돌아온다.
    const signedUp = params.get('signup') === 'true';
    if (signedUp) window.history.replaceState(null, '', '/');
    // 세션 만료 시 이전 사용자 캐시가 다음 로그인 사용자에게 노출되지 않도록 정리한다.
    onSessionExpired(() => {
      queryClient.clear();
      resetUser();
      setState({ status: 'guest', user: null });
    });
    bootstrap()
      .then((user) => {
        setState({ status: user ? 'authed' : 'guest', user });
        if (!user) return;
        identifyUser(user.userId);
        if (signedUp) track('signup_completed');
      })
      .catch(() => setState({ status: 'guest', user: null }));
  }, [queryClient]);

  const startLogin = useCallback(() => {
    if (isDevPreview) {
      setState({ status: 'authed', user: previewUser });
      return;
    }
    login({
      onComplete: (user, error, signup) => {
        if (user) {
          setState({ status: 'authed', user });
          // 식별을 먼저 해야 가입 이벤트가 이 사용자에게 붙는다.
          identifyUser(user.userId);
          if (signup) track('signup_completed');
        } else if (error) setInitialError(loginErrorMessage(error));
      },
    });
  }, []);

  const signOut = useCallback(async () => {
    if (isDevPreview) {
      queryClient.clear();
      setState({ status: 'guest', user: null });
      return;
    }
    try { await logout(); } catch { /* 로컬 세션은 정리 — 쿠키는 다음 refresh 실패로 소멸 */ }
    // 재로그인 시 이전 사용자 캐시 노출 방지.
    queryClient.clear();
    resetUser();
    setState({ status: 'guest', user: null });
  }, [queryClient]);

  return (
    <AuthContext.Provider value={{ ...state, initialError, startLogin, signOut }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth는 AuthProvider 안에서만 쓴다');
  return ctx;
}
