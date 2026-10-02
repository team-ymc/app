import { useEffect, useRef, useState, type ComponentType } from 'react';
import { Link, useNavigate } from 'react-router';
import {
  ArrowDown, ArrowUpRight, BookOpen, ChatCircleText,
  CursorText, Graph, Lightbulb, Translate,
} from '@phosphor-icons/react';
import { useAuth } from '../auth/AuthContext';
import { PaperStackMark } from '../design/components/PaperStackMark';
import { GlobalNav } from '../nav/GlobalNav';
import './FeaturesPage.css';

type Demo = {
  label: string;
  image: string;
  video: string;
  alt: string;
  caption: string;
};

type Feature = {
  id: string;
  label: string;
  english: string;
  summary: string;
  icon: ComponentType<{ size?: number; weight?: 'regular' | 'duotone' }>;
  title: [string, string];
  description: string;
  steps: [string, string, string];
  demos: Demo[];
};

const features: Feature[] = [
  {
    id: 'prerequisites', label: '선행지식', english: 'PREREQUISITE KNOWLEDGE',
    summary: '논문에 필요한 선행지식 확인하기', icon: Lightbulb,
    title: ['논문에 필요한 선행지식,', '클릭해서 확인하세요.'],
    description: '논문을 이해하는 데 필요한 개념을 본문에서 바로 찾으세요. 하이라이트를 누르면 짧은 설명 카드가 열려, 읽던 흐름 그대로 이해를 이어갈 수 있어요.',
    steps: ['상단의 선행지식 표시를 켜세요.', '궁금한 하이라이트를 누르세요.', '설명 카드에서 개념을 확인하세요.'],
    demos: [{ label: '선행지식 켜기 → 개념 카드', image: 'prerequisite-poster.jpg', video: 'prerequisite.mp4',
      alt: 'attention mechanism 하이라이트를 눌러 영어와 한국어 개념 설명 카드가 열린 실제 논문 화면',
      caption: '하이라이트된 용어를 누르면, 설명이 바로 옆에 열립니다.' }],
  },
  {
    id: 'chat', label: 'AI 튜터', english: 'CHAT WITH YOUR PAPER',
    summary: '논문 옆에서 바로 질문하기', icon: ChatCircleText,
    title: ['논문에 대해 궁금한 점,', 'AI 튜터에게 물어보세요.'],
    description: '논문의 핵심부터 이해가 안 되는 부분까지, AI 튜터에게 편하게 물어보세요. 본문과 채팅을 함께 보면서 궁금한 점을 하나씩 풀어갈 수 있어요.',
    steps: ['논문 옆 AI 튜터 패널을 여세요.', '궁금한 내용을 자연스럽게 입력하세요.', '읽고, 묻고, 이어서 질문하세요.'],
    demos: [{ label: '논문 옆에서 질문 작성하기', image: 'chat-poster.jpg', video: 'chat.mp4',
      alt: '논문 본문 옆 AI 튜터 패널에 Transformer와 RNN의 차이를 묻는 질문을 작성한 실제 화면',
      caption: '“Transformer와 RNN의 차이를 쉽게 설명해주세요.”' }],
  },
  {
    id: 'inline-query', label: '인라인 질문', english: 'ASK WITH CONTEXT',
    summary: '문장·그림·표·수식을 질문에 담기', icon: CursorText,
    title: ['문장·그림·표·수식을', '첨부해 질문하세요.'],
    description: '문장은 드래그하고, 그림·표·수식은 클릭하세요. 질문하기를 누르면 선택한 내용이 채팅에 첨부됩니다. 어떤 부분을 말하는지 길게 설명할 필요가 없어요.',
    steps: ['궁금한 문장이나 구성요소를 선택하세요.', '질문하기를 눌러 채팅에 첨부하세요.', '선택한 내용에 대한 질문을 입력하세요.'],
    demos: [{ label: '인용 → 이미지 → 표', image: 'inline-query-poster.jpg', video: 'inline-query.mp4',
      alt: '문장 드래그, 그림 클릭, 표 클릭의 세 가지 독립적인 예시에서 실제 첨부와 질문 입력을 연속으로 보여주는 영상',
      caption: '선택 → 질문하기 → 첨부. 인용·이미지·표, 세 가지 예시를 이어서 보세요.' }],
  },
  {
    id: 'translation', label: '번역', english: 'READ IN TWO LANGUAGES',
    summary: '필요한 문장부터 논문 전체까지', icon: Translate,
    title: ['필요한 문장부터', '논문 전체까지 번역하세요.'],
    description: '잠깐 막히는 문장은 드래그해서 번역을 확인하세요. 논문 전체를 읽을 때는 번역을 켜고, 원문 아래 또는 옆에 한국어를 두고 비교하며 읽을 수 있어요.',
    steps: ['문장을 드래그하고 번역을 누르세요.', '전체 번역은 상단의 번역 버튼으로 켜세요.', '버튼을 다시 눌러 아래·옆 배치를 바꾸세요.'],
    demos: [{ label: '전체 번역 → 인라인 번역', image: 'translation-poster.jpg', video: 'translation.mp4',
      alt: '채팅을 닫은 논문에서 원문 아래, 원문 옆 전체 번역과 드래그한 문장의 인라인 번역을 보여주는 영상',
      caption: '전체 번역은 아래·옆으로. 필요한 문장만 번역할 때는 드래그하세요.' }],
  },
  {
    id: 'bookmap', label: '북맵', english: 'SEE THE BIG PICTURE',
    summary: '전체 구조를 보고 섹션별로 읽기', icon: Graph,
    title: ['북맵으로 논문 구조를 보고,', '섹션별로 읽으세요.'],
    description: '북맵에서 논문의 섹션과 하위 섹션을 한눈에 살펴보세요. 궁금한 섹션을 누르면 본문이 열리고, 이전·다음 섹션으로 이동하며 구조를 따라 읽을 수 있어요.',
    steps: ['상단의 지식 그래프를 여세요.', '북맵을 펼쳐 섹션의 구조를 살펴보세요.', '섹션을 눌러 본문과 번역을 읽으세요.'],
    demos: [{ label: '구조 펼치기 → 섹션별 읽기', image: 'bookmap-poster.jpg', video: 'bookmap.mp4',
      alt: '접힌 북맵에서 섹션과 하위 섹션을 펼치고 전체 구조 확인, 섹션 클릭과 다음 섹션 이동을 보여주는 영상',
      caption: '한 권에서 전체 구조로. 섹션을 누르고, 이전·다음 버튼으로 이어 읽으세요.' }],
  },
];

