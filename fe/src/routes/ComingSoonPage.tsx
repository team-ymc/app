// 아직 열리지 않은 메뉴(플랜·기능)의 자리 페이지. eyebrow 로 어느 섹션인지만 구분한다.
import { Link } from 'react-router';
import { CaretLeft } from '@phosphor-icons/react';
import { useAuth } from '../auth/AuthContext';
import { GlobalNav, GLOBAL_NAV_HEIGHT } from '../nav/GlobalNav';

export default function ComingSoonPage({ eyebrow }: { eyebrow: string }) {
  const { status } = useAuth();
  const back = status === 'authed' ? { to: '/library', label: '내 서재로 돌아가기' } : { to: '/', label: '처음으로 돌아가기' };

  return (
    <div
      style={{
        height: '100vh',
        width: '100%',
        background: 'var(--color-bg-canvas)',
        fontFamily: 'var(--font-sans)',
        boxSizing: 'border-box',
        paddingTop: `${GLOBAL_NAV_HEIGHT}px`,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        overflow: 'hidden',
      }}
    >
      <GlobalNav />

      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '18px', padding: '32px', maxWidth: '560px', textAlign: 'center' }}>
        <div style={{ fontSize: 'var(--label-size)', fontWeight: 'var(--label-weight)', letterSpacing: '0.12em', textTransform: 'uppercase', color: 'var(--color-accent-brass)' }}>
          {eyebrow}
        </div>
        <h1 style={{ margin: 0, fontFamily: 'var(--font-serif)', fontSize: '44px', fontWeight: 600, lineHeight: 1.2, letterSpacing: '-0.02em', color: 'var(--color-text-heading)' }}>
          준비 중입니다
        </h1>
        <p style={{ margin: 0, fontSize: '17px', lineHeight: 1.6, color: 'var(--color-text-muted)' }}>곧 열립니다.</p>
        <div style={{ width: '40px', height: '1px', background: 'var(--color-border)', margin: '10px 0 6px' }} />
        <Link
          to={back.to}
          style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', fontSize: '15px', fontWeight: 600, color: 'var(--color-primary)', textDecoration: 'none' }}
        >
          <CaretLeft size={14} />
          {back.label}
        </Link>
      </div>
    </div>
  );
}
