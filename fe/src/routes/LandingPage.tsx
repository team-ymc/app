import { useState } from 'react';
import { useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { Button } from '../design/components/Button';
import { PaperStackMark } from '../design/components/PaperStackMark';

const accentGradient = {
  background: 'linear-gradient(120deg, #2E9E6B 0%, #2C5EAA 55%, #7C4DBE 100%)',
  WebkitBackgroundClip: 'text',
  backgroundClip: 'text',
  color: 'transparent',
} as const;

export default function LandingPage() {
  const { status, startLogin, initialError } = useAuth();
  const navigate = useNavigate();
  const [signupHover, setSignupHover] = useState(false);

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
          gap: '44px',
          padding: '32px',
          maxWidth: '760px',
          textAlign: 'center',
          position: 'relative',
        }}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: '20px', fontFamily: 'var(--font-serif)', color: 'var(--color-text-heading)' }}>
          <div
            style={{
              fontSize: 'var(--hero-display-size)',
              fontWeight: 'var(--hero-display-weight)',
              lineHeight: 'var(--hero-display-line)',
              letterSpacing: 'var(--hero-display-tracking)',
            }}
          >
            <span style={accentGradient}>어떤 문서</span>든,
            <br />
            나만의 <span style={accentGradient}>AI 튜터</span>로
          </div>
          <div style={{ fontSize: '19px', fontWeight: 400, lineHeight: 1.6, color: 'var(--color-text-muted)', letterSpacing: '0.005em' }}>
            Any document, your own AI tutor.
          </div>
        </div>

        {initialError && (
          <div style={{ fontFamily: 'var(--font-sans)', fontSize: 'var(--ui-body-size)', color: 'var(--color-danger)' }}>
            {initialError}
          </div>
        )}

        <div style={{ display: 'flex', alignItems: 'center', gap: '14px', marginTop: '8px' }}>
          {status === 'authed' ? (
            <Button
              variant="primary"
              onClick={() => navigate('/library')}
              style={{ height: '56px', padding: '0 72px', fontSize: '17px' }}
            >
              <span style={{ whiteSpace: 'nowrap' }}>내 서재로</span>
            </Button>
          ) : (
            <>
              <Button
                variant="primary"
                onClick={startLogin}
                style={{ height: '56px', padding: '0 40px', fontSize: '17px' }}
              >
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
                  height: '56px',
                  padding: '0 40px',
                  background: signupHover ? 'var(--color-primary-subtle)' : 'transparent',
                  color: 'var(--color-primary)',
                  fontFamily: 'var(--font-sans)',
                  fontSize: '17px',
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
            </>
          )}
        </div>
      </div>

      <div style={{ position: 'absolute', bottom: '28px', fontFamily: 'var(--font-sans)', fontSize: '12px', color: 'var(--color-text-muted)', letterSpacing: '0.02em' }}>
        © Paper Teacher
      </div>
    </div>
  );
}
