import { Fragment, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { PaperStackMark } from '../design/components/PaperStackMark';
import { GlobalNav } from '../nav/GlobalNav';
import './LandingPage.css';

const image = (name: string) => `/landing/${name}.jpg`;

type Slide = { label: string; src: string; alt: string };
const viewerSlides: Slide[] = [
  { label: '본문', src: image('viewer'), alt: '논문 본문을 읽는 뷰어 화면' },
  { label: '이미지', src: image('viewer-figure'), alt: '논문에 실린 그래프 이미지를 보여주는 뷰어 화면' },
  { label: '수식', src: image('viewer-formula'), alt: '논문 수식을 보여주는 뷰어 화면' },
  { label: '표', src: image('viewer-table'), alt: '논문 데이터 표를 보여주는 뷰어 화면' },
];
const graphSlides: Slide[] = [
  { label: '전체 구조', src: image('graph-overview'), alt: '논문 섹션을 한눈에 펼친 지식 그래프 화면' },
  { label: '섹션 클릭', src: image('graph-section-detail'), alt: '지식 그래프의 섹션을 클릭해 오른쪽에 본문과 번역이 열린 화면' },
];

function Carousel({ slides, label }: { slides: Slide[]; label: string }) {
  const [current, setCurrent] = useState(0);
  const touchStartX = useRef<number | null>(null);
  const show = (index: number) => setCurrent((index + slides.length) % slides.length);
  return (
    <div className="visual-shell viewer-carousel" aria-label={`${label} 화면 예시`}
      onKeyDown={(event) => {
        if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
          event.preventDefault();
          show(current + (event.key === 'ArrowRight' ? 1 : -1));
        }
      }}
      onTouchStart={(event) => { touchStartX.current = event.touches[0]?.clientX ?? null; }}
      onTouchEnd={(event) => {
        if (touchStartX.current === null) return;
        const distance = event.changedTouches[0].clientX - touchStartX.current;
        if (Math.abs(distance) > 45) show(current + (distance < 0 ? 1 : -1));
        touchStartX.current = null;
      }}>
      <div className="viewer-stage">
        {slides.map((slide, index) => (
          <figure className="screen-slot image-slot viewer-slide" hidden={index !== current} key={slide.label}>
            <img src={slide.src} alt={slide.alt} loading="lazy" />
          </figure>
        ))}
        <button className="viewer-arrow previous" type="button" aria-label={`이전 ${label} 화면`} onClick={() => show(current - 1)}>‹</button>
        <button className="viewer-arrow next" type="button" aria-label={`다음 ${label} 화면`} onClick={() => show(current + 1)}>›</button>
      </div>
      <div className="viewer-controls">
        <div className="viewer-tabs" role="tablist" aria-label={`${label} 화면 종류`}>
          {slides.map((slide, index) => (
            <button type="button" role="tab" aria-label={slide.label} aria-selected={index === current}
              onClick={() => show(index)} key={slide.label} />
          ))}
        </div>
        <span className="viewer-count" aria-live="polite">
          {String(current + 1).padStart(2, '0')} / {String(slides.length).padStart(2, '0')}
        </span>
      </div>
    </div>
  );
}

function Screenshot({ name, alt }: { name: string; alt: string }) {
  return <div className="visual-shell"><div className="screen-slot image-slot"><img src={image(name)} alt={alt} loading="lazy" /></div></div>;
}

