import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { GlobalNav } from './GlobalNav';
import { useAuth } from '../auth/AuthContext';

vi.mock('../auth/AuthContext', () => ({ useAuth: vi.fn() }));
vi.mock('../account/AccountMenu', () => ({ AccountMenu: () => <div data-testid="account-menu" /> }));

const useAuthMock = vi.mocked(useAuth);
const startLogin = vi.fn();

function mockAuth(status: 'loading' | 'guest' | 'authed') {
  useAuthMock.mockReturnValue({ status, user: null, initialError: null, startLogin, signOut: vi.fn() });
}

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

function renderNav(path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <GlobalNav />
    </MemoryRouter>,
  );
}

describe('GlobalNav', () => {
  it('로고는 랜딩으로 가는 링크다', () => {
    mockAuth('guest');
    renderNav();
    expect(screen.getByRole('link', { name: /Paper Teacher/ }).getAttribute('href')).toBe('/');
  });

  it('플랜·기능 메뉴가 각 경로로 연결된다', () => {
    mockAuth('guest');
    renderNav();
    expect(screen.getByRole('link', { name: '플랜' }).getAttribute('href')).toBe('/plans');
    expect(screen.getByRole('link', { name: '기능' }).getAttribute('href')).toBe('/features');
  });

  it('현재 경로의 메뉴 항목만 aria-current="page"다', () => {
    mockAuth('guest');
    renderNav('/plans');
    expect(screen.getByRole('link', { name: '플랜' }).getAttribute('aria-current')).toBe('page');
    expect(screen.getByRole('link', { name: '기능' }).getAttribute('aria-current')).toBeNull();
  });

  it('guest: 로그인 버튼이 startLogin을 부르고 계정 메뉴는 없다', () => {
    mockAuth('guest');
    renderNav();
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));
    expect(startLogin).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('account-menu')).toBeNull();
  });

  it('authed: 계정 메뉴가 보이고 로그인 버튼은 없다', () => {
    mockAuth('authed');
    renderNav();
    expect(screen.getByTestId('account-menu')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '로그인' })).toBeNull();
  });

  it('loading: 오른쪽 영역에 아무것도 그리지 않는다', () => {
    mockAuth('loading');
    renderNav();
    expect(screen.queryByTestId('account-menu')).toBeNull();
    expect(screen.queryByRole('button', { name: '로그인' })).toBeNull();
  });
});
