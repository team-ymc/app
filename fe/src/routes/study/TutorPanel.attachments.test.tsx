import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TutorPanel } from './TutorPanel';
import { streamChatMessage } from '../../chat/chatStream';
import type { SelectionAttachment } from '../../chat/selectionAttachments';

vi.mock('../../chat/chatStream', () => ({ streamChatMessage: vi.fn() }));
vi.mock('../../api/chatSessions', () => ({
  listChatSessions: vi.fn(), listChatSessionMessages: vi.fn(), deleteChatSession: vi.fn(),
}));

Element.prototype.scrollIntoView = vi.fn();

function att(blockId: string, kind: SelectionAttachment['kind'] = 'selection', text = `${blockId} 미리보기`): SelectionAttachment {
  return { text, anchors: { start: { blockId }, end: { blockId } }, kind };
}

function renderPanel(overrides: Partial<Parameters<typeof TutorPanel>[0]> = {}) {
  const qc = new QueryClient();
  const props = {
    paperId: 'p1',
    blocks: [],
    attachments: [] as SelectionAttachment[],
    attachEvent: null,
    onRemoveAttachment: vi.fn(),
    onAttachmentsConsumed: vi.fn(),
    attachNotice: null,
    collapsed: false,
    onToggleCollapse: () => {},
    ...overrides,
  };
  render(
    <QueryClientProvider client={qc}>
      <TutorPanel {...props} />
    </QueryClientProvider>,
  );
  return props;
}

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

describe('TutorPanel — 다중선택 인용 칩', () => {
  it('첨부 2개 이하는 전부 보이고 배지가 없다', () => {
    renderPanel({ attachments: [att('b1'), att('b2', 'image')] });
    expect(screen.getByRole('button', { name: '인용 제거' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '이미지 제거' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /펼치기/ })).toBeNull();
  });

  it('첨부 3개 이상은 2개 + +N 배지로 접히고, 배지 클릭 시 전부 펼친다', () => {
    renderPanel({ attachments: [att('b1'), att('b2'), att('b3', 'table'), att('b4', 'formula')] });
    expect(screen.getAllByRole('button', { name: /제거$/ })).toHaveLength(2);
    const badge = screen.getByRole('button', { name: '숨은 인용 2개 펼치기' });
    expect(badge.textContent).toBe('+2');
    fireEvent.click(badge);
    expect(screen.getAllByRole('button', { name: /제거$/ })).toHaveLength(4);
    expect(screen.getByRole('button', { name: '표 제거' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '수식 제거' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '첨부 접기' }));
    expect(screen.getAllByRole('button', { name: /제거$/ })).toHaveLength(2);
  });

  it('칩의 제거 버튼은 해당 index로 onRemoveAttachment를 호출한다', () => {
    const props = renderPanel({ attachments: [att('b1'), att('b2', 'image')] });
    fireEvent.click(screen.getByRole('button', { name: '이미지 제거' }));
    expect(props.onRemoveAttachment).toHaveBeenCalledWith(1);
  });

  it('전송하면 selections 배열로 스트림을 시작하고 첨부를 소비한다', () => {
    const attachments = [att('b1'), att('b2', 'image')];
    const props = renderPanel({ attachments });
    fireEvent.change(screen.getByPlaceholderText('AI에게 질문해보세요'), { target: { value: '질문' } });
    fireEvent.keyDown(screen.getByPlaceholderText('AI에게 질문해보세요'), { key: 'Enter' });
    expect(streamChatMessage).toHaveBeenCalledWith(expect.objectContaining({
      selections: [attachments[0].anchors, attachments[1].anchors],
    }));
    expect(props.onAttachmentsConsumed).toHaveBeenCalled();
  });

  it('첨부가 없으면 selections를 빈 배열로 보낸다', () => {
    renderPanel();
    fireEvent.change(screen.getByPlaceholderText('AI에게 질문해보세요'), { target: { value: '질문' } });
    fireEvent.keyDown(screen.getByPlaceholderText('AI에게 질문해보세요'), { key: 'Enter' });
    expect(streamChatMessage).toHaveBeenCalledWith(expect.objectContaining({ selections: [] }));
  });

  it('attachNotice가 있으면 컴포저 위에 안내를 표시한다', () => {
    renderPanel({ attachNotice: '인용은 최대 5개까지 첨부할 수 있어요' });
    expect(screen.getByText('인용은 최대 5개까지 첨부할 수 있어요')).toBeTruthy();
  });
});
