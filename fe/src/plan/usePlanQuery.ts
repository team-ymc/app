import { useQuery } from '@tanstack/react-query';
import { getMyPlan } from '../api/plan';

// 폴링 없음 — 사용량이 변하는 시점(채팅 completed·업로드 성공·429)에 invalidateQueries(['plan'])로 갱신한다.
export function usePlanQuery() {
  return useQuery({ queryKey: ['plan'], queryFn: getMyPlan });
}
