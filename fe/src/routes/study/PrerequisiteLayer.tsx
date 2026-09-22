// 선행지식 팝오버 (Design v3). 뷰어 안 mark 클릭을 위임으로 받아 선택 구간 아래에 연다.
// 성공한 설명은 highlightId별로 페이지 메모리에 두어 다시 열 때 요청 없이 보여준다.
import { useEffect, useRef, useState, type RefObject } from 'react';
import { createPrerequisiteDefinition } from '../../api/papers';
import type { PrerequisiteDefinitionResponse, PrerequisiteHighlight } from '../../api/types';
import { computeToolbarPosition } from './selectionPosition';

export interface PrerequisiteLayerProps {
  paperId: string;
  viewerRef: RefObject<HTMLDivElement | null>;
  highlights: PrerequisiteHighlight[];
  /** 토글 상태. false면 클릭을 무시하고 열린 팝오버를 닫는다. */
  visible: boolean;
  /** 다른 오버레이가 열릴 때 증가한다. 바뀌면 닫는다. */
  openSignal: number;
  /** 팝오버가 열릴 때 호출 — StudyPage가 다른 오버레이를 닫는다. */
  onOpen: () => void;
}

const POPOVER_SIZE = { width: 430, height: 200 };

type Layer =
  | { phase: 'idle' }
  | { phase: 'loading'; id: string; term: string; rect: DOMRect; mark: HTMLElement }
  | { phase: 'success'; id: string; term: string; rect: DOMRect; mark: HTMLElement; en: string; ko: string }
  | { phase: 'failed'; id: string; term: string; rect: DOMRect; mark: HTMLElement };

