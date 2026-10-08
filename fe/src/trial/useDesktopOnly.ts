// 체험은 데스크톱 전용 — 모바일 유입은 기존 마케팅 랜딩으로 보낸다.
import { useEffect } from 'react';
import { useNavigate } from 'react-router';

export const DESKTOP_MIN_WIDTH = 1024;

// 페이지가 min-width를 가지면 폰에서 innerWidth가 레이아웃 뷰포트(1024)로 늘어나므로 미디어 쿼리로 본다.
export function isMobileViewport(): boolean {
  if (typeof window.matchMedia === 'function') {
    return window.matchMedia(`(max-width: ${DESKTOP_MIN_WIDTH - 1}px)`).matches;
  }
  return window.innerWidth < DESKTOP_MIN_WIDTH;
}

export function useDesktopOnly() {
  const navigate = useNavigate();
  useEffect(() => {
    if (isMobileViewport()) navigate('/', { replace: true });
  }, [navigate]);
}
