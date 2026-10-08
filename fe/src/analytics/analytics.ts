// 제품 분석(Mixpanel) 단일 접점. 이벤트 정의는 project-docs architecture/observability/product-analytics.md.
// 세션 녹화를 쓰지 않으므로 녹화 모듈이 빠진 코어 로더를 쓴다.
import mixpanel from 'mixpanel-browser/src/loaders/loader-module-core';

// 프로젝트 토큰은 브라우저에 노출되는 공개 값이다. 목록에 없는 주소(로컬 등)에서는 전송하지 않는다.
// prod가 dev 빌드 산출물을 그대로 쓰므로 빌드 환경변수가 아니라 접속 주소로 고른다.
const PROJECT_TOKENS: Record<string, string> = {
  'papertutor.co.kr': 'd0317d5c843c705dae6b005375931460',
  'dev.papertutor.co.kr': '10e7979901fd75f78a643ee0a3022516',
};

export interface AnalyticsEventProps {
  signup_completed: undefined;
  paper_uploaded: { paper_id: string };
  viewer_opened: { paper_id: string };
  prerequisite_toggled: { paper_id: string; enabled: boolean };
  prerequisite_clicked: { paper_id: string };
  knowledge_graph_opened: { paper_id: string };
  translation_requested: { paper_id: string; type: 'full' | 'inline' };
  chat_question_sent: { paper_id: string };
  trial_page_opened: undefined;
  trial_paper_opened: { paper_id: string; topic: string };
  trial_signup_prompted: { source: 'upload' | 'login' | 'chat' };
}

export type AnalyticsEvent = keyof AnalyticsEventProps;

let enabled = false;

// 분석 장애가 화면 동작을 막지 않게 SDK 호출을 모두 여기로 감싼다.
function safely(fn: () => void): void {
  if (!enabled) return;
  try {
    fn();
  } catch {
    /* 전송 실패는 무시한다 */
  }
}

/** 앱 시작 시 1회. 주소에 맞는 토큰이 없으면 이후 호출은 모두 무시된다. */
export function initAnalytics(
  hostname: string = window.location.hostname,
  tokens: Record<string, string> = PROJECT_TOKENS,
): void {
  const token = tokens[hostname];
  if (!token) return;
  try {
    mixpanel.init(token, {
      autocapture: false,
      record_sessions_percent: 0,
      // SPA라 주소가 바뀔 때마다 페이지 조회를 보낸다.
      track_pageview: 'url-with-path',
      // 쿠키로 두면 서브도메인에 공유되고 API 요청마다 실려 간다.
      persistence: 'localStorage',
    });
    enabled = true;
  } catch {
    enabled = false;
  }
}

export function track<E extends AnalyticsEvent>(
  event: E,
  ...props: AnalyticsEventProps[E] extends undefined ? [] : [AnalyticsEventProps[E]]
): void {
  safely(() => mixpanel.track(event, props[0]));
}

/** 로그인·세션 복원 시. 로그인 전 익명 기록이 이 사용자로 이어진다. */
export function identifyUser(userId: string): void {
  safely(() => mixpanel.identify(userId));
}

/** 로그아웃·세션 만료 시. 같은 브라우저의 다음 사용자와 기록이 섞이지 않게 한다. */
export function resetUser(): void {
  safely(() => mixpanel.reset());
}

export function _resetForTest(): void {
  enabled = false;
}
