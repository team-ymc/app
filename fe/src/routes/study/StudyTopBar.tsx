// 학습·지식 그래프 화면이 공유하는 R1 상단 바. 아트보드 Paper Knowledge Graph Page의 `본문 | 지식 그래프` 쌍을
// 옮겼고, 학습 화면 전용 컨트롤(번역·야간 모드)은 rightSlot으로 받는다.
import { type MouseEvent, type ReactNode } from 'react';
import { Link } from 'react-router';
import { ArrowLeft, BookOpen } from '@phosphor-icons/react';
import { PaperStackMark } from '../../design/components/PaperStackMark';
import { AccountMenu } from '../../account/AccountMenu';
import type { KnowledgeGraphStatus } from '../../api/types';
import { knowledgeGraphDisabledReason } from './knowledgeGraphStatus';

export type StudyView = 'content' | 'graph';

export interface StudyTopBarProps {
  paperId: string;
  title: string;
  current: StudyView;
  knowledgeGraphStatus: KnowledgeGraphStatus | null | undefined;
  /** 학습 화면 전용 컨트롤. 계정 메뉴 왼쪽에 놓인다. */
  rightSlot?: ReactNode;
}

const DIVIDER = { width: '1px', height: '18px', background: 'rgba(255,253,247,0.18)', flexShrink: 0 } as const;

/** 아트보드 .pt-topbar-btn. 현재 화면은 밝은 배경, 비활성은 흐리게. */
function pairLinkStyle(isCurrent: boolean, disabled: boolean) {
  return {
    height: '32px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: '7px',
    flexShrink: 0,
    background: isCurrent ? 'var(--color-on-dark)' : 'rgba(255,253,247,0.08)',
    border: `1px solid ${isCurrent ? 'var(--color-on-dark)' : 'rgba(255,253,247,0.22)'}`,
    borderRadius: '8px',
    color: isCurrent ? 'var(--color-bg-walnut)' : 'var(--color-on-dark)',
    padding: '0 10px 0 8px',
    fontFamily: 'var(--font-sans)',
    fontSize: '13px',
    fontWeight: 600,
    textDecoration: 'none',
    whiteSpace: 'nowrap',
    opacity: disabled ? 0.42 : 1,
    cursor: disabled ? 'not-allowed' : 'pointer',
  } as const;
}

/** 아트보드의 지식 그래프 아이콘(노드 여섯 개와 연결선). Phosphor에 같은 모양이 없어 그대로 옮겼다. */
function KnowledgeGraphIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      aria-hidden="true"
      style={{ width: 18, height: 18, fill: 'none', stroke: 'currentColor', strokeWidth: 1.45, strokeLinecap: 'round', strokeLinejoin: 'round', flexShrink: 0 }}
    >
      <path d="M5.2 6.6 10.7 4M13.2 4.4l5 3.1M5.5 8.7l2.2 6M10 15.8l6.1 1M18.4 9.5l-.9 5M9 14.6l2.5-8.4M12.9 6.2l3.9 1.9M9.7 16.8l2.3 2.1M16.1 17.8l-2.2 1.4" />
      <circle cx="4.5" cy="7.6" r="2" />
      <circle cx="12" cy="3.8" r="1.8" />
      <circle cx="19" cy="8.3" r="2" />
      <circle cx="8.5" cy="16.2" r="2.1" />
      <circle cx="17" cy="17" r="2" />
      <circle cx="12.8" cy="20" r="1.4" />
    </svg>
  );
}

export function StudyTopBar({ paperId, title, current, knowledgeGraphStatus, rightSlot }: StudyTopBarProps) {
  const graphDisabled = knowledgeGraphStatus !== 'READY';
  const graphReason = knowledgeGraphDisabledReason(knowledgeGraphStatus);

  function blockIfDisabled(e: MouseEvent<HTMLAnchorElement>) {
    if (graphDisabled) e.preventDefault();
  }

  return (
    <div
      style={{
        height: '64px',
        flexShrink: 0,
        background: 'var(--color-bg-walnut)',
        color: 'var(--color-on-dark)',
        display: 'flex',
        alignItems: 'stretch',
      }}
    >
      <div style={{ flex: 1, minWidth: 0, display: 'flex', alignItems: 'center', padding: '0 20px 0 16px', gap: '16px' }}>
        <Link to="/" style={{ display: 'flex', alignItems: 'center', gap: '10px', flexShrink: 0, textDecoration: 'none', color: 'inherit' }}>
          <PaperStackMark size={22} color="var(--color-on-dark)" style={{ flexShrink: 0 }} />
          <span style={{ fontFamily: 'var(--font-serif)', fontWeight: 600, fontSize: '18px', letterSpacing: '-0.005em', whiteSpace: 'nowrap' }}>
            Paper Teacher
          </span>
        </Link>
        <div style={DIVIDER} />
        <Link
          to="/library"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '6px',
            background: 'transparent',
            border: 'none',
            padding: '7px 10px',
            borderRadius: '9999px',
            color: 'var(--color-on-dark)',
            fontFamily: 'var(--font-sans)',
            fontSize: '13px',
            fontWeight: 600,
            whiteSpace: 'nowrap',
            flexShrink: 0,
            textDecoration: 'none',
          }}
        >
          <ArrowLeft size={14} />
          서재로
        </Link>
        <div style={DIVIDER} />
        <div
          style={{
            fontFamily: 'var(--font-serif)',
            fontSize: '15px',
            fontWeight: 600,
            color: 'var(--color-on-dark)',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            flex: 1,
            minWidth: 0,
            opacity: 0.92,
          }}
        >
          {title}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '6px', flexShrink: 0 }}>
          <Link
            to={`/papers/${paperId}`}
            aria-current={current === 'content' ? 'page' : undefined}
            title="본문"
            style={pairLinkStyle(current === 'content', false)}
          >
            <BookOpen size={17} />
            <span>본문</span>
          </Link>
          <Link
            to={`/papers/${paperId}/graph`}
            aria-current={current === 'graph' ? 'page' : undefined}
            aria-disabled={graphDisabled ? 'true' : undefined}
            tabIndex={graphDisabled ? -1 : undefined}
            title={graphDisabled ? graphReason : '지식 그래프'}
            onClick={blockIfDisabled}
            style={pairLinkStyle(current === 'graph', graphDisabled)}
          >
            <KnowledgeGraphIcon />
            <span>지식 그래프</span>
          </Link>
        </div>
      </div>
      <div
        style={{
          flexShrink: 0,
          display: 'flex',
          alignItems: 'center',
          gap: '14px',
          padding: '0 20px',
          borderLeft: '1px solid rgba(255,253,247,0.14)',
        }}
      >
        {rightSlot}
        {rightSlot ? <div style={DIVIDER} /> : null}
        <AccountMenu />
      </div>
    </div>
  );
}
