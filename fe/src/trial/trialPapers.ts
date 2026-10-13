// 체험 논문 목록. 주제·소개 문구는 마케팅 문구라 정적으로 두고, paperId는 호스트별로 코드에 박는다
// (dist 하나를 dev·prod에 그대로 올리므로 빌드 시점 환경 변수는 쓸 수 없다 — analytics.ts의 토큰과 같은 방식).
// 논문을 바꾸면 여기만 고친다. 체험 경로는 paper.trial인 논문만 응답하므로 id가 공개돼도 문제없다.
import { isDevPreview } from '../dev/preview';

export type TrialTopicKey = 'game' | 'sleep' | 'money' | 'art' | 'love' | 'music' | 'language' | 'space' | 'happiness';

/** 호스트별 체험 논문 paperId. 마스터 계정이 올린 뒤 trial을 켠 논문이다. 비어 있으면 "준비 중"으로 보인다. */
export const TRIAL_PAPER_IDS: Record<string, Partial<Record<TrialTopicKey, string>>> = {
  'dev.papertutor.co.kr': {
    game: 'd881cd9e-e2da-4efd-86ce-cf59454eaa22',
    sleep: '2e38e5ea-b696-473a-8891-157ee827a445',
    money: 'fe8e1932-af02-47c0-9b4f-cc1413abbf17',
    art: '06b57e6d-c8cc-4e6d-ab43-6622534d6937',
    love: '6b413b15-4b28-4fe1-b9e0-0acd4c0567f5',
    music: 'a43fff95-69c0-42a2-8b2d-be12632c69c3',
    language: '0b6a9dfd-b07a-4da5-8528-b50da96db220',
    space: 'ab84c414-b17d-44c9-8dbf-4dd8bcb37bb5',
    happiness: 'dfda6c8b-9f64-4a75-a432-70fbc31449e0',
  },
  'papertutor.co.kr': {
    game: '7e717495-f5ee-4b60-a8dc-5437a40fd9e4',
    sleep: 'ff8aa871-858e-421b-b807-ed788f659c65',
    money: 'b4d5ea89-b6bf-4bb9-85f9-f21103f969ec',
    // art: prod 파싱이 OCR 일시 장애로 실패해 '준비 중'으로 둔다. 재시도 성공 후 b40ef02e-3dbc-483d-9312-358a4da9f424.
    love: 'f573c6f5-c328-4910-bc0d-26c287fd6974',
    music: '1823d914-7d97-4306-87bd-a57ea7edf8ff',
    language: '6c420ddd-86ee-4c57-bca6-18c9fd7551e3',
    space: '4966b9cf-1c14-42dc-9445-81f79daeb45e',
    happiness: 'a9e04bbe-03b8-4be8-9057-f29dbcc915ad',
  },
};

const PREVIEW_IDS: Record<TrialTopicKey, string> = {
  game: 'preview-transformers', sleep: 'preview-transformers', money: 'preview-transformers',
  art: 'preview-transformers', love: 'preview-transformers', music: 'preview-transformers',
  language: 'preview-transformers', space: 'preview-transformers', happiness: 'preview-transformers',
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
  { topicKey: 'game', topic: '게임', title: 'Playing Atari with Deep Reinforcement Learning', hook: '화면 픽셀만 보고 아타리 게임을 사람보다 잘하게 된 첫 신경망. 지금의 딥마인드가 여기서 시작됐다.', year: '2013', venue: 'arXiv', image: '/trial/game.jpg' },
  { topicKey: 'sleep', topic: '수면', title: 'Why Dream? A Conjecture on Dreaming', hook: '잠은 왜 필요하고 꿈은 왜 꾸는가. 몸이 세포를 갈아 끼우는 동안 뇌가 새 회로를 점검한다는 가설.', year: '2009', venue: 'arXiv', image: '/trial/sleep.jpg' },
  { topicKey: 'money', topic: '돈', title: 'Bitcoin: A Peer-to-Peer Electronic Cash System', hook: '은행 없이 돈을 보내는 방법을 아홉 쪽에 담은, 비트코인의 출발점.', year: '2008', venue: 'bitcoin.org', image: '/trial/money.jpg' },
  { topicKey: 'art', topic: '미술', title: 'Instance Normalization: The Missing Ingredient for Fast Stylization', hook: '사진을 고흐 풍으로 바꾸는 스타일 변환. 정규화 한 줄을 바꿔 품질을 끌어올린 발견.', year: '2016', venue: 'arXiv', image: '/trial/art.jpg' },
  { topicKey: 'love', topic: '사랑', title: 'Do LLMs Give Good Romantic Relationship Advice?', hook: '연애 고민을 AI에게 털어놓으면 어떻게 될까. 만족도와 태도 변화를 실험으로 본 연구.', year: '2025', venue: 'preprint', image: '/trial/love.jpg' },
  { topicKey: 'music', topic: '음악', title: 'Simple and Controllable Music Generation', hook: '문장 한 줄로 음악을 만드는 MusicGen. 멜로디까지 조건으로 줄 수 있다.', year: '2023', venue: 'arXiv', image: '/trial/music.jpg' },
  { topicKey: 'language', topic: '언어', title: 'Efficient Estimation of Word Representations in Vector Space', hook: '왕 − 남자 + 여자 = 여왕. 단어를 숫자로 바꾸는 word2vec이 처음 나온 논문.', year: '2013', venue: 'arXiv', image: '/trial/language.jpg' },
  { topicKey: 'space', topic: '우주', title: 'Dissolving the Fermi Paradox', hook: '외계인은 왜 안 보이는가. 불확실성을 제대로 계산하면 역설이 사라진다는 주장.', year: '2018', venue: 'arXiv', image: '/trial/space.jpg' },
  { topicKey: 'happiness', topic: '행복', title: 'Happiness Is Assortative in Online Social Networks', hook: '트위터 글을 분석하니 행복한 사람은 행복한 사람끼리 이어져 있었다. 감정이 네트워크에서 모이는 방식.', year: '2011', venue: 'arXiv', image: '/trial/happiness.jpg' },
];

export function trialPapers(
  hostname: string = typeof window === 'undefined' ? '' : window.location.hostname,
): TrialPaper[] {
  const ids = isDevPreview ? PREVIEW_IDS : (TRIAL_PAPER_IDS[hostname] ?? {});
  return ENTRIES.map((e) => ({ ...e, paperId: ids[e.topicKey] ?? null }));
}

/** 오늘의 논문. 준비된 논문을 현지 날짜 기준으로 하루에 하나씩 돌아가며 고른다. */
export function pickTodaysPaper(papers: TrialPaper[], date: Date = new Date()): TrialPaper {
  const ready = papers.filter((p) => p.paperId);
  const pool = ready.length > 0 ? ready : papers;
  const day = Math.floor((date.getTime() - date.getTimezoneOffset() * 60_000) / 86_400_000);
  return pool[day % pool.length];
}
