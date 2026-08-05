import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { SanitizedHtmlTable } from './SanitizedHtmlTable';

describe('SanitizedHtmlTable', () => {
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
