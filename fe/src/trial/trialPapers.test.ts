import { describe, expect, it } from 'vitest';
import { trialPapers } from './trialPapers';

describe('trialPapers', () => {
  it('환경 변수의 id를 주제 순서대로 붙인다', () => {
    const papers = trialPapers('a, b,c,d,e');
    expect(papers.map((p) => p.topic)).toEqual(['사랑', 'AI', '반도체', '우주', '수면']);
    expect(papers.map((p) => p.paperId)).toEqual(['a', 'b', 'c', 'd', 'e']);
  });

  it('id가 모자라면 그 자리는 null이다', () => {
    const papers = trialPapers('a,b');
    expect(papers[1].paperId).toBe('b');
    expect(papers[2].paperId).toBeNull();
    expect(trialPapers(undefined).every((p) => p.paperId === null)).toBe(true);
  });
});
