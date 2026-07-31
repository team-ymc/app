const MARGIN = 8;

export function computeToolbarPosition(
  sel: DOMRect,
  container: DOMRect,
  popup: { width: number; height: number },
): { top: number; left: number } {
  const top = sel.bottom - container.top + MARGIN;
  const center = sel.left + sel.width / 2 - container.left;
  const left = Math.min(Math.max(MARGIN, center - popup.width / 2), container.width - popup.width - MARGIN);
  return { top, left };
}
