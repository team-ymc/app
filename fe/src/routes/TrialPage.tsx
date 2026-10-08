// 체험 페이지(YMC-430): 가입 없이 주제별 논문 한 편을 바로 읽어본다. 디자인: project-docs/design/trial.
// 데스크톱 전용 한 화면 — 모바일은 랜딩으로 보낸다.
import { useEffect } from 'react';
import { Link } from 'react-router';
import { UploadSimple } from '@phosphor-icons/react';
import { GlobalNav } from '../nav/GlobalNav';
import { PaperStackMark } from '../design/components/PaperStackMark';
import { TrialModeProvider, useTrialMode } from '../trial/TrialMode';
import { trialPapers, type TrialPaper } from '../trial/trialPapers';
import { useDesktopOnly } from '../trial/useDesktopOnly';
import { track } from '../analytics/analytics';
import './TrialPage.css';

const PAPERS = trialPapers();

function PaperPanel({ paper }: { paper: TrialPaper }) {
  const body = (
    <>
      <div className="trial-panel-head"><h2>{paper.topic}</h2></div>
      <div className="trial-thumb"><img src={paper.image} alt={`${paper.title} 첫 페이지`} /></div>
      <div className="trial-panel-body">
        <h3 className="trial-card-title">{paper.title}</h3>
        <p className="trial-card-hook">{paper.hook}</p>
        <div className="trial-panel-foot">
          <div className="trial-meta"><span>{paper.year}</span><span className="trial-dot" /><span className="trial-venue">{paper.venue}</span></div>
          <span className="trial-read">읽어보기 →</span>
        </div>
      </div>
    </>
  );
  if (!paper.paperId) {
    return <div className="trial-panel trial-panel-unavailable" title="아직 준비 중인 논문입니다">{body}</div>;
  }
  return (
    <Link
      className="trial-panel"
      to={`/try/papers/${paper.paperId}`}
      onClick={() => track('trial_paper_opened', { paper_id: paper.paperId!, topic: paper.topicKey })}
    >
      {body}
    </Link>
  );
}

function TrialPageContent() {
  const { requestSignup } = useTrialMode();
  useDesktopOnly();
  useEffect(() => {
    track('trial_page_opened');
  }, []);

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
        <section className="trial-shelf" aria-label="주제별 체험 논문"><div className="trial-container">
          <div className="trial-shelf-head"><span>Five topics · Five papers</span><span>마우스를 올려 살펴보고, 눌러서 읽어보세요</span></div>
          <div className="trial-panels">
            {PAPERS.map((p) => <PaperPanel key={p.topicKey} paper={p} />)}
          </div>
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
