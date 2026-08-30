import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TutorPanel } from './TutorPanel';
import { streamChatMessage } from '../../chat/chatStream';

vi.mock('../../chat/chatStream', () => ({ streamChatMessage: vi.fn() }));
vi.mock('../../api/chatSessions', () => ({
  listChatSessions: vi.fn(), listChatSessionMessages: vi.fn(), deleteChatSession: vi.fn(),
}));

// jsdom 미구현 — TutorPanel의 메시지 목록 스크롤 effect가 호출한다.
Element.prototype.scrollIntoView = vi.fn();

function renderPanel(locked: boolean) {
  const qc = new QueryClient();
  return render(
    <QueryClientProvider client={qc}>
      <TutorPanel
        paperId="p1"
        blocks={[]}
        pendingContext={null}
        onContextConsumed={() => {}}
        collapsed={false}
        onToggleCollapse={() => {}}
        queryLocked={locked}
        lockPlaceholder="금월 사용량 소진 · 9월 1일 초기화"
      />
    </QueryClientProvider>,
  );
}

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

describe('TutorPanel — AI 질의 한도 잠금', () => {
  it('잠금이면 입력창 disabled + 소진 placeholder + 보내기 비활성', () => {
    renderPanel(true);
    const textarea = screen.getByPlaceholderText('금월 사용량 소진 · 9월 1일 초기화') as HTMLTextAreaElement;
    expect(textarea.disabled).toBe(true);
    expect((screen.getByRole('button', { name: '질문 보내기' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('잠금이면 Enter로도 전송되지 않는다', () => {
    renderPanel(true);
    const textarea = screen.getByPlaceholderText('금월 사용량 소진 · 9월 1일 초기화');
    fireEvent.keyDown(textarea, { key: 'Enter' });
    expect(streamChatMessage).not.toHaveBeenCalled();
  });

  it('잠금이 아니면 기존 placeholder와 활성 상태', () => {
    renderPanel(false);
    const textarea = screen.getByPlaceholderText('AI에게 질문해보세요') as HTMLTextAreaElement;
    expect(textarea.disabled).toBe(false);
  });
});
