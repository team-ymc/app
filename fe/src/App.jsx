// 표현 층 — BE 검증용 임시 업로드 UI (DESIGN.md).
// 기반 목업: temp/논문 등록 처리 UI.html. sc-if→{cond && …}, sc-camel-on-click→onClick,
// style="{{ x }}"→style={x} 로 기계적 전사(DESIGN.md D1~D7 표).
// 상태 모델은 DESIGN.md D4.
import { useCallback, useEffect, useRef, useState } from 'react';
import { createPaper, uploadToS3, completeUpload, getStatus, getDownloadUrl, listPapers } from './api';
import ChatPanel from './chat/ChatPanel.jsx';

// 행 표시는 서버 status가 정한다 (FT-002 Story 3 매핑, DESIGN.md D4).
const IN_PROGRESS_STATUSES = ['UPLOAD_PENDING', 'UPLOADED', 'PROCESSING'];
const TERMINAL_STATUSES = ['COMPLETED', 'FAILED', 'EXPIRED'];
const POLL_INTERVAL_MS = 2000;

function statusPhase(status) {
  if (status === 'COMPLETED') return 'completed';
  if (status === 'FAILED' || status === 'EXPIRED') return 'failed';
  return 'progress'; // UPLOAD_PENDING / UPLOADED / PROCESSING
}

