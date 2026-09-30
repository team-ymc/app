// 서재의 재시도 호출. 성공하면 목록을 다시 받아 분석 중 상태가 되고 기존 폴링이 이어진다.
import { useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { retryPaper } from '../../api/papers';
import type { Paper } from '../../api/types';
import { retryErrorMessage } from '../retryMessage';

export function useRetryPaper(showToast: (text: string) => void) {
  const queryClient = useQueryClient();
  const [retryingId, setRetryingId] = useState<string | null>(null);
  // state는 다음 렌더에야 바뀐다 — 같은 틱의 연속 클릭은 ref로 막는다
  const busyRef = useRef(false);

  async function retry(paper: Paper) {
    if (busyRef.current) return;
    busyRef.current = true;
    setRetryingId(paper.paperId);
    try {
      const next = await retryPaper(paper.paperId);
      showToast(next.status === 'COMPLETED' ? '논문이 준비되었습니다' : '다시 분석을 시작했습니다');
    } catch (e) {
      showToast(retryErrorMessage(e));
    } finally {
      // 성공이면 상태와 사용량이, 실패면 실패 사유와 남은 횟수가 바뀌었을 수 있다
      // 목록이 새 상태로 바뀐 뒤에 버튼을 풀어 실패 행이 잠깐 되살아나지 않게 한다
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['papers'] }),
        queryClient.invalidateQueries({ queryKey: ['plan'] }),
      ]);
      busyRef.current = false;
      setRetryingId(null);
    }
  }

  return { retryingId, retry };
}
