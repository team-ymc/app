import { describe, it, expect } from 'vitest';
import { knowledgeGraphDisabledReason, statusRefetchInterval } from './knowledgeGraphStatus';
import { TRANSLATION_POLL_MS } from './translationStatus';

describe('knowledgeGraphDisabledReason', () => {
  it('상태별 툴팁 문구를 아트보드 그대로 돌려준다', () => {
    expect(knowledgeGraphDisabledReason('PENDING')).toBe('지식 그래프를 준비하고 있습니다');
    expect(knowledgeGraphDisabledReason('FAILED')).toBe('지식 그래프를 준비하지 못했습니다');
  });

  it('READY면 비활성 사유가 없다', () => {
    expect(knowledgeGraphDisabledReason('READY')).toBe('');
  });

  it('null(Document 연결 전)·undefined는 실패 문구다', () => {
    expect(knowledgeGraphDisabledReason(null)).toBe('지식 그래프를 준비하지 못했습니다');
    expect(knowledgeGraphDisabledReason(undefined)).toBe('지식 그래프를 준비하지 못했습니다');
  });
});

describe('statusRefetchInterval', () => {
  it('번역·지식 그래프 중 하나라도 PENDING이면 5초 폴링', () => {
    expect(statusRefetchInterval({ translationStatus: 'PENDING', knowledgeGraphStatus: 'READY' })).toBe(TRANSLATION_POLL_MS);
    expect(statusRefetchInterval({ translationStatus: 'READY', knowledgeGraphStatus: 'PENDING' })).toBe(TRANSLATION_POLL_MS);
    expect(statusRefetchInterval({ translationStatus: 'NOT_APPLICABLE', knowledgeGraphStatus: 'PENDING' })).toBe(TRANSLATION_POLL_MS);
  });

  it('둘 다 종결이거나 null이면 폴링하지 않는다', () => {
    expect(statusRefetchInterval({ translationStatus: 'READY', knowledgeGraphStatus: 'READY' })).toBe(false);
    expect(statusRefetchInterval({ translationStatus: 'FAILED', knowledgeGraphStatus: 'FAILED' })).toBe(false);
    expect(statusRefetchInterval({ translationStatus: 'NOT_APPLICABLE', knowledgeGraphStatus: null })).toBe(false);
    expect(statusRefetchInterval(undefined)).toBe(false);
  });
});
