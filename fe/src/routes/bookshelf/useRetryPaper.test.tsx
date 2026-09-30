import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useRetryPaper } from './useRetryPaper';
import { retryPaper } from '../../api/papers';
import { ApiError, type Paper, type PaperStatusResponse } from '../../api/types';

vi.mock('../../api/papers', () => ({ retryPaper: vi.fn() }));

const paper: Paper = {
  paperId: 'p1', title: 'A paper', filename: 'a.pdf', status: 'FAILED',
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', lastAccessedAt: null,
  failReason: 'PROCESSING_FAILED',
};

function response(status: PaperStatusResponse['status']): PaperStatusResponse {
  return {
    paperId: 'p1', status, translationStatus: 'NOT_APPLICABLE', knowledgeGraphStatus: 'PENDING',
    updatedAt: '2026-01-02T00:00:00Z', failReason: null, compileRetryable: false,
  };
}

function setup() {
  const qc = new QueryClient();
  const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
  const showToast = vi.fn();
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
  const hook = renderHook(() => useRetryPaper(showToast), { wrapper });
  return { hook, invalidateSpy, showToast };
}

describe('useRetryPaper', () => {
  beforeEach(() => vi.clearAllMocks());

  it('성공하면 목록과 플랜을 다시 받고 시작 안내를 띄운다', async () => {
    vi.mocked(retryPaper).mockResolvedValue(response('PROCESSING'));
    const { hook, invalidateSpy, showToast } = setup();

    await act(() => hook.result.current.retry(paper));

    expect(retryPaper).toHaveBeenCalledWith('p1');
    expect(showToast).toHaveBeenCalledWith('다시 분석을 시작했습니다');
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['papers'] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['plan'] });
    expect(hook.result.current.retryingId).toBeNull();
  });

  it('이미 분석된 파일이라 바로 완료되면 준비됐다고 안내한다', async () => {
    vi.mocked(retryPaper).mockResolvedValue(response('COMPLETED'));
    const { hook, showToast } = setup();

    await act(() => hook.result.current.retry(paper));

    expect(showToast).toHaveBeenCalledWith('논문이 준비되었습니다');
  });

  it('한도 초과면 BE 문구를 띄우고 목록과 플랜을 다시 받는다', async () => {
    vi.mocked(retryPaper).mockRejectedValue(
      new ApiError('이번 달 문서 등록 횟수를 모두 사용했습니다.', 'PAPER_USAGE_LIMIT_EXCEEDED', 429));
    const { hook, invalidateSpy, showToast } = setup();

    await act(() => hook.result.current.retry(paper));

    expect(showToast).toHaveBeenCalledWith('이번 달 문서 등록 횟수를 모두 사용했습니다.');
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['papers'] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['plan'] });
    expect(hook.result.current.retryingId).toBeNull();
  });

  it('요청이 끝나기 전에는 재시도 중인 논문을 알려주고 다른 요청을 받지 않는다', async () => {
    let finish: (value: PaperStatusResponse) => void = () => {};
    vi.mocked(retryPaper).mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const { hook } = setup();

    let pending: Promise<void> = Promise.resolve();
    act(() => { pending = hook.result.current.retry(paper); });
    await waitFor(() => expect(hook.result.current.retryingId).toBe('p1'));

    await act(() => hook.result.current.retry({ ...paper, paperId: 'p2' }));
    expect(retryPaper).toHaveBeenCalledTimes(1);

    await act(async () => { finish(response('PROCESSING')); await pending; });
    expect(hook.result.current.retryingId).toBeNull();
  });
});
