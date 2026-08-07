// Task 14 brief: document의 selectionchange + mouseup에서 window.getSelection()을 검사해
// 뷰어 컨테이너 내부의 텍스트 선택만 추적한다. 뷰어 밖 선택·collapse된 선택·clear() 호출 시 null.
import { useCallback, useEffect, useState, type RefObject } from 'react';
import { computeSelectionAnchors, type SelectionAnchors } from './selectionAnchors';

export interface TextSelection {
  text: string;
  rect: DOMRect;
  anchors: SelectionAnchors | null;
  clear: () => void;
}

export function useTextSelection(viewerRef: RefObject<HTMLDivElement | null>): TextSelection | null {
  const [selection, setSelection] = useState<TextSelection | null>(null);

  const clear = useCallback(() => {
    window.getSelection()?.removeAllRanges();
    setSelection(null);
  }, []);

  useEffect(() => {
    function handleChange() {
      const container = viewerRef.current;
      const sel = window.getSelection();
      if (!container || !sel || sel.isCollapsed || sel.rangeCount === 0) {
        setSelection(null);
        return;
      }
      const text = sel.toString().trim();
      if (!text) {
        setSelection(null);
        return;
      }
      const range = sel.getRangeAt(0);
      // range가 뷰어 컨테이너 내부인지 — commonAncestorContainer가 컨테이너 안(또는 컨테이너 자신)일 때만 인정.
      if (!container.contains(range.commonAncestorContainer)) {
        setSelection(null);
        return;
      }
      setSelection({ text, rect: range.getBoundingClientRect(), anchors: computeSelectionAnchors(range), clear });
    }

    document.addEventListener('selectionchange', handleChange);
    document.addEventListener('mouseup', handleChange);
    return () => {
      document.removeEventListener('selectionchange', handleChange);
      document.removeEventListener('mouseup', handleChange);
    };
  }, [viewerRef, clear]);

  return selection;
}
