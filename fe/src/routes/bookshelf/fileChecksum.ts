// 계약 checksumSha256: 전체 파일 바이트 SHA-256의 표준 Base64(32 bytes → 44자).
export async function sha256Base64(file: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  let binary = '';
  for (const byte of new Uint8Array(digest)) binary += String.fromCharCode(byte);
  return btoa(binary);
}
