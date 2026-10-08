import { describe, expect, it } from 'vitest';
import { TRIAL_PAPER_IDS, trialPapers } from './trialPapers';

describe('trialPapers', () => {
  it('호스트에 등록된 id를 주제별로 붙이고, 없는 주제는 null이다', () => {
    TRIAL_PAPER_IDS['test.local'] = { love: 'a', ai: 'b' };
    const papers = trialPapers('test.local');
    expect(papers.map((p) => p.topic)).toEqual(['사랑', 'AI', '반도체', '우주', '수면']);
    expect(papers.map((p) => p.paperId)).toEqual(['a', 'b', null, null, null]);
  });

  it('모르는 호스트면 전부 null이다', () => {
    expect(trialPapers('unknown.local').every((p) => p.paperId === null)).toBe(true);
  });
});
