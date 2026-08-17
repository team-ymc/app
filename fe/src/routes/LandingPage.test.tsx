import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import LandingPage from './LandingPage';
import { useAuth } from '../auth/AuthContext';

const navigateMock = vi.fn();
vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useNavigate: () => navigateMock,
}));
vi.mock('../auth/AuthContext', () => ({ useAuth: vi.fn() }));

const useAuthMock = vi.mocked(useAuth);

function mockAuth(status: 'guest' | 'authed') {
  useAuthMock.mockReturnValue({
    status,
    user: null,
    initialError: null,
    startLogin: vi.fn(),
    signOut: vi.fn(),
  });
}

beforeEach(() => {
  vi.clearAllMocks();
});

afterEach(cleanup);

function renderLanding() {
  return render(
    <MemoryRouter>
      <LandingPage />
    </MemoryRouter>,
  );
}

describe('LandingPage', () => {
  it('guest: 로그인·회원가입 버튼이 보이고 내 서재로는 없다', () => {
    mockAuth('guest');
    renderLanding();
    expect(screen.getByRole('button', { name: '로그인' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '회원가입' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '내 서재로' })).toBeNull();
  });

  it('authed: 리다이렉트 없이 랜딩에 머물고 내 서재로 버튼만 보인다', () => {
    mockAuth('authed');
    renderLanding();
    expect(screen.getByText('어떤 문서')).toBeTruthy();
    expect(screen.getByRole('button', { name: '내 서재로' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '로그인' })).toBeNull();
    expect(screen.queryByRole('button', { name: '회원가입' })).toBeNull();
    expect(navigateMock).not.toHaveBeenCalled();
  });

  it('상단 바 로고는 랜딩으로 가는 링크다', () => {
    mockAuth('guest');
    renderLanding();
    expect(screen.getByRole('link', { name: /Paper Teacher/ }).getAttribute('href')).toBe('/');
  });

  it('authed: 내 서재로 클릭 시 /library로 이동한다', () => {
    mockAuth('authed');
    renderLanding();
    fireEvent.click(screen.getByRole('button', { name: '내 서재로' }));
    expect(navigateMock).toHaveBeenCalledWith('/library');
  });
});
