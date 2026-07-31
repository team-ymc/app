// 이식: project-docs/design/v1/Paper Study Page.dc.html — Selection popup(Translate/Ask) /
// Ask popup(현재 채팅·새 채팅) / Direct translation popup 세 영역. 스타일 값은 목업 그대로.
// 위치 모델은 목업과 다르다: 목업은 position:fixed + transform:translate(-50%, calc(-100% - 10px))로
// 뷰포트 기준 중앙정렬하지만, 이 태스크의 브리프는 컨테이너 상대 absolute + computeToolbarPosition(선택
// 영역 "아래" 배치)을 명시한다 — 그대로 따른다(브리프 계약이 목업 좌표식보다 우선).
// 상태기계: idle(선택 없음, 아무것도 렌더 안 함) → toolbar → (translating → translated) | askChoice.
// translating/translated/askChoice로 전이한 뒤에는 클릭 시점에 캡처한 text/rect/clear를 쓴다 — 팝업
// 버튼 클릭으로 브라우저 selection이 collapse되어도(mousedown 기본 동작) 캡처값은 영향받지 않는다.
import { useEffect, useState, type CSSProperties, type RefObject } from 'react';
import { ArrowBendUpLeft, ChatCircleText, NotePencil, Translate, X } from '@phosphor-icons/react';
import { useTextSelection } from './useTextSelection';
import { computeToolbarPosition } from './selectionPosition';
import { translateSelection } from '../../api/translate';

export interface SelectionLayerProps {
  viewerRef: RefObject<HTMLDivElement | null>;
  onAsk: (text: string, mode: 'current' | 'new') => void;
}

// computeToolbarPosition의 top 계산은 popup.height를 쓰지 않는다(brief 구현 참고) — width만 정확하면
// 된다. 목업 CSS 실측값(번역 팝업 width:300 등)을 기준으로, DOM 측정 없이 쓸 근사치를 상수로 둔다.
const TOOLBAR_POPUP_SIZE = { width: 190, height: 48 };
const TRANSLATION_POPUP_SIZE = { width: 300, height: 120 };
const ASK_POPUP_SIZE = { width: 150, height: 74 };

type Layer =
  | { phase: 'idle' }
  | { phase: 'toolbar'; text: string; rect: DOMRect; clear: () => void }
  | { phase: 'translating'; text: string; rect: DOMRect; clear: () => void }
  | { phase: 'translated'; text: string; rect: DOMRect; clear: () => void; translation: string }
  | { phase: 'askChoice'; text: string; rect: DOMRect; clear: () => void };

function truncate(text: string, n: number): string {
  return text.length > n ? `${text.slice(0, n).trim()}…` : text;
}

