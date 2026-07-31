// 이식: project-docs/design/v1/Paper Bookshelf Page.dc.html (R1 spacer / R2 헤더 / R3 컨트롤 / R4 목록 /
// R5 페이지네이션 / Profile dropdown / Custom top bar overlay / Toast). Upload dialog는 Task 11 소유 — 자리만 남긴다.
// sc-if→{cond && …}, x-map→.map, style="{{ x }}"→style={x} 기계적 전사 (플랜 공통 변환표).
import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { Link, useNavigate } from 'react-router';
import {
  Books,
  MagnifyingGlass,
  List as ListIcon,
  SquaresFour,
  FileText,
  User,
  CheckCircle,
} from '@phosphor-icons/react';
import { useAuth } from '../auth/AuthContext';
import { Button } from '../design/components/Button';
import { IconButton } from '../design/components/IconButton';
import { PaperStackMark } from '../design/components/PaperStackMark';
import { usePapersQuery } from './bookshelf/usePapersQuery';
import { filterPapers, paginate } from './bookshelf/paperFilters';
import type { Paper, PaperStatus } from '../api/types';

const PAGE_SIZE = 10;
const TOAST_DURATION_MS = 2400;

type RowPhase = 'progress' | 'completed' | 'failed';

// FT-002 Story 3 매핑: 계약 6개 상태 → 서재 행 표시 3종.
function statusPhase(status: PaperStatus): RowPhase {
  if (status === 'COMPLETED') return 'completed';
  if (status === 'FAILED' || status === 'EXPIRED') return 'failed';
  return 'progress'; // UPLOAD_PENDING / UPLOADED / PROCESSING
}

function formatDate(iso: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleDateString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' });
}

