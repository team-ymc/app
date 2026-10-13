import { describe, expect, it } from 'vitest';
import { TRIAL_PAPER_IDS, pickTodaysPaper, trialPapers } from './trialPapers';

describe('trialPapers', () => {
  it('호스트에 등록된 id를 주제별로 붙이고, 없는 주제는 null이다', () => {
    TRIAL_PAPER_IDS['test.local'] = { game: 'a', sleep: 'b' };
    const papers = trialPapers('test.local');
    expect(papers.map((p) => p.topic)).toEqual(['게임', '수면', '돈', '미술', '사랑', '음악', '언어', '우주', '행복']);
    expect(papers.map((p) => p.paperId)).toEqual(['a', 'b', null, null, null, null, null, null, null]);
  });

  it('모르는 호스트면 전부 null이다', () => {
    expect(trialPapers('unknown.local').every((p) => p.paperId === null)).toBe(true);
  });
});

describe('pickTodaysPaper', () => {
  it('준비된 논문 가운데 날짜별로 하나씩 돌아가며 고른다', () => {
    TRIAL_PAPER_IDS['rotate.local'] = { game: 'g', money: 'm', space: 's' };
    const papers = trialPapers('rotate.local');
    const day = (iso: string) => pickTodaysPaper(papers, new Date(iso)).topicKey;
    const picked = [day('2026-10-13'), day('2026-10-14'), day('2026-10-15')];
    expect(new Set(picked)).toEqual(new Set(['game', 'money', 'space']));
    expect(day('2026-10-16')).toBe(picked[0]);
  });

  it('같은 날은 같은 논문이다', () => {
    const papers = trialPapers('rotate.local');
    expect(pickTodaysPaper(papers, new Date('2026-10-13T00:30:00')).topicKey)
      .toBe(pickTodaysPaper(papers, new Date('2026-10-13T23:30:00')).topicKey);
  });

  it('준비된 논문이 없으면 전체에서 고른다', () => {
    const papers = trialPapers('unknown.local');
    const picked = pickTodaysPaper(papers, new Date('2026-10-13'));
    expect(papers).toContain(picked);
    expect(pickTodaysPaper(papers, new Date('2026-10-22'))).toBe(picked);
  });
});
