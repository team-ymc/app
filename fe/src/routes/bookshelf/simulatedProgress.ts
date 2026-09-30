const THREE_MINUTES_MS = 3 * 60 * 1000;
const SLOW_FINISH_MS = 5 * 60 * 1000;

/**
 * Frontend-only estimate used while the backend exposes a processing status but
 * no real percentage. It reaches 95% at three minutes, then creeps to 99%.
 */
export function simulatedProgress(createdAt: string, now = Date.now()): number {
  const createdAtMs = Date.parse(createdAt);
  if (!Number.isFinite(createdAtMs)) return 0;

  const elapsed = Math.max(0, now - createdAtMs);
  if (elapsed <= THREE_MINUTES_MS) {
    return Math.min(95, (elapsed / THREE_MINUTES_MS) * 95);
  }

  const slowProgress = ((elapsed - THREE_MINUTES_MS) / SLOW_FINISH_MS) * 4;
  return Math.min(99, 95 + slowProgress);
}
