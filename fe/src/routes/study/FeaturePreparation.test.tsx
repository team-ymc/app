import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FeaturePreparation } from './FeaturePreparation';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('FeaturePreparation', () => {
  function renderToolbar(rects: Record<string, DOMRect>) {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      return rects[this.getAttribute('data-testid') ?? ''] ?? new DOMRect();
    });
    return render(
      <>
        <div className="pt-study-topbar">
          <a href="/papers/p1" data-testid="content">본문</a>
          <a href="/papers/p1/graph" data-testid="graph">지식 그래프</a>
          <button role="switch" aria-checked="false" data-testid="prerequisite">선행지식</button>
          <button aria-pressed="false" data-testid="translation">번역</button>
        </div>
        <FeaturePreparation preparing />
        <div className="pt-study-toolbar" data-testid="toolbar">
          <div className="pt-toc-rail"><button data-testid="toc">목차</button></div>
          <button className="pt-study-panel-toggle" data-testid="chat">AI 튜터</button>
        </div>
      </>,
    );
  }

  it('limits the line to graph through translation and follows resizing', () => {
    const rects = {
      graph: new DOMRect(100, 16, 110, 32),
      prerequisite: new DOMRect(230, 16, 100, 32),
      translation: new DOMRect(350, 16, 70, 32),
    };
    const { container } = renderToolbar(rects);
    const track = container.querySelector('.pt-feature-preparation-track') as HTMLElement;
    expect(track.style.left).toBe('100px');
    expect(track.style.width).toBe('320px');
    expect(track.style.top).toBe('52px');
    expect(screen.getByTestId('content').nextElementSibling).toBe(screen.getByTestId('graph'));

    rects.translation = new DOMRect(380, 16, 70, 32);
    fireEvent(window, new Event('resize'));
    expect(track.style.width).toBe('350px');
  });

  it('follows wrapped controls without spanning unrelated toolbar controls', () => {
    const { container } = renderToolbar({
      graph: new DOMRect(300, 46, 110, 32),
      prerequisite: new DOMRect(12, 94, 95, 32),
      translation: new DOMRect(121, 94, 65, 32),
    });
    const tracks = container.querySelectorAll<HTMLElement>('.pt-feature-preparation-track');
    expect(tracks).toHaveLength(2);
    expect(tracks[0].style.left).toBe('300px');
    expect(tracks[0].style.width).toBe('110px');
    expect(tracks[1].style.left).toBe('12px');
    expect(tracks[1].style.width).toBe('174px');
    const label = container.querySelector('.pt-feature-preparation-label') as HTMLElement;
    expect(label.style.left).toBe('99px');
    expect(label.style.top).toBe('81px');
  });

  it.each([320, 478])('uses one centered indicator between the existing phone buttons at %spx', (width) => {
    const media = { matches: true };
    vi.stubGlobal('matchMedia', vi.fn(() => media));
    const { container } = renderToolbar({
      graph: new DOMRect(210, 46, 98, 32),
      prerequisite: new DOMRect(12, 94, 95, 32),
      translation: new DOMRect(121, 94, 65, 32),
      toolbar: new DOMRect(0, 134, width, 56),
      toc: new DOMRect(12, 140, 44, 44),
      chat: new DOMRect(width - 56, 140, 44, 44),
    });
    const indicator = screen.getByRole('status');
    expect(indicator.getAttribute('data-placement')).toBe('mobile');
    const tracks = container.querySelectorAll<HTMLElement>('.pt-feature-preparation-track');
    expect(tracks).toHaveLength(1);
    expect(tracks[0].style.left).toBe(`${(width - 150) / 2}px`);
    expect(tracks[0].style.width).toBe('150px');
    expect(tracks[0].style.top).toBe('172px');
    const label = container.querySelector('.pt-feature-preparation-label') as HTMLElement;
    expect(label.style.left).toBe(`${width / 2}px`);
    expect(label.style.top).toBe('154px');

    media.matches = false;
    fireEvent(window, new Event('resize'));
    expect(indicator.getAttribute('data-placement')).toBe('desktop');
    expect(container.querySelectorAll('.pt-feature-preparation-track')).toHaveLength(2);
  });

  it('keeps showing analysis after 100 seconds until the backend finishes', () => {
    vi.useFakeTimers();
    const { rerender } = render(<FeaturePreparation preparing />);
    expect(screen.getByText('0%')).toBeTruthy();
    act(() => { vi.advanceTimersByTime(50_000); });
    expect(screen.getByText('49%')).toBeTruthy();
    act(() => { vi.advanceTimersByTime(50_000); });
    expect(screen.getByText('99%')).toBeTruthy();
    act(() => { vi.advanceTimersByTime(200_000); });
    expect(screen.getByText('99%')).toBeTruthy();
    expect(screen.queryByText('100%')).toBeNull();
    expect(screen.getByRole('status').textContent).toContain('분석 중');

    rerender(<FeaturePreparation preparing={false} ready />);
    expect(screen.getByText('100%')).toBeTruthy();
    act(() => { vi.advanceTimersByTime(650); });
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('finishes early when readiness arrives and starts a fresh estimate on retry', () => {
    vi.useFakeTimers();
    const { rerender } = render(<FeaturePreparation preparing />);
    act(() => { vi.advanceTimersByTime(10_000); });
    expect(screen.getByText('9%')).toBeTruthy();
    rerender(<FeaturePreparation preparing={false} ready />);
    expect(screen.getByText('100%')).toBeTruthy();
    act(() => { vi.advanceTimersByTime(649); });
    expect(screen.getByText('100%')).toBeTruthy();
    act(() => { vi.advanceTimersByTime(1); });
    expect(screen.queryByRole('status')).toBeNull();

    rerender(<FeaturePreparation preparing />);
    expect(screen.getByText('0%')).toBeTruthy();
  });

  it('ends the visual preview after 100 seconds without changing the controls', () => {
    vi.useFakeTimers();
    const onClick = vi.fn();
    render(
      <>
        <FeaturePreparation preparing={false} preview />
        <button onClick={onClick}>번역</button>
      </>,
    );
    act(() => { vi.advanceTimersByTime(99_999); });
    expect(screen.getByRole('status')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '번역' }));
    expect(onClick).toHaveBeenCalledTimes(1);

    act(() => { vi.advanceTimersByTime(1); });
    expect(screen.getByText('100%')).toBeTruthy();
    act(() => { vi.advanceTimersByTime(650); });
    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.getByRole('button', { name: '번역' })).toBeTruthy();
  });

  it('does not show loading when no preparation or preview is running', () => {
    render(<FeaturePreparation preparing={false} />);
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('does not play completion for an already-ready paper', () => {
    render(<FeaturePreparation preparing={false} ready />);
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('clears a failed preparation without showing 100%', () => {
    vi.useFakeTimers();
    const { rerender } = render(<FeaturePreparation preparing />);
    act(() => { vi.advanceTimersByTime(10_000); });
    rerender(<FeaturePreparation preparing={false} ready={false} />);
    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.queryByText('100%')).toBeNull();
  });

  it('cancels the old completion timer if preparation restarts', () => {
    vi.useFakeTimers();
    const { rerender } = render(<FeaturePreparation preparing />);
    rerender(<FeaturePreparation preparing={false} ready />);
    expect(screen.getByText('100%')).toBeTruthy();
    act(() => { vi.advanceTimersByTime(300); });

    rerender(<FeaturePreparation preparing />);
    expect(screen.getByText('0%')).toBeTruthy();
    act(() => { vi.advanceTimersByTime(350); });
    expect(screen.getByRole('status')).toBeTruthy();
    expect(screen.queryByText('100%')).toBeNull();
  });
});
