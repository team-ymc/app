// BE `paper.upload.max-file-size`와 같은 값을 둔다. 강제는 BE와 S3가 하고 여기선 사전 안내만 한다 —
export const MAX_PDF_UPLOAD_BYTES = 50 * 1024 * 1024;

type UploadCandidate = Pick<File, 'type' | 'size'>;

export function validatePdfUpload(file: UploadCandidate): string | null {
  if (file.type !== 'application/pdf') return 'PDF 파일만 업로드할 수 있습니다';
  if (file.size > MAX_PDF_UPLOAD_BYTES) return 'PDF 파일은 최대 50 MiB까지 업로드할 수 있습니다';
  return null;
}
