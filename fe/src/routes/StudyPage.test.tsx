import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import StudyPage, { splitPctForChatWidth } from './StudyPage';
import { getStatus, fetchPaperContent } from '../api/papers';
import { getMyPlan } from '../api/plan';
import { useTextSelection, type TextSelection } from './study/useTextSelection';
import type { PaperContentResponse } from '../api/types';

vi.mock('../api/papers', () => ({ getStatus: vi.fn(), fetchPaperContent: vi.fn() }));
vi.mock('../api/plan', () => ({ getMyPlan: vi.fn() }));
vi.mock('../account/AccountMenu', () => ({ AccountMenu: () => <div /> }));
vi.mock('./study/TutorPanel', () => ({ TutorPanel: () => <div data-testid="tutor-panel" /> }));
vi.mock('./study/useTextSelection', () => ({ useTextSelection: vi.fn() }));

const STORAGE_KEY = 'pt-translation-mode';

// 이 jsdom에는 localStorage가 없다(Node의 실험적 전역이 가려 window.localStorage가 안 생김) — 메모리 스텁.
const store = new Map<string, string>();
vi.stubGlobal('localStorage', {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => { store.set(k, String(v)); },
  removeItem: (k: string) => { store.delete(k); },
  clear: () => store.clear(),
});

function contentResponse(translated: boolean, sourceLanguage: string | null = 'en'): PaperContentResponse {
  return {
    paperId: 'p1',
    title: '제목',
    sourceLanguage,
    schemaVersion: 1,
    blocks: [
      {
        blockId: 'b1',
        globalOrder: 1,
        label: 'text',
        headingLevel: null,
        sectionPath: [],
        content: translated
          ? { format: 'text', text: 'An attention function', textKor: '어텐션 함수' }
          : { format: 'text', text: 'An attention function' },
      },
    ],
    assets: {},
  };
}

function selection(): TextSelection {
  return {
    text: 'attention',
    rect: { top: 20, left: 10, right: 110, bottom: 36, width: 100, height: 16 } as DOMRect,
    anchors: { start: { blockId: 'b1', offset: 0 }, end: { blockId: 'b1', offset: 9 } },
    clear: vi.fn(),
  };
}

/** 툴바 순환 버튼 — 선택 팝업의 번역 버튼과 달리 aria-pressed를 가진다. */
function modeButton(): HTMLButtonElement {
  return document.querySelector('button[aria-pressed]') as HTMLButtonElement;
}

function popupTranslateButton(): HTMLButtonElement | null {
  return Array.from(document.querySelectorAll('button'))
    .find((b) => b.textContent === '번역' && !b.hasAttribute('aria-pressed')) ?? null;
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
  vi.mocked(getStatus).mockResolvedValue({ paperId: 'p1', status: 'COMPLETED', updatedAt: '2026-09-09T00:00:00Z' });
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

describe('StudyPage — 전체 번역 순환', () => {
  it('버튼 클릭이 off → 아래 → 옆 → off로 순환하고 저장된다', async () => {
    vi.mocked(fetchPaperContent).mockResolvedValue(contentResponse(true));
    renderStudy();
    await waitFor(() => expect(modeButton()).toBeTruthy());

    expect(modeButton().textContent).toBe('번역');
    expect(document.querySelector('aside.pt-translation')).toBeNull();

    fireEvent.click(modeButton());
    expect(modeButton().textContent).toBe('번역 · 아래');
    expect(document.querySelector('.pt-row[data-mode="below"] aside.pt-translation')).toBeTruthy();
    expect(localStorage.getItem(STORAGE_KEY)).toBe('below');

    fireEvent.click(modeButton());
    expect(modeButton().textContent).toBe('번역 · 옆');
    expect(document.querySelector('.pt-row[data-mode="side"] aside.pt-translation')).toBeTruthy();
    expect(localStorage.getItem(STORAGE_KEY)).toBe('side');

    fireEvent.click(modeButton());
    expect(modeButton().textContent).toBe('번역');
    expect(document.querySelector('aside.pt-translation')).toBeNull();
    expect(localStorage.getItem(STORAGE_KEY)).toBe('off');
  });

  it('저장된 side는 번역 있는 논문에서 그대로 복원된다', async () => {
    localStorage.setItem(STORAGE_KEY, 'side');
    vi.mocked(fetchPaperContent).mockResolvedValue(contentResponse(true));
    renderStudy();
    await waitFor(() => expect(modeButton()).toBeTruthy());
    expect(modeButton().textContent).toBe('번역 · 옆');
    expect(document.querySelector('.pt-row[data-mode="side"] aside.pt-translation')).toBeTruthy();
  });

  it('번역이 없는 논문은 버튼이 비활성이고 저장값을 지우지 않은 채 off로 적용된다', async () => {
    localStorage.setItem(STORAGE_KEY, 'side');
    vi.mocked(fetchPaperContent).mockResolvedValue(contentResponse(false, 'ko'));
    vi.mocked(useTextSelection).mockReturnValue(selection());
    renderStudy();
    await waitFor(() => expect(modeButton()).toBeTruthy());

    expect(modeButton().disabled).toBe(true);
    expect(modeButton().title).toBe('한국어 논문은 번역하지 않습니다');
    // 저장값은 'side'지만 이 논문엔 번역이 없으므로 effectiveMode는 'off' — 라벨·aria-pressed도 off를 따른다.
    expect(modeButton().textContent).toBe('번역');
    expect(modeButton().getAttribute('aria-pressed')).toBe('false');
    expect(document.querySelector('aside.pt-translation')).toBeNull();
    expect(popupTranslateButton()).toBeTruthy();
    expect(localStorage.getItem(STORAGE_KEY)).toBe('side');
  });

  it('번역 없는 논문의 툴팁은 언어가 ko가 아니면 준비 안 됨 문구다', async () => {
    vi.mocked(fetchPaperContent).mockResolvedValue(contentResponse(false, null));
    renderStudy();
    await waitFor(() => expect(modeButton()).toBeTruthy());
    expect(modeButton().title).toBe('이 논문은 번역이 준비되지 않았습니다');
  });

  it('전체 번역이 켜지면 선택 팝업에서 번역 버튼이 사라진다', async () => {
    vi.mocked(fetchPaperContent).mockResolvedValue(contentResponse(true));
    vi.mocked(useTextSelection).mockReturnValue(selection());
    renderStudy();
    await waitFor(() => expect(modeButton()).toBeTruthy());
    expect(popupTranslateButton()).toBeTruthy();

    fireEvent.click(modeButton());
    expect(popupTranslateButton()).toBeNull();
    expect(screen.getByRole('button', { name: /질문하기/ })).toBeTruthy();
  });
});

describe('splitPctForChatWidth — side 진입 시 채팅 320px', () => {
  it('스플리터 6px을 빼고 채팅이 320px이 되는 비율을 준다', () => {
    expect(splitPctForChatWidth(1200)).toBeCloseTo(((1200 - 326) / 1200) * 100, 5);
  });

  it('SPLIT_MIN·SPLIT_MAX로 조인다', () => {
    expect(splitPctForChatWidth(5000)).toBe(75);
    expect(splitPctForChatWidth(400)).toBe(30);
  });

  it('폭을 못 재면 null이라 기존 비율을 건드리지 않는다', () => {
    expect(splitPctForChatWidth(0)).toBeNull();
  });
});
