// 체험 모드(YMC-430) — 가입 없이 체험 논문을 읽는 화면들이 공유하는 상태.
// trial이면 API는 /api/trial을 쓰고, AI 질의·업로드·로그인은 가입 모달로 연결한다.
import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { track } from '../analytics/analytics';
import { SignupModal } from './SignupModal';

export type SignupSource = 'upload' | 'login' | 'chat';

interface TrialModeValue {
  trial: boolean;
  /** 가입 모달을 연다. 이미 로그인한 사용자는 서재로 보낸다. */
  requestSignup: (source: SignupSource) => void;
}

const TrialModeContext = createContext<TrialModeValue>({ trial: false, requestSignup: () => {} });

export function TrialModeProvider({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);

  const requestSignup = useCallback((source: SignupSource) => {
    if (status === 'authed') {
      navigate('/library');
      return;
    }
    track('trial_signup_prompted', { source });
    setOpen(true);
  }, [status, navigate]);

  return (
    <TrialModeContext.Provider value={{ trial: true, requestSignup }}>
      {children}
      {open && <SignupModal onClose={() => setOpen(false)} />}
    </TrialModeContext.Provider>
  );
}

export function useTrialMode(): TrialModeValue {
  return useContext(TrialModeContext);
}