const asset = (name: string) => `/features/${name}`;

function FeatureDemo({ feature }: { feature: Feature }) {
  const [visible, setVisible] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [paused, setPaused] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [failed, setFailed] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);
  const screenRef = useRef<HTMLButtonElement>(null);
  const demo = feature.demos[0];

  useEffect(() => {
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
    setPaused(motion.matches);
    const changeMotion = () => setPaused(motion.matches);
    motion.addEventListener('change', changeMotion);
    const observer = new IntersectionObserver(([entry]) => {
      setVisible(entry.isIntersecting);
      if (entry.isIntersecting) setLoaded(true);
    }, { threshold: .15 });
    if (screenRef.current) observer.observe(screenRef.current);
    return () => { observer.disconnect(); motion.removeEventListener('change', changeMotion); };
  }, []);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    let cancelled = false;
    if (visible && !paused && !failed) {
      void video.play().catch(() => { if (!cancelled) setPaused(true); });
    } else video.pause();
    return () => { cancelled = true; };
  }, [visible, paused, failed]);

  return (
    <figure className="features-demo">
      <button className="features-demo-screen" ref={screenRef} type="button" disabled={failed}
        aria-pressed={playing} aria-label={`${feature.label} 영상 ${playing ? '멈추기' : '재생하기'}`}
        onClick={() => setPaused(playing)}>
        {failed ? <img src={asset(demo.image)} alt={demo.alt} width="1224" height="784" /> :
          <video ref={videoRef} src={loaded ? asset(demo.video) : undefined} poster={asset(demo.image)}
            autoPlay muted loop playsInline preload="metadata" width="1224" height="784" aria-label={demo.alt}
            onPlay={() => setPlaying(true)} onPause={() => setPlaying(false)} onError={() => setFailed(true)} />}
      </button>
    </figure>
  );
}

