import { beforeAll, describe, expect, it } from 'vitest';
import { sha256Base64 } from './fileChecksum';

// jsdom의 Blob엔 arrayBuffer()가 없어 테스트에서만 채운다. 브라우저엔 표준 API로 존재.
beforeAll(() => {
  Blob.prototype.arrayBuffer ??= function (this: Blob) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => {
        resolve(reader.result as ArrayBuffer);
      };
      reader.onerror = reject;
      reader.readAsArrayBuffer(this);
    });
  };
});

describe('sha256Base64', () => {
  // 기대값은 공개 검증 벡터: printf 'abc' | openssl dgst -sha256 -binary | base64
  it('표준 벡터 "abc"를 표준 Base64 44자로 돌려준다', async () => {
    const out = await sha256Base64(new Blob(['abc']));
    expect(out).toBe('ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=');
    expect(out).toMatch(/^[A-Za-z0-9+/]{43}=$/);
  });
});
