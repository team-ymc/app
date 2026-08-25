import type { Paper } from '../../api/types';

/** 완료 행의 날짜 표기 — 접근 이력이 없으면 등록일로 폴백한다 (계약 PaperListItem.lastAccessedAt). */
export function accessDisplay(
  paper: Pick<Paper, 'createdAt' | 'lastAccessedAt'>,
): { prefix: '최근 조회' | '등록일'; iso: string } {
  if (paper.lastAccessedAt) {
    return { prefix: '최근 조회', iso: paper.lastAccessedAt };
  }
  return { prefix: '등록일', iso: paper.createdAt };
}
