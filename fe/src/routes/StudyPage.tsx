// 이식: project-docs/design/v1/Paper Study Page.dc.html — R1 top bar / R2 work area / R3 TOC nav rail /
// R4 Paper viewer / Resizable splitter. R5 AI chat panel과 Selection/Ask/Translation popup은 이번 태스크
// 범위 밖(Task 13·14 소유) — 챗 패널 자리는 스플리터 오른쪽 빈 컨테이너 + 주석으로만 남긴다.
// sc-if→{cond && …}, x-map→.map, style="{{ x }}"→style={x} 기계적 전사 (플랜 공통 변환표, Task 9·10과 동일).
//
// Night mode 토글: 이 목업 파일 자체에는 스위치 UI가 없다 (디자인 시스템 readme에서만 "Night Study Mode
// toggle"로 언급). brief Step 3가 명시적으로 요구하는 기능이라 R1 우측 존에 아이콘 버튼을 새로 추가했다
// (report에 기록) — 목업에 없는 요소이므로 기존 변환표 밖 판단.
import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { Link, Navigate, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, User } from '@phosphor-icons/react';
import { PaperStackMark } from '../design/components/PaperStackMark';
import { IconButton } from '../design/components/IconButton';
import { getStatus } from '../api/papers';
import { getPaperContent } from '../markdown/paperContent';
import { PaperViewer } from './study/PaperViewer';
import { TocRail } from './study/TocRail';
import { useScrollSpy } from './study/useScrollSpy';

const NIGHT_STORAGE_KEY = 'pt-night';
const SPLIT_MIN = 30;
const SPLIT_MAX = 75;
const SPLIT_DEFAULT = 70;

export default function StudyPage() {
  const { paperId } = useParams<{ paperId: string }>();

  const statusQuery = useQuery({
    queryKey: ['paper-status', paperId],
    queryFn: () => getStatus(paperId as string),
    enabled: !!paperId,
  });

  if (!paperId) {
    return <Navigate to="/library" replace state={{ toast: '잘못된 접근입니다' }} />;
  }
  if (statusQuery.isPending) {
    return (
      <div style={{ padding: 48, textAlign: 'center', fontFamily: 'var(--font-sans)', color: 'var(--color-text-muted)' }}>
        불러오는 중…
      </div>
    );
  }
  if (statusQuery.isError) {
    return <Navigate to="/library" replace state={{ toast: '논문 상태를 불러오지 못했습니다' }} />;
  }

  // COMPLETED가 아닌 논문은 학습 진입 불가 (FT-002 Story 3와 동일 규칙) — /library로 돌려보내고 토스트로 안내한다.
  if (statusQuery.data.status !== 'COMPLETED') {
    const toast =
      statusQuery.data.status === 'FAILED' || statusQuery.data.status === 'EXPIRED'
        ? '분석에 실패한 논문입니다'
        : '아직 분석 중인 논문입니다';
    return <Navigate to="/library" replace state={{ toast }} />;
  }

  return <StudyPageContent paperId={paperId} />;
}