type Feature = {
  step: string; title: [string, string]; description: string; tags: string[]; caption: string;
  id?: string; reverse?: boolean; emphasis?: boolean; graph?: boolean;
  visual: { image: string; alt: string } | { slides: Slide[]; label: string };
};
const features: Feature[] = [
  { step: '01 / START', id: 'upload', title: ['논문을 올리고,', '내 서재에서 시작하세요.'], description: '읽고 싶은 PDF를 등록하면 내 서재에 담깁니다. 논문을 고르고, 다시 돌아와 읽을 수 있는 출발점입니다.', tags: ['PDF 업로드', '내 서재'], visual: { image: 'upload', alt: '내 서재 위에 열린 PDF 논문 업로드 창' }, caption: 'SCREEN 01 — UPLOAD & LIBRARY' },
  { step: '02 / READ', reverse: true, title: ['논문을 읽는 데', '집중할 수 있는 뷰어.'], description: '본문의 흐름을 따라가며 섹션, 그림, 표, 수식을 함께 살펴보세요. 읽다가 필요한 도움을 바로 이어서 받을 수 있습니다.', tags: ['논문 본문', '이미지·수식·표', '섹션 탐색'], visual: { slides: viewerSlides, label: '논문 뷰어' }, caption: 'SCREEN 02 — PAPER VIEWER' },
  { step: '03 / TRANSLATE', title: ['영어 원문 옆에', '한국어 번역을 함께.'], description: '원문을 가리지 않고 번역을 나란히 보세요. 표현을 비교하며 읽고, 필요한 때는 원문만으로 돌아갈 수 있습니다.', tags: ['원문 유지', '옆·아래 배치'], visual: { image: 'translation', alt: '영어 원문과 한국어 번역을 좌우로 함께 보여주는 화면' }, caption: 'SCREEN 03 — BILINGUAL READING' },
  { step: '04 / UNDERSTAND', reverse: true, title: ['낯선 개념은,', '그 자리에서 확인하세요.'], description: '선행지식 표시를 켜면 논문 속 관련 용어가 드러납니다. 하이라이트를 누르면 그 논문의 문맥에 맞는 설명을 확인할 수 있습니다.', tags: ['선행지식 하이라이트', '클릭해서 설명 보기'], visual: { image: 'highlight', alt: '논문 속 선행지식 하이라이트를 누르면 개념 설명이 열리는 화면' }, caption: 'SCREEN 04 — PREREQUISITE KNOWLEDGE' },
  { step: '05 / ASK', id: 'ai-tutor', emphasis: true, title: ['읽던 부분을', '그대로 인용해 물어보세요.'], description: '궁금한 문장을 드래그하거나 이미지·표를 클릭해 질문에 첨부하세요. AI 튜터가 논문의 맥락을 바탕으로 답하고, 이어서 질문할 수 있습니다.', tags: ['텍스트 드래그 인용', '이미지·표 첨부', 'AI 질의'], visual: { image: 'ai-tutor', alt: '논문 옆 AI 튜터가 질문에 답하는 화면' }, caption: 'SCREEN 05 — ASK WITH CONTEXT' },
  { step: '06 / CONNECT', id: 'knowledge-graph', graph: true, title: ['한 편의 논문을', '한눈에 펼쳐보세요.'], description: '섹션과 주요 개념이 어떻게 이어지는지 지식 그래프로 살펴보세요. 섹션을 누르면 해당 본문과 번역을 바로 읽을 수 있습니다.', tags: ['논문 내부 구조', '개념의 연결', '섹션 탐색'], visual: { slides: graphSlides, label: '지식 그래프' }, caption: 'SCREEN 06 — KNOWLEDGE GRAPH' },
];

function FeatureSection({ feature }: { feature: Feature }) {
  const visual = 'slides' in feature.visual
    ? <Carousel slides={feature.visual.slides} label={feature.visual.label} />
    : <Screenshot name={feature.visual.image} alt={feature.visual.alt} />;
  return (
    <section className={`feature${feature.reverse ? ' reverse' : ''}${feature.emphasis ? ' feature-emphasis' : ''}${feature.graph ? ' graph' : ''}`} id={feature.id}>
      <div className="feature-copy">
        <span className="feature-num">{feature.step}</span>
        <h2>{feature.title.map((line, index) => <Fragment key={line}>{index > 0 && <br />}{line}</Fragment>)}</h2>
        <p>{feature.description}</p>
        <div className="feature-detail">{feature.tags.map((tag) => <span key={tag}>{tag}</span>)}</div>
      </div>
      <div className="feature-visual">{visual}<p className="visual-caption">{feature.caption}</p></div>
    </section>
  );
}

