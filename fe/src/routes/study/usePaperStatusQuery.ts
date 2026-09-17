// 학습·지식 그래프 화면이 공유하는 상태 조회. 키가 같아 캐시를 공유하고 폴링 옵션은 여기 한 곳에만 둔다.
import { useQuery } from '@tanstack/react-query';
import { getStatus } from '../../api/papers';
import { statusRefetchInterval } from './knowledgeGraphStatus';

export function usePaperStatusQuery(paperId: string | undefined) {
  return useQuery({
    queryKey: ['paper-status', paperId],
    queryFn: () => getStatus(paperId as string),
    enabled: !!paperId,
    // 번역·지식 그래프가 준비 중일 때만 폴링한다. 서재 폴링과 달리 파싱 상태는 이미 COMPLETED다.
    refetchInterval: (query) => statusRefetchInterval(query.state.data),
  });
}
