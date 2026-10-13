// 체험 페이지(YMC-430): 가입 없이 주제별 논문 한 편을 바로 읽어본다. 디자인: project-docs/design/trial.
// 데스크톱 전용 한 화면 — 모바일은 랜딩으로 보낸다. 오늘의 논문 하나를 크게 세우고 나머지는 목록으로 둔다.
import { useEffect } from 'react';
import { Link } from 'react-router';
import { UploadSimple } from '@phosphor-icons/react';
import { GlobalNav } from '../nav/GlobalNav';
import { PaperStackMark } from '../design/components/PaperStackMark';
import { TrialModeProvider, useTrialMode } from '../trial/TrialMode';
import { pickTodaysPaper, trialPapers, type TrialPaper } from '../trial/trialPapers';
import { useDesktopOnly } from '../trial/useDesktopOnly';
import { track } from '../analytics/analytics';
import './TrialPage.css';

const PAPERS = trialPapers();

function trackOpen(paper: TrialPaper) {
  track('trial_paper_opened', { paper_id: paper.paperId!, topic: paper.topicKey });
}

function Feature({ paper }: { paper: TrialPaper }) {
  const body = (
    <>
      <span className="trial-feature-tag">오늘의 논문</span>
      <div className="trial-feature-pic"><img src={paper.image} alt={`${paper.title} 첫 페이지`} /></div>
      <div className="trial-feature-text">
        <p className="trial-feature-topic">{paper.topic}</p>
        <h2 className="trial-feature-title">{paper.title}</h2>
        <p className="trial-feature-hook">{paper.hook}</p>
        <div className="trial-meta"><span>{paper.year}</span><span className="trial-dot" /><span>{paper.venue}</span></div>
        <span className="trial-feature-read">{paper.paperId ? '읽어보기 →' : '준비 중'}</span>
      </div>
    </>
  );
  if (!paper.paperId) {
    return <article className="trial-feature trial-unavailable" title="아직 준비 중인 논문입니다">{body}</article>;
  }
  return <Link className="trial-feature" to={`/try/papers/${paper.paperId}`} onClick={() => trackOpen(paper)}>{body}</Link>;
}

function ListItem({ paper }: { paper: TrialPaper }) {
  const body = (
    <>
      <img src={paper.image} alt="" />
      <span className="trial-item-topic">{paper.topic}</span>
      <span className="trial-item-title">{paper.title}</span>
      <span className="trial-item-year">{paper.year}</span>
      <span className="trial-item-read">{paper.paperId ? '→' : '준비 중'}</span>
    </>
  );
  if (!paper.paperId) {
    return <div className="trial-item trial-unavailable" title="아직 준비 중인 논문입니다">{body}</div>;
  }
  return <Link className="trial-item" to={`/try/papers/${paper.paperId}`} onClick={() => trackOpen(paper)}>{body}</Link>;
}

function TrialPageContent() {
  const { requestSignup } = useTrialMode();
  useDesktopOnly();
  useEffect(() => {
    track('trial_page_opened');
  }, []);

  const today = pickTodaysPaper(PAPERS);
  const others = PAPERS.filter((p) => p !== today);

  return (
    <div className="trial-page">
      <GlobalNav />
      <main>
        <section className="trial-hero"><div className="trial-container">
          <p className="trial-eyebrow">Try without sign-up</p>
          <h1><em>논문 한 편</em> 읽어보세요.</h1>
          <button type="button" className="trial-upload" onClick={() => requestSignup('upload')}>
            <UploadSimple size={22} />내 논문 업로드하기
          </button>
        </div></section>
        <section className="trial-shelf" aria-label="체험 논문"><div className="trial-container">
          <Feature paper={today} />
          <nav className="trial-others" aria-label="다른 주제의 체험 논문">
            <div className="trial-others-head">다른 주제도 골라보세요</div>
            {others.map((p) => <ListItem key={p.topicKey} paper={p} />)}
          </nav>
        </div></section>
      </main>
      <footer className="trial-footer"><div className="trial-container">
        <Link to="/" className="trial-brand"><PaperStackMark size={20} color="currentColor" />Paper Teacher</Link>
        <span>논문을 이해하는 더 나은 방법</span>
        <button type="button" className="trial-footer-link" onClick={() => requestSignup('upload')}>읽고 싶은 논문이 따로 있나요? 내 논문 업로드하기 →</button>
      </div></footer>
    </div>
  );
}

export default function TrialPage() {
  return (
    <TrialModeProvider>
      <TrialPageContent />
    </TrialModeProvider>
  );
}
