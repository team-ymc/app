// 글로벌 상단 바 — 로고 · 메뉴(플랜/기능) · 오른쪽 계정 영역. 랜딩과 서재가 같은 것을 쓴다.
// 학습 페이지는 논문 제목·야간 모드가 있어 자체 상단 바를 유지한다.
import { useState } from 'react';
import { Link, NavLink } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { AccountMenu } from '../account/AccountMenu';
import { PaperStackMark } from '../design/components/PaperStackMark';

export const GLOBAL_NAV_HEIGHT = 64;

const MENU = [
  { to: '/plans', label: '플랜' },
  { to: '/features', label: '기능' },
];

export function GlobalNav() {
  const { status, startLogin } = useAuth();

  return (
    <div
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        height: `${GLOBAL_NAV_HEIGHT}px`,
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
      <div style={{ display: 'flex', alignItems: 'center', gap: '22px', height: '100%' }}>
        <Link to="/" style={{ display: 'flex', alignItems: 'center', gap: '10px', flexShrink: 0, textDecoration: 'none', color: 'inherit' }}>
          <PaperStackMark size={22} color="var(--color-on-dark)" style={{ flexShrink: 0 }} />
          <span style={{ fontFamily: 'var(--font-serif)', fontWeight: 600, fontSize: '18px', whiteSpace: 'nowrap' }}>Paper Teacher</span>
        </Link>
        <div style={{ width: '1px', height: '18px', background: 'rgba(255,253,247,0.18)', flexShrink: 0 }} />
        <nav style={{ display: 'flex', alignItems: 'center', gap: '2px', height: '100%' }}>
          {MENU.map((m) => (
            <NavItem key={m.to} to={m.to} label={m.label} />
          ))}
        </nav>
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: '14px' }}>
        {status === 'authed' && <AccountMenu />}
        {status === 'guest' && <LoginButton onClick={startLogin} />}
      </div>
    </div>
  );
}

function NavItem({ to, label }: { to: string; label: string }) {
  const [hover, setHover] = useState(false);
  return (
    <NavLink
      to={to}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={({ isActive }) => ({
        display: 'flex',
        alignItems: 'center',
        height: `${GLOBAL_NAV_HEIGHT}px`,
        padding: '2px 12px 0',
        boxSizing: 'border-box',
        fontFamily: 'var(--font-sans)',
        fontSize: '14px',
        fontWeight: 500,
        letterSpacing: '-0.005em',
        whiteSpace: 'nowrap',
        textDecoration: 'none',
        color: isActive || hover ? 'var(--color-on-dark)' : 'rgba(255,253,247,0.7)',
        borderBottom: `2px solid ${isActive ? 'var(--color-accent-brass-on-dark)' : 'transparent'}`,
        transition: 'color 150ms ease',
      })}
    >
      {label}
    </NavLink>
  );
}

function LoginButton({ onClick }: { onClick: () => void }) {
  const [hover, setHover] = useState(false);
  return (
    <button
      type="button"
      onClick={onClick}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        height: '36px',
        padding: '0 18px',
        fontFamily: 'var(--font-sans)',
        fontSize: '14px',
        fontWeight: 600,
        color: 'var(--color-on-dark)',
        background: hover ? 'var(--color-bg-walnut-raised)' : 'transparent',
        border: `1px solid rgba(255,253,247,${hover ? 0.6 : 0.35})`,
        borderRadius: 'var(--radius-pill)',
        cursor: 'pointer',
        whiteSpace: 'nowrap',
        transition: 'background 150ms ease, border-color 150ms ease',
      }}
    >
      로그인
    </button>
  );
}
