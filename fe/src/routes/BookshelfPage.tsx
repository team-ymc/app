// 이식: project-docs/design/v1/Paper Bookshelf Page.dc.html (R1 spacer / R2 헤더 / R3 컨트롤 / R4 목록 /
// R5 페이지네이션 / Profile dropdown / Custom top bar overlay / Toast). Upload dialog는 Task 11 소유 — 자리만 남긴다.
// sc-if→{cond && …}, x-map→.map, style="{{ x }}"→style={x} 기계적 전사 (플랜 공통 변환표).
import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import {
  Books,
  MagnifyingGlass,
  List as ListIcon,
  SquaresFour,
  FileText,
  CheckCircle,
  WarningCircle,
} from '@phosphor-icons/react';
import { Button } from '../design/components/Button';
import { IconButton } from '../design/components/IconButton';
import { usePapersQuery } from './bookshelf/usePapersQuery';
import { usePlanQuery } from '../plan/usePlanQuery';
import { isExhausted, uploadLimitNotice } from '../plan/planLabels';
import { filterPapers, paginate } from './bookshelf/paperFilters';
import { accessDisplay } from './bookshelf/paperDateLabel';
import UploadDialog from './bookshelf/UploadDialog';
import { getDownloadUrl, renamePaper, deletePaper } from '../api/papers';
import { ApiError } from '../api/types';
import PaperRowMenu from './bookshelf/PaperRowMenu';
import PaperTitleEditor from './bookshelf/PaperTitleEditor';
import ConfirmDialog from './bookshelf/ConfirmDialog';
import { GlobalNav } from '../nav/GlobalNav';
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
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const { data, isPending, isError, error } = usePapersQuery();
  const planQuery = usePlanQuery();
  const plan = planQuery.data;
  const uploadLocked = plan ? isExhausted(plan.usage.paperRegistration) : false;
  const lockNotice = plan ? uploadLimitNotice(plan) : null;

  const [keyword, setKeyword] = useState('');
  const [isGridView, setIsGridView] = useState(false);
  const [page, setPage] = useState(1);
  const [uploadOpen, setUploadOpen] = useState(false); // Task 11: UploadDialog가 이 state를 소비한다
  const [toast, setToast] = useState<string | null>(null);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<Paper | null>(null);
  const [deleting, setDeleting] = useState(false);

  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current);
  }, []);

  function showToast(text: string) {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current);
    setToast(text);
    toastTimerRef.current = setTimeout(() => setToast(null), TOAST_DURATION_MS);
  }

  // StudyPage 진입 가드(비-COMPLETED → /library) 등, 다른 라우트가 라우터 state로 넘긴 토스트 메시지를 표시한다.
  // 표시 후 state를 비워 뒤로가기/새로고침에서 재노출되지 않게 한다 (Task 12).
  useEffect(() => {
    const routedToast = (location.state as { toast?: string } | null)?.toast;
    if (!routedToast) return;
    showToast(routedToast);
    navigate(location.pathname, { replace: true, state: null });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.state]);

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

  // 캐시 형태는 { papers: Paper[] } — 응답으로 받은 행만 바꾼다(낙관적 업데이트 없음).
  function patchPapersCache(update: (papers: Paper[]) => Paper[]) {
    queryClient.setQueryData<{ papers: Paper[] }>(['papers'], (prev) =>
      prev ? { papers: update(prev.papers) } : prev,
    );
  }

  async function handleDownload(paper: Paper) {
    try {
      const { downloadUrl } = await getDownloadUrl(paper.paperId);
      window.location.assign(downloadUrl); // Content-Disposition: attachment가 서명돼 있어 페이지를 떠나지 않는다
    } catch (e) {
      showToast(e instanceof Error ? e.message : '다운로드 URL을 받지 못했습니다');
    }
  }

  async function handleRenameSave(paper: Paper, filename: string) {
    setRenamingId(null);
    try {
      const updated = await renamePaper(paper.paperId, filename);
      patchPapersCache((papers) => papers.map((p) => (p.paperId === updated.paperId ? updated : p)));
    } catch (e) {
      showToast(e instanceof Error ? e.message : '이름을 바꾸지 못했습니다');
    }
  }

  async function handleDeleteConfirm() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deletePaper(deleteTarget.paperId);
      patchPapersCache((papers) => papers.filter((p) => p.paperId !== deleteTarget.paperId));
      showToast('삭제했습니다');
    } catch (e) {
      if (e instanceof ApiError && e.httpStatus === 404) {
        queryClient.invalidateQueries({ queryKey: ['papers'] }); // 이미 지워진 논문 — 목록만 새로 맞춘다
      } else {
        showToast(e instanceof Error ? e.message : '삭제하지 못했습니다');
      }
    } finally {
      setDeleting(false);
      setDeleteTarget(null);
    }
  }

  const allPapers = data?.papers ?? [];
  const filtered = filterPapers(allPapers, keyword);
  const { items: pageItems, totalPages } = paginate(filtered, page, PAGE_SIZE);

  // 삭제로 마지막 페이지가 비면 앞 페이지로 (검색 필터를 거친 결과 기준).
  useEffect(() => {
    if (page > totalPages) setPage(totalPages);
  }, [page, totalPages]);

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
            <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
              {uploadLocked && lockNotice && (
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '8px',
                    height: '36px',
                    padding: '0 12px',
                    boxSizing: 'border-box',
                    background: 'var(--color-bg-surface)',
                    border: '1px solid var(--color-border)',
                    borderRadius: 'var(--radius-control)',
                    fontFamily: 'var(--font-sans)',
                    fontSize: '13px',
                    color: 'var(--color-text-body)',
                    whiteSpace: 'nowrap',
                  }}
                >
                  <WarningCircle size={15} color="var(--color-danger)" />
                  <span>
                    <span style={{ fontWeight: 600 }}>{lockNotice.headline}</span>
                    <span style={{ color: 'var(--color-text-muted)' }}>{lockNotice.reset}</span>
                  </span>
                </div>
              )}
              <Button variant="secondary" icon="plus" onClick={() => setUploadOpen(true)} disabled={uploadLocked} style={{ flexShrink: 0 }}>
                <span style={{ whiteSpace: 'nowrap', fontWeight: 600 }}>업로드</span>
              </Button>
            </div>
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

          {/* 시연용 목업 진입점 — 시연 후 이 블록과 public/demo/를 함께 제거한다 */}
          {!keyword.trim() && <DemoMockupRow />}

          {/* R4 Paper list */}
          {hasResults && (
            isGridView ? (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '20px' }}>
                {pageItems.map((paper) => (
                  <PaperGridCard
                    key={paper.paperId}
                    paper={paper}
                    renaming={renamingId === paper.paperId}
                    onSelect={handleSelectPaper}
                    onDownload={() => handleDownload(paper)}
                    onRenameStart={() => setRenamingId(paper.paperId)}
                    onRenameSave={(name) => handleRenameSave(paper, name)}
                    onRenameCancel={() => setRenamingId(null)}
                    onDeleteRequest={() => setDeleteTarget(paper)}
                  />
                ))}
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                {pageItems.map((paper) => (
                  <PaperListRow
                    key={paper.paperId}
                    paper={paper}
                    renaming={renamingId === paper.paperId}
                    onSelect={handleSelectPaper}
                    onDownload={() => handleDownload(paper)}
                    onRenameStart={() => setRenamingId(paper.paperId)}
                    onRenameSave={(name) => handleRenameSave(paper, name)}
                    onRenameCancel={() => setRenamingId(null)}
                    onDeleteRequest={() => setDeleteTarget(paper)}
                  />
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

      <GlobalNav />

      {uploadOpen && (
        <UploadDialog
          open
          onClose={() => setUploadOpen(false)}
          onUploaded={() => {
            queryClient.invalidateQueries({ queryKey: ['plan'] });
            showToast('등록되었습니다 — 분석이 시작됩니다');
          }}
        />
      )}

      <ConfirmDialog
        open={deleteTarget !== null}
        title="논문을 삭제할까요?"
        message={
          <>
            <span style={{ fontFamily: 'var(--font-serif)', fontWeight: 600, color: 'var(--color-text-heading)' }}>
              {deleteTarget?.filename}
            </span>
            를 서재에서 지웁니다. 이 논문의 채팅 기록도 함께 사라지며 되돌릴 수 없습니다. 등록 횟수는 복구되지 않습니다.
          </>
        }
        confirmLabel="삭제"
        busy={deleting}
        onConfirm={handleDeleteConfirm}
        onCancel={() => { if (!deleting) setDeleteTarget(null); }}
      />

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
    const { prefix, iso } = accessDisplay(paper);
    return (
      <div style={{ fontFamily: 'var(--font-sans)', fontSize: '12px', color: 'var(--color-text-muted)', flexShrink: 0, whiteSpace: 'nowrap' }}>
        {prefix} · {formatDate(iso)}
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

interface PaperItemProps {
  paper: Paper;
  renaming: boolean;
  onSelect: (paper: Paper) => void;
  onDownload: () => void;
  onRenameStart: () => void;
  onRenameSave: (filename: string) => void;
  onRenameCancel: () => void;
  onDeleteRequest: () => void;
}

function PaperListRow({ paper, renaming, onSelect, onDownload, onRenameStart, onRenameSave, onRenameCancel, onDeleteRequest }: PaperItemProps) {
  const [hover, setHover] = useState(false);
  return (
    <div
      role="button"
      tabIndex={0}
      // 이름 변경 중에는 blur로 저장되는 클릭이 곧바로 페이지 이동으로 이어지지 않게 막는다
      onClick={() => { if (!renaming) onSelect(paper); }}
      onKeyDown={(e) => {
        if ((e.key === 'Enter' || e.key === ' ') && !renaming) onSelect(paper);
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
      <div style={{ flex: 1, minWidth: 0, display: 'flex' }}>
        <PaperTitleEditor
          filename={paper.filename}
          editing={renaming}
          titleStyle={{
            fontFamily: 'var(--font-serif)',
            fontSize: '16px',
            fontWeight: 600,
            color: 'var(--color-text-heading)',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
          onSave={onRenameSave}
          onCancel={onRenameCancel}
        />
      </div>
      <StatusBadge paper={paper} />
      <PaperRowMenu paper={paper} onDownload={onDownload} onRename={onRenameStart} onDelete={onDeleteRequest} />
    </div>
  );
}

// 시연용 목업 진입점 — 시연 후 제거. react-router Link가 아니라 <a>여야 SPA를 벗어나 정적 파일로 간다.
function DemoMockupRow() {
  const [hover, setHover] = useState(false);
  return (
    <a
      href="/demo/paper/study.html"
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: '16px',
        padding: '14px 16px',
        marginBottom: '10px',
        background: 'var(--color-bg-surface)',
        border: `1px solid ${hover ? 'var(--color-primary)' : 'var(--color-border)'}`,
        borderRadius: 'var(--radius-structural)',
        textDecoration: 'none',
        transition: 'border-color 150ms ease',
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
          Attention Is All You Need
        </div>
      </div>
      <div
        style={{
          fontFamily: 'var(--font-sans)',
          fontSize: '12px',
          color: 'var(--color-text-muted)',
          flexShrink: 0,
          whiteSpace: 'nowrap',
        }}
      >
        미리보기
      </div>
    </a>
  );
}

function PaperGridCard({ paper, renaming, onSelect, onDownload, onRenameStart, onRenameSave, onRenameCancel, onDeleteRequest }: PaperItemProps) {
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => { if (!renaming) onSelect(paper); }}
      onKeyDown={(e) => {
        if ((e.key === 'Enter' || e.key === ' ') && !renaming) onSelect(paper);
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
        position: 'relative',
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
      <div style={{ position: 'absolute', top: '24px', right: '24px', background: 'var(--color-bg-paper)', border: '1px solid var(--color-border)', borderRadius: 'var(--radius-control)' }}>
        <PaperRowMenu paper={paper} size={28} onDownload={onDownload} onRename={onRenameStart} onDelete={onDeleteRequest} />
      </div>
      <div style={{ display: 'flex', marginBottom: '4px' }}>
        <PaperTitleEditor
          filename={paper.filename}
          editing={renaming}
          titleStyle={{
            fontSize: 'var(--ui-strong-size)',
            fontWeight: 'var(--ui-strong-weight)',
            color: 'var(--color-text-heading)',
            lineHeight: 1.3,
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            minWidth: 0,
            flex: 1,
          }}
          onSave={onRenameSave}
          onCancel={onRenameCancel}
        />
      </div>
      <StatusBadge paper={paper} />
    </div>
  );
}
