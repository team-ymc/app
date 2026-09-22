import { act, fireEvent, render, screen, waitFor, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createRef } from 'react';
import { PrerequisiteLayer } from './PrerequisiteLayer';
import * as papersApi from '../../api/papers';
import { ApiError, type PrerequisiteDefinitionResponse } from '../../api/types';

vi.mock('../../api/papers');

afterEach(cleanup);

const highlights = [{ highlightId: 'h1', blockId: 'b', startOffset: 0, endOffset: 7, text: 'softmax' }];

function setup(opts: { visible?: boolean; openSignal?: number; onOpen?: () => void } = {}) {
  const viewerRef = createRef<HTMLDivElement>();
  const utils = render(
    <div>
      <div ref={viewerRef} data-testid="viewer" style={{ position: 'relative' }}>
        <p><mark className="term-highlight" data-highlight-id="h1" role="button" tabIndex={0}>softmax</mark> rest</p>
      </div>
      <PrerequisiteLayer
        paperId="p" viewerRef={viewerRef} highlights={highlights}
        visible={opts.visible ?? true} openSignal={opts.openSignal ?? 0} onOpen={opts.onOpen ?? (() => {})}
      />
      <aside data-testid="tutor">tutor</aside>
    </div>,
  );
  return { ...utils, viewerRef };
}

describe('PrerequisiteLayer', () => {
  // beforeEach가 아니라 afterEach에서 reset한다 — 앞 테스트의 act() 정리와 다음 테스트 시작 사이에
  // reset이 끼면 언마운트 타이밍이 어긋나 다음 테스트의 reject가 엉뚱하게 실패로 잡힌다.
  afterEach(() => vi.mocked(papersApi.createPrerequisiteDefinition).mockReset());

  it('하이라이트를 누르면 로딩 팝오버가 열리고 완료되면 설명으로 바뀐다', async () => {
    let resolve!: (v: PrerequisiteDefinitionResponse) => void;
    vi.mocked(papersApi.createPrerequisiteDefinition).mockReturnValue(new Promise((r) => { resolve = r; }));
    setup();

    fireEvent.click(screen.getByText('softmax'));
    const dialog = screen.getByRole('dialog');
    expect(dialog.textContent).toContain('softmax');
    expect(dialog.querySelector('.pt-prereq-loading')).not.toBeNull();

    await act(async () => { resolve({ term: 'softmax', definitionEn: 'An en', definitionKo: '국문' }); });
    expect(screen.getByRole('dialog').textContent).toContain('An en');
    expect(screen.getByRole('dialog').textContent).toContain('국문');
    expect(screen.getByText('softmax', { selector: 'mark' }).getAttribute('aria-expanded')).toBe('true');
  });

  it('실패하면 같은 팝오버에 실패 문구', async () => {
    vi.mocked(papersApi.createPrerequisiteDefinition).mockRejectedValue(new ApiError('x', 'PREREQUISITE_DEFINITION_FAILED', 502));
    setup();
    fireEvent.click(screen.getByText('softmax'));
    await waitFor(() => expect(screen.getByRole('dialog').textContent).toContain('설명을 불러오지 못했습니다.'));
  });

  it('429도 같은 실패 문구', async () => {
    vi.mocked(papersApi.createPrerequisiteDefinition).mockRejectedValue(new ApiError('x', 'PREREQUISITE_CONCURRENCY_LIMIT_EXCEEDED', 429));
    setup();
    fireEvent.click(screen.getByText('softmax'));
    await waitFor(() => expect(screen.getByRole('dialog').textContent).toContain('설명을 불러오지 못했습니다.'));
  });

  it('뷰어 빈 영역을 누르면 닫히고, 팝오버 안·튜터는 닫지 않는다', async () => {
    vi.mocked(papersApi.createPrerequisiteDefinition).mockResolvedValue({ term: 'softmax', definitionEn: 'e', definitionKo: 'k' });
    setup();
    fireEvent.click(screen.getByText('softmax'));
    await waitFor(() => screen.getByRole('dialog'));

    fireEvent.mouseDown(screen.getByRole('dialog'));
    expect(screen.queryByRole('dialog')).not.toBeNull();
    fireEvent.mouseDown(screen.getByTestId('tutor'));
    expect(screen.queryByRole('dialog')).not.toBeNull();
    fireEvent.mouseDown(screen.getByText('rest'));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('닫았다 다시 누르면 요청 없이 즉시 열린다', async () => {
    vi.mocked(papersApi.createPrerequisiteDefinition).mockResolvedValue({ term: 'softmax', definitionEn: 'e', definitionKo: 'k' });
    setup();
    fireEvent.click(screen.getByText('softmax'));
    await waitFor(() => expect(screen.getByRole('dialog').textContent).toContain('e'));
    fireEvent.mouseDown(screen.getByText('rest'));

    fireEvent.click(screen.getByText('softmax'));
    expect(screen.getByRole('dialog').textContent).toContain('e');
    expect(papersApi.createPrerequisiteDefinition).toHaveBeenCalledTimes(1);
  });

  it('실패는 기억하지 않아 다시 누르면 새 요청이다', async () => {
    vi.mocked(papersApi.createPrerequisiteDefinition).mockRejectedValueOnce(new ApiError('x', 'PREREQUISITE_DEFINITION_FAILED', 502));
    vi.mocked(papersApi.createPrerequisiteDefinition).mockResolvedValueOnce({ term: 'softmax', definitionEn: 'ok', definitionKo: 'k' });
    setup();
    fireEvent.click(screen.getByText('softmax'));
    await waitFor(() => expect(screen.getByRole('dialog').textContent).toContain('설명을 불러오지 못했습니다.'));
    fireEvent.mouseDown(screen.getByText('rest'));

    fireEvent.click(screen.getByText('softmax'));
    await waitFor(() => expect(screen.getByRole('dialog').textContent).toContain('ok'));
    expect(papersApi.createPrerequisiteDefinition).toHaveBeenCalledTimes(2);
  });

  it('visible이 false면 클릭을 무시한다', () => {
    setup({ visible: false });
    fireEvent.click(screen.getByText('softmax'));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(papersApi.createPrerequisiteDefinition).not.toHaveBeenCalled();
  });

  it('openSignal이 바뀌면 닫힌다', async () => {
    vi.mocked(papersApi.createPrerequisiteDefinition).mockResolvedValue({ term: 'softmax', definitionEn: 'e', definitionKo: 'k' });
    const { rerender, viewerRef } = setup();
    fireEvent.click(screen.getByText('softmax'));
    await waitFor(() => screen.getByRole('dialog'));
    rerender(
      <div>
        <div ref={viewerRef} data-testid="viewer"><p><mark className="term-highlight" data-highlight-id="h1" role="button" tabIndex={0}>softmax</mark> rest</p></div>
        <PrerequisiteLayer paperId="p" viewerRef={viewerRef} highlights={highlights} visible openSignal={1} onOpen={() => {}} />
      </div>,
    );
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('열릴 때 onOpen을 부른다', () => {
    vi.mocked(papersApi.createPrerequisiteDefinition).mockReturnValue(new Promise(() => {}));
    const onOpen = vi.fn();
    setup({ onOpen });
    fireEvent.click(screen.getByText('softmax'));
    expect(onOpen).toHaveBeenCalledTimes(1);
  });
});
