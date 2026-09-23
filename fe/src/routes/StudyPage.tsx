// 이식: project-docs/design/v1/Paper Study Page.dc.html — R1 top bar / R2 work area / R3 TOC nav rail /
// R4 Paper viewer / Resizable splitter / R5 AI chat panel(TutorPanel, Task 13) / Selection·Ask·Translation
// popup(SelectionLayer, Task 14). sc-if→{cond && …}, x-map→.map, style="{{ x }}"→style={x} 기계적 전사
// (플랜 공통 변환표, Task 9·10과 동일).
//
// Night mode 토글: 이 목업 파일 자체에는 스위치 UI가 없다 (디자인 시스템 readme에서만 "Night Study Mode
// toggle"로 언급). brief Step 3가 명시적으로 요구하는 기능이라 R1 우측 존에 아이콘 버튼을 새로 추가했다
// (report에 기록) — 목업에 없는 요소이므로 기존 변환표 밖 판단.
import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { Navigate, useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { IconButton } from '../design/components/IconButton';
import { ApiError, type TranslationStatus, type KnowledgeGraphStatus } from '../api/types';
import { getPaperContent } from '../markdown/paperContent';
import { translationDisabledReason } from './study/translationStatus';
import { PaperViewer, type TranslationMode } from './study/PaperViewer';
import { TranslationModeButton } from './study/TranslationModeButton';
import { PrerequisiteToggle } from './study/PrerequisiteToggle';
import { PrerequisiteLayer } from './study/PrerequisiteLayer';
import { prerequisiteDisabledReason } from './study/prerequisiteStatus';
import { SelectionLayer } from './study/SelectionLayer';
import { ContentAskLayer } from './study/ContentAskLayer';
import { TocRail } from './study/TocRail';
import { TutorPanel, type TutorPanelAttachEvent } from './study/TutorPanel';
import { attachSelection, MAX_ATTACHMENTS, type SelectionAttachment } from '../chat/selectionAttachments';
import { useScrollSpy } from './study/useScrollSpy';
import type { SelectionAnchors } from './study/selectionAnchors';
import { usePlanQuery } from '../plan/usePlanQuery';
import { isExhausted, exhaustedPlaceholder } from '../plan/planLabels';
import { StudyTopBar } from './study/StudyTopBar';
import { usePaperStatusQuery } from './study/usePaperStatusQuery';

const NIGHT_STORAGE_KEY = 'pt-night';
const TRANSLATION_STORAGE_KEY = 'pt-translation-mode';
const SPLIT_MIN = 30;
const SPLIT_MAX = 75;
const SPLIT_DEFAULT = 70;
// 옆 배치로 들어갈 때 맞추는 채팅 폭과 스플리터 폭.
const CHAT_SIDE_WIDTH = 320;
const SPLITTER_WIDTH = 6;

/** 채팅이 CHAT_SIDE_WIDTH가 되는 뷰어 비율. 폭을 못 재면 null(기존 비율 유지). */
export function splitPctForChatWidth(regionWidth: number): number | null {
  if (!(regionWidth > 0)) return null;
  const pct = ((regionWidth - CHAT_SIDE_WIDTH - SPLITTER_WIDTH) / regionWidth) * 100;
  return Math.min(SPLIT_MAX, Math.max(SPLIT_MIN, pct));
}

export default function StudyPage() {
  const { paperId } = useParams<{ paperId: string }>();

  const statusQuery = usePaperStatusQuery(paperId);

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
    const gone = statusQuery.error instanceof ApiError && statusQuery.error.httpStatus === 404;
    return <Navigate to="/library" replace state={{ toast: gone ? '삭제되었거나 없는 논문입니다' : '논문 상태를 불러오지 못했습니다' }} />;
  }

  // COMPLETED가 아닌 논문은 학습 진입 불가 (FT-002 Story 3와 동일 규칙) — /library로 돌려보내고 토스트로 안내한다.
  if (statusQuery.data.status !== 'COMPLETED') {
    const toast =
      statusQuery.data.status === 'FAILED' || statusQuery.data.status === 'EXPIRED'
        ? '분석에 실패한 논문입니다'
        : '아직 분석 중인 논문입니다';
    return <Navigate to="/library" replace state={{ toast }} />;
  }

  return (
    <StudyPageContent
      paperId={paperId}
      translationStatus={statusQuery.data.translationStatus}
      knowledgeGraphStatus={statusQuery.data.knowledgeGraphStatus}
    />
  );
}