export default function FeaturesPage() {
  const { status, startLogin, initialError } = useAuth();
  const navigate = useNavigate();
  const start = () => {
    if (status === 'authed') navigate('/library');
    else if (status === 'guest') startLogin();
  };

  return (
    <div className="features-page" id="top">
      <div className="features-nav"><GlobalNav /></div>
      <main>
        <section className="features-hero">
          <div className="features-container">
            <p className="features-eyebrow"><span /> PAPER TEACHER FEATURES <span /></p>
            <h1>논문을 읽다 막히는 순간,<br /><em>필요한 도움을 그 자리에서.</em></h1>
            <p className="features-hero-copy">낯선 개념은 확인하고, 궁금한 내용은 묻고, 전체 흐름까지 살펴보세요.<br className="features-desktop-break" /> 논문을 읽고 이해하는 다섯 가지 방법을 소개합니다.</p>
            <a className="features-discover" href="#prerequisites">하나씩 살펴보기 <ArrowDown size={15} /></a>
            <div className="features-overview" aria-label="핵심 기능 한눈에 보기">
              {features.map((feature, index) => {
                const Icon = feature.icon;
                return <a key={feature.id} href={`#${feature.id}`}>
                  <div className="features-overview-top"><Icon size={24} weight="duotone" /><span>0{index + 1}</span></div>
                  <strong>{feature.label}</strong><span className="features-overview-summary">{feature.summary}</span>
                  <ArrowDown size={14} className="features-overview-arrow" aria-hidden="true" />
                </a>;
              })}
            </div>
          </div>
        </section>

        <nav className="features-jump-nav" aria-label="기능 바로가기">
          <div className="features-container">{features.map((feature, index) => (
            <a key={feature.id} href={`#${feature.id}`}><span>0{index + 1}</span>{feature.label}</a>
          ))}</div>
        </nav>

        <div className="features-sections">
          {features.map((feature, index) => (
            <section key={feature.id} id={feature.id} aria-labelledby={`${feature.id}-title`}
              className={`features-section${index % 2 === 1 ? ' features-section-reverse' : ''}${feature.id === 'bookmap' ? ' features-section-bookmap' : ''}`}>
              <div className="features-container features-section-layout">
                <div className="features-section-copy">
                  <p className="features-kicker"><span>0{index + 1}</span>{feature.english}</p>
                  <h2 id={`${feature.id}-title`}>{feature.title[0]}<br />{feature.title[1]}</h2>
                  <p className="features-description">{feature.description}</p>
                  <ol className="features-steps">{feature.steps.map((step, i) => <li key={step}><span aria-hidden="true">{i + 1}</span>{step}</li>)}</ol>
                </div>
                <FeatureDemo feature={feature} />
              </div>
            </section>
          ))}
        </div>

        <section className="features-closing">
          <div className="features-container">
            <BookOpen size={30} weight="duotone" />
            <p className="features-kicker">YOUR NEXT PAPER, UNDERSTOOD.</p>
            <h2>다음 논문은,<br />이해하면서 읽어보세요.</h2>
            <p>읽고 싶은 PDF 한 편으로 시작해보세요.</p>
            <button className="features-start" type="button" onClick={start} disabled={status === 'loading'}>무료로 시작하기 <ArrowUpRight size={18} /></button>
            <Link className="features-plans-link" to="/plans">플랜 살펴보기 <ArrowUpRight size={14} /></Link>
            {initialError && <p className="features-error" role="alert">{initialError}</p>}
          </div>
        </section>
      </main>
      <footer className="features-footer"><div className="features-container">
        <Link to="/" className="features-brand"><PaperStackMark size={22} color="currentColor" />Paper Teacher</Link>
        <span>논문을 이해하는 더 나은 방법</span>
        <a href="#top">맨 위로 ↑</a>
      </div></footer>
    </div>
  );
}
