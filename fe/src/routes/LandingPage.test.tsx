import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import LandingPage from './LandingPage';
import { useAuth } from '../auth/AuthContext';

const navigateMock = vi.fn();
const startLoginMock = vi.fn();
vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useNavigate: () => navigateMock,
}));
vi.mock('../auth/AuthContext', () => ({ useAuth: vi.fn() }));
vi.mock('../nav/GlobalNav', () => ({
  GlobalNav: () => <nav data-testid="global-nav"><a href="/">Paper Teacher</a></nav>,
}));

const useAuthMock = vi.mocked(useAuth);
function mockAuth(status: 'guest' | 'authed') {
  useAuthMock.mockReturnValue({
    status, user: null, initialError: null, startLogin: startLoginMock, signOut: vi.fn(),
  });
}
function renderLanding() {
  render(<MemoryRouter><LandingPage /></MemoryRouter>);
}

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

describe('LandingPage', () => {
  it('방문자의 시작 CTA가 인증 흐름을 열고 기능 버튼이 소개 영역으로 이동한다', () => {
    mockAuth('guest');
    renderLanding();
    expect(screen.getByText(/논문 리딩의/)).toBeTruthy();
    expect(screen.getAllByRole('button', { name: /논문 업로드하고 시작하기/ })).toHaveLength(2);
    fireEvent.click(screen.getAllByRole('button', { name: /논문 업로드하고 시작하기/ })[0]);
    expect(startLoginMock).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('link', { name: '기능 살펴보기' }).getAttribute('href')).toBe('#journey');
    expect(screen.getByTestId('global-nav')).toBeTruthy();
  });

  it('로그인 사용자의 시작 CTA는 서재로 이동한다', () => {
    mockAuth('authed');
    renderLanding();
    fireEvent.click(screen.getAllByRole('button', { name: /논문 업로드하고 시작하기/ })[1]);
    expect(navigateMock).toHaveBeenCalledWith('/library');
    expect(startLoginMock).not.toHaveBeenCalled();
  });

  it('뷰어와 지식 그래프 화면을 화살표와 점으로 넘긴다', () => {
    mockAuth('guest');
    renderLanding();
    const viewerImage = screen.getByAltText('논문에 실린 그래프 이미지를 보여주는 뷰어 화면');
    expect(viewerImage.closest('figure')?.hidden).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '다음 논문 뷰어 화면' }));
    expect(viewerImage.closest('figure')?.hidden).toBe(false);
    fireEvent.click(screen.getByRole('tab', { name: '표' }));
    expect(screen.getByAltText('논문 데이터 표를 보여주는 뷰어 화면').closest('figure')?.hidden).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: '다음 지식 그래프 화면' }));
    expect(screen.getByAltText('지식 그래프의 섹션을 클릭해 오른쪽에 본문과 번역이 열린 화면').closest('figure')?.hidden).toBe(false);
  });
});