export default function BookshelfPage() {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  const { data, isPending, isError, error } = usePapersQuery();

  const [keyword, setKeyword] = useState('');
  const [isGridView, setIsGridView] = useState(false);
  const [page, setPage] = useState(1);
  const [uploadOpen, setUploadOpen] = useState(false); // Task 11: UploadDialog가 이 state를 소비한다
  const [toast, setToast] = useState<string | null>(null);
  const [profileMenuOpen, setProfileMenuOpen] = useState(false);

  const profileMenuRef = useRef<HTMLDivElement>(null);
  const profileBtnRef = useRef<HTMLButtonElement>(null);
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    function handleDocMouseDown(e: MouseEvent) {
      const menu = profileMenuRef.current;
      const btn = profileBtnRef.current;
      if (menu && menu.contains(e.target as Node)) return;
      if (btn && btn.contains(e.target as Node)) return;
      setProfileMenuOpen((open) => (open ? false : open));
    }
    document.addEventListener('mousedown', handleDocMouseDown, true);
    return () => document.removeEventListener('mousedown', handleDocMouseDown, true);
  }, []);

  useEffect(() => () => {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current);
  }, []);

  function showToast(text: string) {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current);
    setToast(text);
    toastTimerRef.current = setTimeout(() => setToast(null), TOAST_DURATION_MS);
  }

  function handleQueryChange(e: React.ChangeEvent<HTMLInputElement>) {
    setKeyword(e.target.value);
    setPage(1);
  }

  // COMPLETED가 아닌 행은 학습 진입 불가 (FT-002 Story 3) — 대신 거부 토스트를 보여준다.
  function handleSelectPaper(paper: Paper) {
    if (paper.status === 'COMPLETED') {
      navigate(`/papers/${paper.paperId}`);
      return;
    }
    if (paper.status === 'FAILED' || paper.status === 'EXPIRED') {
      showToast('분석에 실패한 논문입니다');
      return;
    }
    showToast('아직 분석 중인 논문입니다');
  }

  async function handleSignOut() {
    setProfileMenuOpen(false);
    await signOut(); // RequireAuth가 status===guest 전이를 보고 '/'로 리다이렉트한다
  }

  const allPapers = data?.papers ?? [];
  const filtered = filterPapers(allPapers, keyword);
  const { items: pageItems, totalPages } = paginate(filtered, page, PAGE_SIZE);
  const hasResults = filtered.length > 0;
  const noResults = !hasResults;
  const emptyMessage = allPapers.length === 0 ? '아직 등록된 논문이 없습니다' : '검색 결과가 없습니다';

  const toggleBase: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: '38px',
    height: '36px',
    border: 'none',
    cursor: 'pointer',
    transition: 'background 150ms ease, color 150ms ease',
  };
  const listBtnStyle: CSSProperties = {
    ...toggleBase,
    background: !isGridView ? 'var(--color-bg-surface)' : 'var(--color-bg-paper)',
    color: !isGridView ? 'var(--color-text-heading)' : 'var(--color-text-muted)',
  };
  const gridBtnStyle: CSSProperties = {
    ...toggleBase,
    background: isGridView ? 'var(--color-bg-surface)' : 'var(--color-bg-paper)',
    color: isGridView ? 'var(--color-text-heading)' : 'var(--color-text-muted)',
  };

  return (
    <div
      style={{
        height: '100vh',
        width: '100%',
        display: 'flex',
        flexDirection: 'column',
        background: 'var(--color-bg-canvas)',
        fontFamily: 'var(--font-sans)',
        overflow: 'hidden',
      }}
    >
      {/* 불확정 진행률 바 애니메이션 — global.css가 아닌 페이지 전용 스타일로 정의 (spec §8-1) */}
      <style>{`
        @keyframes bookshelf-indeterminate {
          0% { transform: translateX(-60%); }
          100% { transform: translateX(220%); }
        }
      `}</style>

      {/* R1 spacer: 진짜 바는 아래 fixed overlay (profile-menu 앵커링 필요) */}
      <div style={{ height: '64px', flexShrink: 0 }} />

      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', display: 'flex', justifyContent: 'center' }}>
        <div style={{ width: '100%', maxWidth: '960px', padding: '0 32px 64px', boxSizing: 'border-box' }}>
          {/* R2 Bookshelf header */}
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '22px 0 14px' }}>
            <h1
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                flexShrink: 0,
                whiteSpace: 'nowrap',
                fontFamily: 'var(--font-serif)',
                fontSize: '20px',
                fontWeight: 600,
                color: 'var(--color-text-heading)',
                margin: 0,
              }}
            >
              <Books size={19} color="var(--color-primary)" style={{ flexShrink: 0 }} />
              내 서재
            </h1>
            <Button variant="secondary" icon="plus" onClick={() => setUploadOpen(true)} style={{ flexShrink: 0 }}>
              <span style={{ whiteSpace: 'nowrap', fontWeight: 600 }}>업로드</span>
            </Button>
          </div>

          {/* R3 Controls */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '12px',
              paddingBottom: '20px',
              borderBottom: '1px solid var(--color-border)',
              marginBottom: '24px',
            }}
          >
            <div style={{ flex: 1, minWidth: 0, position: 'relative' }}>
              <MagnifyingGlass
                size={16}
                color="var(--color-text-muted)"
                style={{ position: 'absolute', left: '14px', top: '50%', transform: 'translateY(-50%)', pointerEvents: 'none' }}
              />
              <input
                value={keyword}
                onChange={handleQueryChange}
                placeholder="검색"
                style={{
                  width: '100%',
                  boxSizing: 'border-box',
                  fontFamily: 'var(--font-sans)',
                  fontSize: '14px',
                  color: 'var(--color-text-body)',
                  background: 'var(--color-bg-paper)',
                  border: '1px solid var(--color-border)',
                  borderRadius: 'var(--radius-control)',
                  padding: '11px 14px 11px 38px',
                  height: '44px',
                  outline: 'none',
                }}
              />
            </div>
            <div
              style={{
                display: 'flex',
                alignItems: 'stretch',
                flexShrink: 0,
                border: '1px solid var(--color-border)',
                borderRadius: 'var(--radius-control)',
                overflow: 'hidden',
              }}
            >
              <button onClick={() => setIsGridView(false)} aria-label="목록 보기" title="목록 보기" style={listBtnStyle}>
                <ListIcon size={16} />
              </button>
              <div style={{ width: '1px', background: 'var(--color-border)' }} />
              <button onClick={() => setIsGridView(true)} aria-label="격자 보기" title="격자 보기" style={gridBtnStyle}>
                <SquaresFour size={16} />
              </button>
            </div>
          </div>

          {/* R4 Paper list */}
          {hasResults && (
            isGridView ? (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '20px' }}>
                {pageItems.map((paper) => (
                  <PaperGridCard key={paper.paperId} paper={paper} onSelect={handleSelectPaper} />
                ))}
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                {pageItems.map((paper) => (
                  <PaperListRow key={paper.paperId} paper={paper} onSelect={handleSelectPaper} />
                ))}
              </div>
            )
          )}
          {noResults && !isPending && (
            <div style={{ padding: '80px 0', textAlign: 'center', color: 'var(--color-text-muted)', fontFamily: 'var(--font-sans)', fontSize: '14px' }}>
              {emptyMessage}
            </div>
          )}
          {isPending && (
            <div style={{ padding: '80px 0', textAlign: 'center', color: 'var(--color-text-muted)', fontFamily: 'var(--font-sans)', fontSize: '14px' }}>
              불러오는 중…
            </div>
          )}
          {isError && (
            <div style={{ padding: '16px 0', textAlign: 'center', color: 'var(--color-danger)', fontFamily: 'var(--font-sans)', fontSize: '13px' }}>
              {error instanceof Error ? error.message : '목록을 불러오지 못했습니다'}
            </div>
          )}

          {/* R5 Pagination */}
          {hasResults && (
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '14px', paddingTop: '40px' }}>
              <IconButton
                icon="caret-left"
                label="이전 페이지"
                size={32}
                disabled={page <= 1}
                onClick={() => setPage((p) => Math.max(1, p - 1))}
              />
              <span
                style={{
                  fontFamily: 'var(--font-sans)',
                  fontSize: '13px',
                  fontWeight: 600,
                  color: 'var(--color-text-heading)',
                  letterSpacing: '0.02em',
                  whiteSpace: 'nowrap',
                }}
              >
                {page} / {totalPages}
              </span>
              <IconButton
                icon="caret-right"
                label="다음 페이지"
                size={32}
                disabled={page >= totalPages}
                onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
              />
            </div>
          )}
        </div>
      </div>

      {/* Profile dropdown */}
      {profileMenuOpen && (
        <div
          ref={profileMenuRef}
          style={{
            position: 'fixed',
            top: '58px',
            right: '20px',
            width: '180px',
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
            }}
          >
            {user?.displayName ?? user?.email ?? ''}
          </div>
          <div style={{ height: '1px', background: 'var(--color-border)', margin: '2px 6px' }} />
          <DropdownButton>프로필</DropdownButton>
          <DropdownButton>설정</DropdownButton>
          <div style={{ height: '1px', background: 'var(--color-border)', margin: '2px 6px' }} />
          <DropdownButton danger onClick={handleSignOut}>
            로그아웃
          </DropdownButton>
        </div>
      )}

      {/* Custom top bar overlay (GlobalNav 자리에 절대 위치) */}
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
        <Link to="/" style={{ display: 'flex', alignItems: 'center', gap: '10px', flexShrink: 0, textDecoration: 'none', color: 'inherit' }}>
          <PaperStackMark size={22} color="var(--color-on-dark)" style={{ flexShrink: 0 }} />
          <span style={{ fontFamily: 'var(--font-serif)', fontWeight: 600, fontSize: '18px', whiteSpace: 'nowrap' }}>Paper Teacher</span>
        </Link>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <ProfileButton ref={profileBtnRef} onClick={() => setProfileMenuOpen((o) => !o)} />
        </div>
      </div>

      {uploadOpen && <>{/* Task 11: UploadDialog */}</>}

      {/* Toast — 업로드 외에도 학습 진입 거부 등에 재사용된다 */}
      {toast && (
        <div
          style={{
            position: 'fixed',
            bottom: '28px',
            left: '50%',
            transform: 'translateX(-50%)',
            background: 'var(--color-bg-walnut)',
            color: 'var(--color-on-dark)',
            fontFamily: 'var(--font-sans)',
            fontSize: '13px',
            fontWeight: 600,
            padding: '12px 18px',
            borderRadius: '9999px',
            boxShadow: 'var(--shadow-menu)',
            zIndex: 80,
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
          }}
        >
          <CheckCircle size={15} />
          <span style={{ whiteSpace: 'nowrap' }}>{toast}</span>
        </div>
      )}
    </div>
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
        textAlign: 'left',
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

