// 체험 페이지는 1024px부터 데스크톱, 그 아래는 모바일 한 화면이다. 레이아웃 분기를 여기서 판단한다.
import { useEffect, useState } from 'react';

export const DESKTOP_MIN_WIDTH = 1024;
const MOBILE_QUERY = `(max-width: ${DESKTOP_MIN_WIDTH - 1}px)`;

// 페이지가 min-width를 가지면 폰에서 innerWidth가 레이아웃 뷰포트로 늘어나므로 미디어 쿼리로 본다.
export function isMobileViewport(): boolean {
  if (typeof window.matchMedia === 'function') {
    return window.matchMedia(MOBILE_QUERY).matches;
  }
  return window.innerWidth < DESKTOP_MIN_WIDTH;
}

/** 폭 1023px 이하면 true. 창 크기가 바뀌면 따라간다. */
export function useMobileViewport(): boolean {
  const [mobile, setMobile] = useState(isMobileViewport);
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const query = window.matchMedia(MOBILE_QUERY);
    const sync = () => setMobile(query.matches);
    sync();
    query.addEventListener('change', sync);
    return () => query.removeEventListener('change', sync);
  }, []);
  return mobile;
}
