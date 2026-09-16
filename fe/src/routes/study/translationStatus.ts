// 학습 화면의 번역 상태 처리. 폴링은 PENDING일 때만, 툴팁은 아트보드 문구 그대로.
import type { TranslationStatus } from '../../api/types';

/** 컴파일은 분 단위라 서재의 2초 폴링보다 느슨하게 둔다. */
export const TRANSLATION_POLL_MS = 5000;

export function translationRefetchInterval(status: TranslationStatus | undefined): number | false {
  return status === 'PENDING' ? TRANSLATION_POLL_MS : false;
}

const REASON: Partial<Record<TranslationStatus, string>> = {
  NOT_APPLICABLE: '한국어 논문은 번역하지 않습니다',
  PENDING: '번역을 준비하고 있습니다',
  FAILED: '번역 생성에 실패했습니다',
};

/** 번역 버튼 비활성 사유. READY인데 번역 블록이 없는 경우(사이드카 없음·병합 0건)는 준비 안 됨 문구다. */
export function translationDisabledReason(status: TranslationStatus | undefined, hasTranslation: boolean): string {
  if (status === 'READY' && hasTranslation) return '';
  return (status && REASON[status]) ?? '이 논문은 번역이 준비되지 않았습니다';
}
