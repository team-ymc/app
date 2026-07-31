// 인라인 번역 계약 미확정 — 목 응답 (spec §6). 계약 확정 시 이 함수만 실제 fetch로 교체.
export async function translateSelection(text: string): Promise<{ translation: string }> {
  await new Promise((r) => setTimeout(r, 600));
  return { translation: `(번역 결과 자리 — 계약 확정 전 목 응답)\n${text}` };
}
