import { useEffect, useRef, useState } from 'react';
import './FeaturePreparation.css';

function PreparationIndicator({ completing }: { completing: boolean }) {
  const [progress, setProgress] = useState(0);
  const indicatorRef = useRef<HTMLDivElement>(null);
  const [layout, setLayout] = useState<{
    mobile: boolean;
    tracks: { left: number; top: number; width: number }[];
    label: { left: number; top: number };
  } | null>(null);

  useEffect(() => {
    const topBar = indicatorRef.current?.previousElementSibling;
    if (!topBar?.classList.contains('pt-study-topbar')) return;
    const controls = [
      topBar.querySelector('a[href$="/graph"]'),
      topBar.querySelector('button[role="switch"]'),
      topBar.querySelector('button[aria-pressed]'),
    ].filter((control): control is Element => control !== null);
    const toolbar = topBar.parentElement?.querySelector('.pt-study-toolbar');
    const tocButton = toolbar?.querySelector('.pt-toc-rail > button');
    const panelButton = toolbar?.querySelector('.pt-study-panel-toggle');

    function measure() {
      const mobile = typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 680px)').matches;
      const toolbarRect = toolbar?.getBoundingClientRect();
      if (mobile && toolbarRect && toolbarRect.height > 0) {
        const tocRect = tocButton?.getBoundingClientRect();
        const panelRect = panelButton?.getBoundingClientRect();
        // Use the existing space between the two phone toolbar buttons.
        // Reserve the TOC button's space even while the chat panel hides it.
        const left = Math.max(toolbarRect.left + 68, (tocRect?.width ? tocRect.right : toolbarRect.left + 56) + 12);
        const right = Math.min(toolbarRect.right - 68, (panelRect?.width ? panelRect.left : toolbarRect.right - 56) - 12);
        const width = Math.min(150, Math.max(0, right - left));
        if (!width || toolbarRect.bottom <= 0) {
          setLayout(null);
          return;
        }
        const center = (left + right) / 2;
        const middle = toolbarRect.top + toolbarRect.height / 2 + 2;
        setLayout({
          mobile: true,
          tracks: [{ left: center - width / 2, top: middle + 8, width }],
          label: { left: center, top: middle - 10 },
        });
        return;
      }
      const barRect = topBar!.getBoundingClientRect();
      if (barRect.height > 0 && barRect.bottom <= 0) {
        setLayout(null);
        return;
      }
      const rows: { left: number; right: number; top: number; bottom: number }[] = [];
      for (const control of controls) {
        const rect = control.getBoundingClientRect();
        if (!rect.width || !rect.height) continue;
        const row = rows.find((item) => Math.abs(item.top - rect.top) < 8);
        if (row) {
          row.left = Math.min(row.left, rect.left);
          row.right = Math.max(row.right, rect.right);
          row.bottom = Math.max(row.bottom, rect.bottom);
        } else {
          rows.push({ left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom });
        }
      }
      rows.sort((a, b) => a.top - b.top);
      const lastRow = rows[rows.length - 1];
      if (!lastRow) return;
      // Follow the original controls. On narrow screens, continue the line on
      // their existing wrapped rows rather than moving buttons or adding space.
      setLayout({
        mobile: false,
        tracks: rows.map((row) => ({ left: row.left, top: row.bottom + 4, width: row.right - row.left })),
        label: { left: (lastRow.left + lastRow.right) / 2, top: Math.max(5, lastRow.top - 13) },
      });
    }

    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(topBar);
    controls.forEach((control) => observer?.observe(control));
    if (toolbar) observer?.observe(toolbar);
    if (tocButton) observer?.observe(tocButton);
    if (panelButton) observer?.observe(panelButton);
    window.addEventListener('resize', measure);
    window.addEventListener('scroll', measure, true);
    return () => {
      observer?.disconnect();
      window.removeEventListener('resize', measure);
      window.removeEventListener('scroll', measure, true);
    };
  }, []);

  useEffect(() => {
    if (completing) return;
    setProgress(0);
    const startedAt = Date.now();
    const timer = window.setInterval(() => {
      // Display estimate only; elapsed time must never imply readiness.
      const next = Math.min(99, Math.max(0, (Date.now() - startedAt) / 100_000 * 99));
      setProgress(next);
      if (next === 99) window.clearInterval(timer);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [completing]);

  const displayedProgress = completing ? 100 : Math.floor(progress);

  return (
    <div ref={indicatorRef} className="pt-feature-preparation" data-placement={layout?.mobile ? 'mobile' : 'desktop'} data-completing={completing ? 'true' : undefined} role="status" aria-label="추가 기능 분석 중">
      <span className="pt-feature-preparation-label" style={{ ...layout?.label, visibility: layout ? 'visible' : 'hidden' }}>
        분석 중 <span className="pt-feature-preparation-percentage" aria-hidden="true">{displayedProgress}%</span>
      </span>
      {layout?.tracks.map((track, index) => (
        <div key={index} className="pt-feature-preparation-track" style={track} aria-hidden="true">
          <div className="pt-feature-preparation-fill" style={{ transform: `scaleX(${completing ? 1 : progress / 100})` }} />
        </div>
      ))}
    </div>
  );
}

export function FeaturePreparation({ preparing, ready = false, preview = false }: {
  preparing: boolean;
  /** Completion signal for the display only; never controls feature availability. */
  ready?: boolean;
  /** Visual-only demo; the caller restricts this to dev:preview. */
  preview?: boolean;
}) {
  const [previewRunning, setPreviewRunning] = useState(preview);
  const [visible, setVisible] = useState(preparing || preview);

  useEffect(() => {
    setPreviewRunning(preview);
    if (!preview) return;
    const timer = window.setTimeout(() => setPreviewRunning(false), 100_000);
    return () => window.clearTimeout(timer);
  }, [preview]);

  const active = preparing || previewRunning;
  const finished = ready || (preview && !previewRunning);

  useEffect(() => {
    if (active) {
      setVisible(true);
      return;
    }
    if (!finished) {
      setVisible(false);
      return;
    }
    if (!visible) return;
    // Match the library: 600ms to fill to 100%, then clear after 650ms.
    const timer = window.setTimeout(() => setVisible(false), 650);
    return () => window.clearTimeout(timer);
  }, [active, finished, visible]);

  return active || (visible && finished) ? <PreparationIndicator completing={!active} /> : null;
}