export function PrerequisiteLayer({ paperId, viewerRef, highlights, visible, openSignal, onOpen }: PrerequisiteLayerProps) {
  const [layer, setLayer] = useState<Layer>({ phase: 'idle' });
  const popupRef = useRef<HTMLDivElement>(null);
  const memoRef = useRef(new Map<string, PrerequisiteDefinitionResponse>());
  const baseScrollTopRef = useRef(0);
  const [scrollDelta, setScrollDelta] = useState(0);

  // 본문이 바뀌면(하이라이트 배열 교체) 기억한 설명을 비운다.
  useEffect(() => { memoRef.current.clear(); }, [highlights]);

  // 토글 off 또는 다른 오버레이 열림 → 닫기.
  useEffect(() => { setLayer({ phase: 'idle' }); }, [visible, openSignal]);

  // mark의 aria-expanded 동기화.
  useEffect(() => {
    if (layer.phase === 'idle') return;
    layer.mark.setAttribute('aria-expanded', 'true');
    return () => layer.mark.setAttribute('aria-expanded', 'false');
  }, [layer]);

  // 하이라이트 클릭 위임.
  useEffect(() => {
    const el = viewerRef.current;
    if (!el) return;
    function handleClick(e: MouseEvent) {
      if (!visible) return;
      const mark = (e.target as Element).closest?.('mark.term-highlight') as HTMLElement | null;
      if (!mark || !el!.contains(mark)) return;
      const id = mark.getAttribute('data-highlight-id');
      const h = highlights.find((x) => x.highlightId === id);
      if (!id || !h) return;
      e.preventDefault();
      const rect = mark.getBoundingClientRect();
      baseScrollTopRef.current = el!.scrollTop;
      setScrollDelta(0);
      onOpen();
      const memo = memoRef.current.get(id);
      if (memo) {
        setLayer({ phase: 'success', id, term: h.text, rect, mark, en: memo.definitionEn, ko: memo.definitionKo });
        return;
      }
      setLayer({ phase: 'loading', id, term: h.text, rect, mark });
    }
    el.addEventListener('click', handleClick);
    return () => el.removeEventListener('click', handleClick);
  }, [viewerRef, highlights, visible, onOpen]);

  // loading 진입에 반응해 요청한다. 닫히거나 다른 하이라이트로 바뀌면 abort.
  useEffect(() => {
    if (layer.phase !== 'loading') return;
    const { id, term, rect, mark } = layer;
    const controller = new AbortController();
    createPrerequisiteDefinition(paperId, id, controller.signal)
      .then((res) => {
        memoRef.current.set(id, res);
        setLayer((prev) => (prev.phase === 'loading' && prev.id === id
          ? { phase: 'success', id, term, rect, mark, en: res.definitionEn, ko: res.definitionKo } : prev));
      })
      .catch((err) => {
        if (controller.signal.aborted) return;
        console.warn('선행지식 설명 실패', err);
        setLayer((prev) => (prev.phase === 'loading' && prev.id === id ? { phase: 'failed', id, term, rect, mark } : prev));
      });
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [layer.phase === 'loading' ? layer.id : null, paperId]);

  // 뷰어 스크롤을 따라간다.
  useEffect(() => {
    const el = viewerRef.current;
    if (!el) return;
    const onScroll = () => setScrollDelta(el.scrollTop - baseScrollTopRef.current);
    el.addEventListener('scroll', onScroll);
    return () => el.removeEventListener('scroll', onScroll);
  }, [viewerRef]);

  // 바깥 클릭 판정: 팝오버 안·선택한 mark·뷰어 밖(목차·튜터)은 무시, 그 외 뷰어 영역이면 닫는다.
  useEffect(() => {
    if (layer.phase === 'idle') return;
    const { mark } = layer;
    function handleDocMouseDown(e: MouseEvent) {
      const target = e.target as Node;
      if (popupRef.current?.contains(target)) return;
      if (mark.contains(target)) return;
      if (!viewerRef.current?.contains(target)) return;
      setLayer({ phase: 'idle' });
    }
    document.addEventListener('mousedown', handleDocMouseDown, true);
    return () => document.removeEventListener('mousedown', handleDocMouseDown, true);
  }, [layer, viewerRef]);

  if (layer.phase === 'idle' || !viewerRef.current) return null;
  const containerRect = viewerRef.current.getBoundingClientRect();
  const pos = computeToolbarPosition(layer.rect, containerRect, POPOVER_SIZE);

  return (
    <div
      ref={popupRef}
      role="dialog"
      aria-modal="false"
      aria-label={layer.term}
      className="pt-prereq-popover"
      onMouseDown={(e) => e.preventDefault()}
      style={{
        position: 'absolute', top: pos.top - scrollDelta, left: pos.left, width: POPOVER_SIZE.width,
        border: '1px solid #cdbb9d', borderRadius: 7, background: '#fffdf8',
        boxShadow: '0 5px 14px rgba(66,49,31,.14)', zIndex: 5,
      }}
    >
      <div style={{ position: 'relative', padding: '25px 22px 24px' }}>
        <h2 style={{ margin: '0 0 10px', fontFamily: "Georgia,'Times New Roman',serif", fontSize: 21, fontWeight: 700, lineHeight: 1.25, color: '#18385e' }}>
          {layer.term}
        </h2>
        {layer.phase === 'loading' && (
          <div className="pt-prereq-loading" aria-label="설명 생성 중" style={{ display: 'flex', gap: 6, padding: '6px 0' }}>
            <span className="pt-prereq-dot" /><span className="pt-prereq-dot" /><span className="pt-prereq-dot" />
          </div>
        )}
        {layer.phase === 'failed' && (
          <p style={{ margin: 0, fontFamily: 'var(--font-sans)', fontSize: 15, color: '#6d6861' }}>설명을 불러오지 못했습니다.</p>
        )}
        {layer.phase === 'success' && (
          <>
            <p style={{ margin: 0, fontFamily: "Georgia,'Times New Roman',serif", fontSize: 14, lineHeight: 1.5, color: '#3c3935' }}>{layer.en}</p>
            <div style={{ height: 1, margin: '20px 0 16px', background: '#ded3c3' }} />
            <p style={{ margin: 0, fontFamily: 'var(--font-sans)', fontSize: 16, lineHeight: 1.65, color: '#45413c' }}>{layer.ko}</p>
          </>
        )}
      </div>
    </div>
  );
}
