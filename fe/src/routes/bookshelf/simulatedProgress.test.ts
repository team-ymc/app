import { describe, expect, it } from 'vitest';
import { simulatedProgress } from './simulatedProgress';

const CREATED_AT = '2026-09-30T00:00:00.000Z';
const START = Date.parse(CREATED_AT);

describe('simulatedProgress', () => {
  it('starts at 0 and reaches 95% at three minutes', () => {
    expect(simulatedProgress(CREATED_AT, START)).toBe(0);
    expect(simulatedProgress(CREATED_AT, START + 90_000)).toBeCloseTo(47.5);
    expect(simulatedProgress(CREATED_AT, START + 180_000)).toBe(95);
  });

  it('slows after three minutes and never exceeds 99%', () => {
    expect(simulatedProgress(CREATED_AT, START + 240_000)).toBeCloseTo(95.8);
    expect(simulatedProgress(CREATED_AT, START + 480_000)).toBe(99);
    expect(simulatedProgress(CREATED_AT, START + 3_600_000)).toBe(99);
  });

  it('guards against invalid or future timestamps', () => {
    expect(simulatedProgress('not-a-date', START)).toBe(0);
    expect(simulatedProgress(CREATED_AT, START - 1_000)).toBe(0);
  });
});
