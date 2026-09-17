import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import KnowledgeGraphPage from './KnowledgeGraphPage';
import { getStatus, getKnowledgeGraphView, fetchPaperContent } from '../api/papers';
import { ApiError, type KnowledgeGraphStatus, type PaperStatus } from '../api/types';

vi.mock('../api/papers', () => ({ getStatus: vi.fn(), getKnowledgeGraphView: vi.fn(), fetchPaperContent: vi.fn() }));
vi.mock('../account/AccountMenu', () => ({ AccountMenu: () => <div /> }));

function statusResponse(knowledgeGraphStatus: KnowledgeGraphStatus | null = 'READY', status: PaperStatus = 'COMPLETED') {
  return { paperId: 'p1', status, translationStatus: 'READY' as const, knowledgeGraphStatus, updatedAt: '2026-09-17T00:00:00Z' };
}

function renderGraph() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/papers/p1/graph']}>
        <Routes>
          <Route path="/papers/:paperId/graph" element={<KnowledgeGraphPage />} />
          <Route path="/papers/:paperId" element={<div>STUDY-ROUTE</div>} />
          <Route path="/library" element={<div>LIBRARY-ROUTE</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function iframe(): HTMLIFrameElement | null {
  return document.querySelector('iframe[title="지식 그래프"]');
}

beforeEach(() => {
  vi.mocked(getStatus).mockResolvedValue(statusResponse());
  vi.mocked(fetchPaperContent).mockResolvedValue({
    paperId: 'p1', title: '제목', sourceLanguage: 'en', translationStatus: 'READY', schemaVersion: 1, blocks: [], assets: {},
  });
  vi.mocked(getKnowledgeGraphView).mockResolvedValue({ url: 'https://s3.example/viz.html?sig=1', expiresAt: '2026-09-17T00:10:00Z' });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('KnowledgeGraphPage', () => {
  it('READY면 URL을 받아 iframe src로 넣고 sandbox를 건다', async () => {
    renderGraph();
    await waitFor(() => expect(iframe()).toBeTruthy());
    expect(iframe()!.getAttribute('src')).toBe('https://s3.example/viz.html?sig=1');
    expect(iframe()!.getAttribute('sandbox')).toBe('allow-scripts allow-same-origin');
    expect(getKnowledgeGraphView).toHaveBeenCalledWith('p1');
    // 상단 바: 지식 그래프가 현재, 번역 버튼 없음
    expect(screen.getByRole('link', { name: '지식 그래프' }).getAttribute('aria-current')).toBe('page');
    expect(screen.queryByRole('button', { name: /번역/ })).toBeNull();
    expect(screen.getByText('제목')).toBeTruthy();
  });

  it('PENDING이면 URL을 요청하지 않고 준비 중 문구와 본문으로 링크를 보여준다', async () => {
    vi.mocked(getStatus).mockResolvedValue(statusResponse('PENDING'));
    renderGraph();
    await waitFor(() => expect(screen.getByText('지식 그래프를 준비하고 있습니다')).toBeTruthy());
    expect(getKnowledgeGraphView).not.toHaveBeenCalled();
    expect(iframe()).toBeNull();
    const back = screen.getByRole('link', { name: '본문으로' }) as HTMLAnchorElement;
    expect(back.getAttribute('href')).toBe('/papers/p1');
    fireEvent.click(back);
    expect(screen.getByText('STUDY-ROUTE')).toBeTruthy();
  });

  it('FAILED면 실패 문구다', async () => {
    vi.mocked(getStatus).mockResolvedValue(statusResponse('FAILED'));
    renderGraph();
    await waitFor(() => expect(screen.getByText('지식 그래프를 준비하지 못했습니다')).toBeTruthy());
    expect(getKnowledgeGraphView).not.toHaveBeenCalled();
  });

  it('URL 발급 실패면 문구와 다시 시도 버튼이고 누르면 재요청한다', async () => {
    vi.mocked(getKnowledgeGraphView)
      .mockRejectedValueOnce(new ApiError('conflict', 'KNOWLEDGE_GRAPH_NOT_READY', 409))
      .mockResolvedValue({ url: 'https://s3.example/viz.html?sig=2', expiresAt: '2026-09-17T00:10:00Z' });
    renderGraph();
    await waitFor(() => expect(screen.getByText('지식 그래프를 불러오지 못했습니다')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(iframe()).toBeTruthy());
    expect(iframe()!.getAttribute('src')).toBe('https://s3.example/viz.html?sig=2');
    expect(getKnowledgeGraphView).toHaveBeenCalledTimes(2);
  });

  it('파싱이 COMPLETED가 아니면 서재로 돌려보낸다', async () => {
    vi.mocked(getStatus).mockResolvedValue(statusResponse('PENDING', 'PROCESSING'));
    renderGraph();
    await waitFor(() => expect(screen.getByText('LIBRARY-ROUTE')).toBeTruthy());
  });

  it('없는 논문(404)은 서재로 돌려보낸다', async () => {
    vi.mocked(getStatus).mockRejectedValue(new ApiError('not found', 'PAPER_NOT_FOUND', 404));
    renderGraph();
    await waitFor(() => expect(screen.getByText('LIBRARY-ROUTE')).toBeTruthy());
  });
});
