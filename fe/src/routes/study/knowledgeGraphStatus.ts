// 지식 그래프 버튼의 비활성 사유와 상단 바 공용 폴링 판정. 툴팁은 아트보드 문구 그대로.
import type { KnowledgeGraphStatus, PaperStatusResponse } from '../../api/types';
import { TRANSLATION_POLL_MS, translationRefetchInterval } from './translationStatus';

const REASON: Record<KnowledgeGraphStatus, string> = {
  PENDING: '지식 그래프를 준비하고 있습니다',
  READY: '',
  FAILED: '지식 그래프를 준비하지 못했습니다',
};

/** READY면 빈 문자열. null(Document 연결 전)·undefined는 산출물이 없는 것과 같이 실패 문구다. */
export function knowledgeGraphDisabledReason(status: KnowledgeGraphStatus | null | undefined): string {
  return status ? REASON[status] : REASON.FAILED;
}

type StatusSignals = Pick<PaperStatusResponse, 'translationStatus' | 'knowledgeGraphStatus'>;

/** 번역·지식 그래프 중 하나라도 PENDING이면 5초 폴링. null은 준비 중이 아니므로 폴링하지 않는다. */
export function statusRefetchInterval(data: StatusSignals | undefined): number | false {
  if (!data) return false;
  if (translationRefetchInterval(data.translationStatus) !== false) return TRANSLATION_POLL_MS;
  return data.knowledgeGraphStatus === 'PENDING' ? TRANSLATION_POLL_MS : false;
}
