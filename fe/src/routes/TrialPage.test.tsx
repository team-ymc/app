import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import TrialPage from './TrialPage';
import { useAuth } from '../auth/AuthContext';
import { track } from '../analytics/analytics';
import { pickTodaysPaper, trialPapers } from '../trial/trialPapers';

const navigateMock = vi.fn();
const startLoginMock = vi.fn();
vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useNavigate: () => navigateMock,
}));
vi.mock('../auth/AuthContext', () => ({ useAuth: vi.fn() }));
vi.mock('../nav/GlobalNav', () => ({ GlobalNav: () => <nav data-testid="global-nav" /> }));
vi.mock('../analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('../trial/trialPapers', async (importOriginal) => {
  const mod = await importOriginal<typeof import('../trial/trialPapers')>();
  mod.TRIAL_PAPER_IDS['test.local'] = { game: 'game-1', money: 'money-1', space: 'space-1', sleep: 'sleep-1' };
  return { ...mod, trialPapers: () => mod.trialPapers('test.local') };
});

const useAuthMock = vi.mocked(useAuth);
function mockAuth(status: 'guest' | 'authed' | 'loading') {
  useAuthMock.mockReturnValue({ status, user: null, initialError: null, startLogin: startLoginMock, signOut: vi.fn() });
}
function renderPage() {
  return render(<MemoryRouter initialEntries={['/try']}><TrialPage /></MemoryRouter>);
}

const TODAY = new Date('2026-10-13T12:00:00');

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(TODAY);
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1440 });
});
afterEach(() => {
  vi.useRealTimers();
  cleanup();
});

describe('TrialPage', () => {
  it('처음엔 오늘의 논문을 크게 보여주고, 목록에는 9편을 모두 두되 준비된 논문만 뷰어로 연결한다', () => {
    mockAuth('guest');
    renderPage();
    const papers = trialPapers();
    const today = pickTodaysPaper(papers, TODAY);
    const feature = screen.getByRole('link', { name: /오늘의 논문/ });
    expect(feature.getAttribute('href')).toBe(`/try/papers/${today.paperId}`);
    expect(screen.getByRole('heading', { name: today.title })).toBeTruthy();

    const listLinks = screen.getAllByRole('link').filter((a) => a !== feature && a.getAttribute('href')?.startsWith('/try/papers/'));
    const expected = papers.filter((p) => p.paperId).map((p) => `/try/papers/${p.paperId}`);
    expect(listLinks.map((a) => a.getAttribute('href'))).toEqual(expected);
    for (const p of papers.filter((p) => !p.paperId)) expect(screen.getByText(p.title).closest('a')).toBeNull();

    fireEvent.click(listLinks[0]);
    const first = papers.find((p) => `/try/papers/${p.paperId}` === expected[0])!;
    expect(track).toHaveBeenCalledWith('trial_paper_opened', { paper_id: first.paperId, topic: first.topicKey });
  });

  it('목록에 마우스를 올리면 큰 자리가 그 논문으로 바뀌고, 오늘의 논문 꼬리표는 사라진다', () => {
    mockAuth('guest');
    renderPage();
    const papers = trialPapers();
    const today = pickTodaysPaper(papers, TODAY);
    const other = papers.find((p) => p.paperId && p !== today)!;
    const row = screen.getAllByRole('link').find((a) => a.getAttribute('href') === `/try/papers/${other.paperId}` && a.className.includes('trial-item'))!;
    fireEvent.mouseEnter(row);
    expect(screen.getByRole('heading', { name: other.title })).toBeTruthy();
    expect(screen.queryByText('오늘의 논문')).toBeNull();
    expect(row.className).toContain('trial-item-active');
  });

  it('업로드 버튼은 방문자에게 가입 모달을 열고, Google 버튼이 로그인을 시작한다', () => {
    mockAuth('guest');
    renderPage();
    fireEvent.click(screen.getAllByRole('button', { name: /내 논문 업로드하기/ })[0]);
    expect(screen.getByRole('dialog', { name: '가입하고 무료로 이용해보세요.' })).toBeTruthy();
    expect(track).toHaveBeenCalledWith('trial_signup_prompted', { source: 'upload' });
    fireEvent.click(screen.getByRole('button', { name: /Google로 계속하기/ }));
    expect(startLoginMock).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: '계속 둘러보기' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('로그인한 사용자의 업로드 버튼은 서재로 보낸다', () => {
    mockAuth('authed');
    renderPage();
    fireEvent.click(screen.getAllByRole('button', { name: /내 논문 업로드하기/ })[0]);
    expect(navigateMock).toHaveBeenCalledWith('/library');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('모바일 폭이면 랜딩으로 보낸다', () => {
    mockAuth('guest');
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 390 });
    renderPage();
    expect(navigateMock).toHaveBeenCalledWith('/', { replace: true });
  });
});
