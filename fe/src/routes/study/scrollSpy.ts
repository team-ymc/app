export function pickActiveHeading(visibleIds: string[], tocOrder: string[]): string | null {
  const visible = new Set(visibleIds);
  return tocOrder.find((id) => visible.has(id)) ?? null;
}
