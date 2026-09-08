// 이식: project-docs/design/v1/Paper Study Page.dc.html — Selection popup(Translate/Ask) /
// Direct translation popup 두 영역. 스타일 값은 목업 그대로. 질문하기는 선택 팝업 없이 바로 현재 채팅에 첨부한다.
// 위치 모델은 목업과 다르다: 목업은 position:fixed + transform:translate(-50%, calc(-100% - 10px))로
// 뷰포트 기준 중앙정렬하지만, 이 태스크의 브리프는 컨테이너 상대 absolute + computeToolbarPosition(선택
// 영역 "아래" 배치)을 명시한다 — 그대로 따른다(브리프 계약이 목업 좌표식보다 우선).
// 상태기계: idle(선택 없음, 아무것도 렌더 안 함) → toolbar → translating → translated | translateFailed.
// translating/translated/translateFailed로 전이한 뒤에는 클릭 시점에 캡처한 text/rect/clear를 쓴다 — 팝업
// 버튼 클릭으로 브라우저 selection이 collapse되어도(mousedown 기본 동작) 캡처값은 영향받지 않는다.
import { useEffect, useRef, useState, type CSSProperties, type RefObject } from 'react';
import { ChatCircleText, Translate, X } from '@phosphor-icons/react';
import { useTextSelection } from './useTextSelection';
import { computeToolbarPosition } from './selectionPosition';
import { checkTranslationSelection, type TranslationSelectionCheck } from '../../chat/selectionAttachments';
import { streamTranslation } from '../../translation/translationStream';
import { PaperMarkdown } from '../../markdown/PaperMarkdown';
import type { SelectionAnchors } from './selectionAnchors';
import type { PaperBlock } from '../../markdown/paperContent';

export interface SelectionLayerProps {
  paperId: string;
  viewerRef: RefObject<HTMLDivElement | null>;
  blocks: PaperBlock[];
  onAsk: (text: string, mode: 'current' | 'new', anchors: SelectionAnchors | null) => void;
}

// computeToolbarPosition의 top 계산은 popup.height를 쓰지 않는다(brief 구현 참고) — width만 정확하면
// 된다. 목업 CSS 실측값(번역 팝업 width:300 등)을 기준으로, DOM 측정 없이 쓸 근사치를 상수로 둔다.
const TOOLBAR_POPUP_SIZE = { width: 190, height: 48 };
const TRANSLATION_POPUP_SIZE = { width: 300, height: 120 };

type Layer =
  | { phase: 'idle' }
  | { phase: 'toolbar'; text: string; rect: DOMRect; clear: () => void; anchors: SelectionAnchors | null }
  | { phase: 'translating'; text: string; rect: DOMRect; clear: () => void; anchors: SelectionAnchors | null }
  | { phase: 'translated'; text: string; rect: DOMRect; clear: () => void; translation: string; anchors: SelectionAnchors | null }
  | { phase: 'translateFailed'; text: string; rect: DOMRect; clear: () => void; message: string; anchors: SelectionAnchors | null };

const TRANSLATE_BLOCKED_MESSAGE: Record<Exclude<TranslationSelectionCheck, 'ok'>, string> = {
  unknown: '번역할 수 없는 선택 영역입니다.',
  'too-many-blocks': '선택 영역이 너무 큽니다.',
  'too-long': '선택 영역이 너무 큽니다.',
};

function translateBlockedMessage(check: TranslationSelectionCheck): string | undefined {
  return check === 'ok' ? undefined : TRANSLATE_BLOCKED_MESSAGE[check];
}

function truncate(text: string, n: number): string {
  return text.length > n ? `${text.slice(0, n).trim()}…` : text;
}

