import { useQuery } from '@tanstack/react-query';
import { listPapers } from '../../api/papers';
import { TERMINAL_STATUSES } from '../../api/types';

export function usePapersQuery() {
  return useQuery({
    queryKey: ['papers'],
    queryFn: listPapers,
    refetchInterval: (query) => {
      const papers = query.state.data?.papers;
      return papers?.some((p) => !TERMINAL_STATUSES.has(p.status)) ? 2000 : false;
    },
  });
}
