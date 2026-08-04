import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// /api를 BE(8080)로 프록시 → 브라우저 입장 same-origin(5173), preflight 없음 (DESIGN.md D1).
// S3 프리사인 PUT/GET은 프록시를 타지 않는다 — 브라우저가 S3로 직접 쏜다.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // 문자열 축약형은 changeOrigin: true가 암묵 적용돼 Host가 8080으로 바뀐다.
    // OAuth redirect-uri는 요청 Host({baseUrl})로 생성되므로 Host를 5173 그대로 보존해야
    // 콘솔에 등록된 http://localhost:5173/api/login/oauth2/code/google 과 일치한다.
    proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: false } },
  },
  test: { environment: 'jsdom' },
});
