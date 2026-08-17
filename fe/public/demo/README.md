# 시연용 목업 — 제거 대상

발표 시연용으로 임시로 넣은 정적 파일이다. **제품 코드가 아니다.** 시연이 끝나면 이 폴더와 진입점을 함께 제거한다.

`public/` 아래라 Vite가 번들링 없이 `dist/`로 그대로 복사하고, React 코드·라우팅·번들 크기와 무관하다.

## 무엇이 들어 있나

| 경로 | 출처 |
|---|---|
| `paper/study.html` | `frontend-test/Paper Study Page.dc.html` (링크 2곳만 수정) |
| `paper/support.js`, `paper/_ds/` | 목업 런타임(x-dc)과 디자인 시스템 번들. 원본 그대로 |
| `paper/paper-0.0v3.ko.json` | 본문 한국어 번역 95블록. 원본 그대로 |
| `paper/knowledge-graph/` | 지식 그래프 뷰어. viz.html 링크 3곳만 수정 |
| `ymc-documents/papers/0.0v3/` | 파싱본을 파서 schema v1로 변환한 것 |

`study.html`이 `../ymc-documents`를 참조하므로 `paper/`와 `ymc-documents/`는 형제여야 한다.

`ymc-documents/`는 `ai/storage/papers/6b00edc8-8fd1-47ef-8c93-c885d2359d72`(Attention 논문 15페이지 PaddleOCR 파싱본)의 `page_metadata/*.json`을 변환해 만들었다. 텍스트·라벨·bbox·순서·수식 LaTeX·표 HTML이 전부 실제 파싱본에서 온다. 목업이 하드코딩한 asset 경로 16개와 변환 결과가 일치하는 것으로 검증했다.

## 진입점

`src/routes/BookshelfPage.tsx`의 `DemoMockupRow`. 서재 목록 맨 위에 렌더하고 `/demo/paper/study.html`로 간다. SPA 라우터를 벗어나야 정적 파일에 닿으므로 `Link`가 아니라 `<a>`다.

## 제거

```bash
rm -rf fe/public/demo
git checkout fe/src/routes/BookshelfPage.tsx   # DemoMockupRow와 호출부 2곳
```

배포 파이프라인의 `aws s3 sync dist/ --delete`가 S3에서도 지운다.

## 알려진 한계

- 지식 그래프는 첫 로드에서 빈 화면이다. 좌측 `초기화`를 눌러야 그래프가 나온다.
- AI 튜터 답변은 캔드 응답이고, 입력과 무관하게 항상 같은 답이 나온다. 내용도 화면의 Attention 논문이 아니라 다른 논문 기준이다.
- MathJax·Phosphor·폰트를 CDN에서 받는다. 네트워크가 막힌 곳에서는 수식과 아이콘이 깨진다.
