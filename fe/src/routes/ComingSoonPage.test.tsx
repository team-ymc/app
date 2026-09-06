import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import ComingSoonPage from './ComingSoonPage';
import { useAuth } from '../auth/AuthContext';

vi.mock('../auth/AuthContext', () => ({ useAuth: vi.fn() }));
vi.mock('../nav/GlobalNav', () => ({ GlobalNav: () => <nav data-testid="global-nav" />, GLOBAL_NAV_HEIGHT: 64 }));

const useAuthMock = vi.mocked(useAuth);

function mockAuth(status: 'guest' | 'authed') {
  useAuthMock.mockReturnValue({ status, user: null, initialError: null, startLogin: vi.fn(), signOut: vi.fn() });
}

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

function renderPage(eyebrow = 'Plans') {
  return render(
    <MemoryRouter>
      <ComingSoonPage eyebrow={eyebrow} />
    </MemoryRouter>,
  );
}

describe('ComingSoonPage', () => {
  it('상단 바와 준비 중 안내, 섹션 라벨을 그린다', () => {
    mockAuth('guest');
    renderPage('Features');
    expect(screen.getByTestId('global-nav')).toBeTruthy();
    expect(screen.getByRole('heading', { name: '준비 중입니다' })).toBeTruthy();
    expect(screen.getByText('Features')).toBeTruthy();
  });

  it('authed: 내 서재로 돌아가는 링크', () => {
    mockAuth('authed');
    renderPage();
    expect(screen.getByRole('link', { name: '내 서재로 돌아가기' }).getAttribute('href')).toBe('/library');
  });

  it('guest: 처음으로 돌아가는 링크', () => {
    mockAuth('guest');
    renderPage();
    expect(screen.getByRole('link', { name: '처음으로 돌아가기' }).getAttribute('href')).toBe('/');
    expect(screen.queryByRole('link', { name: '내 서재로 돌아가기' })).toBeNull();
  });
});
