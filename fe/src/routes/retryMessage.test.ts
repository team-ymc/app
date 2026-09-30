import { describe, it, expect } from 'vitest';
import { retryErrorMessage, compileRetryErrorMessage } from './retryMessage';
import { ApiError } from '../api/types';

describe('retryErrorMessage', () => {
  it('한도 초과는 BE 문구를 그대로 쓴다', () => {
    const e = new ApiError('이번 달 문서 등록 횟수를 모두 사용했습니다.', 'PAPER_USAGE_LIMIT_EXCEEDED', 429);
    expect(retryErrorMessage(e)).toBe('이번 달 문서 등록 횟수를 모두 사용했습니다.');
  });

  it('시도 횟수 소진은 처리할 수 없는 파일로 안내한다', () => {
    const e = new ApiError('x', 'RETRY_LIMIT_EXCEEDED', 409);
    expect(retryErrorMessage(e)).toBe('처리할 수 없는 파일입니다');
  });

  it('학습 화면의 상한 초과는 번역·지식 그래프 문구다', () => {
    const e = new ApiError('x', 'RETRY_LIMIT_EXCEEDED', 409);
    expect(compileRetryErrorMessage(e)).toBe('번역과 지식 그래프를 더 이상 다시 만들 수 없습니다');
    expect(compileRetryErrorMessage(new ApiError('x', 'PAPER_NOT_RETRYABLE', 409))).toBe('다시 시도할 수 있는 상태가 아닙니다');
  });

  it('재시도 대상이 아니면 그렇게 안내한다', () => {
    const e = new ApiError('x', 'PAPER_NOT_RETRYABLE', 409);
    expect(retryErrorMessage(e)).toBe('다시 시도할 수 있는 상태가 아닙니다');
  });

  it('없는 논문은 삭제 안내를 한다', () => {
    const e = new ApiError('x', 'PAPER_NOT_FOUND', 404);
    expect(retryErrorMessage(e)).toBe('삭제되었거나 없는 논문입니다');
  });

  it('그 밖의 오류는 일반 문구다', () => {
    expect(retryErrorMessage(new Error('network'))).toBe('다시 시도하지 못했습니다');
    expect(retryErrorMessage(new ApiError('x', undefined, 500))).toBe('다시 시도하지 못했습니다');
  });
});
