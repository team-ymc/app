import { ApiError } from '../api/types';

/** 재시도 실패를 사용자 문구로 바꾼다. 한도 초과는 BE 문구가 플랜 안내와 같아 그대로 쓴다. */
export function retryErrorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.code === 'PAPER_USAGE_LIMIT_EXCEEDED') return e.message;
    if (e.code === 'RETRY_LIMIT_EXCEEDED') return '처리할 수 없는 파일입니다';
    if (e.code === 'PAPER_NOT_RETRYABLE') return '다시 시도할 수 있는 상태가 아닙니다';
    if (e.httpStatus === 404) return '삭제되었거나 없는 논문입니다';
  }
  return '다시 시도하지 못했습니다';
}

/** 학습 화면용. 상한 초과는 파일이 아니라 번역·지식 그래프 생성이 막힌 것이다. */
export function compileRetryErrorMessage(e: unknown): string {
  if (e instanceof ApiError && e.code === 'RETRY_LIMIT_EXCEEDED') return '번역과 지식 그래프를 더 이상 다시 만들 수 없습니다';
  return retryErrorMessage(e);
}
