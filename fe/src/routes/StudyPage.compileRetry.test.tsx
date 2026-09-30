import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import StudyPage from './StudyPage';
import { getStatus, fetchPaperContent, retryPaper } from '../api/papers';
import { getMyPlan } from '../api/plan';
import { useTextSelection } from './study/useTextSelection';
import { ApiError, type PaperContentResponse, type PaperStatusResponse } from '../api/types';

vi.mock('../api/papers', () => ({ getStatus: vi.fn(), fetchPaperContent: vi.fn(), retryPaper: vi.fn() }));
vi.mock('../api/plan', () => ({ getMyPlan: vi.fn() }));
vi.mock('../account/AccountMenu', () => ({ AccountMenu: () => <div /> }));
vi.mock('./study/TutorPanel', () => ({ TutorPanel: () => <div data-testid="tutor-panel" /> }));
vi.mock('./study/useTextSelection', () => ({ useTextSelection: vi.fn() }));

// 이 jsdom에는 localStorage가 없다 — 메모리 스텁.
const store = new Map<string, string>();
vi.stubGlobal('localStorage', {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => { store.set(k, String(v)); },
  removeItem: (k: string) => { store.delete(k); },
  clear: () => store.clear(),
});

const RETRY_LABEL = '번역·지식 그래프 다시 만들기';

function content(): PaperContentResponse {
  return {
    paperId: 'p1',
    title: '제목',
    sourceLanguage: 'en',
    translationStatus: 'FAILED',
    schemaVersion: 1,
    blocks: [
      {
        blockId: 'b1',
        globalOrder: 1,
        label: 'text',
        headingLevel: null,
        sectionPath: [],
        content: { format: 'text', text: 'An attention function' },
      },
    ],
    assets: {},
    prerequisiteHighlights: [],
  };
}

function status(overrides: Partial<PaperStatusResponse>): PaperStatusResponse {
  return {
    paperId: 'p1', status: 'COMPLETED', translationStatus: 'FAILED', knowledgeGraphStatus: 'FAILED',
    updatedAt: '2026-09-09T00:00:00Z', failReason: null, compileRetryable: true, ...overrides,
  };
}

function renderStudy() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/study/p1']}>
        <Routes>
          <Route path="/study/:paperId" element={<StudyPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  localStorage.clear();
  vi.mocked(useTextSelection).mockReturnValue(null);
  vi.mocked(fetchPaperContent).mockResolvedValue(content());
  vi.mocked(getMyPlan).mockResolvedValue({
    plan: 'FREE',
    planExpiresAt: null,
    usage: {
      aiQuery: { mode: 'MONTHLY', limit: 10, used: 0, remaining: 10, resetAt: null },
      paperRegistration: { mode: 'MONTHLY', limit: 3, used: 0, remaining: 3, resetAt: null },
    },
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('StudyPage — 번역·지식 그래프 재시도', () => {
  it('재시도할 수 있으면 버튼이 보인다', async () => {
    vi.mocked(getStatus).mockResolvedValue(status({}));
    renderStudy();
    expect(await screen.findByRole('button', { name: RETRY_LABEL })).toBeTruthy();
  });

  it('재시도할 수 없으면 버튼이 없다', async () => {
    vi.mocked(getStatus).mockResolvedValue(status({ compileRetryable: false }));
    renderStudy();
    await screen.findByText('An attention function');
    expect(screen.queryByRole('button', { name: RETRY_LABEL })).toBeNull();
  });

  it('새 필드가 없는 응답이면 버튼이 없다', async () => {
    const { compileRetryable: _omit, failReason: _omit2, ...legacy } = status({});
    vi.mocked(getStatus).mockResolvedValue(legacy);
    renderStudy();
    await screen.findByText('An attention function');
    expect(screen.queryByRole('button', { name: RETRY_LABEL })).toBeNull();
  });

  it('누르면 재시도를 요청하고 응답 상태로 바뀌어 버튼이 사라진다', async () => {
    vi.mocked(getStatus).mockResolvedValue(status({}));
    vi.mocked(retryPaper).mockResolvedValue(status({
      translationStatus: 'PENDING', knowledgeGraphStatus: 'PENDING', compileRetryable: false,
    }));
    renderStudy();

    fireEvent.click(await screen.findByRole('button', { name: RETRY_LABEL }));

    await waitFor(() => expect(retryPaper).toHaveBeenCalledWith('p1'));
    await waitFor(() => expect(screen.queryByRole('button', { name: RETRY_LABEL })).toBeNull());
  });

  it('거절되면 사유를 알리고 상태를 다시 받는다', async () => {
    vi.mocked(getStatus).mockResolvedValue(status({}));
    vi.mocked(retryPaper).mockRejectedValue(new ApiError('x', 'RETRY_LIMIT_EXCEEDED', 409));
    renderStudy();

    fireEvent.click(await screen.findByRole('button', { name: RETRY_LABEL }));

    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toBe('번역과 지식 그래프를 더 이상 다시 만들 수 없습니다'));
    const button = screen.getByRole('button', { name: RETRY_LABEL }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    await waitFor(() => expect(vi.mocked(getStatus).mock.calls.length).toBeGreaterThan(1));
  });
});
