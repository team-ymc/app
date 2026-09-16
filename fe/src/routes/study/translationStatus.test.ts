import { describe, it, expect } from 'vitest';
import { translationRefetchInterval, translationDisabledReason, TRANSLATION_POLL_MS } from './translationStatus';

describe('translationRefetchInterval', () => {
  it('PENDING일 때만 5초 폴링이고 나머지는 폴링하지 않는다', () => {
    expect(translationRefetchInterval('PENDING')).toBe(TRANSLATION_POLL_MS);
    expect(translationRefetchInterval('READY')).toBe(false);
    expect(translationRefetchInterval('FAILED')).toBe(false);
    expect(translationRefetchInterval('NOT_APPLICABLE')).toBe(false);
    expect(translationRefetchInterval(undefined)).toBe(false);
  });
});

describe('translationDisabledReason', () => {
  it('상태별 툴팁 문구를 아트보드 그대로 돌려준다', () => {
    expect(translationDisabledReason('NOT_APPLICABLE', false)).toBe('한국어 논문은 번역하지 않습니다');
    expect(translationDisabledReason('PENDING', false)).toBe('번역을 준비하고 있습니다');
    expect(translationDisabledReason('FAILED', false)).toBe('번역 생성에 실패했습니다');
  });

  it('READY인데 번역 블록이 없으면 준비 안 됨 문구다', () => {
    expect(translationDisabledReason('READY', false)).toBe('이 논문은 번역이 준비되지 않았습니다');
    expect(translationDisabledReason(undefined, false)).toBe('이 논문은 번역이 준비되지 않았습니다');
  });

  it('READY이고 번역 블록이 있으면 비활성 사유가 없다', () => {
    expect(translationDisabledReason('READY', true)).toBe('');
  });
});
