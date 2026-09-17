// 지식 그래프 화면: AI 컴파일 워커가 만든 단독 HTML(viz.html)을 presigned URL로 iframe에 그대로 띄운다.
// 그래프·섹션 리더·번역 토글은 viz.html 자체 기능이라 여기서는 상단 바와 준비 상태만 다룬다.
// 이식: project-docs/design/v2/Paper Knowledge Graph Page.dc.html — R1 top bar / R2 iframe.
import type { ReactNode } from 'react';
import { Link, Navigate, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { getKnowledgeGraphView } from '../api/papers';
import { ApiError, type KnowledgeGraphStatus } from '../api/types';
import { getPaperContent } from '../markdown/paperContent';
import { StudyTopBar } from './study/StudyTopBar';
import { usePaperStatusQuery } from './study/usePaperStatusQuery';
import { knowledgeGraphDisabledReason } from './study/knowledgeGraphStatus';

export default function KnowledgeGraphPage() {
  const { paperId } = useParams<{ paperId: string }>();
  const statusQuery = usePaperStatusQuery(paperId);

  if (!paperId) {
    return <Navigate to="/library" replace state={{ toast: '잘못된 접근입니다' }} />;
  }
  if (statusQuery.isPending) {
    return <Centered>불러오는 중…</Centered>;
  }
  if (statusQuery.isError) {
    const gone = statusQuery.error instanceof ApiError && statusQuery.error.httpStatus === 404;
    return <Navigate to="/library" replace state={{ toast: gone ? '삭제되었거나 없는 논문입니다' : '논문 상태를 불러오지 못했습니다' }} />;
  }
  // 학습 화면과 같은 규칙 — 파싱이 끝나지 않은 논문은 서재로.
  if (statusQuery.data.status !== 'COMPLETED') {
    const toast =
      statusQuery.data.status === 'FAILED' || statusQuery.data.status === 'EXPIRED'
        ? '분석에 실패한 논문입니다'
        : '아직 분석 중인 논문입니다';
    return <Navigate to="/library" replace state={{ toast }} />;
  }

  return <KnowledgeGraphContent paperId={paperId} knowledgeGraphStatus={statusQuery.data.knowledgeGraphStatus} />;
}

function KnowledgeGraphContent({ paperId, knowledgeGraphStatus }: { paperId: string; knowledgeGraphStatus: KnowledgeGraphStatus | null }) {
  // 제목만 쓰지만 학습 화면과 같은 키라 오가는 동안 캐시를 공유한다.
  const contentQuery = useQuery({
    queryKey: ['paper-content', paperId],
    queryFn: () => getPaperContent(paperId),
  });
  const ready = knowledgeGraphStatus === 'READY';
  // 진입할 때마다 새 URL을 받는다(gcTime 0). 떠 있는 동안은 포커스·재접속에도 다시 받지 않는다 — URL이 바뀌면 iframe이 통째로 다시 로드된다.
  const viewQuery = useQuery({
    queryKey: ['knowledge-graph-view', paperId],
    queryFn: () => getKnowledgeGraphView(paperId),
    enabled: ready,
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
  });

  const titleText = contentQuery.data?.title ?? 'Paper Teacher';

  return (
    <div
      style={{
        height: '100vh',
        width: '100%',
        display: 'flex',
        flexDirection: 'column',
        background: 'var(--color-bg-canvas)',
        fontFamily: 'var(--font-sans)',
        overflow: 'hidden',
      }}
    >
      {/* R1 Global top bar — 번역·야간 모드는 viz.html이 스스로 다루므로 없다 */}
      <StudyTopBar paperId={paperId} title={titleText} current="graph" knowledgeGraphStatus={knowledgeGraphStatus} />

      {/* R2 viz.html */}
      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', background: 'var(--color-bg-canvas)' }}>
        {!ready ? (
          <Centered>
            {knowledgeGraphDisabledReason(knowledgeGraphStatus)}{' '}
            <Link to={`/papers/${paperId}`} style={{ marginLeft: 8 }}>본문으로</Link>
          </Centered>
        ) : viewQuery.data ? (
          // S3 오리진 문서라 앱 오리진과 격리된다. allow-same-origin은 viz.html의 localStorage(번역 모드 기억)용이다.
          // iframe 안 로드 실패는 cross-origin이라 감지할 수 없다 — 다시 시도는 URL 발급 실패에만 붙는다.
          <iframe
            src={viewQuery.data.url}
            title="지식 그래프"
            sandbox="allow-scripts allow-same-origin"
            style={{ flex: 1, width: '100%', border: 'none', background: 'var(--color-bg-canvas)' }}
          />
        ) : viewQuery.isPending ? (
          <Centered>지식 그래프를 불러오는 중…</Centered>
        ) : viewQuery.isError ? (
          <Centered>
            지식 그래프를 불러오지 못했습니다{' '}
            <button type="button" onClick={() => viewQuery.refetch()} style={{ marginLeft: 8 }}>다시 시도</button>
          </Centered>
        ) : null}
      </div>
    </div>
  );
}

function Centered({ children }: { children: ReactNode }) {
  return (
    <div style={{ padding: 48, textAlign: 'center', fontFamily: 'var(--font-sans)', color: 'var(--color-text-muted)' }}>
      {children}
    </div>
  );
}
