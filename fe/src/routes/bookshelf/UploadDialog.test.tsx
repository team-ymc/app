import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import UploadDialog from './UploadDialog';
import { sha256Base64 } from './fileChecksum';
import { createPaper, uploadToS3 } from '../../api/papers';
import { ApiError } from '../../api/types';

afterEach(cleanup);

vi.mock('./fileChecksum', () => ({ sha256Base64: vi.fn() }));
vi.mock('../../api/papers', () => ({
  createPaper: vi.fn(), uploadToS3: vi.fn(), completeUpload: vi.fn(),
}));

const HASH = 'ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=';
const CREATED = {
  paperId: 'p1', fileKey: 'k', uploadUrl: 'https://s3/put',
  uploadHeaders: { 'Content-Type': 'application/pdf' as const, 'x-amz-checksum-sha256': HASH },
  uploadExpiresAt: '', status: 'UPLOAD_PENDING' as const, createdAt: '',
};

function renderDialog() {
  const qc = new QueryClient();
  return render(
    <QueryClientProvider client={qc}>
      <UploadDialog open onClose={() => {}} onUploaded={() => {}} />
    </QueryClientProvider>,
  );
}

function selectPdfAndUpload(container: HTMLElement) {
  const input = container.querySelector('input[type="file"]')!;
  fireEvent.change(input, {
    target: { files: [new File(['x'], 'a.pdf', { type: 'application/pdf' })] },
  });
  fireEvent.click(screen.getByRole('button', { name: '업로드' }));
}

function renderDialogWith(qc: QueryClient) {
  return render(
    <QueryClientProvider client={qc}>
      <UploadDialog open onClose={() => {}} onUploaded={() => {}} />
    </QueryClientProvider>,
  );
}

describe('UploadDialog — checksum', () => {
  beforeEach(() => vi.clearAllMocks());

  it('업로드 시 해시를 create에, uploadHeaders를 S3 PUT에 넘긴다', async () => {
    vi.mocked(sha256Base64).mockResolvedValue(HASH);
    vi.mocked(createPaper).mockResolvedValue(CREATED);
    vi.mocked(uploadToS3).mockResolvedValue(undefined);
    const { container } = renderDialog();

    selectPdfAndUpload(container);

    await waitFor(() => expect(createPaper).toHaveBeenCalledWith('a.pdf', 'application/pdf', 1, HASH));
    await waitFor(() => expect(uploadToS3).toHaveBeenCalledWith(
      'https://s3/put', expect.anything(),
      expect.objectContaining({ 'x-amz-checksum-sha256': HASH }), expect.any(Function),
    ));
  });

  it('S3가 BadDigest로 거절하면 checksum 에러 문구를 보여준다', async () => {
    vi.mocked(sha256Base64).mockResolvedValue(HASH);
    vi.mocked(createPaper).mockResolvedValue(CREATED);
    vi.mocked(uploadToS3).mockRejectedValue(
      new Error('파일 검증에 실패했습니다. 파일이 업로드 중 변경되었을 수 있습니다'));
    const { container } = renderDialog();

    selectPdfAndUpload(container);

    await waitFor(() =>
      expect(screen.getByText('파일 검증에 실패했습니다. 파일이 업로드 중 변경되었을 수 있습니다')).toBeTruthy());
  });
});

describe('UploadDialog — 사용량 한도', () => {
  beforeEach(() => vi.clearAllMocks());

  it('429 PAPER_USAGE_LIMIT_EXCEEDED면 plan 쿼리를 invalidate하고 에러를 노출한다', async () => {
    vi.mocked(sha256Base64).mockResolvedValue(HASH);
    vi.mocked(createPaper).mockRejectedValue(new ApiError('한도 초과', 'PAPER_USAGE_LIMIT_EXCEEDED', 429));
    const qc = new QueryClient();
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const { container } = renderDialogWith(qc);

    selectPdfAndUpload(container);

    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['plan'] }));
    await waitFor(() => expect(screen.getByText(/PAPER_USAGE_LIMIT_EXCEEDED/)).toBeTruthy());
  });

  it('한도와 무관한 실패는 plan을 invalidate하지 않는다', async () => {
    vi.mocked(sha256Base64).mockResolvedValue(HASH);
    vi.mocked(createPaper).mockRejectedValue(new ApiError('중복 파일명', 'DUPLICATE_FILENAME', 409));
    const qc = new QueryClient();
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const { container } = renderDialogWith(qc);

    selectPdfAndUpload(container);

    await waitFor(() => expect(screen.getByText(/DUPLICATE_FILENAME/)).toBeTruthy());
    expect(invalidateSpy).not.toHaveBeenCalledWith({ queryKey: ['plan'] });
  });
});
