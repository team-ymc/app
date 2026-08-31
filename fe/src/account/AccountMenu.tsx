// 상단 바 우측 계정 영역 — 플랜 배지, 프로필 메뉴, 사용량 패널.
// 서재와 학습이 같은 것을 쓴다. 메뉴는 fixed로 화면 우상단에 붙으므로 상단 바 높이(64px)에 맞춰져 있다.
import { useEffect, useRef, useState } from 'react';
import { CaretLeft, User } from '@phosphor-icons/react';
import { useAuth } from '../auth/AuthContext';
import { Badge } from '../design/components/Badge';
import { UsageMeter } from '../design/components/UsageMeter';
import { usePlanQuery } from '../plan/usePlanQuery';
import { meterCaption, periodLabel } from '../plan/planLabels';

export function AccountMenu() {
  const { user, signOut } = useAuth();
  const planQuery = usePlanQuery();
  const plan = planQuery.data;

  const [menuOpen, setMenuOpen] = useState(false);
  const [usageOpen, setUsageOpen] = useState(false);

  const menuRef = useRef<HTMLDivElement>(null);
  const btnRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    function handleDocMouseDown(e: MouseEvent) {
      const menu = menuRef.current;
      const btn = btnRef.current;
      if (menu && menu.contains(e.target as Node)) return;
      if (btn && btn.contains(e.target as Node)) return;
      setMenuOpen((open) => (open ? false : open));
    }
    document.addEventListener('mousedown', handleDocMouseDown, true);
    return () => document.removeEventListener('mousedown', handleDocMouseDown, true);
  }, []);

  async function handleSignOut() {
    setMenuOpen(false);
    await signOut(); // RequireAuth가 status===guest 전이를 보고 '/'로 리다이렉트한다
  }

  return (
    <>
      {plan && (
        <Badge
          tone={plan.plan === 'PRO' ? 'proOnDark' : 'neutralOnDark'}
          icon={plan.plan === 'PRO' ? 'seal-check' : undefined}
        >
          {plan.plan === 'PRO' ? 'Pro' : 'Free'}
        </Badge>
      )}

      <ProfileButton
        ref={btnRef}
        onClick={() => {
          setMenuOpen((o) => !o);
          setUsageOpen(false);
        }}
      />

      {menuOpen && (
        <div
          ref={menuRef}
          style={{
            position: 'fixed',
            top: '58px',
            right: '20px',
            width: '200px',
            background: 'var(--color-bg-paper)',
            border: '1px solid var(--color-border)',
            borderRadius: '8px',
            boxShadow: 'var(--shadow-menu)',
            padding: '6px',
            zIndex: 50,
            display: 'flex',
            flexDirection: 'column',
            gap: '2px',
          }}
        >
          <div
            style={{
              padding: '9px 10px',
              fontFamily: 'var(--font-sans)',
              fontSize: '12px',
              color: 'var(--color-text-muted)',
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              textAlign: 'right',
            }}
          >
            {user?.displayName ?? user?.email ?? ''}
          </div>
          <div style={{ height: '1px', background: 'var(--color-border)', margin: '2px 6px' }} />
          <DropdownButton>프로필</DropdownButton>
          <button
            onClick={() => setUsageOpen((v) => !v)}
            style={{
              width: '100%',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: '10px',
              padding: '9px 10px',
              border: 'none',
              borderRadius: '6px',
              background: usageOpen ? 'var(--color-primary-subtle)' : 'transparent',
              fontFamily: 'var(--font-sans)',
              fontSize: '13px',
              color: 'var(--color-text-body)',
              cursor: 'pointer',
              whiteSpace: 'nowrap',
            }}
          >
            <CaretLeft size={13} color="var(--color-text-muted)" />
            <span>사용량</span>
          </button>
          <DropdownButton>설정</DropdownButton>
          <div style={{ height: '1px', background: 'var(--color-border)', margin: '2px 6px' }} />
          <DropdownButton danger onClick={handleSignOut}>
            로그아웃
          </DropdownButton>
          {usageOpen && plan && (
            <div
              style={{
                position: 'absolute',
                top: 0,
                right: 'calc(100% + 8px)',
                width: '260px',
                background: 'var(--color-bg-paper)',
                border: '1px solid var(--color-border)',
                borderRadius: '8px',
                boxShadow: 'var(--shadow-menu)',
                padding: '16px',
                boxSizing: 'border-box',
                display: 'flex',
                flexDirection: 'column',
                gap: '14px',
                zIndex: 55,
              }}
            >
              <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: '10px' }}>
                <span style={{ fontFamily: 'var(--font-serif)', fontSize: '16px', fontWeight: 600, color: 'var(--color-text-heading)', whiteSpace: 'nowrap' }}>
                  이번 달 사용량
                </span>
                <span style={{ fontFamily: 'var(--font-sans)', fontSize: '12px', color: 'var(--color-text-muted)', whiteSpace: 'nowrap' }}>
                  {periodLabel(plan, new Date())}
                </span>
              </div>
              <UsageMeter
                label="AI 질의"
                used={plan.usage.aiQuery.used ?? 0}
                limit={plan.usage.aiQuery.limit}
                unlimited={plan.usage.aiQuery.mode === 'UNLIMITED'}
                resetLabel={meterCaption('질문', plan.usage.aiQuery)}
              />
              <UsageMeter
                label="문서 등록"
                used={plan.usage.paperRegistration.used ?? 0}
                limit={plan.usage.paperRegistration.limit}
                unlimited={plan.usage.paperRegistration.mode === 'UNLIMITED'}
                resetLabel={meterCaption('등록', plan.usage.paperRegistration)}
              />
            </div>
          )}
        </div>
      )}
    </>
  );
}

function DropdownButton({
  children,
  danger,
  onClick,
}: {
  children: React.ReactNode;
  danger?: boolean;
  onClick?: () => void;
}) {
  const [hover, setHover] = useState(false);
  return (
    <button
      onClick={onClick}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        textAlign: 'right',
        padding: '9px 10px',
        border: 'none',
        background: hover ? 'var(--color-primary-subtle)' : 'transparent',
        borderRadius: '6px',
        fontFamily: 'var(--font-sans)',
        fontSize: '13px',
        color: danger ? 'var(--color-danger)' : 'var(--color-text-body)',
        cursor: 'pointer',
        whiteSpace: 'nowrap',
      }}
    >
      {children}
    </button>
  );
}

function ProfileButton({ onClick, ref }: { onClick: () => void; ref: React.Ref<HTMLButtonElement> }) {
  const [hover, setHover] = useState(false);
  return (
    <button
      ref={ref}
      onClick={onClick}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      aria-label="프로필"
      title="프로필"
      style={{
        width: '32px',
        height: '32px',
        borderRadius: '9999px',
        border: '1px solid rgba(255,253,247,0.25)',
        background: hover ? 'var(--color-bg-walnut)' : 'var(--color-bg-walnut-raised)',
        color: 'var(--color-on-dark)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        cursor: 'pointer',
      }}
    >
      <User size={15} />
    </button>
  );
}
