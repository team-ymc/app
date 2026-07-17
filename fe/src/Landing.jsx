// 랜딩(WF-001/002) + 인증 모달(WF-003). 와이어프레임 수준 구현 — 시안 확정 시 표현만 교체한다.
import { useRef, useState } from 'react';
import { login } from './auth';

const center = {
  minHeight: '100vh', display: 'flex', flexDirection: 'column',
  alignItems: 'center', justifyContent: 'center', gap: 16, textAlign: 'center',
};
const primaryBtn = {
  padding: '12px 28px', fontSize: 16, borderRadius: 8, border: 'none',
  background: '#1a73e8', color: '#fff', cursor: 'pointer',
};
const providerBtn = (disabled) => ({
  display: 'block', width: 280, margin: '8px auto', padding: '12px 16px',
  fontSize: 15, borderRadius: 8, border: '1px solid #dadce0',
  background: disabled ? '#f1f3f4' : '#fff',
  color: disabled ? '#9aa0a6' : '#3c4043',
  cursor: disabled ? 'not-allowed' : 'pointer',
});

export default function Landing({ user, initialError, onAuthed, onEnterLibrary }) {
  const [modalOpen, setModalOpen] = useState(false);
  const [error, setError] = useState(initialError);
  const [pending, setPending] = useState(false);

  // login()이 돌려주는 message 리스너 해제 함수 — 모달을 닫으면 진행 중 로그인을 정리한다.
  const cleanupRef = useRef(null);

  const closeModal = () => {
    if (cleanupRef.current) {
      cleanupRef.current();
      cleanupRef.current = null;
    }
    setPending(false);
    setModalOpen(false);
  };

  const handleGoogle = () => {
    setPending(true);
    setError(null);
    cleanupRef.current = login({
      onComplete: (authedUser, loginError) => {
        cleanupRef.current = null;
        setPending(false);
        if (loginError || !authedUser) {
          setError('로그인에 실패했습니다. 다시 시도해 주세요.');
          return;
        }
        setModalOpen(false);
        onAuthed(authedUser); // AuthRoot가 서재로 라우팅 (FT-001 Story 1 AC)
      },
    });
  };

  // WF-002: 로그인 랜딩 — 내 서재 가기
  if (user) {
    return (
      <div style={center}>
        <div style={{ fontSize: 48 }}>📄</div>
        <h1 style={{ fontSize: 24 }}>논문을 이해하도록 가르치는<br />AI 리딩 튜터</h1>
        <button type="button" style={primaryBtn} onClick={onEnterLibrary}>내 서재 가기 →</button>
      </div>
    );
  }

  // WF-001: 비로그인 랜딩 (+ WF-003 모달 오버레이)
  return (
    <div style={center}>
      <div style={{ fontSize: 48 }}>📄</div>
      <h1 style={{ fontSize: 24 }}>논문을 이해하도록 가르치는<br />AI 리딩 튜터</h1>
      <button type="button" style={primaryBtn} onClick={() => setModalOpen(true)}>
        로그인 / 회원가입
      </button>
      {error && !modalOpen && <p style={{ color: '#d93025' }}>{error}</p>}

      {modalOpen && (
        <div
          style={{
            position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}
          onClick={closeModal}
        >
          <div
            style={{ background: '#fff', borderRadius: 12, padding: 32, width: 360, position: 'relative' }}
            onClick={(e) => e.stopPropagation()}
          >
            <button
              type="button" aria-label="닫기"
              style={{ position: 'absolute', top: 12, right: 12, border: 'none', background: 'none', fontSize: 18, cursor: 'pointer' }}
              onClick={closeModal}
            >
              ✕
            </button>
            <h2 style={{ fontSize: 20, marginBottom: 8 }}>로그인 또는 회원가입</h2>
            <p style={{ color: '#5f6368', fontSize: 14, marginBottom: 16 }}>
              논문을 읽고 AI 튜터와 대화하려면 계속 진행해 주세요
            </p>
            <button type="button" style={providerBtn(false)} onClick={handleGoogle} disabled={pending}>
              {pending ? '로그인 진행 중…' : 'Google로 계속하기'}
            </button>
            <button type="button" style={providerBtn(true)} disabled>Kakao로 계속하기 (준비 중)</button>
            <button type="button" style={providerBtn(true)} disabled>Naver로 계속하기 (준비 중)</button>
            {error && <p style={{ color: '#d93025', fontSize: 13 }}>{error}</p>}
            <p style={{ color: '#9aa0a6', fontSize: 12, marginTop: 16 }}>
              계속하면 이용약관 및 개인정보 처리방침에 동의하는 것으로 간주됩니다
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
