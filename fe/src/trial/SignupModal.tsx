// 체험 모드의 가입 모달. 디자인: project-docs/design/trial. 로그인이 끝나면 서재로 간다.
import { useEffect } from 'react';
import { useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { PaperStackMark } from '../design/components/PaperStackMark';
import './SignupModal.css';

export function SignupModal({ onClose }: { onClose: () => void }) {
  const { status, startLogin, initialError } = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    if (status === 'authed') navigate('/library');
  }, [status, navigate]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className="signup-backdrop" onClick={onClose}>
      <div className="signup-modal" role="dialog" aria-modal="true" aria-labelledby="signup-title" onClick={(e) => e.stopPropagation()}>
        <PaperStackMark size={34} color="#9e8158" />
        <h2 id="signup-title">가입하고 무료로 이용해보세요.</h2>
        <p>지금 보던 논문은 서재에서 이어서 읽을 수 있어요.</p>
        <button type="button" className="signup-google" onClick={startLogin} disabled={status === 'loading'}>
          <svg viewBox="0 0 48 48" aria-hidden="true"><path fill="#EA4335" d="M24 9.5c3.5 0 6.6 1.2 9 3.5l6.7-6.7C35.6 2.6 30.2 0 24 0 14.6 0 6.5 5.4 2.6 13.2l7.8 6.1C12.3 13.6 17.7 9.5 24 9.5z" /><path fill="#4285F4" d="M46.5 24.5c0-1.6-.1-3.1-.4-4.5H24v9h12.7c-.6 3-2.3 5.5-4.8 7.2l7.5 5.8c4.4-4 7.1-10 7.1-17.5z" /><path fill="#FBBC05" d="M10.4 28.7A14.5 14.5 0 0 1 9.5 24c0-1.6.3-3.2.8-4.7l-7.8-6.1A24 24 0 0 0 0 24c0 3.9.9 7.5 2.6 10.8l7.8-6.1z" /><path fill="#34A853" d="M24 48c6.5 0 11.9-2.1 15.9-5.8l-7.5-5.8c-2.1 1.4-4.9 2.3-8.4 2.3-6.3 0-11.7-4.1-13.6-9.9l-7.8 6.1C6.5 42.6 14.6 48 24 48z" /></svg>
          Google로 계속하기
        </button>
        {initialError && <p className="signup-error" role="alert">{initialError}</p>}
        <button type="button" className="signup-dismiss" onClick={onClose}>계속 둘러보기</button>
      </div>
    </div>
  );
}
