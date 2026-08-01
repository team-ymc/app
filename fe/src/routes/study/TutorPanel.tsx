// 이식: project-docs/design/v1/Paper Study Page.dc.html — R5 AI chat panel, history 드롭다운 포함
// (clock-counter-clockwise IconButton + historyOpen 드롭다운, L138-146). 목업 handleDocMouseDown(L360-370)은
// selectionToolbar/translationPopup/askPopup만 닫고 historyOpen은 다루지 않는다 — 그대로 이식해 바깥 클릭
// 자동 닫힘을 추가하지 않는다(YMC-260 T2 brief).
// wiring은 구 fe/src/chat/ChatPanel.jsx(삭제 예정, 참조용)의 useReducer(chatReducer)/streamChatMessage/
// AbortController 언마운트 처리·재시도 로직을 그대로 따르되, TS화된 ../../chat/chatState·chatStream(Task 4)을 쓴다.
import { useEffect, useRef, useState, useReducer } from 'react';
import type { CSSProperties } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Quotes, X } from '@phosphor-icons/react';
import { chatReducer, initialChatState } from '../../chat/chatState';
import { streamChatMessage } from '../../chat/chatStream';
import { listChatSessions, listChatSessionMessages } from '../../api/chatSessions';
import { ApiError } from '../../api/types';
import { PaperMarkdown } from '../../markdown/PaperMarkdown';
import { TutorNotebook } from '../../design/components/TutorNotebook';
import { NotebookSection } from '../../design/components/NotebookSection';
import { StudentMessage } from '../../design/components/StudentMessage';
import { IconButton } from '../../design/components/IconButton';

// controller 승인 확장(brief): pendingContext.mode==='new'면 전송 전에 dispatch({type:'reset'})해서
// ask popup의 "새 채팅" 선택을 지원한다. mode 생략 시 기존 세션에 이어 붙인다("현재 채팅").
export interface TutorPanelPendingContext {
  text: string;
  mode?: 'current' | 'new';
}

export interface TutorPanelProps {
  paperId: string;
  pendingContext: TutorPanelPendingContext | null;
  onContextConsumed: () => void;
  collapsed: boolean;
  onToggleCollapse: () => void;
}

const rootStyle: CSSProperties = {
  height: '100%',
  display: 'flex',
  flexDirection: 'column',
  background: 'var(--color-bg-paper)',
  borderLeft: '1px solid var(--color-border)',
  fontFamily: 'var(--font-sans)',
  overflow: 'hidden',
  boxSizing: 'border-box',
};

const contextChipStyle: CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  background: 'var(--color-bg-surface)',
  border: '1px solid var(--color-border)',
  borderRadius: 'var(--radius-pill)',
  padding: '5px 8px 5px 10px',
  fontFamily: 'var(--font-sans)',
  fontSize: 12,
  color: 'var(--color-text-body)',
  cursor: 'pointer',
};

const contextTooltipStyle: CSSProperties = {
  position: 'absolute',
  bottom: 'calc(100% + 8px)',
  left: 0,
  width: 300,
  maxWidth: '70vw',
  boxSizing: 'border-box',
  background: 'var(--color-bg-paper)',
  border: '1px solid var(--color-border)',
  borderRadius: 8,
  boxShadow: 'var(--shadow-menu)',
  padding: '12px 14px',
  fontFamily: 'var(--font-serif)',
  fontSize: 13,
  lineHeight: 1.6,
  color: 'var(--color-text-body)',
  zIndex: 90,
};

const historyDropdownStyle: CSSProperties = {
  position: 'absolute',
  top: 42,
  left: 0,
  width: 220,
  background: 'var(--color-bg-paper)',
  border: '1px solid var(--color-border)',
  borderRadius: 8,
  boxShadow: 'var(--shadow-menu)',
  padding: 6,
  zIndex: 10,
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
};

const historyStatusStyle: CSSProperties = {
  padding: '9px 10px',
  fontFamily: 'var(--font-sans)',
  fontSize: 13,
  color: 'var(--color-text-muted)',
};

