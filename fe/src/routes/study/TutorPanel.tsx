// 이식: project-docs/design/v1/Paper Study Page.dc.html — R5 AI chat panel.
// history 관련 마크업(historyOpen/toggleHistory/historyList)은 이식하지 않는다 — 과거 세션·메시지
// 목록을 조회할 계약이 없다 (픽션 조정 2호, spec §8). "새 대화" 버튼만 남긴다.
// wiring은 구 fe/src/chat/ChatPanel.jsx(삭제 예정, 참조용)의 useReducer(chatReducer)/streamChatMessage/
// AbortController 언마운트 처리·재시도 로직을 그대로 따르되, TS화된 ../../chat/chatState·chatStream(Task 4)을 쓴다.
import { useEffect, useRef, useState, useReducer } from 'react';
import type { CSSProperties } from 'react';
import { Quotes, X } from '@phosphor-icons/react';
import { chatReducer, initialChatState } from '../../chat/chatState';
import { streamChatMessage } from '../../chat/chatStream';
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

  const abortRef = useRef<AbortController | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const lastResetContextRef = useRef<TutorPanelPendingContext | null>(null);

  useEffect(() => () => abortRef.current?.abort(), []); // 언마운트 — BE는 저장을 완주한다

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [state.messages]);

  // ask popup "새 채팅"(mode:'new') 지원 — 같은 pendingContext 객체에 대해 한 번만 reset한다.
  // 참조 구현(mockup newConversation의 clearInterval)처럼 진행 중 스트림부터 끊고 reset한다 —
  // 그러지 않으면 이전 스트림의 delta/completed가 초기화된 state에 뒤늦게 dispatch될 수 있다.
  useEffect(() => {
    setContextTooltipOpen(false);
    if (pendingContext && pendingContext.mode === 'new' && lastResetContextRef.current !== pendingContext) {
      abortRef.current?.abort();
      dispatch({ type: 'reset' });
    }
    lastResetContextRef.current = pendingContext;
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
      onEvent: dispatch,
    });
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
        <IconButton icon="note-pencil" label="새 대화" size={36} onClick={handleNewConversation} />
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

            const loading = m.status === 'GENERATING' && m.content === '';
            return (
              <div key={m.key}>
                {loading ? (
                  <div style={{ display: 'flex', gap: 4, padding: '6px 0' }}>
                    <span style={dotStyle(0)} />
                    <span style={dotStyle(0.15)} />
                    <span style={dotStyle(0.3)} />
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
