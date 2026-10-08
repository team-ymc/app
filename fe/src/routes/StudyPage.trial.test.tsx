import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router';
import StudyPage from './StudyPage';
import { TrialModeProvider } from '../trial/TrialMode';
import { getStatus, fetchPaperContent, fetchTrialPaperContent } from '../api/papers';
import { getMyPlan } from '../api/plan';
import { useAuth } from '../auth/AuthContext';
import type { PaperContentResponse } from '../api/types';

vi.mock('../api/papers', () => ({ getStatus: vi.fn(), fetchPaperContent: vi.fn(), fetchTrialPaperContent: vi.fn(), createPrerequisiteDefinition: vi.fn(), createTrialPrerequisiteDefinition: vi.fn() }));
vi.mock('../api/plan', () => ({ getMyPlan: vi.fn() }));
vi.mock('../auth/AuthContext', () => ({ useAuth: vi.fn() }));
vi.mock('../account/AccountMenu', () => ({ AccountMenu: () => <div data-testid="account-menu" /> }));
vi.mock('./study/TutorPanel', () => ({ TutorPanel: () => <div data-testid="tutor-panel" /> }));
vi.mock('./study/useTextSelection', () => ({ useTextSelection: vi.fn() }));
vi.mock('../analytics/analytics', () => ({ track: vi.fn() }));

const store = new Map<string, string>();
vi.stubGlobal('localStorage', {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => { store.set(k, String(v)); },
  removeItem: (k: string) => { store.delete(k); },
  clear: () => store.clear(),
});

function content(): PaperContentResponse {
  return {
    paperId: 'p1', title: '체험 논문', sourceLanguage: 'en', translationStatus: 'READY', schemaVersion: 1,
    blocks: [{ blockId: 'b1', globalOrder: 1, label: 'text', headingLevel: null, sectionPath: [], content: { format: 'text', text: 'An attention function', textKor: '어텐션 함수' } }],
    assets: {},
    prerequisiteHighlights: [{ highlightId: 'h1', blockId: 'b1', startOffset: 3, endOffset: 12, text: 'attention' }],
  };
}

function renderTrialStudy() {
  vi.mocked(useAuth).mockReturnValue({ status: 'guest', user: null, initialError: null, startLogin: vi.fn(), signOut: vi.fn() });
  vi.mocked(fetchTrialPaperContent).mockResolvedValue(content());
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/try/papers/p1']}>
        <Routes>
          <Route element={<TrialModeProvider><Outlet /></TrialModeProvider>}>
            <Route path="/try/papers/:paperId" element={<StudyPage />} />
          </Route>
          <Route path="/try" element={<div>trial home</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => { vi.clearAllMocks(); store.clear(); });
afterEach(cleanup);

describe('StudyPage — 체험 모드', () => {
  it('체험 경로로만 본문을 받고 상태·플랜·로그인 경로는 부르지 않는다', async () => {
    renderTrialStudy();
    await screen.findAllByText('체험 논문');
    expect(fetchTrialPaperContent).toHaveBeenCalledWith('p1');
    // 본문 응답의 번역 상태가 READY로 '바뀌는' 것은 폴링 전환이 아니다 — 재요청하지 않는다.
    await new Promise((r) => setTimeout(r, 50));
    expect(fetchTrialPaperContent).toHaveBeenCalledTimes(1);
    expect(fetchPaperContent).not.toHaveBeenCalled();
    expect(getStatus).not.toHaveBeenCalled();
    expect(getMyPlan).not.toHaveBeenCalled();
  });

  it('선행지식이 켜진 채 열리고, 상단 바는 체험용 뒤로가기와 로그인 버튼을 보인다', async () => {
    renderTrialStudy();
    await screen.findAllByText('체험 논문');
    expect(screen.getByRole('switch', { name: '선행지식' }).getAttribute('aria-checked')).toBe('true');
    expect(screen.getByRole('link', { name: /다른 논문 보기/ }).getAttribute('href')).toBe('/try');
    expect(screen.getByRole('link', { name: '본문' }).getAttribute('href')).toBe('/try/papers/p1');
    expect(screen.getByRole('button', { name: '로그인' })).toBeTruthy();
    expect(screen.queryByTestId('account-menu')).toBeNull();
    await waitFor(() => expect(screen.getByRole('link', { name: '지식 그래프' }).getAttribute('href')).toBe('/try/papers/p1/graph'));
  });
});