// 진행률 데이터 없음 — 계약 PaperStatusResponse에 progress 필드가 없다 (spec §8-1).
// 같은 자리·같은 크기의 바에 좌우로 흐르는 불확정 애니메이션으로 대체한다.
function IndeterminateBar() {
  return (
    <div
      style={{
        width: '120px',
        maxWidth: '100%',
        height: '5px',
        background: 'var(--color-border)',
        borderRadius: 'var(--radius-pill)',
        overflow: 'hidden',
        position: 'relative',
        flexShrink: 0,
      }}
    >
      <div
        style={{
          position: 'absolute',
          top: 0,
          left: 0,
          width: '40%',
          height: '100%',
          background: 'var(--color-accent-brass)',
          borderRadius: 'var(--radius-pill)',
          animation: 'bookshelf-indeterminate 1.1s ease-in-out infinite',
        }}
      />
    </div>
  );
}

function StatusBadge({ paper }: { paper: Paper }) {
  const phase = statusPhase(paper.status);
  if (phase === 'completed') {
    return (
      <div style={{ fontFamily: 'var(--font-sans)', fontSize: '12px', color: 'var(--color-text-muted)', flexShrink: 0, whiteSpace: 'nowrap' }}>
        등록일 · {formatDate(paper.createdAt)}
      </div>
    );
  }
  if (phase === 'failed') {
    return (
      <span style={{ fontFamily: 'var(--font-sans)', fontSize: '12px', fontWeight: 600, color: 'var(--color-danger)', flexShrink: 0, whiteSpace: 'nowrap' }}>
        실패
      </span>
    );
  }
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexShrink: 0 }}>
      <span style={{ fontFamily: 'var(--font-sans)', fontSize: '12px', fontWeight: 600, color: 'var(--color-text-muted)', whiteSpace: 'nowrap' }}>
        분석 중
      </span>
      <IndeterminateBar />
    </div>
  );
}

