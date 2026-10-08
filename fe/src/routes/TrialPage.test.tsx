import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import TrialPage from './TrialPage';
import { useAuth } from '../auth/AuthContext';
import { track } from '../analytics/analytics';

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
  return { ...mod, trialPapers: () => mod.trialPapers('love-1,ai-1,,space-1,sleep-1') };
});

const useAuthMock = vi.mocked(useAuth);
function mockAuth(status: 'guest' | 'authed' | 'loading') {
  useAuthMock.mockReturnValue({ status, user: null, initialError: null, startLogin: startLoginMock, signOut: vi.fn() });
}
function renderPage() {
  return render(<MemoryRouter initialEntries={['/try']}><TrialPage /></MemoryRouter>);
}

beforeEach(() => {
  vi.clearAllMocks();
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1440 });
});
afterEach(cleanup);

describe('TrialPage', () => {
  it('주제 다섯 개를 보여주고 준비된 논문만 뷰어로 연결한다', () => {
    mockAuth('guest');
    renderPage();
    for (const topic of ['사랑', 'AI', '반도체', '우주', '수면']) expect(screen.getByRole('heading', { name: topic })).toBeTruthy();
    const links = screen.getAllByRole('link').filter((a) => a.getAttribute('href')?.startsWith('/try/papers/'));
    expect(links.map((a) => a.getAttribute('href'))).toEqual(['/try/papers/love-1', '/try/papers/ai-1', '/try/papers/space-1', '/try/papers/sleep-1']);
    fireEvent.click(links[1]);
    expect(track).toHaveBeenCalledWith('trial_paper_opened', { paper_id: 'ai-1', topic: 'ai' });
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