function formatBytes(bytes) {
  if (bytes === null || bytes === undefined) return '';
  const mb = bytes / (1024 * 1024);
  if (mb >= 1) return `${mb.toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

function formatTime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export default function App() {
  const [papers, setPapers] = useState([]);
  const [modal, setModal] = useState('closed'); // 'closed' | 'idle' | 'file-selected' | 'uploading'
  const [selectedFile, setSelectedFile] = useState(null);
  const [uploadPct, setUploadPct] = useState(0);
  const [error, setError] = useState(null);
  const [dragOver, setDragOver] = useState(false);
  const [chatPaper, setChatPaper] = useState(null); // 채팅 패널 대상 (FT-007)

  const pollTimers = useRef({}); // paperId -> intervalId (DESIGN.md D2: 폴링으로만 진행 확인)
  const fileInputRef = useRef(null);

  const stopPolling = useCallback((paperId) => {
    if (pollTimers.current[paperId]) {
      clearInterval(pollTimers.current[paperId]);
      delete pollTimers.current[paperId];
    }
  }, []);

  const startPolling = useCallback(
    (paperId) => {
      if (pollTimers.current[paperId]) return; // 이미 폴링 중
      pollTimers.current[paperId] = setInterval(async () => {
        try {
          const s = await getStatus(paperId);
          setPapers((prev) =>
            prev.map((p) => (p.paperId === paperId ? { ...p, status: s.status, updatedAt: s.updatedAt } : p)),
          );
          if (TERMINAL_STATUSES.includes(s.status)) stopPolling(paperId);
        } catch (e) {
          setError(e.message);
          stopPolling(paperId);
        }
      }, POLL_INTERVAL_MS);
    },
    [stopPolling],
  );

  const loadPapers = useCallback(async () => {
    try {
      const res = await listPapers();
      const list = res.papers || [];
      setPapers(list);
      list.forEach((p) => {
        if (IN_PROGRESS_STATUSES.includes(p.status)) startPolling(p.paperId);
      });
    } catch (e) {
      setError(e.message);
    }
  }, [startPolling]);

  useEffect(() => {
    loadPapers();
    const timers = pollTimers.current;
    return () => {
      Object.values(timers).forEach(clearInterval);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function openModal() {
    setError(null);
    setSelectedFile(null);
    setUploadPct(0);
    setDragOver(false);
    setModal('idle');
  }

  function closeModal() {
    if (modal === 'uploading') return; // 업로드 중엔 닫지 않는다
    setModal('closed');
    setSelectedFile(null);
    setUploadPct(0);
    setError(null);
  }

  function stopPropagation(e) {
    e.stopPropagation();
  }

  function pickFile() {
    fileInputRef.current?.click();
  }

  function onFileChosen(file) {
    if (!file) return;
    setError(null);
    setSelectedFile(file);
    setModal('file-selected');
  }

  function handleFileInputChange(e) {
    onFileChosen(e.target.files?.[0] ?? null);
    e.target.value = '';
  }

  function clearFile() {
    setSelectedFile(null);
    setModal('idle');
  }

  function onDragOver(e) {
    e.preventDefault();
    setDragOver(true);
  }

  function onDragLeave() {
    setDragOver(false);
  }

  function onDrop(e) {
    e.preventDefault();
    setDragOver(false);
    onFileChosen(e.dataTransfer.files?.[0] ?? null);
  }

  async function startUpload() {
    if (modal !== 'file-selected' || !selectedFile) return;
    setModal('uploading');
    setUploadPct(0);
    setError(null);
    try {
      const created = await createPaper(selectedFile.name, 'application/pdf');
      await uploadToS3(created.uploadUrl, selectedFile, (pct) => setUploadPct(pct));
      await completeUpload(created.paperId);
      setModal('closed');
      setSelectedFile(null);
      setUploadPct(0);
      await loadPapers(); // 재조회로 수렴 (DESIGN.md D3)
      startPolling(created.paperId);
    } catch (e) {
      setError(e.message); // 409 DUPLICATE_FILENAME 등, 숨기지 않는다 (DESIGN.md D7)
      setModal('file-selected'); // 재시도 가능하도록 복귀
    }
  }

  // Task 8: 완료 행 다운로드.
  async function handleDownload(paperId) {
    try {
      const { downloadUrl } = await getDownloadUrl(paperId);
      // cross-origin 네비게이션 다운로드 — 파일명은 서버의 Content-Disposition이 결정한다.
      const a = document.createElement('a');
      a.href = downloadUrl;
      a.download = '';
      document.body.appendChild(a);
      a.click();
      a.remove();
    } catch (e) {
      setError(e.message);
    }
  }

  const isEmptyState = papers.length === 0;
  const isLibraryWithPaper = papers.length > 0;
  const isModalOpen = modal !== 'closed';
  const isFileSelected = modal === 'file-selected';
  const isUploading = modal === 'uploading';
  const showDropzone = modal === 'idle';
  const showUploadButton = !isUploading;

  const uploadBarStyle = {
    height: '100%',
    background: 'linear-gradient(90deg, #3B82F6, #1B4FD8)',
    borderRadius: '3px',
    transition: 'width 0.18s ease',
    width: `${uploadPct}%`,
  };

  const dropzoneStyle = {
    border: `2px dashed ${dragOver ? '#1B4FD8' : '#CECBC3'}`,
    borderRadius: '10px',
    padding: '30px 24px',
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: '12px',
    background: dragOver ? '#EFF6FF' : '#FAFAF8',
    transition: 'all 0.15s ease',
    cursor: 'default',
  };

  const uploadBtnStyle = {
    width: '100%',
    height: '44px',
    borderRadius: '8px',
    border: 'none',
    background: isFileSelected ? '#1B4FD8' : '#EAE8E2',
    color: isFileSelected ? '#ffffff' : '#A8A5A0',
    fontSize: '14px',
    fontWeight: '600',
    cursor: isFileSelected ? 'pointer' : 'default',
    fontFamily: 'inherit',
    letterSpacing: '-0.3px',
    display: 'block',
  };

  return (
    <div style={{ minHeight: '100vh', display: 'flex', flexDirection: 'column', background: '#F8F7F4' }}>
      {/* ── NAV ── */}
      <header style={{ background: '#ffffff', borderBottom: '1px solid #EAE8E2', position: 'sticky', top: 0, zIndex: 10, flexShrink: 0 }}>
        <div style={{ maxWidth: 1120, margin: '0 auto', padding: '0 32px', height: 56, display: 'flex', alignItems: 'center', gap: 10 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginRight: 'auto' }}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
              <path d="M4 19.5A2.5 2.5 0 016.5 17H20" stroke="#1B4FD8" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
              <path d="M6.5 2H20v20H6.5A2.5 2.5 0 014 19.5v-15A2.5 2.5 0 016.5 2z" stroke="#1B4FD8" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
              <path d="M9 7h6M9 11h4" stroke="#1B4FD8" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
            <span style={{ fontSize: 15, fontWeight: 700, color: '#111110', letterSpacing: '-0.4px' }}>Paper Teacher</span>
          </div>
          <span style={{ fontSize: 11, color: '#A8A5A0' }}>BE 검증용 임시 UI</span>
        </div>
      </header>

      {/* 에러 배너 — 모달 밖에서 발생한 에러(목록 조회, 폴링, 다운로드). 숨기지 않는다 (DESIGN.md D7). */}
      {error && !isModalOpen && (
        <div style={{ maxWidth: 1120, margin: '0 auto', width: '100%', padding: '12px 32px 0' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px', borderRadius: 8, background: '#FEF2F2', border: '1px solid #FECACA', color: '#B91C1C', fontSize: 13 }}>
            <span style={{ flex: 1 }}>{error}</span>
            <button onClick={() => setError(null)} style={{ border: 'none', background: 'none', color: '#B91C1C', cursor: 'pointer', fontWeight: 700 }}>
              닫기
            </button>
          </div>
        </div>
      )}

      {/* ── MAIN ── */}
      <main style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        {/* EMPTY LIBRARY */}
        {isEmptyState && (
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
            <div style={{ maxWidth: 1120, margin: '0 auto', width: '100%', padding: '0 32px' }}>
              <div style={{ padding: '22px 0 18px', borderBottom: '1px solid #EAE8E2' }}>
                <h1 style={{ margin: 0, fontSize: 20, fontWeight: 700, color: '#111110', letterSpacing: '-0.5px', whiteSpace: 'nowrap' }}>내 서재</h1>
              </div>
            </div>
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 16, padding: '72px 32px', textAlign: 'center' }}>
              <svg width="80" height="72" viewBox="0 0 80 72" fill="none">
                <ellipse cx="40" cy="70" rx="26" ry="3.5" fill="#E8E5DE" />
                <rect x="7" y="54" width="58" height="12" rx="3" fill="#CCFBF1" />
                <rect x="7" y="54" width="8" height="12" rx="3" fill="#5EEAD4" />
                <rect x="19" y="57.5" width="28" height="2.5" rx="1.2" fill="#5EEAD4" />
                <rect x="19" y="62" width="18" height="2.5" rx="1.2" fill="#5EEAD4" />
                <g transform="rotate(-4 40 40)">
                  <rect x="8" y="37" width="56" height="14" rx="3" fill="#EDE9FE" />
                  <rect x="8" y="37" width="8" height="14" rx="3" fill="#A78BFA" />
                  <rect x="20" y="41" width="26" height="2.5" rx="1.2" fill="#A78BFA" />
                  <rect x="20" y="45.5" width="16" height="2.5" rx="1.2" fill="#A78BFA" />
                </g>
                <g transform="rotate(3 40 20)">
                  <rect x="9" y="16" width="54" height="17" rx="3" fill="#DBEAFE" />
                  <rect x="9" y="16" width="8" height="17" rx="3" fill="#60A5FA" />
                  <rect x="21" y="20.5" width="28" height="2.5" rx="1.2" fill="#93C5FD" />
                  <rect x="21" y="25" width="20" height="2.5" rx="1.2" fill="#93C5FD" />
                  <rect x="21" y="29" width="24" height="2" rx="1" fill="#BAD7F5" />
                </g>
                <circle cx="69" cy="9" r="3" fill="#DBEAFE" />
                <circle cx="73" cy="19" r="2" fill="#EDE9FE" />
                <circle cx="5" cy="11" r="2.5" fill="#CCFBF1" />
                <circle cx="2" cy="24" r="1.5" fill="#DBEAFE" />
              </svg>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                <p style={{ margin: 0, fontSize: 17, fontWeight: 600, color: '#111110', letterSpacing: '-0.4px' }}>아직 등록된 논문이 없어요</p>
                <p style={{ margin: 0, fontSize: 14, color: '#6A6965', lineHeight: 1.6 }}>첫 논문을 올리면 여기 목록에 쌓여요</p>
              </div>
              <button
                onClick={openModal}
                style={{ marginTop: 4, height: 40, padding: '0 20px', borderRadius: 8, border: 'none', background: '#1B4FD8', color: '#fff', fontSize: 14, fontWeight: 600, cursor: 'pointer', fontFamily: 'inherit', display: 'flex', alignItems: 'center', gap: 7, letterSpacing: '-0.3px' }}
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none"><path d="M12 5v14M5 12h14" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" /></svg>
                논문 등록
              </button>
            </div>
          </div>
        )}

        {/* LIBRARY WITH PAPERS */}
        {isLibraryWithPaper && (
          <div style={{ maxWidth: 1120, margin: '0 auto', width: '100%', padding: '0 32px' }}>
            <div style={{ padding: '22px 0 18px', borderBottom: '1px solid #EAE8E2', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <h1 style={{ margin: 0, fontSize: 20, fontWeight: 700, color: '#111110', letterSpacing: '-0.5px', whiteSpace: 'nowrap' }}>내 서재</h1>
              <button
                onClick={openModal}
                style={{ height: 36, padding: '0 14px', borderRadius: 7, border: 'none', background: '#1B4FD8', color: '#fff', fontSize: 13, fontWeight: 600, cursor: 'pointer', fontFamily: 'inherit', display: 'flex', alignItems: 'center', gap: 6, letterSpacing: '-0.2px' }}
              >
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none"><path d="M12 5v14M5 12h14" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" /></svg>
                논문 등록
              </button>
            </div>

            <div style={{ padding: '9px 0', borderBottom: '1px solid #EAE8E2', display: 'flex', alignItems: 'center' }}>
              <span style={{ fontSize: 11, fontWeight: 500, color: '#A8A5A0', letterSpacing: '0.5px', textTransform: 'uppercase', whiteSpace: 'nowrap' }}>제목</span>
              <span style={{ fontSize: 11, fontWeight: 500, color: '#A8A5A0', letterSpacing: '0.5px', textTransform: 'uppercase', marginLeft: 'auto' }}>최근</span>
            </div>

            {papers.map((paper) => {
              const phase = statusPhase(paper.status);
              return (
                <div key={paper.paperId} style={{ padding: '18px 0', borderBottom: '1px solid #F2EFE8', display: 'flex', alignItems: 'flex-start', gap: 14, animation: 'fadeIn 0.3s ease' }}>
                  {/* Thumbnail */}
                  <div style={{ width: 44, height: 56, flexShrink: 0, position: 'relative' }}>
                    <div
                      style={{
                        width: '100%',
                        height: '100%',
                        background: phase === 'completed' ? '#F0FDF4' : phase === 'failed' ? '#FEF2F2' : '#EFF6FF',
                        borderRadius: 5,
                        border: `1px solid ${phase === 'completed' ? '#A7F3D0' : phase === 'failed' ? '#FECACA' : '#BFDBFE'}`,
                        display: 'flex',
                        flexDirection: 'column',
                        alignItems: 'center',
                        justifyContent: 'center',
                        gap: 3.5,
                        padding: 8,
                      }}
                    >
                      {[100, 100, 70, 100, 80].map((w, i) => (
                        <div
                          key={i}
                          style={{
                            width: `${w}%`,
                            height: 2.5,
                            borderRadius: 1,
                            background: phase === 'completed' ? '#6EE7B7' : phase === 'failed' ? '#FCA5A5' : '#93C5FD',
                          }}
                        />
                      ))}
                    </div>
                    <div
                      style={{
                        position: 'absolute',
                        bottom: -5,
                        right: -5,
                        width: 18,
                        height: 18,
                        background: phase === 'completed' ? '#059669' : phase === 'failed' ? '#DC2626' : '#F59E0B',
                        borderRadius: 5,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        border: '2px solid #F8F7F4',
                      }}
                    >
                      {phase === 'progress' && (
                        <div style={{ width: 8, height: 8, border: '1.5px solid rgba(255,255,255,0.35)', borderTopColor: '#fff', borderRadius: '50%', animation: 'spin 0.75s linear infinite' }} />
                      )}
                      {phase === 'completed' && (
                        <svg width="9" height="9" viewBox="0 0 10 10" fill="none"><path d="M2 5l2.5 2.5 4-4" stroke="#fff" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
                      )}
                      {phase === 'failed' && (
                        <svg width="9" height="9" viewBox="0 0 10 10" fill="none"><path d="M2.5 2.5l5 5M7.5 2.5l-5 5" stroke="#fff" strokeWidth="1.5" strokeLinecap="round" /></svg>
                      )}
                    </div>
                  </div>

                  {/* Info */}
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <p style={{ margin: '0 0 3px', fontSize: 15, fontWeight: 600, color: '#111110', letterSpacing: '-0.3px', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {paper.filename}
                    </p>
                    <p style={{ margin: '0 0 11px', fontSize: 13, color: '#6A6965' }}>{formatTime(paper.createdAt)}</p>

                    {phase === 'progress' && (
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, padding: '3px 9px', borderRadius: 100, background: '#FFFBEB', border: '1px solid #FDE68A', fontSize: 11, fontWeight: 600, color: '#D97706', whiteSpace: 'nowrap', flexShrink: 0 }}>
                          <div style={{ width: 6, height: 6, border: '1.5px solid #D97706', borderTopColor: 'transparent', borderRadius: '50%', animation: 'spin 0.75s linear infinite', flexShrink: 0 }} />
                          처리 중 · {paper.status}
                        </span>
                        {/* 진행률 데이터 없음 — 불확정 애니메이션 (DESIGN.md D2) */}
                        <div style={{ flex: 1, minWidth: 100, maxWidth: 200, height: 5, background: '#EAE8E2', borderRadius: 3, overflow: 'hidden', position: 'relative' }}>
                          <div style={{ position: 'absolute', top: 0, left: 0, width: '35%', height: '100%', background: 'linear-gradient(90deg, #FBBF24, #F59E0B)', borderRadius: 3, animation: 'indeterminate 1.1s ease-in-out infinite' }} />
                        </div>
                      </div>
                    )}

                    {phase === 'completed' && (
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '3px 9px', borderRadius: 100, background: '#ECFDF5', border: '1px solid #A7F3D0', fontSize: 11, fontWeight: 600, color: '#059669', whiteSpace: 'nowrap' }}>
                        <svg width="8" height="8" viewBox="0 0 10 10" fill="none"><path d="M2 5l2.5 2.5 4-4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
                        완료
                      </span>
                    )}

                    {phase === 'failed' && (
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '3px 9px', borderRadius: 100, background: '#FEF2F2', border: '1px solid #FECACA', fontSize: 11, fontWeight: 600, color: '#DC2626', whiteSpace: 'nowrap' }}>
                        <svg width="8" height="8" viewBox="0 0 10 10" fill="none"><path d="M2.5 2.5l5 5M7.5 2.5l-5 5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" /></svg>
                        실패 · {paper.status}
                      </span>
                    )}
                  </div>

                  {/* Time + action */}
                  <div style={{ flexShrink: 0, display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 8, paddingTop: 2 }}>
                    <span style={{ fontSize: 12, color: '#A8A5A0' }}>{formatTime(paper.updatedAt)}</span>
                    {/* Task 8: COMPLETED 행에만 다운로드 버튼 (FT-002 Story 5) */}
                    {phase === 'completed' && (
                      <div style={{ display: 'flex', gap: 6 }}>
                        <button
                          onClick={() => handleDownload(paper.paperId)}
                          style={{ height: 26, padding: '0 10px', borderRadius: 6, border: '1px solid #A7F3D0', background: '#ECFDF5', color: '#059669', fontSize: 11, fontWeight: 600, cursor: 'pointer', fontFamily: 'inherit', display: 'flex', alignItems: 'center', gap: 4 }}
                        >
                          <svg width="10" height="10" viewBox="0 0 24 24" fill="none"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /><polyline points="7 11 12 16 17 11" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /><line x1="12" y1="16" x2="12" y2="4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" /></svg>
                          다운로드
                        </button>
                        {/* Task 3: COMPLETED 행 채팅 진입 (FT-007) — 다운로드 버튼과 동일 스타일 */}
                        <button
                          onClick={() => setChatPaper(paper)}
                          style={{ height: 26, padding: '0 10px', borderRadius: 6, border: '1px solid #BFDBFE', background: '#EFF6FF', color: '#1B4FD8', fontSize: 11, fontWeight: 600, cursor: 'pointer', fontFamily: 'inherit', display: 'flex', alignItems: 'center', gap: 4 }}
                        >
                          <svg width="10" height="10" viewBox="0 0 24 24" fill="none"><path d="M21 11.5a8.38 8.38 0 01-.9 3.8 8.5 8.5 0 01-7.6 4.7 8.38 8.38 0 01-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 01-.9-3.8 8.5 8.5 0 014.7-7.6 8.38 8.38 0 013.8-.9h.5a8.48 8.48 0 018 8v.5z" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>
                          채팅
                        </button>
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </main>

      {/* ── MODAL OVERLAY ── */}
      {isModalOpen && (
        <div onClick={closeModal} style={{ position: 'fixed', inset: 0, background: 'rgba(17,17,16,0.48)', zIndex: 50, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24 }}>
          <div onClick={stopPropagation} style={{ width: '100%', maxWidth: 452, background: '#fff', borderRadius: 16, boxShadow: '0 24px 80px rgba(0,0,0,0.18), 0 4px 16px rgba(0,0,0,0.08)', overflow: 'hidden', animation: 'slideUp 0.22s ease' }}>
            <div style={{ padding: '20px 20px 0' }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
                <h2 style={{ margin: 0, fontSize: 16, fontWeight: 700, color: '#111110', letterSpacing: '-0.4px' }}>논문 등록</h2>
                <button
                  onClick={closeModal}
                  disabled={isUploading}
                  style={{ width: 28, height: 28, borderRadius: 7, border: 'none', background: '#F5F4F1', display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: isUploading ? 'default' : 'pointer', color: '#6A6965', flexShrink: 0, opacity: isUploading ? 0.5 : 1 }}
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none"><path d="M18 6L6 18M6 6l12 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" /></svg>
                </button>
              </div>
              <div style={{ display: 'flex', borderBottom: '1px solid #EAE8E2', gap: 0 }}>
                <button style={{ padding: '7px 14px 11px', border: 'none', background: 'none', fontSize: 13, fontWeight: 600, color: '#1B4FD8', cursor: 'default', fontFamily: 'inherit', borderBottom: '2px solid #1B4FD8', marginBottom: -1, letterSpacing: '-0.2px', whiteSpace: 'nowrap' }}>PDF 업로드</button>
              </div>
            </div>

            <div style={{ padding: 20 }}>
              <input ref={fileInputRef} type="file" accept="application/pdf,.pdf" onChange={handleFileInputChange} style={{ display: 'none' }} />

              {/* IDLE: dropzone */}
              {showDropzone && (
                <div onDragOver={onDragOver} onDragLeave={onDragLeave} onDrop={onDrop} style={dropzoneStyle}>
                  <div style={{ width: 46, height: 46, background: '#EFF6FF', borderRadius: 11, display: 'flex', alignItems: 'center', justifyContent: 'center', border: '1px solid #BFDBFE' }}>
                    <svg width="21" height="21" viewBox="0 0 24 24" fill="none">
                      <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" stroke="#1D4ED8" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                      <polyline points="17 8 12 3 7 8" stroke="#1D4ED8" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                      <line x1="12" y1="3" x2="12" y2="15" stroke="#1D4ED8" strokeWidth="2" strokeLinecap="round" />
                    </svg>
                  </div>
                  <div style={{ textAlign: 'center', display: 'flex', flexDirection: 'column', gap: 4 }}>
                    <p style={{ margin: 0, fontSize: 14, fontWeight: 500, color: '#3A3935', letterSpacing: '-0.2px' }}>여기로 PDF를 끌어다 놓기</p>
                    <p style={{ margin: 0, fontSize: 13, color: '#A8A5A0' }}>또는</p>
                  </div>
                  <button
                    onClick={pickFile}
                    style={{ height: 34, padding: '0 18px', borderRadius: 7, border: '1.5px solid #1B4FD8', background: '#fff', fontSize: 13, fontWeight: 600, color: '#1B4FD8', cursor: 'pointer', fontFamily: 'inherit', letterSpacing: '-0.2px' }}
                  >
                    파일 선택
                  </button>
                  <p style={{ margin: 0, fontSize: 11, color: '#B8B5AE' }}>PDF 형식</p>
                </div>
              )}

              {/* FILE SELECTED */}
              {isFileSelected && selectedFile && (
                <div style={{ border: '1.5px dashed #D0CCC5', borderRadius: 10, padding: 16, display: 'flex', alignItems: 'center', gap: 12, background: '#FAFAF8' }}>
                  <div style={{ width: 38, height: 48, background: '#EFF6FF', borderRadius: 5, border: '1px solid #BFDBFE', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', flexShrink: 0, gap: 3, padding: 6 }}>
                    <span style={{ fontSize: 8, fontWeight: 800, color: '#1D4ED8' }}>PDF</span>
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <p style={{ margin: '0 0 2px', fontSize: 13, fontWeight: 600, color: '#111110', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', letterSpacing: '-0.2px' }}>{selectedFile.name}</p>
                    <p style={{ margin: 0, fontSize: 12, color: '#A8A5A0' }}>{formatBytes(selectedFile.size)}</p>
                  </div>
                  <button
                    onClick={clearFile}
                    style={{ width: 26, height: 26, borderRadius: 6, border: '1px solid #E2E0DA', background: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', color: '#A8A5A0', flexShrink: 0 }}
                  >
                    <svg width="11" height="11" viewBox="0 0 24 24" fill="none"><path d="M18 6L6 18M6 6l12 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" /></svg>
                  </button>
                </div>
              )}

              {/* UPLOADING to S3 — 실제 XHR progress (DESIGN.md D2) */}
              {isUploading && selectedFile && (
                <div style={{ border: '1.5px dashed #D0CCC5', borderRadius: 10, padding: 16, background: '#FAFAF8' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 14 }}>
                    <div style={{ width: 38, height: 48, background: '#EFF6FF', borderRadius: 5, border: '1px solid #BFDBFE', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', flexShrink: 0, gap: 3, padding: 6 }}>
                      <span style={{ fontSize: 8, fontWeight: 800, color: '#1D4ED8' }}>PDF</span>
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <p style={{ margin: '0 0 3px', fontSize: 13, fontWeight: 600, color: '#111110', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', letterSpacing: '-0.2px' }}>{selectedFile.name}</p>
                      <p style={{ margin: 0, fontSize: 12, color: '#6A6965' }}>S3에 업로드하는 중... {uploadPct}%</p>
                    </div>
                  </div>
                  <div style={{ height: 5, background: '#EAE8E2', borderRadius: 3, overflow: 'hidden' }}>
                    <div style={uploadBarStyle} />
                  </div>
                </div>
              )}

              {/* 에러 — 숨기지 않는다 (DESIGN.md D7) */}
              {error && (
                <div style={{ marginTop: 12, padding: '10px 12px', borderRadius: 8, background: '#FEF2F2', border: '1px solid #FECACA', color: '#B91C1C', fontSize: 12.5 }}>
                  {error}
                </div>
              )}
            </div>

            {showUploadButton && (
              <div style={{ padding: '0 20px 20px' }}>
                <button onClick={startUpload} disabled={!isFileSelected} style={uploadBtnStyle}>
                  업로드
                </button>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Task 3: 채팅 패널 (FT-007) */}
      {chatPaper && (
        <ChatPanel
          paperId={chatPaper.paperId}
          filename={chatPaper.filename}
          onClose={() => setChatPaper(null)}
        />
      )}
    </div>
  );
}