export function SelectionLayer({ viewerRef, onAsk }: SelectionLayerProps) {
  const sel = useTextSelection(viewerRef);
  const [layer, setLayer] = useState<Layer>({ phase: 'idle' });

  // 선택이 생기면 toolbar로, 사라지면(그리고 지금 toolbar 단계일 때만) idle로 — translating 이후
  // 단계는 캡처값으로 독립 운영되므로 브라우저 selection 변화에 영향받지 않는다.
  useEffect(() => {
    if (sel) {
      setLayer((prev) => (prev.phase === 'idle' || prev.phase === 'toolbar' ? { phase: 'toolbar', ...sel } : prev));
    } else {
      setLayer((prev) => (prev.phase === 'toolbar' ? { phase: 'idle' } : prev));
    }
  }, [sel]);

  // 스크롤 시 툴바 dismiss(선택이 사라지면 idle 복귀) — toolbar 단계에서만.
  useEffect(() => {
    const el = viewerRef.current;
    if (!el) return;
    function handleScroll() {
      setLayer((prev) => {
        if (prev.phase === 'toolbar') {
          prev.clear();
          return { phase: 'idle' };
        }
        return prev;
      });
    }
    el.addEventListener('scroll', handleScroll);
    return () => el.removeEventListener('scroll', handleScroll);
  }, [viewerRef]);

  if (layer.phase === 'idle') return null;

  const container = viewerRef.current?.getBoundingClientRect() ?? new DOMRect();

  function handleTranslate() {
    if (layer.phase !== 'toolbar') return;
    const { text, rect, clear } = layer;
    setLayer({ phase: 'translating', text, rect, clear });
    translateSelection(text).then(({ translation }) => {
      setLayer((prev) => (prev.phase === 'translating' ? { phase: 'translated', text, rect, clear, translation } : prev));
    });
  }

  function handleAsk() {
    if (layer.phase !== 'toolbar') return;
    setLayer({ phase: 'askChoice', text: layer.text, rect: layer.rect, clear: layer.clear });
  }

  // 번역 팝업 닫기 → clear()로 원문 읽기 복귀 (FT-006 Story 2).
  function handleCloseTranslation() {
    if (layer.phase !== 'translating' && layer.phase !== 'translated') return;
    layer.clear();
    setLayer({ phase: 'idle' });
  }

  // AI에게 질문 선택 → onAsk(text, mode). StudyPage가 pendingContext 세팅 + 챗 패널 열림·포커스한다.
  function handleAskChoice(mode: 'current' | 'new') {
    if (layer.phase !== 'askChoice') return;
    const { text, clear } = layer;
    clear();
    setLayer({ phase: 'idle' });
    onAsk(text, mode);
  }

  if (layer.phase === 'toolbar') {
    const pos = computeToolbarPosition(layer.rect, container, TOOLBAR_POPUP_SIZE);
    return (
      <div
        style={{
          position: 'absolute',
          top: pos.top,
          left: pos.left,
          display: 'inline-flex',
          background: 'var(--color-bg-walnut)',
          borderRadius: 'var(--radius-control)',
          boxShadow: 'var(--shadow-menu)',
          padding: 4,
          gap: 2,
          zIndex: 80,
        }}
      >
        <ToolbarButton icon={<Translate size={14} />} label="번역" onClick={handleTranslate} />
        <div style={{ width: 1, alignSelf: 'stretch', background: 'rgba(255,253,247,0.18)', margin: '4px 0' }} />
        <ToolbarButton icon={<ChatCircleText size={14} />} label="질문하기" onClick={handleAsk} />
      </div>
    );
  }

  if (layer.phase === 'translating' || layer.phase === 'translated') {
    const pos = computeToolbarPosition(layer.rect, container, TRANSLATION_POPUP_SIZE);
    return (
      <div
        style={{
          position: 'absolute',
          top: pos.top,
          left: pos.left,
          width: 300,
          boxSizing: 'border-box',
          background: 'var(--color-bg-paper)',
          border: '1px solid var(--color-border)',
          borderRadius: 'var(--radius-control)',
          boxShadow: 'var(--shadow-menu)',
          padding: '14px 16px',
          zIndex: 80,
        }}
      >
        <button
          onClick={handleCloseTranslation}
          aria-label="닫기"
          style={{
            position: 'absolute',
            top: 10,
            right: 10,
            background: 'transparent',
            border: 'none',
            cursor: 'pointer',
            color: 'var(--color-text-muted)',
            padding: 2,
            display: 'flex',
            alignItems: 'center',
          }}
        >
          <X size={14} />
        </button>
        <div
          style={{
            fontFamily: 'var(--font-serif)',
            fontSize: 13,
            lineHeight: 1.6,
            color: 'var(--color-text-muted)',
            paddingRight: 18,
            paddingBottom: 10,
            marginBottom: 10,
            borderBottom: '1px solid var(--color-border)',
          }}
        >
          {truncate(layer.text, 220)}
        </div>
        <div style={{ fontFamily: 'var(--font-serif)', fontSize: 15, lineHeight: 1.7, color: 'var(--color-text-body)' }}>
          {layer.phase === 'translating' ? '번역 중…' : layer.translation}
        </div>
      </div>
    );
  }

  // askChoice
  const pos = computeToolbarPosition(layer.rect, container, ASK_POPUP_SIZE);
  return (
    <div
      style={{
        position: 'absolute',
        top: pos.top,
        left: pos.left,
        width: 'max-content',
        boxSizing: 'border-box',
        background: 'var(--color-bg-paper)',
        border: '1px solid var(--color-border)',
        borderRadius: 10,
        boxShadow: 'var(--shadow-menu)',
        padding: 4,
        display: 'flex',
        flexDirection: 'column',
        zIndex: 80,
      }}
    >
      <AskRow icon={<ArrowBendUpLeft size={13} color="var(--color-primary)" />} label="현재 채팅" onClick={() => handleAskChoice('current')} />
      <div style={{ height: 1, background: 'var(--color-border)', margin: '2px 6px' }} />
      <AskRow icon={<NotePencil size={13} color="var(--color-primary)" />} label="새 채팅" onClick={() => handleAskChoice('new')} />
    </div>
  );
}

function ToolbarButton({ icon, label, onClick }: { icon: React.ReactNode; label: string; onClick: () => void }) {
  const [hover, setHover] = useState(false);
  const style: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    background: hover ? 'rgba(255,253,247,0.14)' : 'transparent',
    border: 'none',
    cursor: 'pointer',
    color: 'var(--color-on-dark)',
    fontFamily: 'var(--font-sans)',
    fontSize: 13,
    fontWeight: 600,
    padding: '8px 12px',
    borderRadius: 'var(--radius-structural)',
    whiteSpace: 'nowrap',
    transition: 'background 150ms ease',
  };
  return (
    <button onClick={onClick} onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)} style={style}>
      {icon}
      {label}
    </button>
  );
}

function AskRow({ icon, label, onClick }: { icon: React.ReactNode; label: string; onClick: () => void }) {
  const [hover, setHover] = useState(false);
  const style: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    width: '100%',
    boxSizing: 'border-box',
    textAlign: 'left',
    whiteSpace: 'nowrap',
    padding: '7px 12px 7px 7px',
    border: 'none',
    background: hover ? 'var(--color-primary-subtle)' : 'transparent',
    borderRadius: 7,
    cursor: 'pointer',
    transition: 'background 150ms ease',
  };
  return (
    <button onClick={onClick} onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)} style={style}>
      <span
        style={{
          width: 26,
          height: 26,
          borderRadius: '50%',
          background: 'var(--color-primary-subtle)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flexShrink: 0,
        }}
      >
        {icon}
      </span>
      <span style={{ fontFamily: 'var(--font-sans)', fontSize: 13, fontWeight: 600, color: 'var(--color-text-heading)' }}>{label}</span>
    </button>
  );
}
