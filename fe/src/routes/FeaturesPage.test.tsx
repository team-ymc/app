import { cleanup, fireEvent, render, screen, act, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import FeaturesPage from './FeaturesPage';

vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ status: 'guest', startLogin: vi.fn(), initialError: null }) }));
vi.mock('../nav/GlobalNav', () => ({ GlobalNav: () => <nav>Paper Teacher</nav> }));

let intersections: IntersectionObserverCallback[];
let reducedMotion = false;
const play = vi.fn(function(this: HTMLVideoElement) {
  this.dispatchEvent(new Event('play'));
  return Promise.resolve();
});
const pause = vi.fn(function(this: HTMLVideoElement) { this.dispatchEvent(new Event('pause')); });

beforeEach(() => {
  intersections = [];
  reducedMotion = false;
  vi.clearAllMocks();
  vi.stubGlobal('matchMedia', () => ({ matches: reducedMotion, addEventListener: vi.fn(), removeEventListener: vi.fn() }));
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback: IntersectionObserverCallback) { intersections.push(callback); }
    observe() {}
    disconnect() {}
  });
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(play);
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(pause);
});
afterEach(() => {
  cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals();
});

function setup() { return render(<MemoryRouter><FeaturesPage /></MemoryRouter>); }
function enter(index: number, visible = true) {
  act(() => intersections[index]([{ isIntersecting: visible } as IntersectionObserverEntry], {} as IntersectionObserver));
}

describe('feature demonstration films', () => {
  it('provides five silent inline autoplaying loops', () => {
    const { container } = setup();
    const videos = [...container.querySelectorAll('.features-demo-screen video')];
    expect(videos).toHaveLength(5);
    expect(videos.every(video => !video.getAttribute('src'))).toBe(true);
    videos.forEach((_, index) => enter(index));
    for (const video of videos) {
      expect(video.getAttribute('src')).toMatch(/\.mp4$/);
      expect((video as HTMLVideoElement).muted).toBe(true);
      expect(video.hasAttribute('autoplay')).toBe(true);
      expect(video.hasAttribute('playsinline')).toBe(true);
      expect(video.hasAttribute('loop')).toBe(true);
      expect(video.getAttribute('width')).toBe('1224');
      expect(video.getAttribute('height')).toBe('784');
    }
    expect(videos[2].getAttribute('src')).toBe('/features/inline-query.mp4');
  });

  it('automatically plays when visible and pauses offscreen', () => {
    setup();
    enter(0);
    expect(play).toHaveBeenCalled();
    pause.mockClear();
    enter(0, false);
    expect(pause).toHaveBeenCalled();
  });

  it('preserves a manual pause when the demo leaves and reenters the viewport', () => {
    setup(); enter(0);
    fireEvent.click(screen.getByRole('button', { name: '선행지식 영상 멈추기' }));
    play.mockClear(); enter(0, false); enter(0);
    expect(play).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '선행지식 영상 재생하기' }));
    expect(play).toHaveBeenCalled();
  });

  it('respects reduced motion while allowing an explicit play', () => {
    reducedMotion = true;
    setup(); enter(0);
    expect(play).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '선행지식 영상 재생하기' }));
    expect(play).toHaveBeenCalled();
  });

  it('shows only framed films without surrounding labels, captions, or controls', () => {
    const { container } = setup();
    const demos = [...container.querySelectorAll('.features-demo')];
    expect(demos).toHaveLength(5);
    for (const demo of demos) {
      expect(demo.textContent).toBe('');
      expect(demo.children).toHaveLength(1);
      expect(demo.querySelector('video')!.controls).toBe(false);
      expect(demo.querySelector('.features-demo-toolbar, figcaption, dialog')).toBeNull();
    }
  });

  it('falls back to a poster on media errors', async () => {
    const { container } = setup();
    fireEvent.error(container.querySelector('video')!);
    await waitFor(() => expect(container.querySelector('.features-demo-screen img')).not.toBeNull());
  });
});
