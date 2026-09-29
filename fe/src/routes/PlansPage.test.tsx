import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import PlansPage from './PlansPage';

vi.mock('../auth/AuthContext', () => ({ useAuth: vi.fn() }));
vi.mock('../nav/GlobalNav', () => ({ GlobalNav: () => <nav data-testid="global-nav" /> }));

const useAuthMock = vi.mocked(useAuth);
const startLogin = vi.fn();

function renderPage(status: 'guest' | 'authed' | 'loading' = 'guest') {
  useAuthMock.mockReturnValue({ status, user: null, initialError: null, startLogin, signOut: vi.fn() });
  return render(<MemoryRouter initialEntries={['/plans']}><Routes>
    <Route path="/plans" element={<PlansPage />} />
    <Route path="/library" element={<p>내 서재</p>} />
  </Routes></MemoryRouter>);
}

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

describe('PlansPage', () => {
  it('Free 사용량과 Pro 이용 안내를 보여주고 FAQ로 이동한다', () => {
    renderPage();
    expect(screen.getByRole('heading', { name: 'Free' })).toBeTruthy();
    expect(screen.getByText('30')).toBeTruthy();
    expect(screen.getByText('더 많은 논문')).toBeTruthy();
    expect(screen.getByText('더 많은 질문')).toBeTruthy();
    expect(screen.getByRole('link', { name: /Pro 이용 안내/ }).getAttribute('href')).toBe('#pro-faq');
    expect(screen.getByText(/곧 설문조사, 이벤트 등을 통해/)).toBeTruthy();
  });

  it('비로그인 Free 시작 버튼은 로그인 흐름을 연다', () => {
    renderPage();
    fireEvent.click(screen.getAllByRole('button', { name: /무료로 시작하기/ })[0]);
    expect(startLogin).toHaveBeenCalledOnce();
  });

  it('로그인한 사용자는 내 서재로 이동한다', () => {
    renderPage('authed');
    fireEvent.click(screen.getAllByRole('button', { name: /무료로 시작하기/ })[0]);
    expect(screen.getByText('내 서재')).toBeTruthy();
  });
});
