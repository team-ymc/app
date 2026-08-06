import { describe, it, expect } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { SanitizedHtmlTable } from './SanitizedHtmlTable';

describe('SanitizedHtmlTable', () => {
  it('셀 안 인라인 LaTeX($…$)를 수식으로 렌더한다', async () => {
    const { container } = render(
      <SanitizedHtmlTable html="<table><tr><td>$ O(n^{2}) $</td></tr></table>" />,
    );
    await waitFor(() => {
      expect(container.querySelector('.katex')).not.toBeNull();
    });
    expect(container.querySelector('td')?.textContent).not.toContain('$');
  });

  it('표 마크업은 그대로 렌더한다', () => {
    const { container } = render(
      <SanitizedHtmlTable html="<table><tr><td>BLEU</td></tr></table>" />,
    );
    expect(container.querySelector('table')).not.toBeNull();
    expect(container.textContent).toContain('BLEU');
  });

  it('script·이벤트 핸들러는 제거한다', () => {
    const { container } = render(
      <SanitizedHtmlTable html={'<table><tr><td onmouseover="alert(1)">x</td></tr></table><script>alert(2)</script>'} />,
    );
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('td')?.getAttribute('onmouseover')).toBeNull();
  });
});
