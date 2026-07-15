import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// /api를 BE(8080)로 프록시 → 브라우저 입장 same-origin(5173), preflight 없음 (DESIGN.md D1).
// S3 프리사인 PUT/GET은 프록시를 타지 않는다 — 브라우저가 S3로 직접 쏜다.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { '/api': 'http://localhost:8080' } },
  test: { environment: 'jsdom' },
});
