import { useEffect, useState } from 'react';
import { pickActiveHeading } from './scrollSpy';

export function useScrollSpy(containerRef: React.RefObject<HTMLElement | null>, tocOrder: string[]) {
  const [activeId, setActiveId] = useState<string | null>(null);
  useEffect(() => {
    const container = containerRef.current;
    if (!container || tocOrder.length === 0) return;
    // jsdom(테스트 환경)에는 IntersectionObserver가 없다 — no-op 가드.
    if (typeof IntersectionObserver === 'undefined') return;
    const visible = new Set<string>();
    const observer = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          const id = (e.target as HTMLElement).dataset.blockId!;
          if (e.isIntersecting) visible.add(id); else visible.delete(id);
        }
        setActiveId(pickActiveHeading([...visible], tocOrder));
      },
      { root: container, rootMargin: '0px 0px -60% 0px' },
    );
    for (const id of tocOrder) {
      const el = container.querySelector(`[data-block-id="${id}"]`);
      if (el) observer.observe(el);
    }
    return () => observer.disconnect();
  }, [containerRef, tocOrder]);
  return activeId;
}
