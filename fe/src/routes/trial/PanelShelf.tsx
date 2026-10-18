// 체험 페이지 패널 레이아웃: 주제별 패널을 한 줄로 세우고, 마우스를 올린 패널이 넓어지며 소개가 펼쳐진다.
// 오늘의 논문 레이아웃과 A/B로 비교하기 위한 변형이다.
import { Link } from 'react-router';
import type { TrialPaper } from '../../trial/trialPapers';
import './PanelShelf.css';

function Panel({ paper, onOpen }: { paper: TrialPaper; onOpen: (paper: TrialPaper) => void }) {
  const body = (
    <>
      <div className="trial-panel-head"><h2>{paper.topic}</h2></div>
      <div className="trial-thumb"><img src={paper.image} alt={`${paper.title} 첫 페이지`} /></div>
      <div className="trial-panel-body">
        <h3 className="trial-panel-title" lang="en">{paper.title}</h3>
        <p className="trial-panel-hook">{paper.hook}</p>
        <div className="trial-panel-foot">
          <div className="trial-panel-meta"><span>{paper.year}</span><span className="trial-dot" /><span className="trial-panel-venue">{paper.venue}</span></div>
          <span className="trial-panel-read">{paper.paperId ? '읽어보기 →' : '준비 중'}</span>
        </div>
      </div>
    </>
  );
  if (!paper.paperId) {
    return <div className="trial-panel trial-unavailable" title="아직 준비 중인 논문입니다">{body}</div>;
  }
  return (
    <Link className="trial-panel" to={`/try/papers/${paper.paperId}`} onClick={() => onOpen(paper)}>
      {body}
    </Link>
  );
}

export function PanelShelf({ papers, onOpen }: { papers: TrialPaper[]; onOpen: (paper: TrialPaper) => void }) {
  return (
    <section className="trial-shelf trial-shelf-panels" aria-label="체험 논문"><div className="trial-container">
      <div className="trial-panels-head"><span>Nine topics · Nine papers</span><span>마우스를 올려 살펴보고, 눌러서 읽어보세요</span></div>
      <div className="trial-panels">
        {papers.map((p) => <Panel key={p.topicKey} paper={p} onOpen={onOpen} />)}
      </div>
    </div></section>
  );
}
