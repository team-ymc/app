import { describe, expect, it } from 'vitest';
import { MAX_PDF_UPLOAD_BYTES, validatePdfUpload } from './uploadValidation';

describe('validatePdfUpload', () => {
  it('50 MiB까지 허용한다', () => {
    expect(validatePdfUpload({ type: 'application/pdf', size: MAX_PDF_UPLOAD_BYTES })).toBeNull();
  });

  it('50 MiB를 1 byte라도 넘으면 거절한다', () => {
    expect(validatePdfUpload({ type: 'application/pdf', size: MAX_PDF_UPLOAD_BYTES + 1 }))
      .toBe('PDF 파일은 최대 50 MiB까지 업로드할 수 있습니다');
  });

  it('PDF가 아닌 파일은 거절한다', () => {
    expect(validatePdfUpload({ type: 'image/png', size: 1 })).toBe('PDF 파일만 업로드할 수 있습니다');
  });
});