export default function LandingPage() {
  const { status, startLogin, initialError } = useAuth();
  const navigate = useNavigate();
  const start = () => {
    if (status === 'authed') navigate('/library');
    else if (status === 'guest') startLogin();
  };
  const cta = () => <button className="button-primary" type="button" onClick={start} disabled={status === 'loading'}>논문 업로드하고 시작하기 <span aria-hidden="true">↗</span></button>;
  return (
    <div className="marketing-landing">
      <GlobalNav />
      <main id="top">
        <section className="hero"><div className="container hero-layout">
          <div>
            <div className="eyebrow">A BETTER WAY TO READ PAPERS</div>
            <h1>논문 리딩의<br />새로운 기준,<br /><em className="brand-line">Paper Teacher!!</em></h1>
            <p className="hero-copy">언어와 낯선 개념 앞에서 멈추지 않도록. 번역과 선행지식, AI 튜터와 지식 그래프가 논문을 이해하는 과정을 함께합니다.</p>
            <div className="hero-actions">{cta()}<a className="button-text" href="#journey">기능 살펴보기</a></div>
            {initialError && <p className="landing-error" role="alert">{initialError}</p>}
            <p className="cta-note">로그인 또는 회원가입 후 서재에서 논문을 업로드할 수 있어요.</p>
            <div className="hero-note"><span className="line" />한 편의 논문이 이해되는 여정</div>
          </div>
          <div className="hero-visual"><Screenshot name="translation" alt="논문 뷰어에서 영어 원문과 한국어 번역을 나란히 읽는 화면" /><div className="visual-tag">실제 제품 화면 ↗</div></div>
        </div></section>
        <section className="pain" id="pain"><div className="container">
          <div className="pain-top"><p className="section-kicker">SOUND FAMILIAR?</p><h2>논문 한 편을 읽는 일이<br />생각보다 어려웠던 이유.</h2></div>
          <div className="pain-list">
            <div className="pain-item"><span>01</span><p>영어 논문을 펼칠 때마다, 언어가 먼저 장벽이 되지 않았나요?</p></div>
            <div className="pain-item"><span>02</span><p>번역기를 돌렸는데도 논문 맥락과 맞지 않는 표현 때문에 더 헷갈린 적은 없나요?</p></div>
            <div className="pain-item"><span>03</span><p>모르는 전공 용어와 개념 때문에 읽던 흐름을 멈춘 적은 없나요?</p></div>
          </div>
          <div className="pain-answer"><strong>이제, Paper Teacher와 함께 읽으세요.</strong><p>원문 옆에서 번역을 비교하고, 필요한 선행지식을 확인하고, 막히는 부분을 AI 튜터에게 바로 물어보세요.</p></div>
        </div></section>
        <section className="intro" id="journey"><div className="container"><p className="section-kicker">THE READING JOURNEY</p><h2>업로드한 논문이<br />내 것이 되는 순간부터, 이해하는 순간까지.</h2><p>기능을 오가느라 흐름이 끊기지 않도록, 한 곳에서 읽고 묻고 살펴보세요.</p></div></section>
        <div className="flow"><div className="container">{features.slice(0, 5).map((feature) => <FeatureSection feature={feature} key={feature.step} />)}</div></div>
        <div className="graph-wrap"><div className="container"><FeatureSection feature={features[5]} /></div></div>
        <section className="closing"><div className="container"><p className="section-kicker">READ WITH UNDERSTANDING</p><h2>다음 논문은,<br />끝까지 이해하며 읽어보세요.</h2><p>한 편을 올리고, 읽고, 묻고, 전체 흐름까지 확인하는 경험.</p>{cta()}<p className="cta-note">로그인 또는 회원가입 후 서재에서 논문을 업로드할 수 있어요.</p></div></section>
      </main>
      <footer className="footer"><div className="container"><a className="brand" href="#top"><PaperStackMark size={22} color="currentColor" />Paper Teacher</a><span>논문을 이해하는 더 나은 방법</span><span>Paper Teacher</span></div></footer>
    </div>
  );
}