function StudyPageContent({ paperId }: { paperId: string }) {
  const contentQuery = useQuery({
    queryKey: ['paper-content', paperId],
    queryFn: () => getPaperContent(paperId),
  });

  const [tocOpen, setTocOpen] = useState(false);
  const [nightMode, setNightMode] = useState<boolean>(() => {
    try {
      return localStorage.getItem(NIGHT_STORAGE_KEY) === '1';
    } catch {
      return false;
    }
  });
  const [splitPct, setSplitPct] = useState(SPLIT_DEFAULT);
  const [splitterHover, setSplitterHover] = useState(false);

  const viewerRef = useRef<HTMLDivElement>(null);
  const splitRegionRef = useRef<HTMLDivElement>(null);

  const blocks = contentQuery.data?.blocks ?? [];
  const toc = contentQuery.data?.toc ?? [];
  // toc.map()이 렌더마다 새 배열을 만들면 useScrollSpy의 effect deps가 매번 바뀌어
  // IntersectionObserver가 불필요하게 재구축된다(스플리터 드래그 중 pointermove마다 리렌더되면 특히 심함) — 메모이즈.
  const tocOrder = useMemo(() => toc.map((t) => t.blockId), [toc]);
  const activeId = useScrollSpy(viewerRef, tocOrder);

  useEffect(() => {
    try {
      localStorage.setItem(NIGHT_STORAGE_KEY, nightMode ? '1' : '0');
    } catch {
      /* storage 접근 불가 — night 상태는 세션 내에서만 유지된다 */
    }
  }, [nightMode]);

  function handleJump(blockId: string) {
    document.getElementById(blockId)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function handleSplitterPointerDown(e: ReactPointerEvent<HTMLDivElement>) {
    e.preventDefault();
    const region = splitRegionRef.current;
    if (!region) return;

    function onMove(ev: PointerEvent) {
      const rect = region!.getBoundingClientRect();
      const pct = ((ev.clientX - rect.left) / rect.width) * 100;
      setSplitPct(Math.min(SPLIT_MAX, Math.max(SPLIT_MIN, pct)));
    }
    function onUp() {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
    }
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  }

  // 문서 제목 필드가 계약에 없다 — 픽스처의 첫 heading 블록 텍스트를 R1 상단바 제목으로 쓴다 (report 기록).
  const titleText = blocks.find((b) => b.type === 'heading')?.headingText ?? 'Paper Teacher';

  return (
    <div
      data-theme={nightMode ? 'night' : undefined}
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
      {/* R1 Global top bar */}
      <div
        style={{
          height: '52px',
          flexShrink: 0,
          background: 'var(--color-bg-walnut)',
          color: 'var(--color-on-dark)',
          display: 'flex',
          alignItems: 'stretch',
        }}
      >
        <div style={{ flex: 1, minWidth: 0, display: 'flex', alignItems: 'center', padding: '0 20px 0 16px', gap: '16px' }}>
          <Link to="/" style={{ display: 'flex', alignItems: 'center', gap: '10px', flexShrink: 0, textDecoration: 'none', color: 'inherit' }}>
            <PaperStackMark size={22} color="var(--color-on-dark)" style={{ flexShrink: 0 }} />
            <span style={{ fontFamily: 'var(--font-serif)', fontWeight: 600, fontSize: '16px', letterSpacing: '-0.005em', whiteSpace: 'nowrap' }}>
              Paper Teacher
            </span>
          </Link>
          <div style={{ width: '1px', height: '18px', background: 'rgba(255,253,247,0.18)', flexShrink: 0 }} />
          <Link
            to="/library"
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              background: 'transparent',
              border: 'none',
              padding: '7px 10px',
              borderRadius: '9999px',
              color: 'var(--color-on-dark)',
              fontFamily: 'var(--font-sans)',
              fontSize: '13px',
              fontWeight: 600,
              whiteSpace: 'nowrap',
              flexShrink: 0,
              textDecoration: 'none',
            }}
          >
            <ArrowLeft size={14} />
            서재로
          </Link>
          <div style={{ width: '1px', height: '18px', background: 'rgba(255,253,247,0.18)', flexShrink: 0 }} />
          <div
            style={{
              fontFamily: 'var(--font-serif)',
              fontSize: '15px',
              fontWeight: 600,
              color: 'var(--color-on-dark)',
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              flex: 1,
              minWidth: 0,
              opacity: 0.92,
            }}
          >
            {titleText}
          </div>
        </div>
        <div
          style={{
            flexShrink: 0,
            display: 'flex',
            alignItems: 'center',
            gap: '4px',
            padding: '0 20px',
            borderLeft: '1px solid rgba(255,253,247,0.14)',
          }}
        >
          <IconButton
            icon={nightMode ? 'sun' : 'moon'}
            label={nightMode ? '주간 모드로 전환' : 'Night Study Mode 켜기'}
            size={32}
            onClick={() => setNightMode((v) => !v)}
            style={{ color: 'var(--color-on-dark)' }}
          />
          <button
            aria-label="프로필"
            title="프로필"
            style={{
              width: '32px',
              height: '32px',
              borderRadius: '9999px',
              border: '1px solid rgba(255,253,247,0.25)',
              background: 'var(--color-bg-walnut-raised)',
              color: 'var(--color-on-dark)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              cursor: 'pointer',
            }}
          >
            <User size={15} />
          </button>
        </div>
      </div>

      {/* R2 Study work area */}
      <div style={{ flex: 1, display: 'flex', minHeight: 0, overflow: 'hidden' }}>
        {/* R3 TOC nav rail */}
        <TocRail toc={toc} activeId={activeId} tocOpen={tocOpen} onToggle={() => setTocOpen((v) => !v)} onJump={handleJump} />

        <div ref={splitRegionRef} style={{ flex: 1, display: 'flex', minWidth: 0, overflow: 'hidden' }}>
          {/* R4 Paper viewer */}
          <div style={{ width: `${splitPct}%`, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
            <PaperViewer blocks={blocks} containerRef={viewerRef} />
          </div>

          {/* Resizable splitter */}
          <div
            onPointerDown={handleSplitterPointerDown}
            onMouseEnter={() => setSplitterHover(true)}
            onMouseLeave={() => setSplitterHover(false)}
            style={{
              width: '6px',
              flexShrink: 0,
              cursor: 'col-resize',
              background: splitterHover ? 'var(--color-primary-subtle)' : 'transparent',
              position: 'relative',
            }}
          >
            <div style={{ position: 'absolute', top: 0, bottom: 0, left: '2px', width: '1px', background: 'var(--color-border)' }} />
          </div>

          {/* Task 13: TutorPanel */}
          <div
            style={{
              width: `${100 - splitPct}%`,
              minWidth: 0,
              boxSizing: 'border-box',
              flexShrink: 0,
              display: 'flex',
              flexDirection: 'column',
              background: 'var(--color-bg-paper)',
              borderLeft: '1px solid var(--color-border)',
              overflow: 'hidden',
            }}
          />
        </div>
      </div>
    </div>
  );
}
