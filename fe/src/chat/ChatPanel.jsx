// fe/src/chat/ChatPanel.jsx
// AI 튜터 채팅 패널 (FT-007, 기능 중심 최소 UI — 설계 §5). 로직은 chatState/chatStream에
// 있고 여기는 wiring만. markdown 렌더링은 follow-up — content는 pre-wrap plain text다.
import { useEffect, useReducer, useRef, useState } from 'react';
import { chatReducer, initialChatState } from './chatState';
import { streamChatMessage } from './chatStream';

export default function ChatPanel({ paperId, filename, onClose }) {
  const [state, dispatch] = useReducer(chatReducer, initialChatState);
  const [input, setInput] = useState('');
  const abortRef = useRef(null);
  const bottomRef = useRef(null);

  useEffect(() => () => abortRef.current?.abort(), []); // 언마운트 — BE는 저장을 완주한다
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [state.messages]);

  const run = (clientMessageId, content, resend) => {
    dispatch({ type: 'send', clientMessageId, content, resend });
    const controller = new AbortController();
    abortRef.current = controller;
    streamChatMessage({
      paperId, sessionId: state.sessionId, clientMessageId, content,
      signal: controller.signal, onEvent: dispatch,
    });
  };

  const handleSend = () => {
    const content = input.trim();
    if (!content || state.streaming) return;
    setInput('');
    run(crypto.randomUUID(), content, false);
  };

  // 재시도: 결과 미상(pending 유지)이면 같은 id로 재전송, 확인된 실패면 새 id (설계 §3)
  const handleRetry = (failedMessage) => {
    if (state.streaming) return;
    if (state.pending) {
      run(state.pending.clientMessageId, state.pending.content, true);
    } else {
      const lastUser = [...state.messages].reverse().find((m) => m.role === 'user');
      if (lastUser) run(crypto.randomUUID(), lastUser.content, false);
    }
  };

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(17,17,16,0.35)', display: 'flex', justifyContent: 'flex-end', zIndex: 40 }} onClick={onClose}>
      <div style={{ width: 'min(480px, 100%)', background: '#ffffff', display: 'flex', flexDirection: 'column', boxShadow: '-8px 0 24px rgba(0,0,0,0.12)' }} onClick={(e) => e.stopPropagation()}>
        <header style={{ padding: '14px 20px', borderBottom: '1px solid #EAE8E2', display: 'flex', alignItems: 'center', gap: 8 }}>
          <strong style={{ fontSize: 15, color: '#111110', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
            {filename} — AI 튜터
          </strong>
          <button onClick={onClose} style={{ border: 'none', background: 'none', cursor: 'pointer', fontSize: 18, color: '#6B7280' }}>×</button>
        </header>

        <div style={{ flex: 1, overflowY: 'auto', padding: 20, display: 'flex', flexDirection: 'column', gap: 12, background: '#F8F7F4' }}>
          {state.messages.length === 0 && (
            <p style={{ color: '#6B7280', fontSize: 14, textAlign: 'center', marginTop: 40 }}>
              이 논문에 대해 무엇이든 질문해 보세요.
            </p>
          )}
          {state.messages.map((m) => (
            <div key={m.key} style={{ alignSelf: m.role === 'user' ? 'flex-end' : 'flex-start', maxWidth: '85%' }}>
              <div style={{
                padding: '10px 14px', borderRadius: 12, fontSize: 14, lineHeight: 1.6,
                whiteSpace: 'pre-wrap', wordBreak: 'break-word',
                background: m.role === 'user' ? '#111110' : '#ffffff',
                color: m.role === 'user' ? '#ffffff' : '#111110',
                border: m.role === 'user' ? 'none' : '1px solid #EAE8E2',
              }}>
                {m.content || (m.status === 'GENERATING' ? '생각하는 중…' : '')}
              </div>
              {m.role === 'assistant' && m.status === 'FAILED' && (
                <div style={{ marginTop: 6, fontSize: 13, color: '#B91C1C', display: 'flex', gap: 8, alignItems: 'center' }}>
                  <span>{m.error?.message || '답변을 받지 못했습니다.'}</span>
                  {m.error?.retryable !== false && (
                    <button onClick={() => handleRetry(m)} style={{ border: '1px solid #B91C1C', background: 'none', color: '#B91C1C', borderRadius: 6, padding: '2px 10px', cursor: 'pointer', fontSize: 12 }}>
                      재시도
                    </button>
                  )}
                </div>
              )}
            </div>
          ))}
          <div ref={bottomRef} />
        </div>

        <footer style={{ padding: 16, borderTop: '1px solid #EAE8E2', display: 'flex', gap: 8 }}>
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(); }
            }}
            placeholder={state.streaming ? '답변을 생성하는 중…' : '질문을 입력하세요 (Enter로 전송)'}
            disabled={state.streaming}
            rows={2}
            style={{ flex: 1, resize: 'none', border: '1px solid #EAE8E2', borderRadius: 8, padding: '10px 12px', fontSize: 14, fontFamily: 'inherit' }}
          />
          <button
            onClick={handleSend}
            disabled={state.streaming || !input.trim()}
            style={{ border: 'none', borderRadius: 8, padding: '0 18px', background: state.streaming || !input.trim() ? '#D1D5DB' : '#111110', color: '#ffffff', cursor: state.streaming ? 'default' : 'pointer', fontWeight: 600 }}
          >
            전송
          </button>
        </footer>
      </div>
    </div>
  );
}
