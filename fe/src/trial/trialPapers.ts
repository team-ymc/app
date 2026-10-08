// 체험 논문 목록. 주제·소개 문구는 마케팅 문구라 정적으로 두고, paperId는 호스트별로 코드에 박는다
// (dist 하나를 dev·prod에 그대로 올리므로 빌드 시점 환경 변수는 쓸 수 없다 — analytics.ts의 토큰과 같은 방식).
// 논문을 바꾸면 여기만 고친다. 체험 경로는 paper.trial인 논문만 응답하므로 id가 공개돼도 문제없다.
import { isDevPreview } from '../dev/preview';

export type TrialTopicKey = 'love' | 'ai' | 'semiconductor' | 'space' | 'sleep';

/** 호스트별 체험 논문 paperId. 마스터 계정이 올린 뒤 trial을 켠 논문이다. 비어 있으면 "준비 중"으로 보인다. */
export const TRIAL_PAPER_IDS: Record<string, Partial<Record<TrialTopicKey, string>>> = {
  'dev.papertutor.co.kr': {
    love: '1e90a612-731b-40bc-8a57-267326ca0882',
    ai: '374defd7-bc38-4655-8025-3cc443c761e1',
    semiconductor: '71d0231d-73be-442a-8c25-6a327f07dae6',
    space: '03bb177f-bf69-4bca-b603-2f4202998790',
    sleep: 'a8c6b6a3-d141-4c1f-8046-b1927ef207fa',
  },
  'papertutor.co.kr': {
    love: '572d3d66-181d-4364-8319-352317d3405a',
    ai: '35cc834e-ff84-4159-b9a7-c5c9716da9a3',
    semiconductor: '43710f00-174a-43a3-bd65-d5a21bd2451c',
    space: 'dde1989b-99b0-4387-8a2a-034fb382077f',
    // sleep: prod에서는 당분간 '준비 중'으로 둔다. 켤 때 0209060b-f4fd-4cfe-ad98-aea5b7fd9b79.
  },
};

const PREVIEW_IDS: Record<TrialTopicKey, string> = {
  love: 'preview-transformers', ai: 'preview-transformers', semiconductor: 'preview-transformers',
  space: 'preview-transformers', sleep: 'preview-transformers',
};

export interface TrialPaper {
  topic: string;
  topicKey: TrialTopicKey;
  title: string;
  hook: string;
  year: string;
  venue: string;
  image: string;
  paperId: string | null;
}

const ENTRIES: Omit<TrialPaper, 'paperId'>[] = [
  { topicKey: 'love', topic: '사랑', title: 'Love-related Changes in the Brain: A Resting-state fMRI Study', hook: '사랑에 빠진 사람, 막 이별한 사람, 연애한 적 없는 사람. 가만히 쉬고 있을 때조차 세 집단의 뇌는 다르게 움직인다.', year: '2015', venue: 'Frontiers in Human Neuroscience', image: '/trial/love.jpg' },
  { topicKey: 'ai', topic: 'AI', title: 'Attention Is All You Need', hook: 'ChatGPT를 비롯한 오늘날 언어 모델의 뼈대, 트랜스포머가 처음 등장한 논문.', year: '2017', venue: 'NeurIPS', image: '/trial/attention.jpg' },
  { topicKey: 'semiconductor', topic: '반도체', title: 'Cramming More Components onto Integrated Circuits', hook: "반세기 동안 반도체 산업을 이끈 '무어의 법칙'이 처음 쓰인 네 쪽짜리 글.", year: '1965', venue: 'Electronics', image: '/trial/moore.jpg' },
  { topicKey: 'space', topic: '우주', title: 'Observation of Gravitational Waves from a Binary Black Hole Merger', hook: '13억 년 전 두 블랙홀이 합쳐지며 낸 시공간의 떨림을 인류가 처음 들은 날의 기록.', year: '2016', venue: 'Physical Review Letters', image: '/trial/ligo.jpg' },
  { topicKey: 'sleep', topic: '수면', title: 'Sleep Loss Causes Social Withdrawal and Loneliness', hook: '하룻밤 잠을 설치면 사람을 피하게 되고, 그 외로움은 옆 사람에게까지 옮는다. 뇌 영상과 실험으로 보인 연구.', year: '2018', venue: 'Nature Communications', image: '/trial/sleep.jpg' },
];

export function trialPapers(
  hostname: string = typeof window === 'undefined' ? '' : window.location.hostname,
): TrialPaper[] {
  const ids = isDevPreview ? PREVIEW_IDS : (TRIAL_PAPER_IDS[hostname] ?? {});
  return ENTRIES.map((e) => ({ ...e, paperId: ids[e.topicKey] ?? null }));
}