function PaperListRow({ paper, onSelect }: { paper: Paper; onSelect: (paper: Paper) => void }) {
  const [hover, setHover] = useState(false);
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => onSelect(paper)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') onSelect(paper);
      }}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: '16px',
        padding: '14px 16px',
        background: 'var(--color-bg-surface)',
        border: `1px solid ${hover ? 'var(--color-primary)' : 'var(--color-border)'}`,
        borderRadius: 'var(--radius-structural)',
        textDecoration: 'none',
        transition: 'border-color 150ms ease',
        cursor: 'pointer',
      }}
    >
      <div
        style={{
          width: '52px',
          height: '68px',
          flexShrink: 0,
          background: 'var(--color-bg-paper)',
          border: '1px solid var(--color-border)',
          borderRadius: '2px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <FileText size={22} color="var(--color-text-muted)" />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div
          style={{
            fontFamily: 'var(--font-serif)',
            fontSize: '16px',
            fontWeight: 600,
            color: 'var(--color-text-heading)',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
        >
          {paper.filename}
        </div>
      </div>
      <StatusBadge paper={paper} />
    </div>
  );
}

function PaperGridCard({ paper, onSelect }: { paper: Paper; onSelect: (paper: Paper) => void }) {
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => onSelect(paper)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') onSelect(paper);
      }}
      style={{
        width: '200px',
        background: 'var(--color-bg-surface)',
        border: '1px solid var(--color-border)',
        borderRadius: 'var(--radius-structural)',
        padding: '16px',
        fontFamily: 'var(--font-sans)',
        textDecoration: 'none',
        boxSizing: 'border-box',
        display: 'block',
        cursor: 'pointer',
      }}
    >
      <div
        style={{
          background: 'var(--color-bg-paper)',
          border: '1px solid var(--color-border)',
          borderRadius: 'var(--radius-paper)',
          height: '140px',
          marginBottom: '12px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <FileText size={32} color="var(--color-text-muted)" />
      </div>
      <div
        style={{
          fontSize: 'var(--ui-strong-size)',
          fontWeight: 'var(--ui-strong-weight)',
          color: 'var(--color-text-heading)',
          marginBottom: '4px',
          lineHeight: 1.3,
          whiteSpace: 'nowrap',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}
      >
        {paper.filename}
      </div>
      <StatusBadge paper={paper} />
    </div>
  );
}
