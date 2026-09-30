// 서재 실패 행의 상태 표시. 문구와 배치는 아트보드 그대로.
import type { KeyboardEvent, MouseEvent } from 'react';
import { Button } from '../../design/components/Button';
import type { Paper } from '../../api/types';

export interface FailedRowStatusProps {
  paper: Paper;
  retrying: boolean;
  onRetry: () => void;
  /** 격자 카드는 폭이 168px이라 가로로 놓으면 넘친다. 세로로 쌓는다. */
  stacked?: boolean;
}

const LABEL = {
  fontFamily: 'var(--font-sans)',
  fontSize: '12px',
  fontWeight: 600,
  color: 'var(--color-danger)',
  flexShrink: 0,
  whiteSpace: 'nowrap',
} as const;

const HINT = {
  fontFamily: 'var(--font-sans)',
  fontSize: '11px',
  color: 'var(--color-text-muted)',
} as const;

export default function FailedRowStatus({ paper, retrying, onRetry, stacked = false }: FailedRowStatusProps) {
  if (paper.failReason === 'RETRY_LIMIT_EXCEEDED') {
    return <span style={LABEL}>처리할 수 없는 파일입니다</span>;
  }
  if (paper.failReason !== 'PROCESSING_FAILED') {
    return <span style={LABEL}>실패</span>;
  }

  // 행 전체가 버튼 역할이라 클릭과 키 입력이 행으로 올라가지 않게 막는다
  function stop(e: MouseEvent | KeyboardEvent) {
    e.stopPropagation();
  }

  return (
    <div
      data-testid="failed-row-status"
      data-layout={stacked ? 'stacked' : 'inline'}
      style={{
        display: 'flex',
        flexDirection: stacked ? 'column' : 'row',
        alignItems: stacked ? 'flex-start' : 'center',
        gap: stacked ? '8px' : '12px',
        flexShrink: 0,
      }}
    >
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: stacked ? 'flex-start' : 'flex-end', gap: '2px' }}>
        <span style={LABEL}>실패</span>
        <span style={{ ...HINT, whiteSpace: stacked ? 'normal' : 'nowrap' }}>등록 횟수가 차감되지 않았습니다</span>
      </div>
      <span onClick={stop} onKeyDown={stop} style={{ display: 'inline-flex', flexShrink: 0 }}>
        <Button
          variant="secondary"
          icon="arrow-clockwise"
          disabled={retrying}
          onClick={onRetry}
          style={{ height: '32px', padding: '0 12px', fontSize: '13px' }}
        >
          <span style={{ whiteSpace: 'nowrap', fontWeight: 600 }}>다시 시도</span>
        </Button>
      </span>
    </div>
  );
}