function StudyPageContent({
  paperId, translationStatus, knowledgeGraphStatus,
}: { paperId: string; translationStatus: TranslationStatus; knowledgeGraphStatus: KnowledgeGraphStatus | null }) {
  const queryClient = useQueryClient();
  const contentQuery = useQuery({
    queryKey: ['paper-content', paperId],
    queryFn: () => getPaperContent(paperId),
  });

  // READY로 바뀌는 순간 본문을 다시 받는다. 기존 데이터를 보여주다 새 데이터로 바뀌므로 화면이 비지 않는다.
  const prevTranslationStatus = useRef(translationStatus);
  useEffect(() => {
    if (prevTranslationStatus.current !== 'READY' && translationStatus === 'READY') {
      queryClient.invalidateQueries({ queryKey: ['paper-content', paperId] });
    }
    prevTranslationStatus.current = translationStatus;
  }, [translationStatus, paperId, queryClient]);

  // 지식 그래프 컴파일이 끝나 READY가 되는 순간에도 본문을 다시 받는다 — 선행지식 하이라이트가 이때 채워진다.
  const prevKnowledgeGraphStatus = useRef(knowledgeGraphStatus);
  useEffect(() => {
    if (prevKnowledgeGraphStatus.current !== 'READY' && knowledgeGraphStatus === 'READY') {
      queryClient.invalidateQueries({ queryKey: ['paper-content', paperId] });
    }
    prevKnowledgeGraphStatus.current = knowledgeGraphStatus;
  }, [knowledgeGraphStatus, paperId, queryClient]);

  const planQuery = usePlanQuery();
  const aiUsage = planQuery.data?.usage.aiQuery;

  const [tocOpen, setTocOpen] = useState(false);
  const [nightMode, setNightMode] = useState<boolean>(() => {
    try {
      return localStorage.getItem(NIGHT_STORAGE_KEY) === '1';
    } catch {
      return false;
    }
  });
  const [translationMode, setTranslationMode] = useState<TranslationMode>(() => {
    try {
      const saved = localStorage.getItem(TRANSLATION_STORAGE_KEY);
      return saved === 'below' || saved === 'side' ? saved : 'off';
    } catch {
      return 'off';
    }
  });
  const [splitPct, setSplitPct] = useState(SPLIT_DEFAULT);
  const [splitterHover, setSplitterHover] = useState(false);
  const [chatCollapsed, setChatCollapsed] = useState(false);
  const [prerequisiteOn, setPrerequisiteOn] = useState(false);
  // 세 오버레이(선행지식·선택·질문하기)가 서로를 닫는 신호. 값이 바뀌면 해당 오버레이가 idle로 돌아간다.
  const [overlaySignals, setOverlaySignals] = useState({ prerequisite: 0, selection: 0, ask: 0 });
  // SelectionLayer·ContentAskLayer의 "AI에게 질문" → 컴포저 인용 첨부 목록으로 누적된다 (FT-006 Story 5).
  const [attachments, setAttachments] = useState<SelectionAttachment[]>([]);
  const [attachEvent, setAttachEvent] = useState<TutorPanelAttachEvent | null>(null);
  const [attachNotice, setAttachNotice] = useState<string | null>(null);

  const viewerRef = useRef<HTMLDivElement>(null);
  const splitRegionRef = useRef<HTMLDivElement>(null);
  const attachSeqRef = useRef(0);
  const noticeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const blocks = useMemo(() => contentQuery.data?.blocks ?? [], [contentQuery.data]);
  const toc = contentQuery.data?.toc ?? [];
  // PrerequisiteLayer의 클릭 위임 effect가 이 배열에 의존한다 — 매 렌더 새 배열이면 effect가 계속 재등록된다.
  const prerequisiteHighlights = useMemo(() => contentQuery.data?.prerequisiteHighlights ?? [], [contentQuery.data]);
  const prerequisiteEnabled = prerequisiteHighlights.length > 0;
  const prerequisiteVisible = prerequisiteOn && prerequisiteEnabled;
  // toc.map()이 렌더마다 새 배열을 만들면 useScrollSpy의 effect deps가 매번 바뀌어
  // IntersectionObserver가 불필요하게 재구축된다(스플리터 드래그 중 pointermove마다 리렌더되면 특히 심함) — 메모이즈.
  const tocOrder = useMemo(() => toc.map((t) => t.blockId), [toc]);
  const activeId = useScrollSpy(viewerRef, tocOrder);

  // 오버레이 세 개(선행지식·선택·질문하기) 중 하나가 열리면 나머지를 닫는다.
  const handlePrerequisiteOpen = useCallback(() => {
    setOverlaySignals((s) => ({ ...s, selection: s.selection + 1, ask: s.ask + 1 }));
  }, []);
  const handleSelectionOpen = useCallback(() => {
    setOverlaySignals((s) => ({ ...s, prerequisite: s.prerequisite + 1, ask: s.ask + 1 }));
  }, []);
  const handleAskOpen = useCallback(() => {
    setOverlaySignals((s) => ({ ...s, prerequisite: s.prerequisite + 1, selection: s.selection + 1 }));
  }, []);

  const expiredRefetched = useRef(false);
  const assetExpiresAt = contentQuery.data?.assetExpiresAt;
  const refetchContent = contentQuery.refetch;
  // PaperViewer가 memo라 콜백 identity를 고정한다.
  const handleImageError = useCallback(() => {
    if (!assetExpiresAt || expiredRefetched.current) return; // 재조회는 1회만 — 무한 루프 방지
    if (Date.now() > Date.parse(assetExpiresAt)) {
      expiredRefetched.current = true;
      refetchContent();
    }
  }, [assetExpiresAt, refetchContent]);

  useEffect(() => {
    try {
      localStorage.setItem(NIGHT_STORAGE_KEY, nightMode ? '1' : '0');
    } catch {
      /* storage 접근 불가 — night 상태는 세션 내에서만 유지된다 */
    }
  }, [nightMode]);

  useEffect(() => {
    try {
      localStorage.setItem(TRANSLATION_STORAGE_KEY, translationMode);
    } catch {
      /* storage 접근 불가 — 번역 배치는 세션 내에서만 유지된다 */
    }
  }, [translationMode]);

  // 옆 배치로 들어갈 때만 채팅 폭을 한 번 맞춘다. 이후 스플리터 조작과 off 복귀는 건드리지 않는다.
  function handleCycleTranslation() {
    const next: TranslationMode = translationMode === 'off' ? 'below' : translationMode === 'below' ? 'side' : 'off';
    if (next === 'side' && !chatCollapsed) {
      const pct = splitPctForChatWidth(splitRegionRef.current?.getBoundingClientRect().width ?? 0);
      if (pct !== null) setSplitPct(pct);
    }
    setTranslationMode(next);
  }

  function handleJump(blockId: string) {
    document.getElementById(blockId)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function showAttachNotice(message: string) {
    if (noticeTimerRef.current) clearTimeout(noticeTimerRef.current);
    setAttachNotice(message);
    noticeTimerRef.current = setTimeout(() => setAttachNotice(null), 2500);
  }

  // SelectionLayer·ContentAskLayer의 Ask popup에서 "현재 채팅"/"새 채팅"을 고르면 호출된다 —
  // 첨부를 누적하고(완전 중복 무시·상한·크기 검증) 챗 패널이 접혀 있으면 펼친다.
  // anchors를 못 만든 선택은 첨부 없이 패널만 연다 (기존 단수 시절과 동일한 폴백).
  function handleAsk(text: string, mode: 'current' | 'new', anchors: SelectionAnchors | null) {
    if (anchors) {
      const result = attachSelection(attachments, blocks, { text, anchors });
      if (!result.ok) {
        showAttachNotice(result.reason === 'limit'
          ? `인용은 최대 ${MAX_ATTACHMENTS}개까지 첨부할 수 있어요`
          : '선택 영역이 너무 커요. 더 짧게 선택해 주세요.');
        return;
      }
      setAttachments(result.attachments);
    }
    attachSeqRef.current += 1;
    setAttachEvent({ seq: attachSeqRef.current, mode });
    setChatCollapsed(false);
  }

  function handleRemoveAttachment(index: number) {
    setAttachments((prev) => prev.filter((_, i) => i !== index));
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

  if (contentQuery.isPending) {
    return (
      <div style={{ padding: 48, textAlign: 'center', fontFamily: 'var(--font-sans)', color: 'var(--color-text-muted)' }}>
        본문을 불러오는 중…
      </div>
    );
  }
  if (contentQuery.isError) {
    // 진입 게이트(status COMPLETED)를 통과했는데 409면 적재 지연 등 일시 상태 — 서재로 안내
    if (contentQuery.error instanceof ApiError && contentQuery.error.code === 'PAPER_NOT_READY') {
      return <Navigate to="/library" replace state={{ toast: '본문 준비 중입니다. 잠시 후 다시 열어주세요' }} />;
    }
    return (
      <div style={{ padding: 48, textAlign: 'center', fontFamily: 'var(--font-sans)', color: 'var(--color-text-muted)' }}>
        본문을 불러오지 못했습니다{' '}
        <button onClick={() => contentQuery.refetch()} style={{ marginLeft: 8 }}>다시 시도</button>
      </div>
    );
  }

  const hasTranslation = contentQuery.data?.hasTranslation ?? false;
  const translationEnabled = translationStatus === 'READY' && hasTranslation;
  // 저장된 선호값은 그대로 두고, 번역을 쓸 수 없는 논문에서는 off로만 적용한다.
  const effectiveMode: TranslationMode = translationEnabled ? translationMode : 'off';

  const titleText =
    contentQuery.data?.title
    ?? blocks.find((b) => b.type === 'heading')?.headingText
    ?? 'Paper Teacher';

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
      <StudyTopBar
        paperId={paperId}
        title={titleText}
        current="content"
        knowledgeGraphStatus={knowledgeGraphStatus}
        rightSlot={
          <>
            <PrerequisiteToggle
              checked={prerequisiteOn}
              disabled={!prerequisiteEnabled}
              disabledReason={prerequisiteDisabledReason(prerequisiteHighlights.length)}
              onToggle={() => setPrerequisiteOn((v) => !v)}
            />
            <TranslationModeButton
              mode={effectiveMode}
              disabled={!translationEnabled}
              disabledReason={translationDisabledReason(translationStatus, hasTranslation)}
              onCycle={handleCycleTranslation}
            />
            <IconButton
              icon={nightMode ? 'sun' : 'moon'}
              label={nightMode ? '주간 모드로 전환' : 'Night Study Mode 켜기'}
              size={32}
              onClick={() => setNightMode((v) => !v)}
              style={{ color: 'var(--color-on-dark)' }}
            />
          </>
        }
      />

      {/* R2 Study work area */}
      <div style={{ flex: 1, display: 'flex', minHeight: 0, overflow: 'hidden' }}>
        {/* R3 TOC nav rail */}
        <TocRail toc={toc} activeId={activeId} tocOpen={tocOpen} onToggle={() => setTocOpen((v) => !v)} onJump={handleJump} />

        <div ref={splitRegionRef} style={{ flex: 1, display: 'flex', minWidth: 0, overflow: 'hidden' }}>
          {/* R4 Paper viewer */}
          <div
            style={{
              width: chatCollapsed ? 'calc(100% - 44px - 6px)' : `${splitPct}%`,
              minWidth: 0,
              display: 'flex',
              flexDirection: 'column',
              position: 'relative',
            }}
          >
            <PaperViewer blocks={blocks} containerRef={viewerRef} translationMode={effectiveMode} onImageError={handleImageError} prerequisiteVisible={prerequisiteVisible} />
            <SelectionLayer
              paperId={paperId}
              viewerRef={viewerRef}
              blocks={blocks}
              onAsk={handleAsk}
              translationVisible={effectiveMode !== 'off'}
              closeSignal={overlaySignals.selection}
              onOpen={handleSelectionOpen}
            />
            <ContentAskLayer
              viewerRef={viewerRef}
              blocks={blocks}
              onAsk={handleAsk}
              closeSignal={overlaySignals.ask}
              onOpen={handleAskOpen}
            />
            <PrerequisiteLayer
              paperId={paperId}
              viewerRef={viewerRef}
              highlights={prerequisiteHighlights}
              visible={prerequisiteVisible}
              openSignal={overlaySignals.prerequisite}
              onOpen={handlePrerequisiteOpen}
            />
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

          {/* R5 AI chat panel */}
          <div
            style={{
              width: chatCollapsed ? '44px' : `${100 - splitPct}%`,
              minWidth: 0,
              boxSizing: 'border-box',
              flexShrink: 0,
              overflow: 'hidden',
            }}
          >
            <TutorPanel
              paperId={paperId}
              blocks={blocks}
              attachments={attachments}
              attachEvent={attachEvent}
              onRemoveAttachment={handleRemoveAttachment}
              onAttachmentsConsumed={() => setAttachments([])}
              attachNotice={attachNotice}
              collapsed={chatCollapsed}
              onToggleCollapse={() => setChatCollapsed((v) => !v)}
              queryLocked={aiUsage ? isExhausted(aiUsage) : false}
              lockPlaceholder={exhaustedPlaceholder(aiUsage?.resetAt ?? null)}
            />
          </div>
        </div>
      </div>
    </div>
  );
}
