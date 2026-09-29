import { useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { PaperStackMark } from '../design/components/PaperStackMark';
import { GlobalNav } from '../nav/GlobalNav';
import './PlansPage.css';

export default function PlansPage() {
  const { status, startLogin, initialError } = useAuth();
  const navigate = useNavigate();
  const startFree = () => {
    if (status === 'authed') navigate('/library');
    else if (status === 'guest') startLogin();
  };

  return (
    <div className="plans-page" id="top">
      <GlobalNav />
      <main>
        <section className="plans-hero">
          <div className="plans-container">
            <p className="plans-eyebrow">PAPER TEACHER PLANS</p>
            <h1>논문을 읽는 속도에 맞춰,<br /><em>필요한 만큼 시작하세요.</em></h1>
            <p>어떤 플랜이든 논문을 읽고 이해하는 핵심 경험은 함께합니다. 더 많은 논문과 질문이 필요할 때 Pro로 넓혀보세요.</p>
          </div>
        </section>
        <section className="plans-cards" aria-label="플랜 선택">
          <div className="plans-container">
            <div className="plans-grid">
              <article className="plans-card plans-free">
                <div className="plans-card-top"><h2>Free</h2><span className="plans-badge">가볍게 시작하기</span></div>
                <p className="plans-subtitle">첫 논문부터 차근차근 읽고 싶을 때.</p>
                <div className="plans-price"><strong>무료</strong><span>계속 이용 가능</span></div>
                <p className="plans-quota-intro">매월 제공되는 사용량</p>
                <div className="plans-quota-list">
                  <div className="plans-quota"><span>논문 등록</span><strong>3<small>편</small></strong></div>
                  <div className="plans-quota"><span>AI 질의</span><strong>30<small>회</small></strong></div>
                </div>
                <p className="plans-message">번역, 선행지식, AI 튜터, 지식 그래프를 함께 이용할 수 있어요.</p>
                <button type="button" className="plans-action" onClick={startFree} disabled={status === 'loading'}>무료로 시작하기 <span aria-hidden="true">↗</span></button>
              </article>
              <article className="plans-card plans-pro">
                <div className="plans-card-top"><h2>Pro</h2><span className="plans-badge">BETA</span></div>
                <p className="plans-subtitle">더 많은 논문을 꾸준히 읽고, 깊이 있게 질문할 때.</p>
                <div className="plans-price"><strong>더 넉넉하게</strong><span>곧 이용 기회 안내</span></div>
                <p className="plans-quota-intro">매월 제공되는 사용량</p>
                <div className="plans-quota-list">
                  <div className="plans-quota"><span>논문 등록</span><strong>더 많은 논문</strong></div>
                  <div className="plans-quota"><span>AI 질의</span><strong>더 많은 질문</strong></div>
                </div>
                <p className="plans-message">Free의 읽기 경험을 그대로, 더 자주 읽고 물어보세요.</p>
                <a className="plans-action" href="#pro-faq">Pro 이용 안내 <span aria-hidden="true">↓</span></a>
              </article>
            </div>
            <p className="plans-fineprint">월간 횟수는 한국 시간 매월 1일에 초기화됩니다. 인라인 번역은 AI 질의 횟수에 포함됩니다.</p>
            {initialError && <p className="plans-error" role="alert">{initialError}</p>}
          </div>
        </section>
        <section className="plans-faq" aria-labelledby="plans-faq-title" id="pro-faq">
          <div className="plans-container plans-faq-layout">
            <div><p className="plans-kicker">QUESTIONS</p><h2 id="plans-faq-title">플랜에 대해<br />궁금한 점.</h2><p className="plans-faq-lead">시작하기 전에 꼭 알아야 할 내용만 모았습니다.</p></div>
            <div className="plans-faq-list">
              <details><summary>Free에서도 모든 읽기 기능을 사용할 수 있나요?</summary><p>네. 논문 뷰어, 전체 번역, 선행지식 하이라이트, AI 튜터와 지식 그래프를 이용할 수 있습니다. 월간 논문 등록과 AI 질의 횟수는 플랜별로 다릅니다.</p></details>
              <details><summary>월간 횟수는 언제 초기화되나요?</summary><p>한국 시간 기준 매월 1일 00:00에 새로운 월간 횟수가 시작됩니다. 인라인 번역은 AI 질의 횟수에 포함됩니다.</p></details>
              <details><summary>Pro는 어떻게 이용할 수 있나요?</summary><p>곧 설문조사, 이벤트 등을 통해 Pro 이용 기회를 제공할 예정입니다. 자세한 내용은 추후 안내드릴게요.</p></details>
            </div>
          </div>
        </section>
        <section className="plans-closing"><div className="plans-container"><h2>먼저 한 편, 끝까지 읽어보세요.</h2><p>Paper Teacher의 핵심 기능을 Free로 시작할 수 있습니다.</p><button type="button" className="plans-action" onClick={startFree} disabled={status === 'loading'}>무료로 시작하기 ↗</button></div></section>
      </main>
      <footer className="plans-footer"><div className="plans-container"><a href="/" className="plans-brand"><PaperStackMark size={22} color="currentColor" />Paper Teacher</a><span>논문을 이해하는 더 나은 방법</span></div></footer>
    </div>
  );
}
