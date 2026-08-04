import { useState } from 'react';
import { Navigate } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { Button } from '../design/components/Button';
import { PaperStackMark } from '../design/components/PaperStackMark';

export default function LandingPage() {
  const { status, startLogin, initialError } = useAuth();
  const [signupHover, setSignupHover] = useState(false);

  if (status === 'authed') return <Navigate to="/library" replace />;

  return (
    <div
      style={{
        height: '100vh',
        width: '100%',
        background: 'var(--color-bg-canvas)',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        fontFamily: 'var(--font-sans)',
        boxSizing: 'border-box',
        position: 'relative',
        overflow: 'hidden',
      }}
    >
      {/* top bar, consistent with bookshelf/study pages */}
      <div
        style={{
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          height: '64px',
          background: 'var(--color-bg-walnut)',
          color: 'var(--color-on-dark)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '0 24px',
          fontFamily: 'var(--font-sans)',
          boxSizing: 'border-box',
          zIndex: 5,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', flexShrink: 0 }}>
          <PaperStackMark size={22} color="var(--color-on-dark)" />
          <span style={{ fontFamily: 'var(--font-serif)', fontWeight: 600, fontSize: '18px', whiteSpace: 'nowrap' }}>
            Paper Teacher
          </span>
        </div>
      </div>

      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: '36px',
          padding: '32px',
          maxWidth: '480px',
          textAlign: 'center',
          position: 'relative',
        }}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: '16px', fontFamily: 'var(--font-serif)', color: 'var(--color-text-heading)' }}>
          <div
            style={{
              fontSize: 'var(--display-lg-size)',
              fontWeight: 'var(--display-lg-weight)',
              lineHeight: 'var(--display-lg-line)',
              letterSpacing: 'var(--display-lg-tracking)',
            }}
          >
            <span style={{ color: 'var(--color-primary)' }}>업로드</span>만 하세요.
            <br />
            <span style={{ color: 'var(--color-primary)' }}>이해</span>시켜드립니다.
          </div>
          <div style={{ fontSize: '16px', fontWeight: 400, lineHeight: 1.6, color: 'var(--color-text-muted)', letterSpacing: '0.005em' }}>
            Just upload. We will make you understand.
          </div>
        </div>

        {initialError && (
          <div style={{ fontFamily: 'var(--font-sans)', fontSize: 'var(--ui-body-size)', color: 'var(--color-danger)' }}>
            {initialError}
          </div>
        )}

        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginTop: '8px' }}>
          <Button variant="primary" onClick={startLogin}>
            <span style={{ whiteSpace: 'nowrap' }}>로그인</span>
          </Button>
          <button
            type="button"
            onClick={startLogin}
            onMouseEnter={() => setSignupHover(true)}
            onMouseLeave={() => setSignupHover(false)}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              height: '48px',
              padding: '0 24px',
              background: signupHover ? 'var(--color-primary-subtle)' : 'transparent',
              color: 'var(--color-primary)',
              fontFamily: 'var(--font-sans)',
              fontSize: 'var(--ui-strong-size)',
              fontWeight: 'var(--ui-strong-weight)',
              borderRadius: 'var(--radius-pill)',
              border: signupHover ? '1px solid var(--color-primary)' : '1px solid var(--color-border)',
              cursor: 'pointer',
              transition: 'border-color 150ms ease, background 150ms ease',
              whiteSpace: 'nowrap',
            }}
          >
            회원가입
          </button>
        </div>
      </div>

      <div style={{ position: 'absolute', bottom: '28px', fontFamily: 'var(--font-sans)', fontSize: '12px', color: 'var(--color-text-muted)', letterSpacing: '0.02em' }}>
        © Paper Teacher
      </div>
    </div>
  );
}
