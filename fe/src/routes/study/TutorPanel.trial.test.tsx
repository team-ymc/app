import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { TutorPanel } from './TutorPanel';
import { TrialModeProvider } from '../../trial/TrialMode';
import { streamChatMessage } from '../../chat/chatStream';
import { useAuth } from '../../auth/AuthContext';

vi.mock('../../chat/chatStream', () => ({ streamChatMessage: vi.fn() }));
vi.mock('../../api/chatSessions', () => ({
  listChatSessions: vi.fn(), listChatSessionMessages: vi.fn(), deleteChatSession: vi.fn(),
}));
vi.mock('../../auth/AuthContext', () => ({ useAuth: vi.fn() }));
vi.mock('../../analytics/analytics', () => ({ track: vi.fn() }));

Element.prototype.scrollIntoView = vi.fn();

function renderTrialPanel() {
  vi.mocked(useAuth).mockReturnValue({ status: 'guest', user: null, initialError: null, startLogin: vi.fn(), signOut: vi.fn() });
  const qc = new QueryClient();
  return render(
    <MemoryRouter>
      <QueryClientProvider client={qc}>
        <TrialModeProvider>
          <TutorPanel
            paperId="p1" blocks={[]} attachments={[]} attachEvent={null}
            onRemoveAttachment={() => {}} onAttachmentsConsumed={() => {}} attachNotice={null}
            collapsed={false} onToggleCollapse={() => {}}
          />
        </TrialModeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

describe('TutorPanel — 체험 모드', () => {
  it('질문을 보내는 순간 가입 모달이 뜨고 스트림은 시작하지 않으며 입력은 남는다', () => {
    renderTrialPanel();
    const textarea = screen.getByPlaceholderText('AI에게 질문해보세요') as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: 'Transformer가 빠른 이유는?' } });
    fireEvent.keyDown(textarea, { key: 'Enter' });
    expect(screen.getByRole('dialog', { name: '가입하고 무료로 이용해보세요.' })).toBeTruthy();
    expect(streamChatMessage).not.toHaveBeenCalled();
    expect(textarea.value).toBe('Transformer가 빠른 이유는?');
  });

  it('대화 기록·새 대화 버튼 대신 AI 튜터 라벨만 보인다', () => {
    renderTrialPanel();
    expect(screen.queryByRole('button', { name: '이전 대화 기록' })).toBeNull();
    expect(screen.queryByRole('button', { name: '새 대화' })).toBeNull();
    expect(screen.getByText('AI 튜터')).toBeTruthy();
  });
});
