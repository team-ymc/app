// 체험 논문 목록. 주제·소개 문구는 마케팅 문구라 정적으로 두고, paperId만 환경 변수로 받는다
// (dev·prod가 다르다). VITE_TRIAL_PAPER_IDS는 아래 순서대로 쉼표로 잇는다.
import { isDevPreview } from '../dev/preview';

export interface TrialPaper {
  topic: string;
  topicKey: string;
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

// 미리보기(dev:preview)는 완료 상태인 샘플 논문 한 편을 다섯 자리에 쓴다.
const PREVIEW_IDS = Array(5).fill('preview-transformers').join(',');

export function trialPapers(
  idsEnv: string | undefined = import.meta.env.VITE_TRIAL_PAPER_IDS ?? (isDevPreview ? PREVIEW_IDS : undefined),
): TrialPaper[] {
  const ids = (idsEnv ?? '').split(',').map((s) => s.trim());
  return ENTRIES.map((e, i) => ({ ...e, paperId: ids[i] || null }));
}
