import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { StudyTopBar, type StudyTopBarProps } from './StudyTopBar';
import { TrialModeProvider } from '../../trial/TrialMode';
import { useAuth } from '../../auth/AuthContext';

vi.mock('../../account/AccountMenu', () => ({ AccountMenu: () => <div data-testid="account-menu" /> }));
vi.mock('../../auth/AuthContext', () => ({ useAuth: vi.fn() }));
vi.mock('../../analytics/analytics', () => ({ track: vi.fn() }));

function mockAuth(status: 'loading' | 'guest' | 'authed') {
  vi.mocked(useAuth).mockReturnValue({ status, user: null, initialError: null, startLogin: vi.fn(), signOut: vi.fn() });
}

afterEach(cleanup);

function renderBar(props: Partial<StudyTopBarProps> = {}) {
  mockAuth('authed');
  render(
    <MemoryRouter initialEntries={['/papers/p1']}>
      <Routes>
        <Route
          path="/papers/:paperId/*"
          element={<StudyTopBar paperId="p1" title="Attention" current="content" knowledgeGraphStatus="READY" {...props} />}
        />
        <Route path="/papers/p1/graph" element={<div>GRAPH-ROUTE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

function contentLink(): HTMLAnchorElement {
  return screen.getByRole('link', { name: '본문' }) as HTMLAnchorElement;
}
function graphLink(): HTMLAnchorElement {
  return screen.getByRole('link', { name: '지식 그래프' }) as HTMLAnchorElement;
}

describe('StudyTopBar — 본문 | 지식 그래프 쌍', () => {
  it('현재 화면이 aria-current로 드러나고 링크 경로가 맞다', () => {
    renderBar();
    expect(contentLink().getAttribute('aria-current')).toBe('page');
    expect(graphLink().getAttribute('aria-current')).toBeNull();
    expect(contentLink().getAttribute('href')).toBe('/papers/p1');
    expect(graphLink().getAttribute('href')).toBe('/papers/p1/graph');
    expect(screen.getByText('Attention')).toBeTruthy();
    expect(screen.getByTestId('account-menu')).toBeTruthy();
  });

  it('graph가 현재면 지식 그래프가 눌린다', () => {
    renderBar({ current: 'graph' });
    expect(graphLink().getAttribute('aria-current')).toBe('page');
    expect(contentLink().getAttribute('aria-current')).toBeNull();
  });

  it('READY가 아니면 지식 그래프가 비활성이고 툴팁이 사유다', () => {
    renderBar({ knowledgeGraphStatus: 'PENDING' });
    expect(graphLink().getAttribute('aria-disabled')).toBe('true');
    expect(graphLink().title).toBe('지식 그래프를 준비하고 있습니다');
    expect(graphLink().tabIndex).toBe(-1);
    fireEvent.click(graphLink());
    expect(screen.queryByText('GRAPH-ROUTE')).toBeNull();
  });

  it('FAILED·null도 비활성이다', () => {
    renderBar({ knowledgeGraphStatus: 'FAILED' });
    expect(graphLink().title).toBe('지식 그래프를 준비하지 못했습니다');
    cleanup();
    renderBar({ knowledgeGraphStatus: null });
    expect(graphLink().getAttribute('aria-disabled')).toBe('true');
  });

  it('READY면 클릭으로 그래프 화면에 간다', () => {
    renderBar();
    expect(graphLink().getAttribute('aria-disabled')).toBeNull();
    expect(graphLink().title).toBe('지식 그래프');
    expect(graphLink().tabIndex).toBe(0);
    fireEvent.click(graphLink());
    expect(screen.getByText('GRAPH-ROUTE')).toBeTruthy();
  });

  it('rightSlot이 있으면 계정 메뉴 왼쪽에 그린다', () => {
    renderBar({ rightSlot: <button type="button">번역</button> });
    expect(screen.getByRole('button', { name: '번역' })).toBeTruthy();
  });
});

function renderTrialBar() {
  render(
    <MemoryRouter initialEntries={['/try/papers/p1']}>
      <TrialModeProvider>
        <StudyTopBar paperId="p1" title="Attention" current="content" knowledgeGraphStatus="READY" />
      </TrialModeProvider>
    </MemoryRouter>,
  );
}

describe('StudyTopBar — 체험 모드 계정 영역', () => {
  it('비로그인이면 로그인 버튼을 둔다', () => {
    mockAuth('guest');
    renderTrialBar();
    expect(screen.getByRole('button', { name: '로그인' })).toBeTruthy();
    expect(screen.queryByTestId('account-menu')).toBeNull();
  });

  it('로그인 상태면 로그인 버튼 대신 계정 메뉴를 둔다', () => {
    mockAuth('authed');
    renderTrialBar();
    expect(screen.queryByRole('button', { name: '로그인' })).toBeNull();
    expect(screen.getByTestId('account-menu')).toBeTruthy();
  });

  it('확인 중이면 둘 다 그리지 않는다', () => {
    mockAuth('loading');
    renderTrialBar();
    expect(screen.queryByRole('button', { name: '로그인' })).toBeNull();
    expect(screen.queryByTestId('account-menu')).toBeNull();
  });
});