function HistoryItemButton({ title, onClick }: { title: string; onClick: () => void }) {
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
        borderRadius: 6,
        fontFamily: 'var(--font-sans)',
        fontSize: 13,
        color: 'var(--color-text-body)',
        cursor: 'pointer',
      }}
    >
      {title}
    </button>
  );
}

function dotStyle(delay: number): CSSProperties {
  return {
    width: 6,
    height: 6,
    borderRadius: '50%',
    background: 'var(--color-text-muted)',
    display: 'inline-block',
    animation: 'pt-tutor-dot-bounce 1.2s infinite ease-in-out',
    animationDelay: `${delay}s`,
  };
}

// 계약: content는 자유 텍스트 — BE 변경 없이 FE 포맷팅으로 인용 구절을 붙인다 (brief Step 1).
function buildContent(context: TutorPanelPendingContext | null, question: string): string {
  if (!context) return question;
  return `다음은 논문에서 선택한 구절이다:\n> ${context.text}\n\n${question}`;
}

export function TutorPanel({ paperId, pendingContext, onContextConsumed, collapsed, onToggleCollapse }: TutorPanelProps) {
  const [state, dispatch] = useReducer(chatReducer, initialChatState);
  const [input, setInput] = useState('');
  const [composerFocused, setComposerFocused] = useState(false);
  const [contextTooltipOpen, setContextTooltipOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyLoadError, setHistoryLoadError] = useState<string | null>(null);

  const queryClient = useQueryClient();
  // 드롭다운이 열릴 때만 조회 — 열 때마다 refetchOnMount 기본값으로 신선하게 받는다.
  const sessionsQuery = useQuery({
    queryKey: ['chat-sessions', paperId],
    queryFn: () => listChatSessions(paperId),
    enabled: historyOpen,
  });

  const abortRef = useRef<AbortController | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const lastResetContextRef = useRef<TutorPanelPendingContext | null>(null);
  // 히스토리 로드 레이스 가드 — reset·전송 시작이 세대를 올려서, 그 이후 도착하는 stale
  // listChatSessionMessages 응답(성공/실패 모두)이 새 상태를 덮어쓰지 못하게 한다.
  const historyLoadSeq = useRef(0);

  useEffect(() => () => abortRef.current?.abort(), []); // 언마운트 — BE는 저장을 완주한다

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [state.messages]);

  // ask popup "새 채팅"(mode:'new') 지원 — 같은 pendingContext 객체에 대해 한 번만 reset한다.
  // 참조 구현(mockup newConversation의 clearInterval)처럼 진행 중 스트림부터 끊고 reset한다 —
  // 그러지 않으면 이전 스트림의 delta/completed가 초기화된 state에 뒤늦게 dispatch될 수 있다.
  // SelectionLayer의 "AI에게 질문"이 pendingContext를 세팅하면 입력창에 포커스한다(FT-006 Story 4).
  // StudyPage가 같은 이벤트 핸들러에서 collapsed도 함께 해제하므로, 이 effect가 실행되는 시점에는
  // 이미 펼쳐진 상태로 커밋되어 textareaRef가 존재한다.
  useEffect(() => {
    setContextTooltipOpen(false);
    if (pendingContext && pendingContext.mode === 'new' && lastResetContextRef.current !== pendingContext) {
      historyLoadSeq.current += 1; // 진행 중인 히스토리 로드를 무효화 — reset 후 stale 응답이 덮어쓰지 못하게
      abortRef.current?.abort();
      dispatch({ type: 'reset' });
    }
    lastResetContextRef.current = pendingContext;
    if (pendingContext) textareaRef.current?.focus();
  }, [pendingContext]);

  function run(clientMessageId: string, content: string, resend: boolean) {
    dispatch({ type: 'send', clientMessageId, content, resend });
    const controller = new AbortController();
    abortRef.current = controller;
    streamChatMessage({
      paperId,
      sessionId: state.sessionId,
      clientMessageId,
      content,
      signal: controller.signal,
      onEvent: (e) => {
        // 전송 완료 시 세션 목록을 무효화 — 새 세션이 다음에 드롭다운을 열 때 반영되게 한다.
        if (e.type === 'completed') queryClient.invalidateQueries({ queryKey: ['chat-sessions', paperId] });
        dispatch(e);
      },
    });
  }

  function handleToggleHistory() {
    setHistoryLoadError(null);
    setHistoryOpen((v) => !v);
  }

  async function handleSelectHistorySession(sessionId: string) {
    setHistoryLoadError(null);
    const wasStreaming = state.streaming; // abort는 dispatch를 하지 않으므로 클릭 시점에 미리 잡아둔다
    abortRef.current?.abort();
    const gen = ++historyLoadSeq.current;
    try {
      const items = await listChatSessionMessages(paperId, sessionId);
      if (gen !== historyLoadSeq.current) return; // stale — 그 사이 reset·새 전송이 있었음
      dispatch({ type: 'historyLoaded', sessionId, items });
      setHistoryOpen(false);
    } catch (e) {
      if (gen !== historyLoadSeq.current) return; // stale — 이미 다른 상호작용이 상태를 대체함
      setHistoryLoadError(e instanceof ApiError ? e.message : '대화를 불러오지 못했습니다.');
      if (wasStreaming) {
        // abort만으로는 streaming=true가 풀리지 않는다 — 결과 미상 실패로 명시해 입력창 고착을 막고
        // pending을 유지한다(재시도 가능, 기존 STREAM_INTERRUPTED 의미론과 동일).
        dispatch({ type: 'failed', confirmed: false, code: 'STREAM_INTERRUPTED', message: '연결이 중단되었습니다.', retryable: true });
      }
    }
  }

  function resetComposerHeight() {
    if (textareaRef.current) textareaRef.current.style.height = '';
  }

  function handleInputChange(e: React.ChangeEvent<HTMLTextAreaElement>) {
    setInput(e.target.value);
    const el = e.target;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 150)}px`;
  }

  function handleSend() {
    const question = input.trim();
    if (!question || state.streaming) return;
    const content = buildContent(pendingContext, question);
    setInput('');
    resetComposerHeight();
    historyLoadSeq.current += 1; // 전송 시작 — 진행 중인 히스토리 로드를 무효화
    run(crypto.randomUUID(), content, false);
    if (pendingContext) onContextConsumed();
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  function handleNewConversation() {
    historyLoadSeq.current += 1; // 진행 중인 히스토리 로드를 무효화 — reset 후 stale 응답이 덮어쓰지 못하게
    abortRef.current?.abort(); // 목업 newConversation의 clearInterval과 같은 결 — reset 전에 진행 중 스트림을 끊는다
    dispatch({ type: 'reset' });
  }

  // 재시도 계약(설계 §3, openapi ChatStreamErrorDetail): retryable=true는 항상 재시도 가능하다.
  // - 결과 미상 실패(pending 유지): 같은 clientMessageId로 재전송(멱등).
  // - 확인된 실패(pending 비워짐, 예: AI_RUN_FAILED): 새 clientMessageId로 재시도 — 마지막 user
  //   메시지 content를 재사용한다.
  function handleRetry() {
    if (state.streaming) return;
    if (state.pending) {
      run(state.pending.clientMessageId, state.pending.content, true);
      return;
    }
    const lastUser = [...state.messages].reverse().find((m) => m.role === 'user');
    if (lastUser) run(crypto.randomUUID(), lastUser.content, true);
  }

  if (collapsed) {
    return (
      <div style={rootStyle}>
        <div style={{ paddingTop: 12, display: 'flex', justifyContent: 'center' }}>
          <IconButton icon="sidebar-simple" label="AI 튜터 열기" size={36} onClick={onToggleCollapse} />
        </div>
      </div>
    );
  }

  const lastMessage = state.messages[state.messages.length - 1];
  // retryable=true면 pending 유무와 무관하게 재시도 가능(계약 ChatStreamErrorDetail) — confirmed
  // 실패(pending 비워짐)는 handleRetry가 새 clientMessageId로 처리한다.
  const canRetry =
    !!lastMessage &&
    lastMessage.role === 'assistant' &&
    lastMessage.status === 'FAILED' &&
    lastMessage.error?.retryable !== false;

  const composer = (
    <div style={{ position: 'relative' }}>
      {pendingContext ? (
        <div style={{ marginBottom: 8, position: 'relative', display: 'inline-block' }}>
          <div onClick={() => setContextTooltipOpen((v) => !v)} style={contextChipStyle}>
            <Quotes size={12} color="var(--color-text-muted)" />
            <span>인용</span>
            <button
              onClick={(e) => {
                e.stopPropagation();
                onContextConsumed();
              }}
              aria-label="인용 제거"
              style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--color-text-muted)', padding: 0, display: 'flex', alignItems: 'center', marginLeft: 2 }}
            >
              <X size={12} />
            </button>
          </div>
          {contextTooltipOpen ? (
            <div style={contextTooltipStyle}>
              <button
                onClick={() => setContextTooltipOpen(false)}
                aria-label="닫기"
                style={{ position: 'absolute', top: 10, right: 10, background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--color-text-muted)', padding: 2, display: 'flex', alignItems: 'center' }}
              >
                <X size={12} />
              </button>
              <div style={{ paddingRight: 20, paddingTop: 2, maxHeight: 160, overflowY: 'auto' }}>{pendingContext.text}</div>
            </div>
          ) : null}
        </div>
      ) : null}
      <div
        style={{
          display: 'flex',
          alignItems: 'flex-end',
          gap: 4,
          border: `1px solid ${composerFocused ? 'var(--color-primary)' : 'var(--color-border)'}`,
          borderRadius: 14,
          background: 'var(--color-bg-paper)',
          padding: '5px 5px 5px 14px',
          transition: 'border-color 150ms ease',
        }}
      >
        <textarea
          ref={textareaRef}
          value={input}
          onChange={handleInputChange}
          onKeyDown={handleKeyDown}
          onFocus={() => setComposerFocused(true)}
          onBlur={() => setComposerFocused(false)}
          placeholder="AI에게 질문해보세요"
          rows={1}
          style={{
            flex: 1,
            minWidth: 0,
            resize: 'none',
            border: 'none',
            outline: 'none',
            background: 'transparent',
            padding: '8px 0',
            fontFamily: 'var(--font-sans)',
            fontSize: 15,
            lineHeight: 1.5,
            color: 'var(--color-text-body)',
            minHeight: 22,
            maxHeight: 150,
            boxSizing: 'border-box',
          }}
        />
        <IconButton
          icon="paper-plane-tilt"
          label="질문 보내기"
          size={34}
          onClick={handleSend}
          disabled={state.streaming}
          style={{ flexShrink: 0, marginBottom: 2 }}
        />
      </div>
    </div>
  );

  return (
    <div style={rootStyle}>
      <style>{`
        @keyframes pt-tutor-dot-bounce { 0%,80%,100% { transform: translateY(0); opacity: .4; } 40% { transform: translateY(-4px); opacity: 1; } }
      `}</style>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '12px 14px',
          borderBottom: '1px solid var(--color-border)',
          flexShrink: 0,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 2 }}>
          <div style={{ position: 'relative' }}>
            <IconButton icon="clock-counter-clockwise" label="이전 대화 기록" size={36} onClick={handleToggleHistory} />
            {historyOpen ? (
              <div style={historyDropdownStyle}>
                {sessionsQuery.isLoading ? (
                  <div style={historyStatusStyle}>불러오는 중…</div>
                ) : sessionsQuery.isError ? (
                  <div style={{ ...historyStatusStyle, color: 'var(--color-danger)' }}>
                    {sessionsQuery.error instanceof ApiError ? sessionsQuery.error.message : '대화 목록을 불러오지 못했습니다.'}
                  </div>
                ) : sessionsQuery.data && sessionsQuery.data.length === 0 ? (
                  <div style={historyStatusStyle}>저장된 대화가 없습니다</div>
                ) : (
                  sessionsQuery.data?.map((s) => (
                    <HistoryItemButton key={s.sessionId} title={s.title} onClick={() => handleSelectHistorySession(s.sessionId)} />
                  ))
                )}
                {historyLoadError ? (
                  <div style={{ ...historyStatusStyle, color: 'var(--color-danger)' }}>{historyLoadError}</div>
                ) : null}
              </div>
            ) : null}
          </div>
          <IconButton icon="note-pencil" label="새 대화" size={36} onClick={handleNewConversation} />
        </div>
        <IconButton icon="sidebar-simple" label="AI 튜터 접기" size={36} onClick={onToggleCollapse} />
      </div>

      <div style={{ flex: 1, minHeight: 0 }}>
        <TutorNotebook style={{ borderLeft: 'none' }} composer={composer}>
          {state.messages.map((m) => {
            if (m.role === 'user') {
              return (
                <div key={m.key} style={{ display: 'flex', justifyContent: 'flex-end' }}>
                  <StudentMessage
                    style={{
                      background: 'var(--color-bg-surface)',
                      color: 'var(--color-text-body)',
                      border: 'none',
                      borderRadius: '14px 14px 4px 14px',
                      padding: '11px 15px',
                    }}
                  >
                    {m.content}
                  </StudentMessage>
                </div>
              );
            }

            // 바운스는 "streaming 중 + 이 메시지가 아직 델타를 못 받은 GENERATING"일 때만. 그 외
            // GENERATING(히스토리에서 로드된, resume 미지원 — 계약 상태 보존)은 muted 안내 텍스트.
            // content===''을 함께 요구해 duplicate 안내문(GENERATING+안내 텍스트, reducer 'duplicate'
            // 분기)이나 스트리밍 중 델타 수신분(GENERATING+부분 텍스트)이 히스토리 문구로 오분류되지
            // 않게 한다 — 히스토리 로드분만 content가 비어 있다(계약: GENERATING은 partial 저장 안 함).
            const bouncing = m.status === 'GENERATING' && m.content === '' && state.streaming;
            const historyGenerating = m.status === 'GENERATING' && m.content === '' && !state.streaming;
            return (
              <div key={m.key}>
                {bouncing ? (
                  <div style={{ display: 'flex', gap: 4, padding: '6px 0' }}>
                    <span style={dotStyle(0)} />
                    <span style={dotStyle(0.15)} />
                    <span style={dotStyle(0.3)} />
                  </div>
                ) : historyGenerating ? (
                  <div style={{ fontSize: 13, color: 'var(--color-text-muted)', padding: '6px 0' }}>
                    답변을 생성하던 중이던 메시지입니다 — 잠시 후 다시 열어 확인해 주세요.
                  </div>
                ) : (
                  <div style={{ borderLeft: '2px solid var(--color-accent-brass)', paddingLeft: 12 }}>
                    <NotebookSection>
                      <PaperMarkdown>{m.content}</PaperMarkdown>
                    </NotebookSection>
                  </div>
                )}
                {m.status === 'FAILED' ? (
                  <div style={{ marginTop: 6, fontSize: 13, color: 'var(--color-danger)', display: 'flex', gap: 8, alignItems: 'center' }}>
                    <span>{m.error?.message || '답변을 받지 못했습니다.'}</span>
                    {canRetry ? (
                      <button
                        onClick={handleRetry}
                        style={{
                          border: '1px solid var(--color-danger)',
                          background: 'none',
                          color: 'var(--color-danger)',
                          borderRadius: 6,
                          padding: '2px 10px',
                          cursor: 'pointer',
                          fontSize: 12,
                        }}
                      >
                        다시 시도
                      </button>
                    ) : null}
                  </div>
                ) : null}
              </div>
            );
          })}
          <div ref={bottomRef} />
        </TutorNotebook>
      </div>
    </div>
  );
}
