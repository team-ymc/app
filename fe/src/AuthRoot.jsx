// 인증 게이트 — 부트스트랩 후 비로그인 랜딩 / 로그인 랜딩 / 서재를 분기한다.
// 서재(App)는 인증 상태에서만 마운트되므로 App 내부는 인증을 모른다.
import { useEffect, useState } from 'react';
import App from './App.jsx';
import Landing from './Landing.jsx';
import { bootstrap, logout, onSessionExpired } from './auth';

export default function AuthRoot() {
  const [auth, setAuth] = useState({ status: 'loading', user: null });
  const [view, setView] = useState('landing'); // 'landing' | 'library'
  // 전체 리다이렉트 폴백의 실패 복귀(/?error=...) 표시용 — 1회 읽고 URL에서 지운다.
  const [initialError] = useState(() => {
    const params = new URLSearchParams(window.location.search);
    const error = params.get('error');
    if (error) window.history.replaceState(null, '', '/');
    return error ? '로그인에 실패했습니다. 다시 시도해 주세요.' : null;
  });

  useEffect(() => {
    onSessionExpired(() => {
      setAuth({ status: 'guest', user: null });
      setView('landing');
    });
    bootstrap().then((user) =>
      setAuth({ status: user ? 'authed' : 'guest', user }));
  }, []);

  const handleLogout = async () => {
    await logout();
    setAuth({ status: 'guest', user: null });
    setView('landing');
  };

  if (auth.status === 'loading') {
    return <div style={{ padding: 48, textAlign: 'center' }}>불러오는 중…</div>;
  }

  if (auth.status === 'guest' || view === 'landing') {
    return (
      <Landing
        user={auth.user}
        initialError={initialError}
        onAuthed={(user) => {
          setAuth({ status: 'authed', user });
          setView('library'); // 인증 완료 → 서재 (FT-001 Story 1 AC)
        }}
        onEnterLibrary={() => setView('library')}
      />
    );
  }

  return (
    <div>
      <header
        style={{
          display: 'flex', justifyContent: 'flex-end', alignItems: 'center',
          gap: 12, padding: '10px 20px', borderBottom: '1px solid #eee', fontSize: 14,
        }}
      >
        <span style={{ color: '#5f6368' }}>{auth.user.email ?? auth.user.displayName}</span>
        <button
          type="button"
          style={{ border: '1px solid #dadce0', background: '#fff', borderRadius: 6, padding: '6px 12px', cursor: 'pointer' }}
          onClick={handleLogout}
        >
          로그아웃
        </button>
      </header>
      <App />
    </div>
  );
}