export function SelectionLayer({ paperId, viewerRef, blocks, onAsk }: SelectionLayerProps) {
  const sel = useTextSelection(viewerRef, blocks);
  const [layer, setLayer] = useState<Layer>({ phase: 'idle' });
  // 스트리밍 중 누적 텍스트. layer 밖에 두는 이유: delta마다 layer를 갈아끼우면 아래 번역 effect가 재실행돼 요청을 다시 보낸다.
  const [partial, setPartial] = useState('');
  const popupRef = useRef<HTMLDivElement>(null);

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

  // 바깥 클릭 dismiss — 목업 handleDocMouseDown(L360-370) 1:1 이식. 팝업이 열려 있을 때만 등록한다.
  // 판별 순서(목업과 동일): 팝업 내부 클릭 무시 → 뷰어 내부 클릭 무시(새 선택은 useTextSelection이
  // 처리) → 그 외는 dismiss. 열려 있던 모든 phase(toolbar/translating/translated)를
  // idle로 되돌리고, 기존 닫기 버튼과 동일하게 clear()로 원문 읽기 상태를 복원한다.
  useEffect(() => {
    if (layer.phase === 'idle') return;
    const { clear } = layer;
    function handleDocMouseDown(e: MouseEvent) {
      const target = e.target as Node;
      if (popupRef.current && popupRef.current.contains(target)) return;
      if (viewerRef.current && viewerRef.current.contains(target)) return;
      clear();
      setLayer({ phase: 'idle' });
    }
    document.addEventListener('mousedown', handleDocMouseDown, true);
    return () => document.removeEventListener('mousedown', handleDocMouseDown, true);
  }, [layer, viewerRef]);

  // 'translating' 진입에 반응해 스트림을 연다. cleanup의 abort는 FE 수신 중단일 뿐이다 —
  // translation.started 이후라면 BE는 완주하고, 그 전에 끊기면 run이 생기지 않을 수도 있다.
  useEffect(() => {
    if (layer.phase !== 'translating') return;
    const { text, rect, clear, anchors } = layer;
    if (!anchors) {
      setLayer({ phase: 'translateFailed', text, rect, clear, anchors, message: TRANSLATE_BLOCKED_MESSAGE.unknown });
      return;
    }
    const controller = new AbortController();
    let accumulated = '';
    setPartial('');
    void streamTranslation({
      paperId,
      selection: anchors,
      signal: controller.signal,
      onEvent: (e) => {
        if (controller.signal.aborted) return;
        switch (e.type) {
          case 'delta':
            accumulated += e.delta;
            setPartial(accumulated);
            break;
          case 'completed':
            setLayer((prev) => (prev.phase === 'translating' ? { phase: 'translated', text, rect, clear, anchors, translation: e.translation } : prev));
            break;
          case 'failed':
            setLayer((prev) => (prev.phase === 'translating'
              ? {
                  phase: 'translateFailed', text, rect, clear, anchors,
                  message: e.code === 'TRANSLATION_IN_PROGRESS' ? '이전 번역이 끝나면 다시 시도해 주세요.' : e.message,
                }
              : prev));
            break;
          default:
            break;
        }
      },
    });
    return () => controller.abort();
  }, [layer, paperId]);

  if (layer.phase === 'idle') return null;

  const container = viewerRef.current?.getBoundingClientRect() ?? new DOMRect();

  function handleTranslate() {
    if (layer.phase !== 'toolbar') return;
    const { text, rect, clear, anchors } = layer;
    setPartial('');
    setLayer({ phase: 'translating', text, rect, clear, anchors });
  }

  // 질문하기 → 바로 현재 채팅에 첨부. StudyPage가 첨부·챗 패널 열림·포커스를 처리한다.
  function handleAsk() {
    if (layer.phase !== 'toolbar') return;
    const { text, clear, anchors } = layer;
    clear();
    setLayer({ phase: 'idle' });
    onAsk(text, 'current', anchors);
  }

  // 번역 팝업 닫기 → clear()로 원문 읽기 복귀 (FT-006 Story 2).
  function handleCloseTranslation() {
    if (layer.phase !== 'translating' && layer.phase !== 'translated' && layer.phase !== 'translateFailed') return;
    layer.clear();
    setLayer({ phase: 'idle' });
  }

  if (layer.phase === 'toolbar') {
    const pos = computeToolbarPosition(layer.rect, container, TOOLBAR_POPUP_SIZE);
    const blocked = translateBlockedMessage(checkTranslationSelection(blocks, layer.anchors));
    const translateDisabled = blocked !== undefined;
    return (
      <div
        ref={popupRef}
        onMouseDown={(e) => e.preventDefault()}
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
        <ToolbarButton
          icon={<Translate size={14} />}
          label="번역"
          onClick={handleTranslate}
          disabled={translateDisabled}
          title={blocked}
        />
        <div style={{ width: 1, alignSelf: 'stretch', background: 'rgba(255,253,247,0.18)', margin: '4px 0' }} />
        <ToolbarButton icon={<ChatCircleText size={14} />} label="질문하기" onClick={handleAsk} />
      </div>
    );
  }

  if (layer.phase === 'translating' || layer.phase === 'translated' || layer.phase === 'translateFailed') {
    const pos = computeToolbarPosition(layer.rect, container, TRANSLATION_POPUP_SIZE);
    return (
      <div
        ref={popupRef}
        onMouseDown={(e) => e.preventDefault()}
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
        <div style={{ fontFamily: 'var(--font-serif)', fontSize: 15, lineHeight: 1.7, color: 'var(--color-text-body)', maxHeight: 320, overflowY: 'auto' }}>
          {layer.phase === 'translating' && (partial || '번역 중…')}
          {layer.phase === 'translated' && <PaperMarkdown>{layer.translation}</PaperMarkdown>}
          {layer.phase === 'translateFailed' && (
            <span role="alert" style={{ color: 'var(--color-text-muted)' }}>{layer.message}</span>
          )}
        </div>
      </div>
    );
  }

  return null;
}

export function ToolbarButton({ icon, label, onClick, disabled = false, title }: {
  icon: React.ReactNode; label: string; onClick: () => void; disabled?: boolean; title?: string;
}) {
  const [hover, setHover] = useState(false);
  const style: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    background: hover && !disabled ? 'rgba(255,253,247,0.14)' : 'transparent',
    border: 'none',
    cursor: disabled ? 'not-allowed' : 'pointer',
    color: 'var(--color-on-dark)',
    fontFamily: 'var(--font-sans)',
    fontSize: 13,
    fontWeight: 600,
    padding: '8px 12px',
    borderRadius: 'var(--radius-structural)',
    whiteSpace: 'nowrap',
    transition: 'background 150ms ease',
    opacity: disabled ? 0.5 : 1,
  };
  const button = (
    <button onClick={onClick} disabled={disabled} onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)} style={style}>
      {icon}
      {label}
    </button>
  );
  if (title) {
    // Safari는 disabled 컨트롤에서 title 툴팁을 억제한다 — span으로 감싸 pointer event를 받게 한다.
    return <span title={title} style={{ display: 'inline-flex' }}>{button}</span>;
  }
  return button;
}
