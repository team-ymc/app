// 구성요소(이미지·표·수식) 클릭 → 블록 우측 상단 "질문하기" → 현재/새 채팅 선택 → 블록 단위
// atomic 첨부 (FT-006 Story 6, design/v2 contentAskAction 이식). 텍스트 선택 흐름(SelectionLayer)과
// 동일한 onAsk 계약을 쓴다 — StudyPage가 첨부·패널 열림을 처리한다.
import { useEffect, useRef, useState, type RefObject } from 'react';
import { ArrowBendUpLeft, ChatCircleText, NotePencil } from '@phosphor-icons/react';
import { AskRow, ToolbarButton } from './SelectionLayer';
import type { SelectionAnchors } from './selectionAnchors';
import type { PaperBlock } from '../../markdown/paperContent';

export interface ContentAskLayerProps {
  viewerRef: RefObject<HTMLDivElement | null>;
  blocks: PaperBlock[];
  onAsk: (text: string, mode: 'current' | 'new', anchors: SelectionAnchors | null) => void;
}

const ATOMIC_TYPES = new Set(['figure', 'table', 'equation']);

type Layer =
  | { phase: 'idle' }
  | { phase: 'action' | 'askChoice'; blockId: string; rect: DOMRect };

export function ContentAskLayer({ viewerRef, blocks, onAsk }: ContentAskLayerProps) {
  const [layer, setLayer] = useState<Layer>({ phase: 'idle' });
  const popupRef = useRef<HTMLDivElement>(null);

  // 레이어가 열려 있는 동안 대상 블록에 하이라이트를 유지한다 — 목업 data-context-target 이식.
  // (호버 링은 markdown.css의 :hover가 담당한다.)
  useEffect(() => {
    if (layer.phase === 'idle') return;
    const section = document.getElementById(layer.blockId);
    section?.setAttribute('data-context-target', 'true');
    return () => section?.removeAttribute('data-context-target');
  }, [layer]);

  // 바깥 클릭 dismiss — SelectionLayer와 동일 규칙. 팝업 내부는 무시, 뷰어 내부는 자체 click
  // 핸들러가 처리(비-atomic이면 어차피 idle), 그 외는 닫는다.
  useEffect(() => {
    if (layer.phase === 'idle') return;
    function handleDocMouseDown(e: MouseEvent) {
      const target = e.target as Node;
      if (popupRef.current && popupRef.current.contains(target)) return;
      if (viewerRef.current && viewerRef.current.contains(target)) return;
      setLayer({ phase: 'idle' });
    }
    document.addEventListener('mousedown', handleDocMouseDown, true);
    return () => document.removeEventListener('mousedown', handleDocMouseDown, true);
  }, [layer, viewerRef]);

  useEffect(() => {
    const el = viewerRef.current;
    if (!el) return;
    function handleClick(e: MouseEvent) {
      const section = (e.target as Element).closest?.('[data-block-id]');
      if (!section) {
        setLayer({ phase: 'idle' });
        return;
      }
      const blockId = section.getAttribute('data-block-id')!;
      const block = blocks.find((b) => b.id === blockId);
      if (!block || !ATOMIC_TYPES.has(block.type)) {
        setLayer({ phase: 'idle' });
        return;
      }
      // 버튼 앵커는 블록(전체 폭)이 아니라 테두리 프레임 요소다 — 가운데 정렬된 이미지에서
      // 버튼이 프레임 밖에 뜨지 않게 한다.
      const frame = section.querySelector('.pt-figure img, .katex-display, .pt-table-scroll');
      setLayer({ phase: 'action', blockId, rect: (frame ?? section).getBoundingClientRect() });
    }
    function handleScroll() {
      setLayer({ phase: 'idle' });
    }
    el.addEventListener('click', handleClick);
    el.addEventListener('scroll', handleScroll);
    return () => {
      el.removeEventListener('click', handleClick);
      el.removeEventListener('scroll', handleScroll);
    };
  }, [viewerRef, blocks]);

  if (layer.phase === 'idle') return null;

  const container = viewerRef.current?.getBoundingClientRect() ?? new DOMRect();
  const position = {
    position: 'absolute' as const,
    top: Math.max(0, layer.rect.top - container.top + 6),
    left: layer.rect.right - container.left - 8,
    transform: 'translateX(-100%)',
    zIndex: 80,
  };

  function handleChoice(mode: 'current' | 'new') {
    if (layer.phase !== 'askChoice') return;
    const { blockId } = layer;
    setLayer({ phase: 'idle' });
    onAsk('', mode, { start: { blockId }, end: { blockId } });
  }

  if (layer.phase === 'action') {
    return (
      <div
        ref={popupRef}
        style={{
          ...position,
          display: 'inline-flex',
          background: 'var(--color-bg-walnut)',
          borderRadius: 'var(--radius-control)',
          boxShadow: 'var(--shadow-menu)',
          padding: 4,
        }}
      >
        <ToolbarButton
          icon={<ChatCircleText size={14} />}
          label="질문하기"
          onClick={() => setLayer({ phase: 'askChoice', blockId: layer.blockId, rect: layer.rect })}
        />
      </div>
    );
  }

  return (
    <div
      ref={popupRef}
      style={{
        ...position,
        width: 'max-content',
        boxSizing: 'border-box',
        background: 'var(--color-bg-paper)',
        border: '1px solid var(--color-border)',
        borderRadius: 10,
        boxShadow: 'var(--shadow-menu)',
        padding: 4,
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <AskRow icon={<ArrowBendUpLeft size={13} color="var(--color-primary)" />} label="현재 채팅" onClick={() => handleChoice('current')} />
      <div style={{ height: 1, background: 'var(--color-border)', margin: '2px 6px' }} />
      <AskRow icon={<NotePencil size={13} color="var(--color-primary)" />} label="새 채팅" onClick={() => handleChoice('new')} />
    </div>
  );
}
