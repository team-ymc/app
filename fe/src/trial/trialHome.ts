// 체험 페이지는 /try(오늘의 논문)와 /trys(패널) 두 주소가 있다. 뷰어에서 돌아갈 때 들어온 쪽으로
// 보내려고 마지막으로 연 체험 페이지 주소를 탭 안에서 기억한다.
export type TrialHome = '/try' | '/trys';

const KEY = 'trialHome';

export function rememberTrialHome(home: TrialHome): void {
  try {
    sessionStorage.setItem(KEY, home);
  } catch {
    /* 저장이 막혀 있으면 기본 주소로 돌아간다 */
  }
}

export function trialHome(): TrialHome {
  try {
    return sessionStorage.getItem(KEY) === '/trys' ? '/trys' : '/try';
  } catch {
    return '/try';
  }
}
