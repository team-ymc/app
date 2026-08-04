import type { Paper } from '../../api/types';

export function filterPapers(papers: Paper[], keyword: string): Paper[] {
  const k = keyword.trim().toLowerCase();
  if (!k) return papers;
  return papers.filter((p) => p.filename.toLowerCase().includes(k));
}

export function paginate<T>(items: T[], page: number, pageSize: number): { items: T[]; totalPages: number } {
  const totalPages = Math.max(1, Math.ceil(items.length / pageSize));
  const clamped = Math.min(Math.max(1, page), totalPages);
  return { items: items.slice((clamped - 1) * pageSize, clamped * pageSize), totalPages };
}
